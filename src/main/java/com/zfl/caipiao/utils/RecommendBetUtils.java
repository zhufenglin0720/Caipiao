package com.zfl.caipiao.utils;

import cn.hutool.core.util.StrUtil;
import com.zfl.caipiao.cache.HmCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 推荐五注：按近窗「命中位次」共性挑位置，再做号码差异化。
 * 组三组选：从完整直选预测中提取唯一组三形态。
 */
public final class RecommendBetUtils {

    public static final int HIT_LOOKBACK = 50;
    public static final int PICK = 5;
    /** 候选位次池：命中频次最高的前若干名，再从中差异化挑 5 */
    private static final int RANK_CANDIDATE_POOL = 15;

    private RecommendBetUtils() {
    }

    /**
     * 用历史对比缓存的命中位次，重排新预测：差异化五注置前，其余原序接后。
     */
    public static String reorderByHitRanks(String pred, List<HmCache.CompareDto> history) {
        if (StrUtil.isBlank(pred)) {
            return pred;
        }
        List<String> all = parseBets(pred);
        if (all.isEmpty()) {
            return pred;
        }
        int[] ranks = topHitRanks(history, HIT_LOOKBACK, RANK_CANDIDATE_POOL);
        List<String> front = pickDiverseAtRanks(all, ranks, PICK);
        if (front.isEmpty()) {
            // 无历史时退回：从全列表差异化取前5
            front = pickDiverseSequential(all, PICK);
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

    /** 组三组选是否命中（开奖为组三且形态在列表中） */
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
     * 统计近 lookback 期开奖落在预测列表的第几注（1-based），返回频次最高的若干位次。
     */
    static int[] topHitRanks(List<HmCache.CompareDto> history, int lookback, int topN) {
        int[] freq = new int[101]; // index 1..100
        if (history != null) {
            int end = history.size();
            int start = Math.max(0, end - lookback);
            for (int i = start; i < end; i++) {
                HmCache.CompareDto dto = history.get(i);
                if (dto == null || StrUtil.isBlank(dto.getAiHm()) || StrUtil.isBlank(dto.getRealHm())
                        || dto.getRealHm().length() != 3) {
                    continue;
                }
                int rank = indexOfBet(dto.getAiHm(), dto.getRealHm().trim());
                if (rank >= 1 && rank <= 100) {
                    freq[rank]++;
                }
            }
        }
        Integer[] order = new Integer[100];
        for (int i = 0; i < 100; i++) {
            order[i] = i + 1;
        }
        Arrays.sort(order, (a, b) -> {
            if (freq[a] != freq[b]) {
                return Integer.compare(freq[b], freq[a]);
            }
            return Integer.compare(a, b);
        });
        List<Integer> ranks = new ArrayList<>();
        for (int i = 0; i < 100 && ranks.size() < topN; i++) {
            if (freq[order[i]] > 0) {
                ranks.add(order[i]);
            }
        }
        if (ranks.isEmpty()) {
            for (int i = 1; i <= Math.min(topN, PICK); i++) {
                ranks.add(i);
            }
        }
        return ranks.stream().mapToInt(Integer::intValue).toArray();
    }

    private static List<String> pickDiverseAtRanks(List<String> all, int[] ranks, int pick) {
        List<String> selected = new ArrayList<>(pick);
        Set<Integer> usedIdx = new LinkedHashSet<>();
        // 先按命中共性位次尝试
        for (int rank : ranks) {
            if (selected.size() >= pick) {
                break;
            }
            int idx = rank - 1;
            if (idx < 0 || idx >= all.size() || usedIdx.contains(idx)) {
                continue;
            }
            String bet = all.get(idx);
            if (isDiverse(bet, selected, true)) {
                selected.add(bet);
                usedIdx.add(idx);
            }
        }
        // 放宽差异
        for (int rank : ranks) {
            if (selected.size() >= pick) {
                break;
            }
            int idx = rank - 1;
            if (idx < 0 || idx >= all.size() || usedIdx.contains(idx)) {
                continue;
            }
            String bet = all.get(idx);
            if (isDiverse(bet, selected, false) && !selected.contains(bet)) {
                selected.add(bet);
                usedIdx.add(idx);
            }
        }
        // 位次池不够则按列表顺序差异化补齐（仍禁止同三码排列）
        if (selected.size() < pick) {
            for (int i = 0; i < all.size() && selected.size() < pick; i++) {
                if (usedIdx.contains(i)) {
                    continue;
                }
                String bet = all.get(i);
                if (isDiverse(bet, selected, true) || isDiverse(bet, selected, false)) {
                    selected.add(bet);
                    usedIdx.add(i);
                }
            }
        }
        // 最后补齐：只要求三码集合互不相同（禁止 353/335/533 同类）
        for (int i = 0; i < all.size() && selected.size() < pick; i++) {
            if (usedIdx.contains(i)) {
                continue;
            }
            String bet = all.get(i);
            if (!selected.contains(bet) && !sameDigitSet(bet, selected)) {
                selected.add(bet);
                usedIdx.add(i);
            }
        }
        return selected;
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
            if (selected.size() >= pick) {
                break;
            }
            if (!selected.contains(bet) && !sameDigitSet(bet, selected)) {
                selected.add(bet);
            }
        }
        return selected;
    }

    /**
     * @param strict true：同三码集合禁止，且同位相同≥2 禁止；false：仍禁止同三码集合，仅放宽同位约束
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

    /** 1-based index of real in pred list, or -1 */
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

    /** 描述当前命中位次共性，便于日志 */
    public static String describeHitRanks(List<HmCache.CompareDto> history) {
        int[] ranks = topHitRanks(history, HIT_LOOKBACK, PICK);
        int[] freq = new int[101];
        if (history != null) {
            int end = history.size();
            int start = Math.max(0, end - HIT_LOOKBACK);
            for (int i = start; i < end; i++) {
                HmCache.CompareDto dto = history.get(i);
                if (dto == null || StrUtil.isBlank(dto.getAiHm()) || StrUtil.isBlank(dto.getRealHm())) {
                    continue;
                }
                int r = indexOfBet(dto.getAiHm(), dto.getRealHm().trim());
                if (r >= 1 && r <= 100) {
                    freq[r]++;
                }
            }
        }
        StringBuilder sb = new StringBuilder("命中共性位次[");
        for (int i = 0; i < ranks.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ranks[i]).append("(×").append(freq[ranks[i]]).append(')');
        }
        sb.append(']');
        return sb.toString();
    }
}
