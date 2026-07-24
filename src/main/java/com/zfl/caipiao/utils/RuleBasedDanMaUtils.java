package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 胆码预测：百/十/个各输出 {@link #PER_POS} 码，按「对应位置命中」评估。
 * <p>
 * 命中定义：开奖某位数字落在该位预测列表中（不是跨位号码集合相交）。
 * 主指标：至少 1 位定位命中，目标 ≥65%。
 * 输出示例：{@code 百位:9,6,8 十位:2,0,3 个位:5,7,1}（仅页面展示，不发邮件）。
 */
@Slf4j
public final class RuleBasedDanMaUtils {

    public enum GameKind {
        SD_3D,
        PL3
    }

    /** 每位候选码数量 */
    public static final int PER_POS = 3;
    private static final int MIN_HISTORY = 30;
    private static final Pattern POS_PAT = Pattern.compile("([百十个])位[:：]([0-9,]+)");

    private RuleBasedDanMaUtils() {
    }

    public static String get3dDanMa() {
        return predict(HmCache.getSdCache(), HmCache.getSdCompareCache(), GameKind.SD_3D);
    }

    public static String getPl3DanMa() {
        return predict(HmCache.getPl3Cache(), HmCache.getPl3CompareCache(), GameKind.PL3);
    }

    public static String predict(List<Hm> history, List<HmCache.CompareDto> compares, GameKind kind) {
        if (history == null || history.size() < MIN_HISTORY) {
            return "";
        }
        int[][] pick = pickPositional(history);
        softBoostExternal(pick, history, compares, kind);
        String out = formatMulti(pick);
        log.info("胆码预测[{}]: {} | perPos={} strategy=pos-topk", kind, out, PER_POS);
        return out;
    }

    /** 回测入口：每位 Top-{@link #PER_POS}，不依赖外部比对缓存 */
    static int[][] adaptCoverMulti(List<Hm> history, int ignoredVal) {
        return pickPositional(history);
    }

    /**
     * 兼容旧单码接口：返回每位第一候选（仅探针/过渡）。
     */
    static int[] adaptCover(List<Hm> history, int val) {
        int[][] m = adaptCoverMulti(history, val);
        return new int[]{m[0][0], m[1][0], m[2][0]};
    }

    /**
     * 分位独立打分后取 Top-K：多窗频次 + 遗漏甜区 + 一位转移。
     * 允许跨位重复（定位命中不要求三位互异）。
     */
    static int[][] pickPositional(List<Hm> history) {
        int[][] out = new int[3][PER_POS];
        for (int pos = 0; pos < 3; pos++) {
            out[pos] = topKDouble(scorePositionPositional(history, pos), PER_POS);
        }
        return out;
    }

    static double[] scorePositionPositional(List<Hm> history, int pos) {
        double[] s = new double[10];
        int[][] windows = {{12, 2}, {25, 3}, {40, 2}, {60, 1}};
        for (int[] w : windows) {
            List<Hm> t = tail(history, w[0]);
            for (int i = 0; i < t.size(); i++) {
                double wt = w[1] * (1.0 + 0.5 * Math.exp(-0.05 * (t.size() - 1 - i)));
                s[digitsOf(t.get(i).toString())[pos]] += wt;
            }
        }

        int[] last = digitsOf(history.get(history.size() - 1).toString());
        int lastD = last[pos];
        List<Hm> tm = tail(history, 70);
        int[][] tr = new int[10][10];
        for (int i = 1; i < tm.size(); i++) {
            int prev = digitsOf(tm.get(i - 1).toString())[pos];
            int cur = digitsOf(tm.get(i).toString())[pos];
            tr[prev][cur]++;
        }
        for (int d = 0; d < 10; d++) {
            s[d] += 2.0 * tr[lastD][d];
        }

        int n = Math.min(45, history.size());
        List<Hm> to = tail(history, n);
        int[] omit = new int[10];
        Arrays.fill(omit, n);
        for (int i = 0; i < to.size(); i++) {
            omit[digitsOf(to.get(i).toString())[pos]] = to.size() - 1 - i;
        }
        double mean = 0;
        for (int o : omit) {
            mean += o;
        }
        mean /= 10.0;
        for (int d = 0; d < 10; d++) {
            s[d] += 2.2 / (1.0 + Math.abs(omit[d] - mean * 0.7));
            if (omit[d] >= 2 && omit[d] <= 8) {
                s[d] += 0.7;
            }
        }
        s[(lastD + 1) % 10] += 0.35;
        s[(lastD + 9) % 10] += 0.35;
        return s;
    }

    /** 外部信号：在不改变候选集合大小的前提下微调各位分数后重排 Top-K */
    private static void softBoostExternal(int[][] pick, List<Hm> history,
                                          List<HmCache.CompareDto> compares, GameKind kind) {
        if (pick == null || compares == null) {
            return;
        }
        for (int pos = 0; pos < 3; pos++) {
            double[] sc = scorePositionPositional(history, pos);
            boostPosFromDingWei(sc, compares, pos, 0.35);
            boostPosFromDadi(sc, history, kind, pos, 0.25);
            int[] rerank = topKDouble(sc, PER_POS);
            // 保留原集合优先：先放原候选中仍靠前的，再补齐
            boolean[] keep = new boolean[10];
            for (int v : pick[pos]) {
                if (v >= 0 && v <= 9) {
                    keep[v] = true;
                }
            }
            int[] merged = new int[PER_POS];
            int n = 0;
            boolean[] used = new boolean[10];
            for (int v : rerank) {
                if (keep[v] && !used[v]) {
                    merged[n++] = v;
                    used[v] = true;
                    if (n >= PER_POS) {
                        break;
                    }
                }
            }
            for (int v : rerank) {
                if (n >= PER_POS) {
                    break;
                }
                if (!used[v]) {
                    merged[n++] = v;
                    used[v] = true;
                }
            }
            while (n < PER_POS) {
                merged[n++] = pick[pos][Math.min(n, pick[pos].length - 1)];
            }
            pick[pos] = merged;
        }
    }

    private static void boostPosFromDingWei(double[] sc, List<HmCache.CompareDto> compares, int pos, double w) {
        if (compares == null || compares.isEmpty()) {
            return;
        }
        for (int i = compares.size() - 1; i >= 0 && i >= compares.size() - 3; i--) {
            String dw = compares.get(i).getAiDingWeiHm();
            if (dw == null || dw.isBlank()) {
                continue;
            }
            Matcher m = POS_PAT.matcher(dw.replace('：', ':'));
            while (m.find()) {
                String label = m.group(1);
                int p = switch (label) {
                    case "百" -> 0;
                    case "十" -> 1;
                    case "个" -> 2;
                    default -> -1;
                };
                if (p != pos) {
                    continue;
                }
                for (String part : m.group(2).split(",")) {
                    String t = part.trim();
                    if (t.matches("\\d")) {
                        sc[t.charAt(0) - '0'] += w;
                    }
                }
            }
        }
    }

    private static void boostPosFromDadi(double[] sc, List<Hm> history, GameKind kind, int pos, double w) {
        try {
            String dadi = kind == GameKind.SD_3D
                    ? RuleBasedPredictUtils.get3dPredict()
                    : RuleBasedPredictUtils.getPl3Predict();
            if (dadi == null || dadi.isBlank()) {
                // 无缓存时用历史末段模拟：跳过
                return;
            }
            int[] cnt = new int[10];
            int n = 0;
            for (String bet : dadi.split(",")) {
                String t = pad3(bet.trim());
                if (t.length() < 3) {
                    continue;
                }
                int d = t.charAt(pos) - '0';
                if (d >= 0 && d <= 9) {
                    cnt[d]++;
                    n++;
                }
            }
            if (n == 0) {
                return;
            }
            for (int d = 0; d < 10; d++) {
                sc[d] += w * (cnt[d] / (double) n) * 10.0;
            }
        } catch (Throwable ignored) {
            // 大底不可用时跳过
        }
    }

    static String formatMulti(int[][] pick) {
        return String.format(Locale.ROOT, "百位:%s 十位:%s 个位:%s",
                join(pick[0]), join(pick[1]), join(pick[2]));
    }

    /** 兼容旧单码格式化 */
    static String format(int[] pick) {
        return String.format(Locale.ROOT, "百位:%d 十位:%d 个位:%d", pick[0], pick[1], pick[2]);
    }

    private static String join(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(a[i]);
        }
        return sb.toString();
    }

    /**
     * 解析为每位候选列表。兼容旧单码格式。
     *
     * @return length-3，每位为候选数组；失败返回 null
     */
    public static int[][] parseMulti(String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        String norm = answer.replace('：', ':');
        Matcher m = POS_PAT.matcher(norm);
        int[][] out = new int[3][];
        while (m.find()) {
            String label = m.group(1);
            int pos = switch (label) {
                case "百" -> 0;
                case "十" -> 1;
                case "个" -> 2;
                default -> -1;
            };
            if (pos < 0) {
                continue;
            }
            String[] parts = m.group(2).split(",");
            int[] arr = new int[parts.length];
            int n = 0;
            for (String p : parts) {
                String t = p.trim();
                if (t.matches("\\d")) {
                    arr[n++] = t.charAt(0) - '0';
                }
            }
            if (n == 0) {
                return null;
            }
            out[pos] = Arrays.copyOf(arr, n);
        }
        if (out[0] == null || out[1] == null || out[2] == null) {
            return null;
        }
        return out;
    }

    /** 旧接口：取每位第一码 */
    public static int[] parseDigits(String answer) {
        int[][] m = parseMulti(answer);
        if (m == null) {
            return null;
        }
        return new int[]{m[0][0], m[1][0], m[2][0]};
    }

    /** 三位定位全中：开奖三位均落在对应位候选中 */
    public static boolean isFullHit(String danMa, String realHm) {
        boolean[] h = posHits(danMa, realHm);
        return h != null && h[0] && h[1] && h[2];
    }

    /** 至少 1 位定位命中（主指标） */
    public static boolean isAnyPosHit(String danMa, String realHm) {
        boolean[] h = posHits(danMa, realHm);
        return h != null && (h[0] || h[1] || h[2]);
    }

    public static boolean isAnyPosHit(int[][] pick, int[] actual) {
        if (pick == null || actual == null || pick.length < 3 || actual.length < 3) {
            return false;
        }
        for (int p = 0; p < 3; p++) {
            if (posContains(pick[p], actual[p])) {
                return true;
            }
        }
        return false;
    }

    /** @deprecated 旧「号码集合相交」口径，保留兼容 */
    @Deprecated
    public static boolean isUnionHit(String danMa, String realHm) {
        return isAnyPosHit(danMa, realHm);
    }

    @Deprecated
    public static boolean isUnionHit(int[] pick, int[] actual) {
        if (pick == null || actual == null) {
            return false;
        }
        // 旧单码：退化为定位任一命中
        for (int p = 0; p < 3 && p < pick.length && p < actual.length; p++) {
            if (pick[p] == actual[p]) {
                return true;
            }
        }
        return false;
    }

    public static boolean[] posHits(String danMa, String realHm) {
        int[][] m = parseMulti(danMa);
        if (m == null || realHm == null || realHm.length() < 3) {
            return null;
        }
        int[] act = digitsOf(realHm);
        return new boolean[]{
                posContains(m[0], act[0]),
                posContains(m[1], act[1]),
                posContains(m[2], act[2])
        };
    }

    public static boolean[] posHits(int[][] pick, int[] actual) {
        if (pick == null || actual == null) {
            return null;
        }
        return new boolean[]{
                posContains(pick[0], actual[0]),
                posContains(pick[1], actual[1]),
                posContains(pick[2], actual[2])
        };
    }

    private static boolean posContains(int[] cands, int digit) {
        if (cands == null) {
            return false;
        }
        for (int v : cands) {
            if (v == digit) {
                return true;
            }
        }
        return false;
    }

    private static int[] topKDouble(double[] score, int k) {
        Integer[] idx = new Integer[10];
        for (int i = 0; i < 10; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> Double.compare(score[b], score[a]));
        int[] out = new int[Math.min(k, 10)];
        for (int i = 0; i < out.length; i++) {
            out[i] = idx[i];
        }
        return out;
    }

    private static List<Hm> tail(List<Hm> list, int n) {
        if (list.size() <= n) {
            return list;
        }
        return list.subList(list.size() - n, list.size());
    }

    static int[] digitsOf(String s) {
        String t = pad3(s);
        return new int[]{t.charAt(0) - '0', t.charAt(1) - '0', t.charAt(2) - '0'};
    }

    static String pad3(String s) {
        if (s == null) {
            return "000";
        }
        String t = s.trim();
        if (t.length() >= 3) {
            return t.substring(t.length() - 3);
        }
        return "0".repeat(3 - t.length()) + t;
    }
}
