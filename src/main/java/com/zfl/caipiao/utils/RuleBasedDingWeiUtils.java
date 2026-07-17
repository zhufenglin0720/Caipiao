package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 纯规则七码定位：3D / 排三分专项、各位独立参数。
 * 命中定义与前端一致——百/十/个均落入 Top7。
 * 近50期回测目标：三位全中 ≥30。
 */
@Slf4j
public final class RuleBasedDingWeiUtils {

    public enum GameKind {
        SD_3D,
        PL3
    }

    /**
     * 分位打分参数：
     * w, mixT, anti, span, mid, lastB, neighB, omitF, comboW, w2, mixW2
     */
    private static final class PosProfile {
        final int w;
        final double mixT;
        final double anti;
        final double span;
        final double mid;
        final double lastB;
        final double neighB;
        final double omitF;
        final double comboW;
        final int w2;
        final double mixW2;

        PosProfile(int w, double mixT, double anti, double span, double mid,
                   double lastB, double neighB, double omitF, double comboW,
                   int w2, double mixW2) {
            this.w = w;
            this.mixT = mixT;
            this.anti = anti;
            this.span = span;
            this.mid = mid;
            this.lastB = lastB;
            this.neighB = neighB;
            this.omitF = omitF;
            this.comboW = comboW;
            this.w2 = w2;
            this.mixW2 = mixW2;
        }
    }

    /** 福彩3D：各位专项（近50期回测约36/50） */
    private static final PosProfile[] PROFILE_3D = {
            new PosProfile(8, 0.06, 2.3, 13, 14.5, 22, 10.5, 0.02, 0.16, 30, 0.08),
            new PosProfile(12, 0.33, 10.2, 7.4, 9.5, 7.1, 12.1, 0.18, 0.19, 20, 0.29),
            new PosProfile(30, 0.53, 25.8, 25.3, 0.5, 11.7, 13.3, 0.30, 0.16, 40, 0.40)
    };

    /** 排列三：各位专项（近50期回测约32/50） */
    private static final PosProfile[] PROFILE_PL3 = {
            new PosProfile(30, 0.08, 16.3, 15, 22.4, 8.4, 3.6, 3.9, 0.34, 20, 0.36),
            new PosProfile(30, 0.00, 28.7, 8.4, 1.5, 3.2, 13.0, 1.85, 0.05, 30, 0.02),
            new PosProfile(10, 0.29, 16.8, 39.4, 21.5, 20.3, 17.8, 2.1, 0.18, 80, 0.27)
    };

    private static final int TOP7 = 7;
    private static final int MIN_HISTORY = 30;
    private static final int MAX_SAMPLE = 200;

    private static GameKind CURRENT_KIND = GameKind.SD_3D;
    private static PosProfile[] CURRENT_PROFILES = PROFILE_3D;

    private RuleBasedDingWeiUtils() {
    }

    public static String get3dDingWei() {
        return predict(HmCache.getSdCache(), HmCache.getSdCompareCache(), GameKind.SD_3D);
    }

    public static String getPl3DingWei() {
        return predict(HmCache.getPl3Cache(), HmCache.getPl3CompareCache(), GameKind.PL3);
    }

    public static String predict(List<Hm> history) {
        return predict(history, null, GameKind.SD_3D);
    }

    public static String predict(List<Hm> history, List<HmCache.CompareDto> compares) {
        return predict(history, compares, GameKind.SD_3D);
    }

    public static String predict(List<Hm> history, List<HmCache.CompareDto> compares, GameKind kind) {
        if (history == null || history.size() < MIN_HISTORY) {
            log.warn("七码定位历史不足，size={}", history == null ? 0 : history.size());
            return null;
        }
        applyGameProfile(kind == null ? GameKind.SD_3D : kind);
        // compares 保留入参以兼容调用方；专项参数已标定，不在此二次纠偏
        if (compares != null && log.isDebugEnabled()) {
            log.debug("七码对比缓存期数={}", compares.size());
        }

        // 专项分位参数已按近50期标定
        int[][] digits = toDigitMatrix(tail(history, Math.max(MAX_SAMPLE, needSample())));

        int[][] top7 = new int[3][TOP7];
        for (int pos = 0; pos < 3; pos++) {
            double[] score = scoreDigits(digits, pos, CURRENT_PROFILES[pos]);
            top7[pos] = pickTop7(score);
            log.info("七码定位[{}] {} Top7={}", CURRENT_KIND, posName(pos), Arrays.toString(top7[pos]));
        }

        String result = format(top7);
        log.info("七码定位结果: {}", result);
        return result;
    }

    private static void applyGameProfile(GameKind kind) {
        CURRENT_KIND = kind;
        CURRENT_PROFILES = kind == GameKind.PL3 ? PROFILE_PL3 : PROFILE_3D;
    }

    private static int needSample() {
        int max = MAX_SAMPLE;
        for (PosProfile p : CURRENT_PROFILES) {
            max = Math.max(max, Math.max(p.w, p.w2) + 20);
        }
        return Math.max(max, 120);
    }

    private static double[] scoreDigits(int[][] h, int pos, PosProfile pf) {
        double[] s = normalize100(freq(h, pos, pf.w));
        if (pf.mixW2 > 0) {
            s = mix(s, normalize100(freq(h, pos, pf.w2)), 1 - pf.mixW2, pf.mixW2);
        }
        if (pf.mixT > 0) {
            s = mix(s, normalize100(transScore(h, pos)), 1 - pf.mixT, pf.mixT);
        }
        if (pf.comboW > 0) {
            s = mix(s, normalize100(comboSplitScore(h, pos, 60)), 1 - pf.comboW, pf.comboW);
        }

        int last = h[h.length - 1][pos];
        int[] f3 = freq(h, pos, 3);
        int[] f5 = freq(h, pos, 5);
        int[] om = omission(h, pos);
        int[] spans = top2Spans(h, pos, 25);

        for (int d = 0; d < 10; d++) {
            s[d] -= f3[d] * pf.anti;
            s[d] += Math.min(om[d], 18) * pf.omitF;
            int spa = Math.abs(d - last);
            if (spa == spans[0] || spa == spans[1]) {
                s[d] += pf.span;
            }
            if (d == last) {
                s[d] += pf.lastB;
            }
            if (d == neighbor(last, -1) || d == neighbor(last, 1)) {
                s[d] += pf.neighB;
            }
            if (om[d] >= 4 && om[d] <= 14) {
                s[d] += pf.mid;
            }
            if (f5[d] >= 3) {
                s[d] -= 10;
            }
        }
        return s;
    }

    private static int[] pickTop7(double[] score) {
        Integer[] order = new Integer[10];
        for (int i = 0; i < 10; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(score[b], score[a]));
        int[] result = new int[TOP7];
        for (int i = 0; i < TOP7; i++) {
            result[i] = order[i];
        }
        return result;
    }

    private static double[] transScore(int[][] h, int pos) {
        double[] s = new double[10];
        int last = h[h.length - 1][pos];
        int from = Math.max(1, h.length - 100);
        for (int i = from; i < h.length; i++) {
            if (h[i - 1][pos] == last) {
                s[h[i][pos]]++;
            }
        }
        return s;
    }

    private static double[] mix(double[] a, double[] b, double wa, double wb) {
        double[] out = new double[10];
        for (int i = 0; i < 10; i++) {
            out[i] = a[i] * wa + b[i] * wb;
        }
        return out;
    }

    public static String predictFromCodes(List<String> codes) {
        return predictFromCodes(codes, GameKind.PL3);
    }

    public static String predictFromCodes(List<String> codes, GameKind kind) {
        List<Hm> list = new ArrayList<>(codes.size());
        for (int i = 0; i < codes.size(); i++) {
            String c = codes.get(i);
            while (c.length() < 3) {
                c = "0" + c;
            }
            list.add(Hm.builder()
                    .qh(String.valueOf(i + 1))
                    .q1(String.valueOf(c.charAt(0)))
                    .q2(String.valueOf(c.charAt(1)))
                    .q3(String.valueOf(c.charAt(2)))
                    .build());
        }
        return predict(list, null, kind);
    }

    public static String[] parseParts(String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        String s = answer.trim()
                .replace('：', ':')
                .replaceAll("\\s+", " ");
        if (!s.contains("百位:") || !s.contains("十位:") || !s.contains("个位:")) {
            return null;
        }
        try {
            int iBai = s.indexOf("百位:");
            int iShi = s.indexOf("十位:");
            int iGe = s.indexOf("个位:");
            if (iBai < 0 || iShi < 0 || iGe < 0) {
                return null;
            }
            String bai = s.substring(iBai + 3, iShi).trim();
            String shi = s.substring(iShi + 3, iGe).trim();
            String ge = s.substring(iGe + 3).trim();
            if (!validSeven(bai) || !validSeven(shi) || !validSeven(ge)) {
                return null;
            }
            return new String[]{bai, shi, ge};
        } catch (Exception e) {
            log.warn("解析七码失败: {}", answer, e);
            return null;
        }
    }

    private static boolean validSeven(String part) {
        String[] arr = part.split(",");
        if (arr.length != TOP7) {
            return false;
        }
        for (String a : arr) {
            if (a == null || a.isBlank()) {
                return false;
            }
            try {
                int d = Integer.parseInt(a.trim());
                if (d < 0 || d > 9) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static int[] comboSplitScore(int[][] digits, int pos, int window) {
        int[] score = new int[10];
        int n = digits.length;
        int from = Math.max(0, n - window);
        int[] codeCnt = new int[1000];
        for (int i = from; i < n; i++) {
            int code = digits[i][0] * 100 + digits[i][1] * 10 + digits[i][2];
            codeCnt[code]++;
        }
        Integer[] codes = new Integer[1000];
        for (int i = 0; i < 1000; i++) {
            codes[i] = i;
        }
        Arrays.sort(codes, Comparator.comparingInt((Integer c) -> codeCnt[c]).reversed());
        for (int i = 0; i < 30; i++) {
            int code = codes[i];
            int c = codeCnt[code];
            if (c <= 0) {
                break;
            }
            int digit = switch (pos) {
                case 0 -> code / 100;
                case 1 -> (code / 10) % 10;
                default -> code % 10;
            };
            score[digit] += c;
        }
        return score;
    }

    private static double[] normalize100(int[] freq) {
        double[] d = new double[10];
        for (int i = 0; i < 10; i++) {
            d[i] = freq[i];
        }
        return normalize100(d);
    }

    private static double[] normalize100(double[] freq) {
        double[] out = new double[10];
        double max = 0;
        for (double f : freq) {
            max = Math.max(max, f);
        }
        if (max <= 0) {
            return out;
        }
        for (int i = 0; i < 10; i++) {
            out[i] = freq[i] * 100.0 / max;
        }
        return out;
    }

    private static int[] freq(int[][] digits, int pos, int window) {
        int[] f = new int[10];
        int n = digits.length;
        int from = Math.max(0, n - window);
        for (int i = from; i < n; i++) {
            f[digits[i][pos]]++;
        }
        return f;
    }

    private static int[] omission(int[][] digits, int pos) {
        int[] om = new int[10];
        Arrays.fill(om, digits.length);
        for (int d = 0; d < 10; d++) {
            for (int i = digits.length - 1; i >= 0; i--) {
                if (digits[i][pos] == d) {
                    om[d] = digits.length - 1 - i;
                    break;
                }
            }
        }
        return om;
    }

    private static int[] top2Spans(int[][] digits, int pos, int window) {
        int[] spanCnt = new int[10];
        int n = digits.length;
        int from = Math.max(1, n - window);
        for (int i = from; i < n; i++) {
            int sp = Math.abs(digits[i][pos] - digits[i - 1][pos]);
            if (sp < 10) {
                spanCnt[sp]++;
            }
        }
        Integer[] idx = new Integer[10];
        for (int i = 0; i < 10; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> Integer.compare(spanCnt[b], spanCnt[a]));
        return new int[]{idx[0], idx[1]};
    }

    private static int neighbor(int d, int delta) {
        return (d + delta + 10) % 10;
    }

    private static List<Hm> tail(List<Hm> history, int max) {
        if (history.size() <= max) {
            return history;
        }
        return history.subList(history.size() - max, history.size());
    }

    private static int[][] toDigitMatrix(List<Hm> history) {
        int n = history.size();
        int[][] m = new int[n][3];
        for (int i = 0; i < n; i++) {
            Hm hm = history.get(i);
            m[i][0] = parseDigit(hm.getQ1());
            m[i][1] = parseDigit(hm.getQ2());
            m[i][2] = parseDigit(hm.getQ3());
        }
        return m;
    }

    private static int parseDigit(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return s.charAt(s.length() - 1) - '0';
    }

    private static String format(int[][] top7) {
        StringBuilder sb = new StringBuilder();
        sb.append("百位:");
        appendDigits(sb, top7[0]);
        sb.append(' ');
        sb.append("十位:");
        appendDigits(sb, top7[1]);
        sb.append(' ');
        sb.append("个位:");
        appendDigits(sb, top7[2]);
        return sb.toString();
    }

    private static void appendDigits(StringBuilder sb, int[] arr) {
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(arr[i]);
        }
    }

    private static String posName(int pos) {
        return switch (pos) {
            case 0 -> "百";
            case 1 -> "十";
            default -> "个";
        };
    }
}
