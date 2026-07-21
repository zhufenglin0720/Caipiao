package com.zfl.caipiao.utils;

import cn.hutool.core.util.StrUtil;
import com.zfl.caipiao.cache.HmCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从预测大底中挑展示/邮件推荐注（固定 10 注）。
 * <p>
 * 命中率优先策略：
 * 1) 近窗按衰减统计各预测位次的直选命中密度，并做邻位平滑；
 * 2) 搜索「单位宽度命中最高」的连续位次带（对齐引擎「命中名次带」思想）；
 * 3) 带内优先 + 全表密度打分，取 Top10；仅禁同三码不同序，避免浪费直选名额。
 */
public final class RecommendBetUtils {

    public static final int HIT_LOOKBACK = 100;
    public static final int MIN_PICK = 10;
    public static final int MAX_PICK = 10;
    private static final int MAX_RANK = 200;
    /** 越近的命中权重越高 */
    private static final double RECENCY_DECAY = 0.96;
    /** 搜索密集带的目标宽度（约等于 10 注可覆盖的位次邻域） */
    private static final int DENSE_BAND_WIDTH = 18;
    /** 密集带内额外加成 */
    private static final double BAND_BOOST = 0.55;
    /** 历史密度权重（其余为轻微模型序，仅作并列打散） */
    private static final double HIST_WEIGHT = 0.90;
    private static final int COLD_TOP_RANKS = 40;

    private RecommendBetUtils() {
    }

    public static String pickRecommendBets(String pred, List<HmCache.CompareDto> history) {
        List<String> all = parseBets(pred);
        if (all.isEmpty()) {
            return "";
        }
        double[] scores = scoreRanks(all.size(), history);
        List<String> picked = pickByScores(all, scores);
        if (picked.size() < MIN_PICK) {
            picked = fillUniqueDigitSets(all, MAX_PICK);
        }
        return String.join(",", picked);
    }

    /**
     * 同一组选形态只保留预测序第一注（如 353 已保留则丢弃 335/533）。
     * 用于 200 注大底落盘前去重，抬组选覆盖效率。
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

    /** 去重后注数（组选形态数） */
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

    /**
     * 位次综合得分。索引 0 不用；scores[rank] 对应预测第 rank 注（1-based）。
     */
    static double[] scoreRanks(int predSize, List<HmCache.CompareDto> history) {
        int n = Math.min(MAX_RANK, Math.max(predSize, 1));
        double[] hist = new double[n + 1];
        if (history != null && !history.isEmpty()) {
            int end = history.size();
            int start = Math.max(0, end - HIT_LOOKBACK);
            for (int i = start; i < end; i++) {
                HmCache.CompareDto dto = history.get(i);
                if (dto == null || StrUtil.isBlank(listForRank(dto)) || StrUtil.isBlank(dto.getRealHm())
                        || dto.getRealHm().length() != 3) {
                    continue;
                }
                int rank = indexOfBet(listForRank(dto), dto.getRealHm().trim());
                if (rank < 1 || rank > n) {
                    continue;
                }
                int age = end - 1 - i;
                hist[rank] += Math.pow(RECENCY_DECAY, age);
            }
        }

        double[] smooth = new double[n + 1];
        double smoothSum = 0;
        for (int r = 1; r <= n; r++) {
            double v = hist[r];
            if (r > 1) {
                v += 0.50 * hist[r - 1];
            }
            if (r < n) {
                v += 0.50 * hist[r + 1];
            }
            if (r > 2) {
                v += 0.22 * hist[r - 2];
            }
            if (r < n - 1) {
                v += 0.22 * hist[r + 2];
            }
            smooth[r] = v;
            smoothSum += v;
        }

        int[] band = discoverDenseBand(smooth, n);
        double[] scores = new double[n + 1];
        boolean hasHist = smoothSum > 1e-9;
        for (int r = 1; r <= n; r++) {
            // 模型序仅轻微参与：本引擎刻意把命中放到名次带而非 Top1
            double prior = 1.0 / (1.0 + Math.log(r));
            if (!hasHist) {
                scores[r] = r <= COLD_TOP_RANKS ? prior : prior * 0.12;
                continue;
            }
            double dens = smooth[r] / smoothSum;
            scores[r] = HIST_WEIGHT * dens + (1.0 - HIST_WEIGHT) * prior;
            if (r >= band[0] && r <= band[1]) {
                scores[r] += BAND_BOOST * dens;
            }
            if (hist[r] <= 0 && (r < band[0] - 8 || r > band[1] + 8)) {
                scores[r] *= 0.42;
            }
        }
        return scores;
    }

    /** 找平滑密度最高的固定宽度连续带 */
    static int[] discoverDenseBand(double[] smooth, int n) {
        int width = Math.min(DENSE_BAND_WIDTH, Math.max(MIN_PICK, n));
        if (n <= width) {
            return new int[]{1, n};
        }
        double bestSum = -1;
        int bestLo = 1;
        for (int lo = 1; lo + width - 1 <= n; lo++) {
            int hi = lo + width - 1;
            double sum = 0;
            for (int r = lo; r <= hi; r++) {
                sum += smooth[r];
            }
            // 轻微偏好中段：与「命中名次带」一致，避免纯贴边噪声
            if (lo <= 2) {
                sum *= 0.92;
            }
            if (sum > bestSum) {
                bestSum = sum;
                bestLo = lo;
            }
        }
        return new int[]{bestLo, bestLo + width - 1};
    }

    private static List<String> pickByScores(List<String> all, double[] scores) {
        int n = Math.min(all.size(), scores.length - 1);
        List<Integer> ranks = new ArrayList<>(n);
        for (int r = 1; r <= n; r++) {
            ranks.add(r);
        }
        ranks.sort(Comparator
                .comparingDouble((Integer r) -> scores[r]).reversed()
                .thenComparingInt(r -> r));

        List<String> selected = new ArrayList<>(MAX_PICK);
        Set<String> usedDigitKeys = new LinkedHashSet<>();
        for (int rank : ranks) {
            if (selected.size() >= MAX_PICK) {
                break;
            }
            String bet = all.get(rank - 1);
            if (bet == null || bet.length() != 3) {
                continue;
            }
            String key = digitKey(bet);
            if (usedDigitKeys.contains(key)) {
                continue;
            }
            selected.add(bet);
            usedDigitKeys.add(key);
        }
        return selected;
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
            int rank = indexOfBet(listForRank(dto), dto.getRealHm().trim());
            if (rank >= 1 && rank <= MAX_RANK) {
                freq[rank]++;
            }
        }
        return freq;
    }

    /** 位次统计优先用原始200注列表，与邮件挑选对齐 */
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
        for (int i = 0; i < bets.size(); i++) {
            if (bets.get(i).equals(real)) {
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
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            while (t.length() < 3) {
                t = "0" + t;
            }
            if (t.length() > 3) {
                t = t.substring(t.length() - 3);
            }
            if (seen.add(t)) {
                list.add(t);
            }
        }
        return list;
    }

    public static String describeHitRanks(List<HmCache.CompareDto> history) {
        double[] scores = scoreRanks(MAX_RANK, history);
        List<Integer> ranks = new ArrayList<>();
        for (int r = 1; r < scores.length; r++) {
            ranks.add(r);
        }
        ranks.sort(Comparator
                .comparingDouble((Integer r) -> scores[r]).reversed()
                .thenComparingInt(r -> r));
        int[] top = new int[Math.min(MAX_PICK, ranks.size())];
        for (int i = 0; i < top.length; i++) {
            top[i] = ranks.get(i);
        }
        double[] smooth = new double[MAX_RANK + 1];
        int[] freq = hitRankFreq(history, HIT_LOOKBACK);
        for (int r = 1; r <= MAX_RANK; r++) {
            smooth[r] = freq[r];
        }
        int[] band = discoverDenseBand(smooth, MAX_RANK);
        int covered = 0;
        int total = 0;
        for (int r = 1; r <= MAX_RANK; r++) {
            total += freq[r];
            if (r >= band[0] && r <= band[1]) {
                covered += freq[r];
            }
        }
        return String.format("近%d期密集位次带=%d~%d(落%d/%d)，按密度取固定%d注 优选=%s",
                HIT_LOOKBACK, band[0], band[1], covered, total, MAX_PICK, Arrays.toString(top));
    }
}
