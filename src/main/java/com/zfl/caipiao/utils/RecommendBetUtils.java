package com.zfl.caipiao.utils;

import cn.hutool.core.util.StrUtil;
import com.zfl.caipiao.cache.HmCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从 ≤150 注中挑展示/邮件推荐注：
 * 按近 100 期「开奖落在预测列表的位次」找普遍命中区间，再在该区间内差异化取 5~10 注。
 * 禁止三码完全相同仅顺序不同（如 353 / 335 / 533）。
 */
public final class RecommendBetUtils {

    /** 与三码回测窗口对齐 */
    public static final int HIT_LOOKBACK = 100;
    public static final int MIN_PICK = 5;
    public static final int MAX_PICK = 10;
    /** 预测列表最大位次（150 注） */
    private static final int MAX_RANK = 150;
    /** 命中带至少覆盖的样本比例 */
    private static final int COVER_PCT = 55;

    private RecommendBetUtils() {
    }

    /**
     * 选出 5~10 注推荐（仅推荐本身，不含其余 150 注）。
     */
    public static String pickRecommendBets(String pred, List<HmCache.CompareDto> history) {
        List<String> all = parseBets(pred);
        if (all.isEmpty()) {
            return "";
        }
        int[] freq = hitRankFreq(history, HIT_LOOKBACK);
        int[] band = discoverHitBand(freq);
        List<String> picked = pickFromBand(all, freq, band[0], band[1]);
        if (picked.size() < MIN_PICK) {
            picked = pickDiverseSequential(all, MAX_PICK);
        }
        return String.join(",", picked);
    }

    /**
     * 推荐注置前，其余原序接后（完整列表仍保留供大底等使用）。
     */
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
            front = pickDiverseSequential(all, MAX_PICK);
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

    /**
     * 从直选预测中提取组三组选（去重、按首次出现顺序），逗号分隔如 112,334,055。
     */
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

    static int[] hitRankFreq(List<HmCache.CompareDto> history, int lookback) {
        int[] freq = new int[MAX_RANK + 1];
        if (history == null) {
            return freq;
        }
        int end = history.size();
        int start = Math.max(0, end - lookback);
        for (int i = start; i < end; i++) {
            HmCache.CompareDto dto = history.get(i);
            if (dto == null || StrUtil.isBlank(dto.getAiHm()) || StrUtil.isBlank(dto.getRealHm())
                    || dto.getRealHm().length() != 3) {
                continue;
            }
            int rank = indexOfBet(dto.getAiHm(), dto.getRealHm().trim());
            if (rank >= 1 && rank <= MAX_RANK) {
                freq[rank]++;
            }
        }
        return freq;
    }

    /**
     * 找覆盖 ≥COVER_PCT% 命中样本的最短连续位次带；偏好避开纯前几名（1~5）。
     */
    static int[] discoverHitBand(int[] freq) {
        int total = 0;
        for (int r = 1; r <= MAX_RANK; r++) {
            total += freq[r];
        }
        if (total == 0) {
            // 无历史：用中段，避免默认前几注
            return new int[]{8, 40};
        }
        int need = (total * COVER_PCT + 99) / 100;
        int bestLo = 6, bestHi = 40, bestScore = Integer.MAX_VALUE;
        for (int lo = 1; lo <= MAX_RANK; lo++) {
            int sum = 0;
            for (int hi = lo; hi <= MAX_RANK; hi++) {
                sum += freq[hi];
                if (sum < need) {
                    continue;
                }
                int width = hi - lo;
                int headPenalty = (hi <= 5) ? 8 : (lo <= 5 ? 3 : 0);
                int score = width + headPenalty;
                if (score < bestScore || (score == bestScore && lo >= 6 && bestLo < 6)) {
                    bestScore = score;
                    bestLo = lo;
                    bestHi = hi;
                }
                break;
            }
        }
        // 带宽至少能支撑 5~10 选号
        if (bestHi - bestLo + 1 < MIN_PICK) {
            bestHi = Math.min(MAX_RANK, bestLo + MIN_PICK - 1);
        }
        if (bestHi - bestLo + 1 > 60) {
            // 过宽则收到命中峰值附近
            int peak = 1;
            for (int r = 2; r <= MAX_RANK; r++) {
                if (freq[r] > freq[peak]) {
                    peak = r;
                }
            }
            bestLo = Math.max(1, peak - 15);
            bestHi = Math.min(MAX_RANK, bestLo + 35);
            if (bestLo <= 5 && freq[peak] > 0 && peak > 5) {
                bestLo = Math.max(6, peak - 12);
                bestHi = Math.min(MAX_RANK, bestLo + 35);
            }
        }
        return new int[]{bestLo, bestHi};
    }

    private static List<String> pickFromBand(List<String> all, int[] freq, int bandLo, int bandHi) {
        // 带内位次按命中频次排序
        List<Integer> ranks = new ArrayList<>();
        for (int r = bandLo; r <= bandHi; r++) {
            ranks.add(r);
        }
        ranks.sort((a, b) -> {
            int c = Integer.compare(freq[b], freq[a]);
            return c != 0 ? c : Integer.compare(a, b);
        });

        List<String> selected = new ArrayList<>(MAX_PICK);
        Set<Integer> usedIdx = new LinkedHashSet<>();

        // 1) 严格差异：不同组选 + 同位相同 < 2
        for (int rank : ranks) {
            if (selected.size() >= MAX_PICK) {
                break;
            }
            tryAdd(all, rank, selected, usedIdx, true);
        }
        // 2) 放宽同位，仍禁同组选
        for (int rank : ranks) {
            if (selected.size() >= MAX_PICK) {
                break;
            }
            tryAdd(all, rank, selected, usedIdx, false);
        }
        // 3) 带内剩余：只禁同组选
        for (int rank = bandLo; rank <= bandHi && selected.size() < MAX_PICK; rank++) {
            int idx = rank - 1;
            if (idx < 0 || idx >= all.size() || usedIdx.contains(idx)) {
                continue;
            }
            String bet = all.get(idx);
            if (!sameDigitSet(bet, selected)) {
                selected.add(bet);
                usedIdx.add(idx);
            }
        }
        // 4) 不足 MIN_PICK：向带外扩展（仍禁同组选），优先带右侧再左侧
        for (int step = 1; selected.size() < MIN_PICK && step < MAX_RANK; step++) {
            for (int rank : new int[]{bandHi + step, bandLo - step}) {
                if (selected.size() >= MIN_PICK) {
                    break;
                }
                if (rank < 1 || rank > MAX_RANK) {
                    continue;
                }
                int idx = rank - 1;
                if (idx >= all.size() || usedIdx.contains(idx)) {
                    continue;
                }
                String bet = all.get(idx);
                if (!sameDigitSet(bet, selected)) {
                    selected.add(bet);
                    usedIdx.add(idx);
                }
            }
        }
        // 目标尽量靠近 MAX_PICK：带外继续补（已有 ≥MIN）
        for (int rank = 1; rank <= Math.min(MAX_RANK, all.size()) && selected.size() < MAX_PICK; rank++) {
            if (rank >= bandLo && rank <= bandHi) {
                continue;
            }
            int idx = rank - 1;
            if (usedIdx.contains(idx)) {
                continue;
            }
            String bet = all.get(idx);
            if (!sameDigitSet(bet, selected)) {
                selected.add(bet);
                usedIdx.add(idx);
            }
        }
        return selected;
    }

    private static void tryAdd(List<String> all, int rank, List<String> selected,
                               Set<Integer> usedIdx, boolean strict) {
        int idx = rank - 1;
        if (idx < 0 || idx >= all.size() || usedIdx.contains(idx)) {
            return;
        }
        String bet = all.get(idx);
        if (isDiverse(bet, selected, strict)) {
            selected.add(bet);
            usedIdx.add(idx);
        }
    }

    private static List<String> pickDiverseSequential(List<String> all, int pick) {
        List<String> selected = new ArrayList<>(pick);
        for (String bet : all) {
            if (selected.size() >= pick) {
                break;
            }
            if (isDiverse(bet, selected, true)) {
                selected.add(bet);
            }
        }
        for (String bet : all) {
            if (selected.size() >= pick) {
                break;
            }
            if (!selected.contains(bet) && isDiverse(bet, selected, false)) {
                selected.add(bet);
            }
        }
        for (String bet : all) {
            if (selected.size() >= Math.max(MIN_PICK, pick)) {
                break;
            }
            if (!selected.contains(bet) && !sameDigitSet(bet, selected)) {
                selected.add(bet);
            }
        }
        return selected;
    }

    /**
     * @param strict true：同三码集合禁止，且同位相同≥2 禁止；false：仍禁止同三码集合
     */
    private static boolean isDiverse(String cand, List<String> selected, boolean strict) {
        if (cand == null || cand.length() != 3) {
            return false;
        }
        if (sameDigitSet(cand, selected)) {
            return false;
        }
        for (String s : selected) {
            if (s == null || s.length() != 3) {
                continue;
            }
            if (cand.equals(s)) {
                return false;
            }
            int samePos = 0;
            for (int i = 0; i < 3; i++) {
                if (cand.charAt(i) == s.charAt(i)) {
                    samePos++;
                }
            }
            if (strict && samePos >= 2) {
                return false;
            }
        }
        return true;
    }

    /** 是否与已选任一注三码完全相同（忽略顺序，如 353 与 335） */
    private static boolean sameDigitSet(String cand, List<String> selected) {
        if (cand == null || cand.length() != 3) {
            return true;
        }
        String candKey = digitKey(cand);
        for (String s : selected) {
            if (s != null && s.length() == 3 && digitKey(s).equals(candKey)) {
                return true;
            }
        }
        return false;
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
        int[] freq = hitRankFreq(history, HIT_LOOKBACK);
        int[] band = discoverHitBand(freq);
        int cover = 0, total = 0;
        for (int r = 1; r <= MAX_RANK; r++) {
            total += freq[r];
            if (r >= band[0] && r <= band[1]) {
                cover += freq[r];
            }
        }
        return String.format("近%d期命中位次带=%d~%d (覆盖%d/%d)，推荐%d~%d注且禁同号不同序",
                HIT_LOOKBACK, band[0], band[1], cover, total, MIN_PICK, MAX_PICK);
    }
}
