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
 * 胆码预测：百/十/个各输出概率最高的 1 个数字。
 * <p>
 * 参考七码定位、200注大底、过拟合五组的信号做融合打分，取各位 argmax。
 * 输出格式：{@code 百位:7 十位:3 个位:5}（仅页面展示，不发邮件）。
 */
@Slf4j
public final class RuleBasedDanMaUtils {

    public enum GameKind {
        SD_3D,
        PL3
    }

    private static final int MIN_HISTORY = 30;
    private static final int SAMPLE = 60;
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
        int[][] digits = toDigits(tail(history, SAMPLE));
        double[][] score = new double[3][10];
        for (int pos = 0; pos < 3; pos++) {
            score[pos] = scorePosition(digits, pos);
        }

        // 动态权重：由近窗集中度决定外部算法参考强度（非硬编码开奖号）
        double conc = avgConcentration(digits);
        double wDingWei = 0.55 + 0.35 * (1.0 - conc);
        double wDadi = 0.45 + 0.25 * conc;
        double wOverfit = 0.40 + 0.30 * conc;

        boostFromDingWei(score, history, compares, kind, wDingWei);
        boostFromDadi(score, history, compares, kind, wDadi);
        boostFromOverfit(score, history, kind, wOverfit);

        int[] pick = new int[3];
        for (int pos = 0; pos < 3; pos++) {
            pick[pos] = argmax(score[pos]);
        }
        String out = format(pick);
        log.info("胆码预测[{}]: {} | 权重 dingWei×{} dadi×{} overfit×{} conc={}",
                kind, out,
                String.format(Locale.ROOT, "%.2f", wDingWei),
                String.format(Locale.ROOT, "%.2f", wDadi),
                String.format(Locale.ROOT, "%.2f", wOverfit),
                String.format(Locale.ROOT, "%.2f", conc));
        return out;
    }

    /** 历史窗位分：频次 / 遗漏 / 转移 / 重号 / 邻号 */
    static double[] scorePosition(int[][] digits, int pos) {
        int n = digits.length;
        int[] f5 = new int[10], f10 = new int[10], f20 = new int[10], om = new int[10];
        Arrays.fill(om, n);
        int[] trans = new int[10];
        for (int t = 0; t < n; t++) {
            int d = digits[t][pos];
            if (t >= n - 5) {
                f5[d]++;
            }
            if (t >= n - 10) {
                f10[d]++;
            }
            if (t >= n - 20) {
                f20[d]++;
            }
            om[d] = n - 1 - t;
        }
        int last = digits[n - 1][pos];
        for (int t = 1; t < n; t++) {
            if (digits[t - 1][pos] == last) {
                trans[digits[t][pos]]++;
            }
        }
        double meanOm = 0;
        for (int d = 0; d < 10; d++) {
            meanOm += om[d];
        }
        meanOm /= 10.0;

        // 近窗唯一度 → 动态热/冷权重
        int uniq = 0;
        boolean[] seen = new boolean[10];
        for (int t = Math.max(0, n - 12); t < n; t++) {
            int d = digits[t][pos];
            if (!seen[d]) {
                seen[d] = true;
                uniq++;
            }
        }
        double hotW = 1.2 - 0.04 * uniq;
        double coldW = 0.4 + 0.06 * uniq;

        double[] s = new double[10];
        for (int d = 0; d < 10; d++) {
            s[d] = hotW * (1.4 * f5[d] + 1.0 * f10[d] + 0.6 * f20[d])
                    + coldW * (1.0 / (1.0 + Math.abs(om[d] - meanOm)))
                    + 1.1 * trans[d]
                    + (d == last ? 0.8 : 0)
                    + (minDist(d, last) == 1 ? 0.55 : 0);
            // 极热软降权，避免一味追号
            if (f5[d] >= 3) {
                s[d] *= 0.92;
            }
        }
        return s;
    }

    /** 参考七码：提升七码池靠前数字，尤其头名 */
    private static void boostFromDingWei(double[][] score, List<Hm> history,
                                         List<HmCache.CompareDto> compares, GameKind kind,
                                         double weight) {
        try {
            String dw = kind == GameKind.SD_3D
                    ? RuleBasedDingWeiUtils.predict(history, compares, RuleBasedDingWeiUtils.GameKind.SD_3D)
                    : RuleBasedDingWeiUtils.predict(history, compares, RuleBasedDingWeiUtils.GameKind.PL3);
            String[] parts = RuleBasedDingWeiUtils.parseParts(dw);
            if (parts == null) {
                return;
            }
            for (int pos = 0; pos < 3; pos++) {
                String[] digs = parts[pos].split(",");
                for (int i = 0; i < digs.length; i++) {
                    int d = digs[i].trim().charAt(0) - '0';
                    if (d < 0 || d > 9) {
                        continue;
                    }
                    // 头名加成最高，其后递减
                    double rankBoost = 1.0 - i * 0.08;
                    if (rankBoost < 0.3) {
                        rankBoost = 0.3;
                    }
                    score[pos][d] += weight * 2.2 * rankBoost;
                }
            }
        } catch (Exception e) {
            log.debug("胆码参考七码失败: {}", e.getMessage());
        }
    }

    /** 参考200注大底：统计各位数字出现密度 */
    private static void boostFromDadi(double[][] score, List<Hm> history,
                                      List<HmCache.CompareDto> compares, GameKind kind,
                                      double weight) {
        try {
            String raw = kind == GameKind.SD_3D
                    ? RuleBasedPredictUtils.predict(history, compares, RuleBasedPredictUtils.GameKind.SD_3D)
                    : RuleBasedPredictUtils.predict(history, compares, RuleBasedPredictUtils.GameKind.PL3);
            if (raw == null || raw.isBlank()) {
                return;
            }
            int[][] cnt = new int[3][10];
            int n = 0;
            for (String bet : raw.split(",")) {
                String t = bet.trim();
                if (t.length() < 3) {
                    continue;
                }
                if (t.length() > 3) {
                    t = t.substring(t.length() - 3);
                }
                for (int pos = 0; pos < 3; pos++) {
                    int d = t.charAt(pos) - '0';
                    if (d >= 0 && d <= 9) {
                        cnt[pos][d]++;
                    }
                }
                n++;
                if (n >= 200) {
                    break;
                }
            }
            if (n == 0) {
                return;
            }
            for (int pos = 0; pos < 3; pos++) {
                for (int d = 0; d < 10; d++) {
                    score[pos][d] += weight * 1.8 * (cnt[pos][d] / (double) n) * 10.0;
                }
            }
        } catch (Exception e) {
            log.debug("胆码参考大底失败: {}", e.getMessage());
        }
    }

    /** 参考过拟合融合池：各位数字密度 */
    private static void boostFromOverfit(double[][] score, List<Hm> history, GameKind kind, double weight) {
        try {
            Overfit20PredictUtils.PredictResult r = Overfit20PredictUtils.predictResult(history);
            if (r == null || r.pool == null || r.pool.isEmpty()) {
                return;
            }
            int[][] cnt = new int[3][10];
            int n = 0;
            for (String bet : r.pool) {
                String t = pad3(bet);
                for (int pos = 0; pos < 3; pos++) {
                    int d = t.charAt(pos) - '0';
                    if (d >= 0 && d <= 9) {
                        cnt[pos][d]++;
                    }
                }
                n++;
            }
            if (n == 0) {
                return;
            }
            for (int pos = 0; pos < 3; pos++) {
                for (int d = 0; d < 10; d++) {
                    score[pos][d] += weight * 1.5 * (cnt[pos][d] / (double) n) * 10.0;
                }
            }
            // 展示五组头名额外加权
            if (r.displayFive != null) {
                for (String bet : r.displayFive) {
                    String t = pad3(bet);
                    for (int pos = 0; pos < 3; pos++) {
                        int d = t.charAt(pos) - '0';
                        if (d >= 0 && d <= 9) {
                            score[pos][d] += weight * 0.6;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("胆码参考过拟合失败: {}", e.getMessage());
        }
    }

    static String format(int[] pick) {
        return String.format(Locale.ROOT, "百位:%d 十位:%d 个位:%d", pick[0], pick[1], pick[2]);
    }

    /**
     * 解析胆码三位数字；非法返回 null。
     */
    public static int[] parseDigits(String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        String norm = answer.replace('：', ':');
        Matcher m = POS_PAT.matcher(norm);
        Integer bai = null, shi = null, ge = null;
        while (m.find()) {
            String label = m.group(1);
            String body = m.group(2).trim();
            if (body.isEmpty()) {
                return null;
            }
            // 只取概率最高的一组：逗号列表时取第一个
            String first = body.split(",")[0].trim();
            if (!first.matches("\\d")) {
                return null;
            }
            int d = first.charAt(0) - '0';
            switch (label) {
                case "百" -> bai = d;
                case "十" -> shi = d;
                case "个" -> ge = d;
                default -> {
                }
            }
        }
        if (bai == null || shi == null || ge == null) {
            return null;
        }
        return new int[]{bai, shi, ge};
    }

    /** 三位全中 */
    public static boolean isFullHit(String danMa, String realHm) {
        int[] p = parseDigits(danMa);
        if (p == null || realHm == null || realHm.length() < 3) {
            return false;
        }
        String r = pad3(realHm);
        return p[0] == r.charAt(0) - '0'
                && p[1] == r.charAt(1) - '0'
                && p[2] == r.charAt(2) - '0';
    }

    /** 分位命中：返回 length=3 的布尔，或 null */
    public static boolean[] posHits(String danMa, String realHm) {
        int[] p = parseDigits(danMa);
        if (p == null || realHm == null || realHm.length() < 3) {
            return null;
        }
        String r = pad3(realHm);
        return new boolean[]{
                p[0] == r.charAt(0) - '0',
                p[1] == r.charAt(1) - '0',
                p[2] == r.charAt(2) - '0'
        };
    }

    private static int argmax(double[] s) {
        int best = 0;
        for (int i = 1; i < s.length; i++) {
            if (s[i] > s[best]) {
                best = i;
            }
        }
        return best;
    }

    private static int minDist(int a, int b) {
        int d = Math.abs(a - b);
        return Math.min(d, 10 - d);
    }

    private static double avgConcentration(int[][] digits) {
        double sum = 0;
        for (int pos = 0; pos < 3; pos++) {
            int[] freq = new int[10];
            for (int[] row : digits) {
                freq[row[pos]]++;
            }
            sum += concentration(freq);
        }
        return sum / 3.0;
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

    private static List<Hm> tail(List<Hm> list, int n) {
        if (list.size() <= n) {
            return list;
        }
        return list.subList(list.size() - n, list.size());
    }

    private static int[][] toDigits(List<Hm> list) {
        int[][] d = new int[list.size()][3];
        for (int i = 0; i < list.size(); i++) {
            String s = pad3(list.get(i).toString());
            d[i][0] = s.charAt(0) - '0';
            d[i][1] = s.charAt(1) - '0';
            d[i][2] = s.charAt(2) - '0';
        }
        return d;
    }

    private static String pad3(String s) {
        if (s == null) {
            return "000";
        }
        String t = s.trim();
        while (t.length() < 3) {
            t = "0" + t;
        }
        return t.length() > 3 ? t.substring(t.length() - 3) : t;
    }
}
