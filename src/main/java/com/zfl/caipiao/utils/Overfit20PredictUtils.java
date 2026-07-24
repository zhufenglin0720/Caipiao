package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 近 20 期过拟合五组预测。
 * <p>
 * 仅使用预测时点之前（或回测拟合窗内）最近 {@link #WINDOW} 期开奖，
 * 用「动态近因加权组覆盖 + 窗内转移择序」选出恰好 {@link #GROUP_COUNT} 组直选号。
 * 所有权重/候选均由窗口数据现场计算，禁止硬编码开奖号码或固定形态表。
 */
@Slf4j
public final class Overfit20PredictUtils {

    /** 过拟合样本窗 */
    public static final int WINDOW = 20;
    /** 输出组数 */
    public static final int GROUP_COUNT = 5;
    /** 回测评估期数（取拟合窗内最近若干期做命中评估） */
    public static final int EVAL_PERIODS = 10;

    private Overfit20PredictUtils() {
    }

    public static String get3dPredict() {
        return predict(HmCache.getSdCache());
    }

    public static String getPl3Predict() {
        return predict(HmCache.getPl3Cache());
    }

    public static String predict(List<Hm> history) {
        List<String> codes = toCodes(history);
        if (codes.isEmpty()) {
            return "";
        }
        int from = Math.max(0, codes.size() - WINDOW);
        List<String> window = codes.subList(from, codes.size());
        List<String> picks = fitFiveGroups(window);
        String out = String.join(",", picks);
        log.info("近{}期过拟合五组: {} | 窗末={}, 组={}", WINDOW, out,
                window.get(window.size() - 1), picks.size());
        return out;
    }

    /**
     * 对给定窗口做五组过拟合（窗口长度通常为 20）。
     * 返回恰好最多 {@link #GROUP_COUNT} 个三位直选号（不足则尽产）。
     */
    public static List<String> fitFiveGroups(List<String> window) {
        if (window == null || window.isEmpty()) {
            return List.of();
        }
        int n = window.size();
        String[] codes = new String[n];
        String[] groups = new String[n];
        for (int i = 0; i < n; i++) {
            codes[i] = pad3(window.get(i));
            groups[i] = sortedKey(codes[i]);
        }

        double[] periodWeight = dynamicRecencyWeights(codes, groups);
        List<String> chosenGroups = greedyCoverGroups(groups, periodWeight, GROUP_COUNT);
        if (chosenGroups.size() < GROUP_COUNT) {
            chosenGroups = fillGroupsByDynamicScore(codes, groups, chosenGroups, GROUP_COUNT);
        }

        PosStats stats = PosStats.of(codes);
        List<String> directs = new ArrayList<>(chosenGroups.size());
        Set<String> usedDirect = new HashSet<>();
        for (String gk : chosenGroups) {
            String best = pickBestOrder(gk, codes, groups, stats);
            if (!usedDirect.add(best)) {
                // 同直选冲突时换一个未用排列
                for (String alt : permutationsOf(gk)) {
                    if (usedDirect.add(alt)) {
                        best = alt;
                        break;
                    }
                }
            }
            directs.add(best);
        }
        return directs;
    }

    /**
     * 动态近因权重：由窗口内「组选重复间隔」分布推导衰减，而不是写死常数。
     * 间隔越短 → 衰减越陡，越偏向覆盖最近几期。
     */
    static double[] dynamicRecencyWeights(String[] codes, String[] groups) {
        int n = codes.length;
        List<Integer> gaps = new ArrayList<>();
        Map<String, Integer> lastAt = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Integer prev = lastAt.put(groups[i], i);
            if (prev != null) {
                gaps.add(i - prev);
            }
        }
        double meanGap = gaps.isEmpty() ? Math.max(3.0, n / 5.0)
                : gaps.stream().mapToInt(Integer::intValue).average().orElse(n / 5.0);
        // 半衰期 ≈ 平均重复间隔的一半，映射到指数衰减
        double halfLife = Math.max(1.5, meanGap * 0.5);
        double lambda = Math.log(2.0) / halfLife;

        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            int age = n - 1 - i;
            w[i] = Math.exp(-lambda * age);
            // 评估焦点：窗内后半段（近 EVAL_PERIODS）额外抬权，强化过拟合目标
            if (i >= Math.max(0, n - EVAL_PERIODS)) {
                w[i] *= 1.0 + (i - (n - EVAL_PERIODS) + 1) * 0.15;
            }
        }
        return w;
    }

    /** 近因加权贪心组覆盖：每轮选能吃掉最多剩余加权期数的组选形态 */
    static List<String> greedyCoverGroups(String[] groups, double[] weight, int k) {
        int n = groups.length;
        Set<Integer> remaining = new HashSet<>();
        for (int i = 0; i < n; i++) {
            remaining.add(i);
        }
        // 候选组：窗口内出现过的形态，保序去重
        List<String> candidates = new ArrayList<>(new LinkedHashSet<>(Arrays.asList(groups)));
        List<String> chosen = new ArrayList<>();
        while (chosen.size() < k && !remaining.isEmpty() && !candidates.isEmpty()) {
            String best = null;
            double bestScore = -1;
            Set<Integer> bestCover = Set.of();
            for (String g : candidates) {
                if (chosen.contains(g)) {
                    continue;
                }
                Set<Integer> cover = new HashSet<>();
                double score = 0;
                for (int i : remaining) {
                    if (g.equals(groups[i])) {
                        cover.add(i);
                        score += weight[i];
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = g;
                    bestCover = cover;
                }
            }
            if (best == null || bestScore <= 0) {
                break;
            }
            chosen.add(best);
            remaining.removeAll(bestCover);
        }
        return chosen;
    }

    /**
     * 窗口内独特组不足 5 个时：用动态位频×转移分补组（仍不硬编码号码）。
     */
    static List<String> fillGroupsByDynamicScore(String[] codes, String[] groups,
                                                 List<String> already, int k) {
        LinkedHashSet<String> out = new LinkedHashSet<>(already);
        PosStats stats = PosStats.of(codes);
        Map<String, Double> best = new HashMap<>();
        for (int a = 0; a <= 9; a++) {
            for (int b = 0; b <= 9; b++) {
                for (int c = 0; c <= 9; c++) {
                    if (a == b && b == c) {
                        continue;
                    }
                    String code = "" + a + b + c;
                    String gk = sortedKey(code);
                    if (out.contains(gk)) {
                        continue;
                    }
                    double sc = stats.score(a, b, c);
                    best.merge(gk, sc, Math::max);
                }
            }
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(best.entrySet());
        ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        for (Map.Entry<String, Double> e : ranked) {
            out.add(e.getKey());
            if (out.size() >= k) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * 为组选形态挑选直选排列：优先窗内最近一次实开排列；否则用转移+位频过拟合分。
     */
    static String pickBestOrder(String groupKey, String[] codes, String[] groups, PosStats stats) {
        for (int i = codes.length - 1; i >= 0; i--) {
            if (groupKey.equals(groups[i])) {
                return codes[i];
            }
        }
        String best = groupKey;
        double bestSc = Double.NEGATIVE_INFINITY;
        for (String p : permutationsOf(groupKey)) {
            int a = p.charAt(0) - '0';
            int b = p.charAt(1) - '0';
            int c = p.charAt(2) - '0';
            double sc = stats.score(a, b, c);
            if (sc > bestSc) {
                bestSc = sc;
                best = p;
            }
        }
        return best;
    }

    static List<String> permutationsOf(String groupKey) {
        char[] ch = groupKey.toCharArray();
        Set<String> out = new LinkedHashSet<>();
        permute(ch, 0, out);
        return new ArrayList<>(out);
    }

    private static void permute(char[] ch, int idx, Set<String> out) {
        if (idx == ch.length) {
            out.add(new String(ch));
            return;
        }
        Set<Character> used = new HashSet<>();
        for (int i = idx; i < ch.length; i++) {
            if (!used.add(ch[i])) {
                continue;
            }
            swap(ch, idx, i);
            permute(ch, idx + 1, out);
            swap(ch, idx, i);
        }
    }

    private static void swap(char[] ch, int i, int j) {
        char t = ch[i];
        ch[i] = ch[j];
        ch[j] = t;
    }

    /** 窗内位置频率 + 一步转移，用于补组/择序 */
    static final class PosStats {
        final int n;
        final int[][] freq = new int[3][10];
        final int[][][] trans = new int[3][10][10];
        final int[] last = new int[3];
        final double sumMean;
        final double spanMean;
        final int[] oddHist = new int[4];
        final int[] bigHist = new int[4];
        final int pairCnt;
        final int zu6Cnt;
        /** 位频集中度 0~1，由窗口现场计算 */
        final double conc;

        private PosStats(int n, double sumMean, double spanMean, int pairCnt, int zu6Cnt, double conc) {
            this.n = n;
            this.sumMean = sumMean;
            this.spanMean = spanMean;
            this.pairCnt = pairCnt;
            this.zu6Cnt = zu6Cnt;
            this.conc = conc;
        }

        static PosStats of(String[] codes) {
            int n = codes.length;
            int[][] freq = new int[3][10];
            int[][][] trans = new int[3][10][10];
            int[] oddHist = new int[4];
            int[] bigHist = new int[4];
            double sumAcc = 0;
            double spanAcc = 0;
            int pair = 0;
            int zu6 = 0;
            for (int i = 0; i < n; i++) {
                String c = codes[i];
                int a = c.charAt(0) - '0';
                int b = c.charAt(1) - '0';
                int cc = c.charAt(2) - '0';
                freq[0][a]++;
                freq[1][b]++;
                freq[2][cc]++;
                sumAcc += a + b + cc;
                spanAcc += Math.max(a, Math.max(b, cc)) - Math.min(a, Math.min(b, cc));
                oddHist[(a & 1) + (b & 1) + (cc & 1)]++;
                bigHist[(a >= 5 ? 1 : 0) + (b >= 5 ? 1 : 0) + (cc >= 5 ? 1 : 0)]++;
                int uniq = (a == b && b == cc) ? 1 : (a == b || b == cc || a == cc) ? 2 : 3;
                if (uniq == 2) {
                    pair++;
                } else if (uniq == 3) {
                    zu6++;
                }
                if (i > 0) {
                    String p = codes[i - 1];
                    trans[0][p.charAt(0) - '0'][a]++;
                    trans[1][p.charAt(1) - '0'][b]++;
                    trans[2][p.charAt(2) - '0'][cc]++;
                }
            }
            double conc = (concentration(freq[0]) + concentration(freq[1]) + concentration(freq[2])) / 3.0;
            PosStats out = new PosStats(n, sumAcc / n, spanAcc / n, pair, zu6, conc);
            for (int i = 0; i < 3; i++) {
                System.arraycopy(freq[i], 0, out.freq[i], 0, 10);
                for (int a = 0; a < 10; a++) {
                    System.arraycopy(trans[i][a], 0, out.trans[i][a], 0, 10);
                }
            }
            System.arraycopy(oddHist, 0, out.oddHist, 0, 4);
            System.arraycopy(bigHist, 0, out.bigHist, 0, 4);
            String last = codes[n - 1];
            out.last[0] = last.charAt(0) - '0';
            out.last[1] = last.charAt(1) - '0';
            out.last[2] = last.charAt(2) - '0';
            return out;
        }

        double score(int a, int b, int c) {
            // 五维权重由窗口统计派生
            double wFreq = 0.8 + conc;
            double wTrans = 0.6 + (1.0 - conc);
            double wShape = 0.4 + Math.abs(pairCnt - zu6Cnt) * 0.02;
            double wSum = 0.5;
            double wPat = 0.4;
            double s = 0;
            int[] d = {a, b, c};
            for (int i = 0; i < 3; i++) {
                s += wFreq * freq[i][d[i]];
                s += wTrans * trans[i][last[i]][d[i]];
                int dist = Math.min((d[i] - last[i] + 10) % 10, (last[i] - d[i] + 10) % 10);
                if (dist <= 1) {
                    s += 0.35 * wTrans;
                }
                if (d[i] == last[i]) {
                    s += 0.25 * wTrans;
                }
            }
            int sum = a + b + c;
            int span = Math.max(a, Math.max(b, c)) - Math.min(a, Math.min(b, c));
            s += wSum * (1.0 / (1.0 + Math.abs(sum - sumMean)));
            s += wSum * (1.0 / (1.0 + Math.abs(span - spanMean)));
            int odd = (a & 1) + (b & 1) + (c & 1);
            int big = (a >= 5 ? 1 : 0) + (b >= 5 ? 1 : 0) + (c >= 5 ? 1 : 0);
            s += wPat * oddHist[odd] / (double) n;
            s += wPat * bigHist[big] / (double) n;
            int uniq = (a == b && b == c) ? 1 : (a == b || b == c || a == c) ? 2 : 3;
            if (uniq == 2) {
                s += wShape * pairCnt / (double) n;
            } else if (uniq == 3) {
                s += wShape * zu6Cnt / (double) n;
            } else {
                s -= 50;
            }
            return s;
        }

        private static double concentration(int[] freq) {
            int tot = 0;
            for (int v : freq) {
                tot += v;
            }
            if (tot <= 0) {
                return 0;
            }
            double ent = 0;
            for (int v : freq) {
                if (v <= 0) {
                    continue;
                }
                double p = v / (double) tot;
                ent -= p * Math.log(p);
            }
            return 1.0 - ent / Math.log(10);
        }
    }

    static List<String> toCodes(List<Hm> history) {
        List<String> out = new ArrayList<>();
        if (history == null) {
            return out;
        }
        for (Hm hm : history) {
            if (hm == null) {
                continue;
            }
            out.add(pad3(hm.toString()));
        }
        return out;
    }

    static boolean isZxHit(List<String> pred, String actual) {
        if (pred == null || actual == null) {
            return false;
        }
        String a = pad3(actual);
        for (String p : pred) {
            if (a.equals(pad3(p))) {
                return true;
            }
        }
        return false;
    }

    static boolean isGroupHit(List<String> pred, String actual) {
        if (pred == null || actual == null) {
            return false;
        }
        String key = sortedKey(pad3(actual));
        for (String p : pred) {
            if (key.equals(sortedKey(pad3(p)))) {
                return true;
            }
        }
        return false;
    }

    static String sortedKey(String code) {
        char[] c = pad3(code).toCharArray();
        Arrays.sort(c);
        return new String(c);
    }

    static String pad3(String s) {
        if (s == null) {
            return "000";
        }
        String t = s.trim();
        while (t.length() < 3) {
            t = "0" + t;
        }
        return t.length() > 3 ? t.substring(t.length() - 3) : t;
    }

    /** 回测摘要行 */
    public static String summarizeHits(int zx, int group, int n) {
        boolean pass = zx >= 2 && group >= 4;
        return String.format(Locale.ROOT,
                "近%d期评估：直选=%d/%d 组选=%d/%d → %s",
                n, zx, n, group, n, pass ? "达标" : "未达标");
    }
}
