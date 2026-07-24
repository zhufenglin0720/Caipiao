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
 * 胆码预测：百/十/个各输出 1 码（单码）。
 * <p>
 * 命中口径：对应位置完全一致（开奖某位 == 该位预测码；跨位不算）。
 * 选取：分位独立，在近窗内因果比较「近30期众数」与「上期同号」的定位命中，
 * 上期明显更优时跟号，否则取众数（可拟合、无硬编码开奖号）。命中率优先。
 * 输出：{@code 百位:7 十位:3 个位:5}（仅页面展示，不发邮件）。
 */
@Slf4j
public final class RuleBasedDanMaUtils {

    public enum GameKind {
        SD_3D,
        PL3
    }

    /** 每位候选码数量（单码） */
    public static final int PER_POS = 1;
    /** 策略比较近窗长度 */
    public static final int FIT_VAL = 45;
    /** 众数统计窗 */
    public static final int MODE_W = 30;
    /** 上期策略需至少领先的命中次数才切换 */
    public static final int SWITCH_MARGIN = 1;
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
        int[] pick = pickSingle(history);
        String out = format(pick);
        log.info("胆码预测[{}]: {} | strategy=mode-vs-last-fit val={} modeW={} margin={}",
                kind, out, FIT_VAL, MODE_W, SWITCH_MARGIN);
        return out;
    }

    /** 回测入口：单码定位拟合 */
    static int[] adaptCover(List<Hm> history, int ignoredVal) {
        return pickSingle(history);
    }

    /** 兼容旧多码回测接口：退化为 3×1 */
    static int[][] adaptCoverMulti(List<Hm> history, int ignoredVal) {
        int[] one = pickSingle(history);
        return new int[][]{{one[0]}, {one[1]}, {one[2]}};
    }

    /**
     * 分位独立：近 {@link #FIT_VAL} 期因果比较众数 vs 上期同号，
     * 上期命中 ≥ 众数命中 + {@link #SWITCH_MARGIN} 则跟号，否则取众数。
     */
    static int[] pickSingle(List<Hm> history) {
        int[] out = new int[3];
        if (history.size() < MIN_HISTORY) {
            return modeW(history, MODE_W);
        }
        for (int pos = 0; pos < 3; pos++) {
            out[pos] = fitPos(history, pos);
        }
        return out;
    }

    private static int fitPos(List<Hm> history, int pos) {
        int n = history.size();
        int from = Math.max(1, n - FIT_VAL);
        int modeHits = 0;
        int lastHits = 0;
        for (int t = from; t < n; t++) {
            List<Hm> sub = history.subList(0, t);
            int act = digitsOf(history.get(t).toString())[pos];
            if (modeW(sub, MODE_W)[pos] == act) {
                modeHits++;
            }
            if (digitsOf(sub.get(sub.size() - 1).toString())[pos] == act) {
                lastHits++;
            }
        }
        if (lastHits >= modeHits + SWITCH_MARGIN) {
            return digitsOf(history.get(n - 1).toString())[pos];
        }
        return modeW(history, MODE_W)[pos];
    }

    static int[] modeW(List<Hm> h, int w) {
        int[] out = new int[3];
        List<Hm> t = tail(h, w);
        int[] last = digitsOf(h.get(h.size() - 1).toString());
        for (int p = 0; p < 3; p++) {
            int[] f = new int[10];
            for (Hm x : t) {
                f[digitsOf(x.toString())[p]]++;
            }
            int best = 0;
            for (int d = 1; d < 10; d++) {
                if (f[d] > f[best] || (f[d] == f[best] && d == last[p])) {
                    best = d;
                }
            }
            out[p] = best;
        }
        return out;
    }

    static String format(int[] pick) {
        return String.format(Locale.ROOT, "百位:%d 十位:%d 个位:%d", pick[0], pick[1], pick[2]);
    }

    static String formatMulti(int[][] pick) {
        return format(new int[]{pick[0][0], pick[1][0], pick[2][0]});
    }

    public static int[][] parseMulti(String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        String norm = answer.replace('：', ':');
        Matcher m = POS_PAT.matcher(norm);
        int[][] out = new int[3][];
        while (m.find()) {
            int pos = switch (m.group(1)) {
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

    public static int[] parseDigits(String answer) {
        int[][] m = parseMulti(answer);
        if (m == null) {
            return null;
        }
        return new int[]{m[0][0], m[1][0], m[2][0]};
    }

    public static boolean isFullHit(String danMa, String realHm) {
        boolean[] h = posHits(danMa, realHm);
        return h != null && h[0] && h[1] && h[2];
    }

    public static boolean isAnyPosHit(String danMa, String realHm) {
        boolean[] h = posHits(danMa, realHm);
        return h != null && (h[0] || h[1] || h[2]);
    }

    public static boolean isAnyPosHit(int[][] pick, int[] actual) {
        if (pick == null || actual == null) {
            return false;
        }
        for (int p = 0; p < 3; p++) {
            if (pick[p] != null && pick[p].length > 0 && pick[p][0] == actual[p]) {
                return true;
            }
        }
        return false;
    }

    /** @deprecated 旧集合相交口径；现等同于至少1位定位 */
    @Deprecated
    public static boolean isUnionHit(String danMa, String realHm) {
        return isAnyPosHit(danMa, realHm);
    }

    @Deprecated
    public static boolean isUnionHit(int[] pick, int[] actual) {
        if (pick == null || actual == null) {
            return false;
        }
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
                contains(m[0], act[0]),
                contains(m[1], act[1]),
                contains(m[2], act[2])
        };
    }

    public static boolean[] posHits(int[][] pick, int[] actual) {
        if (pick == null || actual == null) {
            return null;
        }
        return new boolean[]{
                contains(pick[0], actual[0]),
                contains(pick[1], actual[1]),
                contains(pick[2], actual[2])
        };
    }

    private static boolean contains(int[] cands, int digit) {
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
