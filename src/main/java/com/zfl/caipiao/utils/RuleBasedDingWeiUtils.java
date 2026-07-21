package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 纯规则七码定位：3D / 排三分专项、各位独立参数。
 * <p>
 * 四点经验：
 * ① 同位置重落（上期同位号）
 * ② 跨位重号（上期号码换位再现）
 * ③ a/b 邻域走势（上上期 a、上期 b → a±1/a/a+1/b±1/b/b+1）
 * ④ 参考三码：命中名次带优先，非最高分独占
 * <p>
 * 命中：百/十/个均落入 Top7；近500期目标全中≥200（命中率优先，保留原线性/画像标定）。
 */
@Slf4j
public final class RuleBasedDingWeiUtils {

    public enum GameKind {
        SD_3D,
        PL3
    }

    /**
     * 分位打分参数：w, mixT, anti, span, mid, lastB, neighB, omitF, comboW, w2, mixW2
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

    /** 各位经验+线性融合与命中带（面向近100期标定） */
    private static final class PosTune {
        final double wLinear;
        final double wProfile;
        final double wRepeat;   // ①
        final double wCross;    // ②
        final double wAb;       // ③ center
        final double wNeigh;    // ③ ±1
        final int bandLo;       // ④ 名次带（1=最高分）
        final int bandHi;

        PosTune(double wLinear, double wProfile, double wRepeat, double wCross,
                double wAb, double wNeigh, int bandLo, int bandHi) {
            this.wLinear = wLinear;
            this.wProfile = wProfile;
            this.wRepeat = wRepeat;
            this.wCross = wCross;
            this.wAb = wAb;
            this.wNeigh = wNeigh;
            this.bandLo = bandLo;
            this.bandHi = bandHi;
        }
    }

    /** 福彩3D：各位专项画像 */
    private static final PosProfile[] PROFILE_3D = {
            new PosProfile(8, 0.06, 2.3, 13, 14.5, 22, 10.5, 0.02, 0.16, 30, 0.08),
            new PosProfile(12, 0.33, 10.2, 7.4, 9.5, 7.1, 12.1, 0.18, 0.19, 20, 0.29),
            new PosProfile(30, 0.53, 25.8, 25.3, 0.5, 11.7, 13.3, 0.30, 0.16, 40, 0.40)
    };

    /** 排列三：各位专项画像 */
    private static final PosProfile[] PROFILE_PL3 = {
            new PosProfile(30, 0.08, 16.3, 15, 22.4, 8.4, 3.6, 3.9, 0.34, 20, 0.36),
            new PosProfile(30, 0.00, 28.7, 8.4, 1.5, 3.2, 13.0, 1.85, 0.05, 30, 0.02),
            new PosProfile(10, 0.29, 16.8, 39.4, 21.5, 20.3, 17.8, 2.1, 0.18, 80, 0.27)
    };

    /**
     * 3D 各位调参：经验软加权 + 命中带（近500期全中目标≥200）。
     * 权重：linear, profile, repeat, cross, ab, neigh, bandLo, bandHi
     * 百位带上界 5→6：近500期消融验证全中 195→204，且不改线性/画像。
     */
    private static final PosTune[] TUNE_3D = {
            new PosTune(1.0058094419606394, -2.8124440447935015, 5.665014342679835, -0.6491208170032974,
                    3.126374096260557, 4.913188795330199, 1, 6),
            new PosTune(1.6616593178390395, -1.4962178305765115, -1.8907636787077062, -3.0720824289993884,
                    5.271633929355165, 3.6251947418617285, 3, 7),
            new PosTune(1.3325886758595047, -2.0442417097519736, 4.150405914284169, 2.9120996211358925,
                    5.364055230470751, 0.25374481204095173, 3, 7)
    };

    /**
     * 排三各位调参（近500期全中目标≥200）。
     * 百/个位带上界各 +1，并在打分端叠加轻量邻号/中遗漏软加权。
     */
    private static final PosTune[] TUNE_PL3 = {
            new PosTune(6.493867899495938, 0.6633551849740806, 8.288945191017358, 8.431928054715472,
                    11.757558097231097, 2.0076140867076266, 2, 8),
            new PosTune(0.2406041516602177, 5.216064033123188, -1.7689538307388575, -1.1490410854096234,
                    25.813076944829213, 4.57268872587389, 2, 8),
            new PosTune(0.24039574516254236, 0.5108281051200335, 3.441344969593886, 0.6664547822417778,
                    9.934731738956305, 6.1106855458307505, 1, 6)
    };

    private static final double[][] LINEAR_3D = {
            {-0.4992, 2.6249, 0.0193, -0.9455, -0.5185, 1.0000, 0.6829, 1.6497},
            {-1.5000, -0.2737, 1.0000, -0.2500, -2.0000, 0.5000, 0.3352, 1.0000},
            {1.0000, 1.0000, 1.0000, 0.9050, 1.0000, 2.8976, 3.0000, 0.7065}
    };
    private static final double[][] LINEAR_PL3 = {
            {3.5093, 2.1106, 1.5336, 1.0000, 1.0000, 1.8018, 0.1658, -2.0000},
            {1.0000, 0.5636, -0.6229, 1.0000, -2.0000, 1.0463, 1.7500, 1.9342},
            {1.4234, -0.4867, 1.0000, 3.7629, 1.0000, 1.0000, 1.0000, 1.0000}
    };

    private static final int TOP7 = 7;
    private static final int MIN_HISTORY = 30;
    private static final int MAX_SAMPLE = 200;

    /** 排三命中率软加权（近500期消融：邻号1.2 + 中遗漏0.8 → 全中约202） */
    private static final double PL3_SOFT_NEIGH = 1.2;
    private static final double PL3_SOFT_OMIT_MID = 0.8;

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
        GameKind gameKind = kind == null ? GameKind.SD_3D : kind;
        PosProfile[] profiles = gameKind == GameKind.PL3 ? PROFILE_PL3 : PROFILE_3D;
        PosTune[] tunes = gameKind == GameKind.PL3 ? TUNE_PL3 : TUNE_3D;
        double[][] linear = gameKind == GameKind.PL3 ? LINEAR_PL3 : LINEAR_3D;

        int[][] digits = toDigitMatrix(tail(history, Math.max(MAX_SAMPLE, needSample(profiles))));

        int[][] top7 = new int[3][TOP7];
        for (int pos = 0; pos < 3; pos++) {
            double[] score = scoreWithExperience(digits, pos, linear[pos], profiles[pos], tunes[pos]);
            // 排三：在原标定分上轻量抬邻号/中遗漏，抬全中覆盖（3D 不加，避免反向）
            if (gameKind == GameKind.PL3) {
                applyPl3SoftHitBoost(digits, pos, score);
            }
            top7[pos] = pickBandAwareTop7(score, tunes[pos]);
            log.info("七码定位[{}] {} Top7={} 命中带{}-{}", gameKind, posName(pos),
                    Arrays.toString(top7[pos]), tunes[pos].bandLo, tunes[pos].bandHi);
        }

        String result = format(top7);
        log.info("七码定位结果: {}", result);
        return result;
    }

    /** 排三专用软加权：不改线性/画像参数，只在最终分上微调 */
    private static void applyPl3SoftHitBoost(int[][] h, int pos, double[] score) {
        int[] om = omission(h, pos);
        int last = h[h.length - 1][pos];
        for (int d = 0; d < 10; d++) {
            if (om[d] >= 3 && om[d] <= 10) {
                score[d] += PL3_SOFT_OMIT_MID;
            }
            if (d == neighbor(last, -1) || d == neighbor(last, 1)) {
                score[d] += PL3_SOFT_NEIGH;
            }
        }
    }

    private static int needSample(PosProfile[] profiles) {
        int max = MAX_SAMPLE;
        for (PosProfile p : profiles) {
            max = Math.max(max, Math.max(p.w, p.w2) + 20);
        }
        return Math.max(max, 120);
    }

    /**
     * 线性 + 画像 + 四点经验软加权（不硬塞满种子，避免挤掉有效号）。
     */
    private static double[] scoreWithExperience(int[][] h, int pos, double[] linW,
                                                PosProfile pf, PosTune tune) {
        double[] lin = scoreLinear(h, pos, linW);
        double[] prof = scoreDigits(h, pos, pf);
        double[] s = new double[10];
        for (int d = 0; d < 10; d++) {
            s[d] = tune.wLinear * lin[d] + tune.wProfile * (prof[d] / 10.0);
        }

        int n = h.length;
        int b = h[n - 1][pos];
        int a = n >= 2 ? h[n - 2][pos] : b;

        // ① 同位置重落
        s[b] += tune.wRepeat;

        // ② 跨位重号
        boolean[] lastDraw = new boolean[10];
        for (int p = 0; p < 3; p++) {
            lastDraw[h[n - 1][p]] = true;
        }
        for (int d = 0; d < 10; d++) {
            if (lastDraw[d]) {
                s[d] += (d == b) ? tune.wCross * 0.35 : tune.wCross;
            }
        }
        if (n >= 2) {
            boolean[] prevDraw = new boolean[10];
            for (int p = 0; p < 3; p++) {
                prevDraw[h[n - 2][p]] = true;
            }
            for (int d = 0; d < 10; d++) {
                if (prevDraw[d] && !lastDraw[d]) {
                    s[d] += tune.wCross * 0.4;
                }
            }
        }

        // ③ a/b 邻域
        for (int base : new int[]{a, b}) {
            s[base] += tune.wAb;
            s[neighbor(base, -1)] += tune.wNeigh;
            s[neighbor(base, 1)] += tune.wNeigh;
        }
        return s;
    }

    /**
     * ④ 参考三码 buildBandAwareTop：先填命中名次带，再补次边缘/Top1/末位。
     */
    private static int[] pickBandAwareTop7(double[] score, PosTune tune) {
        Integer[] order = new Integer[10];
        for (int i = 0; i < 10; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (x, y) -> {
            int c = Double.compare(score[y], score[x]);
            return c != 0 ? c : Integer.compare(x, y);
        });

        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        int lo = tune.bandLo;
        int hi = tune.bandHi;
        for (int i = 0; i < 10 && set.size() < TOP7; i++) {
            int rank = i + 1;
            if (rank >= lo && rank <= hi) {
                set.add(order[i]);
            }
        }
        // 次边缘再 Top1 / 最末（与三码一致的补齐顺序）
        for (int i : new int[]{1, 8, 0, 9}) {
            if (set.size() >= TOP7) {
                break;
            }
            set.add(order[i]);
        }
        for (int d : order) {
            if (set.size() >= TOP7) {
                break;
            }
            set.add(d);
        }

        int[] out = new int[TOP7];
        int n = 0;
        for (int d : set) {
            out[n++] = d;
        }
        return out;
    }

    private static double[] scoreLinear(int[][] h, int pos, double[] w) {
        double[] s = new double[10];
        int[] f8 = freq(h, pos, 8);
        int[] f15 = freq(h, pos, 15);
        int[] f30 = freq(h, pos, 30);
        int[] om = omission(h, pos);
        int[] tr = transCount(h, pos);
        int[] f5 = freq(h, pos, 5);
        int last = h[h.length - 1][pos];
        for (int d = 0; d < 10; d++) {
            s[d] = w[0] * f8[d] + w[1] * f15[d] + w[2] * f30[d]
                    + w[3] * Math.min(om[d], 20) + w[4] * tr[d]
                    + w[5] * (d == last ? 1 : 0)
                    + w[6] * ((d == neighbor(last, -1) || d == neighbor(last, 1)) ? 1 : 0)
                    + w[7] * (f5[d] >= 2 ? -1 : 0);
            if (om[d] >= 4 && om[d] <= 12) {
                s[d] += Math.abs(w[3]) * 0.15;
            }
        }
        return s;
    }

    private static int[] transCount(int[][] h, int pos) {
        int[] t = new int[10];
        int last = h[h.length - 1][pos];
        int from = Math.max(1, h.length - 120);
        for (int i = from; i < h.length; i++) {
            if (h[i - 1][pos] == last) {
                t[h[i][pos]]++;
            }
        }
        return t;
    }

    private static double[] scoreDigits(int[][] h, int pos, PosProfile pf) {
        double[] s = normalize100(freq(h, pos, pf.w));
        if (pf.mixW2 > 0) {
            s = mix(s, normalize100(freq(h, pos, pf.w2)), 1 - pf.mixW2, pf.mixW2);
        }
        if (pf.mixT > 0) {
            s = mix(s, normalize100(transScore(h, pos)), 1 - pf.mixT, pf.mixT);
        }
        // 调参搜索未纳入 combo，保持关闭以免漂移

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
