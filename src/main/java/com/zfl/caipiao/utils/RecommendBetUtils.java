package com.zfl.caipiao.utils;

import cn.hutool.core.util.StrUtil;
import com.zfl.caipiao.cache.HmCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 从预测大底中挑展示/邮件推荐注（固定 10 注）。
 * <p>
 * 策略（条件转化优先）：
 * 1) 大底按位次分 4 段：1-50 / 51-100 / 101-150 / 151-200；
 * 2) 仅用「大底直选已命中」的近窗样本估计各段落点，分配 10 槽；
 * 3) 段内按同位次衰减密度 + 轻微模型序挑选；禁同三码不同序；
 * 4) 过拟合号若已在大底内，最多占 1 槽。
 */
public final class RecommendBetUtils {

    public static final int HIT_LOOKBACK = 100;
    public static final int MIN_PICK = 10;
    public static final int MAX_PICK = 10;
    private static final int MAX_RANK = 200;
    /** 条件转化统计回看（只计大底直选命中期） */
    private static final int COND_LOOKBACK = 40;
    private static final double RECENCY_DECAY = 0.96;
    private static final double HIST_WEIGHT = 0.88;
    /** 4 段边界（hi inclusive, 1-based） */
    private static final int[] SEG_HI = {50, 100, 150, 200};
    /** 默认偏中后段（近窗大底直选命中常落 150+） */
    private static final int[] DEFAULT_QUOTA = {1, 2, 3, 4};

    private RecommendBetUtils() {
    }

    public static String pickRecommendBets(String pred, List<HmCache.CompareDto> history) {
        return pickRecommendBets(pred, history, null, false);
    }

    public static String pickRecommendBets(String pred, List<HmCache.CompareDto> history,
                                           String overfitPool) {
        return pickRecommendBets(pred, history, overfitPool, false);
    }

    public static String pickRecommendBets(String pred, List<HmCache.CompareDto> history,
                                           String overfitPool, boolean pl3) {
        List<String> all = parseBets(pred);
        if (all.isEmpty()) {
            return "";
        }
        int n = Math.min(all.size(), MAX_RANK);
        int[] quota = allocateQuota(history, n);
        double[] rankScores = scoreRanksForStratified(n, history);
        Set<String> overfitSet = new LinkedHashSet<>();
        if (overfitPool != null && !overfitPool.isBlank()) {
            overfitSet.addAll(parseBets(overfitPool));
        }

        List<String> picked = new ArrayList<>(MAX_PICK);
        Set<String> usedDigitKeys = new LinkedHashSet<>();

        // 过拟合：大底内号最多占 2 槽（条件转化优先），从所在段扣配额
        int ofSlots = 0;
        for (String bet : overfitSet) {
            if (ofSlots >= 2 || picked.size() >= MAX_PICK) {
                break;
            }
            int rank = -1;
            for (int r = 1; r <= n; r++) {
                if (all.get(r - 1).equals(bet)) {
                    rank = r;
                    break;
                }
            }
            if (rank < 1) {
                continue;
            }
            String key = digitKey(bet);
            if (!usedDigitKeys.add(key)) {
                continue;
            }
            int seg = segmentOf(rank);
            if (seg >= 0 && quota[seg] > 0) {
                quota[seg]--;
            } else {
                int maxI = 3;
                for (int s = 0; s < 4; s++) {
                    if (quota[s] > quota[maxI]) {
                        maxI = s;
                    }
                }
                if (quota[maxI] > 0) {
                    quota[maxI]--;
                }
            }
            picked.add(bet);
            ofSlots++;
        }

        // 按段取号：段内按「过拟合加分 + 历史位次密度」取 Top，并做子箱去挤兑
        for (int seg = 0; seg < 4; seg++) {
            int need = quota[seg];
            if (need <= 0) {
                continue;
            }
            int lo = seg == 0 ? 1 : SEG_HI[seg - 1] + 1;
            int hi = Math.min(SEG_HI[seg], n);
            if (lo > hi) {
                continue;
            }
            List<Integer> ranks = new ArrayList<>();
            for (int r = lo; r <= hi; r++) {
                ranks.add(r);
            }
            final Set<String> ofFinal = overfitSet;
            ranks.sort(Comparator
                    .comparingDouble((Integer r) -> {
                        String bet = all.get(r - 1);
                        double sc = rankScores[r];
                        if (ofFinal.contains(bet)) {
                            sc += 5.0;
                        }
                        // 段内再按子箱拉开：避免全挤在密度尖峰
                        int span = hi - lo + 1;
                        int bin = span <= 1 ? 0 : ((r - lo) * need) / span;
                        sc += (need - bin) * 1e-4;
                        return sc;
                    }).reversed()
                    .thenComparingInt(r -> r));

            // 子箱各取至多 1，再按分补满
            int span = hi - lo + 1;
            int[] binUsed = new int[Math.max(1, need)];
            int got = 0;
            for (int r : ranks) {
                if (got >= need || picked.size() >= MAX_PICK) {
                    break;
                }
                int bin = span <= 1 ? 0 : Math.min(need - 1, ((r - lo) * need) / span);
                if (binUsed[bin] >= 1 && got >= Math.min(need, binUsed.length)) {
                    continue;
                }
                if (binUsed[bin] >= 1) {
                    continue;
                }
                String bet = all.get(r - 1);
                if (bet == null || bet.length() != 3) {
                    continue;
                }
                String key = digitKey(bet);
                if (usedDigitKeys.contains(key)) {
                    continue;
                }
                usedDigitKeys.add(key);
                picked.add(bet);
                binUsed[bin]++;
                got++;
            }
            for (int r : ranks) {
                if (got >= need || picked.size() >= MAX_PICK) {
                    break;
                }
                String bet = all.get(r - 1);
                if (bet == null || bet.length() != 3) {
                    continue;
                }
                String key = digitKey(bet);
                if (usedDigitKeys.contains(key)) {
                    continue;
                }
                usedDigitKeys.add(key);
                picked.add(bet);
                got++;
            }
        }

        // 不足则全表按分补齐
        if (picked.size() < MIN_PICK) {
            List<Integer> ranks = new ArrayList<>();
            for (int r = 1; r <= n; r++) {
                ranks.add(r);
            }
            ranks.sort(Comparator
                    .comparingDouble((Integer r) -> rankScores[r]).reversed()
                    .thenComparingInt(r -> r));
            for (int r : ranks) {
                if (picked.size() >= MAX_PICK) {
                    break;
                }
                String bet = all.get(r - 1);
                if (bet == null || bet.length() != 3) {
                    continue;
                }
                String key = digitKey(bet);
                if (usedDigitKeys.add(key)) {
                    picked.add(bet);
                }
            }
        }
        if (picked.size() < MIN_PICK) {
            for (String bet : fillUniqueDigitSets(all, MAX_PICK)) {
                if (picked.size() >= MAX_PICK) {
                    break;
                }
                String key = digitKey(bet);
                if (usedDigitKeys.add(key)) {
                    picked.add(bet);
                }
            }
        }
        return String.join(",", picked.subList(0, Math.min(MAX_PICK, picked.size())));
    }

    /**
     * 仅用大底直选命中期，估计各段落点密度 → 分配 10 槽。
     * 默认 3,3,2,2；样本不足时用默认。
     */
    static int[] allocateQuota(List<HmCache.CompareDto> history, int predSize) {
        int[] quota = Arrays.copyOf(DEFAULT_QUOTA, 4);
        double[] segW = new double[4];
        int samples = 0;
        if (history != null && !history.isEmpty()) {
            int end = history.size();
            int start = Math.max(0, end - COND_LOOKBACK);
            for (int i = start; i < end; i++) {
                HmCache.CompareDto dto = history.get(i);
                if (dto == null || StrUtil.isBlank(dto.getRealHm()) || dto.getRealHm().length() != 3) {
                    continue;
                }
                String list = listForRank(dto);
                if (StrUtil.isBlank(list)) {
                    continue;
                }
                String actual = pad3(dto.getRealHm());
                int rank = indexOfBet(list, actual);
                if (rank < 1) {
                    continue; // 大底未直中，不计入条件样本
                }
                int seg = segmentOf(rank);
                if (seg < 0) {
                    continue;
                }
                int age = end - 1 - i;
                segW[seg] += Math.pow(RECENCY_DECAY, age);
                samples++;
            }
        }
        if (samples < 3) {
            return clampQuotaToSize(quota, predSize);
        }
        double sum = 0;
        for (double w : segW) {
            sum += w;
        }
        if (sum <= 1e-12) {
            return clampQuotaToSize(quota, predSize);
        }
        // 按密度分配，每段至少1、至多4，总和=10
        int[] raw = new int[4];
        int assigned = 0;
        for (int s = 0; s < 4; s++) {
            raw[s] = 1; // 保底
            assigned++;
        }
        int remain = MAX_PICK - assigned;
        // 按权重分剩余 6 槽
        double[] frac = new double[4];
        for (int s = 0; s < 4; s++) {
            frac[s] = segW[s] / sum * remain;
            int add = (int) Math.floor(frac[s]);
            add = Math.min(3, add); // 加上保底后 ≤4
            raw[s] += add;
            assigned += add;
        }
        remain = MAX_PICK - (raw[0] + raw[1] + raw[2] + raw[3]);
        // 按小数部分补齐
        Integer[] order = {0, 1, 2, 3};
        Arrays.sort(order, (a, b) -> Double.compare(frac[b] - Math.floor(frac[b]), frac[a] - Math.floor(frac[a])));
        int oi = 0;
        while (remain > 0 && oi < 40) {
            int s = order[oi % 4];
            if (raw[s] < 4) {
                raw[s]++;
                remain--;
            }
            oi++;
        }
        while (remain < 0) {
            // 超出则从权重最低且>1 的段减
            int best = -1;
            double bestW = Double.POSITIVE_INFINITY;
            for (int s = 0; s < 4; s++) {
                if (raw[s] > 1 && segW[s] < bestW) {
                    bestW = segW[s];
                    best = s;
                }
            }
            if (best < 0) {
                break;
            }
            raw[best]--;
            remain++;
        }
        return clampQuotaToSize(raw, predSize);
    }

    private static int[] clampQuotaToSize(int[] quota, int predSize) {
        int[] q = Arrays.copyOf(quota, 4);
        if (predSize <= 0) {
            return q;
        }
        // 空段配额并到有票的段
        for (int s = 0; s < 4; s++) {
            int lo = s == 0 ? 1 : SEG_HI[s - 1] + 1;
            int hi = Math.min(SEG_HI[s], predSize);
            if (lo > hi && q[s] > 0) {
                int move = q[s];
                q[s] = 0;
                for (int t = 0; t < 4 && move > 0; t++) {
                    int tLo = t == 0 ? 1 : SEG_HI[t - 1] + 1;
                    int tHi = Math.min(SEG_HI[t], predSize);
                    if (tLo <= tHi && q[t] < 4) {
                        int add = Math.min(move, 4 - q[t]);
                        q[t] += add;
                        move -= add;
                    }
                }
            }
        }
        int sum = q[0] + q[1] + q[2] + q[3];
        if (sum != MAX_PICK) {
            // 归一到 10
            if (sum <= 0) {
                return Arrays.copyOf(DEFAULT_QUOTA, 4);
            }
            while (sum < MAX_PICK) {
                int best = 0;
                for (int s = 1; s < 4; s++) {
                    if (q[s] < q[best]) {
                        best = s;
                    }
                }
                if (q[best] < 4) {
                    q[best]++;
                    sum++;
                } else {
                    break;
                }
            }
            while (sum > MAX_PICK) {
                int best = 0;
                for (int s = 1; s < 4; s++) {
                    if (q[s] > q[best]) {
                        best = s;
                    }
                }
                if (q[best] > 1) {
                    q[best]--;
                    sum--;
                } else {
                    break;
                }
            }
        }
        return q;
    }

    static int segmentOf(int rank) {
        if (rank < 1) {
            return -1;
        }
        for (int s = 0; s < 4; s++) {
            if (rank <= SEG_HI[s]) {
                return s;
            }
        }
        return 3;
    }

    /**
     * 分层用位次分：仅用「大底直选命中」样本的落点密度（条件转化），
     * 并对最近若干次命中位次做邻域尖峰加成。
     */
    static double[] scoreRanksForStratified(int predSize, List<HmCache.CompareDto> history) {
        int n = Math.min(MAX_RANK, Math.max(predSize, 1));
        double[] hist = new double[n + 1];
        List<Integer> recentHitRanks = new ArrayList<>();
        if (history != null && !history.isEmpty()) {
            int end = history.size();
            int start = Math.max(0, end - COND_LOOKBACK);
            for (int i = start; i < end; i++) {
                HmCache.CompareDto dto = history.get(i);
                if (dto == null || StrUtil.isBlank(listForRank(dto)) || StrUtil.isBlank(dto.getRealHm())
                        || dto.getRealHm().length() != 3) {
                    continue;
                }
                int rank = indexOfBet(listForRank(dto), pad3(dto.getRealHm()));
                if (rank < 1 || rank > n) {
                    continue; // 大底未中，不进条件密度
                }
                int age = end - 1 - i;
                hist[rank] += Math.pow(RECENCY_DECAY, age);
                recentHitRanks.add(rank);
            }
        }
        // 最近 6 次大底直中位次：±3 邻域尖峰（越近越强）
        int from = Math.max(0, recentHitRanks.size() - 6);
        for (int i = from; i < recentHitRanks.size(); i++) {
            int rh = recentHitRanks.get(i);
            double w = Math.pow(0.85, recentHitRanks.size() - 1 - i);
            for (int d = -3; d <= 3; d++) {
                int r = rh + d;
                if (r >= 1 && r <= n) {
                    hist[r] += w * (4 - Math.abs(d)) * 0.35;
                }
            }
        }
        double[] smooth = new double[n + 1];
        double smoothSum = 0;
        for (int r = 1; r <= n; r++) {
            double v = hist[r];
            if (r > 1) {
                v += 0.35 * hist[r - 1];
            }
            if (r < n) {
                v += 0.35 * hist[r + 1];
            }
            smooth[r] = v;
            smoothSum += v;
        }
        double[] scores = new double[n + 1];
        boolean hasHist = smoothSum > 1e-9;
        for (int r = 1; r <= n; r++) {
            // 冷启动：略偏中后段
            double prior = (r >= 100 ? 1.2 : 0.8) / (1.0 + Math.log(1 + r));
            if (!hasHist) {
                scores[r] = prior;
            } else {
                scores[r] = HIST_WEIGHT * (smooth[r] / smoothSum) + (1.0 - HIST_WEIGHT) * prior;
            }
        }
        return scores;
    }

    /**
     * 同一组选形态只保留预测序第一注（如 353 已保留则丢弃 335/533）。
     */
    public static String dedupeByGroupKeepFirst(String pred) {
        List<String> all = parseBets(pred);
        if (all.isEmpty()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        Set<String> seenGroup = new LinkedHashSet<>();
        for (String bet : all) {
            if (bet == null || bet.length() != 3) {
                continue;
            }
            String key = digitKey(bet);
            if (seenGroup.add(key)) {
                out.add(bet);
            }
        }
        return String.join(",", out);
    }

    public static int countBets(String pred) {
        return parseBets(pred).size();
    }

    public static String reorderByHitRanks(String pred, List<HmCache.CompareDto> history) {
        if (StrUtil.isBlank(pred)) {
            return pred;
        }
        List<String> all = parseBets(pred);
        if (all.isEmpty()) {
            return pred;
        }
        List<String> front = parseBets(pickRecommendBets(pred, history));
        if (front.isEmpty()) {
            front = fillUniqueDigitSets(all, MAX_PICK);
        }
        Set<String> used = new LinkedHashSet<>(front);
        List<String> ordered = new ArrayList<>(all.size());
        ordered.addAll(front);
        for (String b : all) {
            if (!used.contains(b)) {
                ordered.add(b);
            }
        }
        return String.join(",", ordered);
    }

    public static String extractZuSanGroups(String pred) {
        if (StrUtil.isBlank(pred)) {
            return "";
        }
        Set<String> groups = new LinkedHashSet<>();
        for (String bet : parseBets(pred)) {
            if (bet.length() != 3) {
                continue;
            }
            int a = bet.charAt(0) - '0';
            int b = bet.charAt(1) - '0';
            int c = bet.charAt(2) - '0';
            if (isPairSet(a, b, c)) {
                groups.add(sortedKey(a, b, c));
            }
        }
        return String.join(",", groups);
    }

    public static boolean isZuSanHit(String zuSanHm, String realHm) {
        if (StrUtil.isBlank(zuSanHm) || StrUtil.isBlank(realHm) || realHm.length() != 3) {
            return false;
        }
        int a = realHm.charAt(0) - '0';
        int b = realHm.charAt(1) - '0';
        int c = realHm.charAt(2) - '0';
        if (!isPairSet(a, b, c)) {
            return false;
        }
        String key = sortedKey(a, b, c);
        for (String g : zuSanHm.split(",")) {
            if (key.equals(g.trim())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPairSet(int a, int b, int c) {
        return (a == b && b != c) || (a == c && a != b) || (b == c && a != b);
    }

    public static String sortedKey(int a, int b, int c) {
        int[] x = {a, b, c};
        Arrays.sort(x);
        return "" + x[0] + x[1] + x[2];
    }

    /** 兼容旧回测：密集带打分 */
    static double[] scoreRanks(int predSize, List<HmCache.CompareDto> history) {
        return scoreRanksForStratified(predSize, history);
    }

    static double[] scoreRanks(int predSize, List<HmCache.CompareDto> history,
                               int lookback, int denseBandWidth) {
        return scoreRanksForStratified(predSize, history);
    }

    static int[] discoverDenseBand(double[] smooth, int n) {
        return discoverDenseBand(smooth, n, 18);
    }

    static int[] discoverDenseBand(double[] smooth, int n, int denseBandWidth) {
        int width = Math.min(denseBandWidth > 0 ? denseBandWidth : 18, Math.max(MIN_PICK, n));
        if (n <= width) {
            return new int[]{1, n};
        }
        double bestSum = -1;
        int bestLo = 1;
        for (int lo = 1; lo + width - 1 <= n; lo++) {
            double sum = 0;
            for (int r = lo; r <= lo + width - 1; r++) {
                sum += smooth[r];
            }
            if (sum > bestSum) {
                bestSum = sum;
                bestLo = lo;
            }
        }
        return new int[]{bestLo, bestLo + width - 1};
    }

    private static List<String> fillUniqueDigitSets(List<String> all, int pick) {
        List<String> selected = new ArrayList<>(pick);
        Set<String> used = new LinkedHashSet<>();
        for (String bet : all) {
            if (selected.size() >= pick) {
                break;
            }
            if (bet == null || bet.length() != 3) {
                continue;
            }
            String key = digitKey(bet);
            if (used.add(key)) {
                selected.add(bet);
            }
        }
        return selected;
    }

    static int[] hitRankFreq(List<HmCache.CompareDto> history, int lookback) {
        int[] freq = new int[MAX_RANK + 1];
        if (history == null) {
            return freq;
        }
        int end = history.size();
        int start = Math.max(0, end - lookback);
        for (int i = start; i < end; i++) {
            HmCache.CompareDto dto = history.get(i);
            if (dto == null || StrUtil.isBlank(listForRank(dto)) || StrUtil.isBlank(dto.getRealHm())
                    || dto.getRealHm().length() != 3) {
                continue;
            }
            int rank = indexOfBet(listForRank(dto), pad3(dto.getRealHm()));
            if (rank >= 1 && rank <= MAX_RANK) {
                freq[rank]++;
            }
        }
        return freq;
    }

    private static String listForRank(HmCache.CompareDto dto) {
        if (dto == null) {
            return "";
        }
        if (StrUtil.isNotBlank(dto.getAiFullHm())) {
            return dto.getAiFullHm();
        }
        return dto.getAiHm();
    }

    private static String digitKey(String code) {
        char[] c = code.toCharArray();
        Arrays.sort(c);
        return new String(c);
    }

    private static int indexOfBet(String pred, String real) {
        List<String> bets = parseBets(pred);
        String a = pad3(real);
        for (int i = 0; i < bets.size(); i++) {
            if (bets.get(i).equals(a)) {
                return i + 1;
            }
        }
        return -1;
    }

    private static List<String> parseBets(String pred) {
        List<String> list = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (StrUtil.isBlank(pred)) {
            return list;
        }
        for (String p : pred.split(",")) {
            String t = pad3(p.trim());
            if (t.length() != 3) {
                continue;
            }
            if (seen.add(t)) {
                list.add(t);
            }
        }
        return list;
    }

    private static String pad3(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        while (t.length() < 3) {
            t = "0" + t;
        }
        return t.length() > 3 ? t.substring(t.length() - 3) : t;
    }

    public static String describeHitRanks(List<HmCache.CompareDto> history) {
        int[] q = allocateQuota(history, MAX_RANK);
        int[] freq = hitRankFreq(history, COND_LOOKBACK);
        int[] segHits = new int[4];
        int total = 0;
        for (int r = 1; r <= MAX_RANK; r++) {
            if (freq[r] <= 0) {
                continue;
            }
            int seg = segmentOf(r);
            if (seg >= 0) {
                segHits[seg] += freq[r];
                total += freq[r];
            }
        }
        return String.format(Locale.ROOT,
                "分层配额=%s 近%d期大底直中落段命中=%s/%d（条件转化选10注）",
                Arrays.toString(q), COND_LOOKBACK, Arrays.toString(segHits), total);
    }
}
