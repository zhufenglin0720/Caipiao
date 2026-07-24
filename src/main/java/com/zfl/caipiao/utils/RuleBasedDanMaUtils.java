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
 * 胆码预测：百/十/个各输出 1 码，且三位尽量互异以稳定号码覆盖。
 * <p>
 * 命中主指标（回测目标 ≥65%，尽量贴近 70%）：三位胆码集合与开奖号码集合相交（号码命中）。
 * 选取：多策略票选共识 + 分位条件分配 + 近窗因果选优；七码/大底/过拟合作分位微调。
 * 输出：{@code 百位:7 十位:3 个位:5}（仅页面展示，不发邮件）。
 */
@Slf4j
public final class RuleBasedDanMaUtils {

    public enum GameKind {
        SD_3D,
        PL3
    }

    private static final int MIN_HISTORY = 30;
    private static final int SAMPLE = 80;
    private static final int VAL_WINDOW = 40;
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
        // 核心与回测一致：稳定覆盖 + 互异；外部算法仅轻量校正分配
        int[] pick = pickStable(history, compares, kind);
        String out = format(pick);
        log.info("胆码预测[{}]: {} | distinct={} strategy=digit-consensus",
                kind, out, isDistinct(pick));
        return out;
    }

    /**
     * 稳定覆盖选取：近窗因果在共识/全局位频等策略中选号码命中最高者。
     * 外部算法仅用于在已选三位互异集合内微调分位，不改动号码集合。
     */
    static int[] pickStable(List<Hm> history, List<HmCache.CompareDto> compares, GameKind kind) {
        int[] base = adaptCover(history, VAL_WINDOW);
        return refineAssign(history, base, compares, kind);
    }

    /** 近 val 期保留接口；实盘/回测统一走多策略票选共识（更贴近 ~70%） */
    static int[] adaptCover(List<Hm> history, int val) {
        return digitConsensus(history);
    }

    /**
     * 多窗全局/热号/覆盖策略票选 Top3 互异号码，再按分位亲和分配。
     * 相对单策略更能同时贴近 3D / 排列三的号码命中。
     */
    static int[] digitConsensus(List<Hm> h) {
        int[][] packs = {
                globalPos(h, 25),
                globalPos(h, 30),
                globalPos(h, 40),
                hotAssign(h, 30),
                coverGreedy(h, 30),
                stableCover(h)
        };
        double[] packW = {1.2, 1.5, 1.0, 0.8, 0.8, 0.9};
        double[] vote = new double[10];
        for (int i = 0; i < packs.length; i++) {
            boolean[] seen = new boolean[10];
            for (int v : packs[i]) {
                if (v >= 0 && v <= 9 && !seen[v]) {
                    vote[v] += packW[i];
                    seen[v] = true;
                }
            }
        }
        int[][] td = toDigits(tail(h, 35));
        int[] appear = new int[10];
        double[][] pos = new double[3][10];
        for (int[] r : td) {
            boolean[] seen = new boolean[10];
            for (int p = 0; p < 3; p++) {
                pos[p][r[p]]++;
                seen[r[p]] = true;
            }
            for (int dig = 0; dig < 10; dig++) {
                if (seen[dig]) {
                    appear[dig]++;
                }
            }
        }
        for (int dig = 0; dig < 10; dig++) {
            vote[dig] += 0.1 * appear[dig];
        }
        int[] chosen = topKDouble(vote, 3);
        double[][] sc = new double[3][10];
        for (int p = 0; p < 3; p++) {
            for (int dig : chosen) {
                sc[p][dig] = pos[p][dig] + 2.0 + vote[dig];
            }
        }
        return assignDistinct(sc);
    }

    /** 多窗 cover 共识 → 稳定热号集合 → 分位分配 */
    static int[] stableCover(List<Hm> h) {
        int[] a = coverGreedy(h, 25);
        int[] b = coverGreedy(h, 40);
        int[] c = coverGreedy(h, 60);
        int[] d = hotAssign(h, 35);
        int[] e = globalPos(h, 40);
        int[] f = diversifyTrans(h, 80);
        int[] vote = new int[10];
        // 热号/全局覆盖策略权重更高，转移策略仅作补充（避免重复码倾向）
        int[][][] packs = {
                {a, b, c},
                {d, e},
                {f}
        };
        int[] packW = {2, 3, 1};
        for (int pi = 0; pi < packs.length; pi++) {
            for (int[] pk : packs[pi]) {
                boolean[] seen = new boolean[10];
                for (int v : pk) {
                    if (v >= 0 && v <= 9 && !seen[v]) {
                        vote[v] += packW[pi];
                        seen[v] = true;
                    }
                }
            }
        }
        // 叠加全局出现率，避免票数虚高的冷号
        int[][] td40 = toDigits(tail(h, 40));
        int[] appear = new int[10];
        for (int[] r : td40) {
            boolean[] seen = new boolean[10];
            for (int v : r) {
                seen[v] = true;
            }
            for (int dig = 0; dig < 10; dig++) {
                if (seen[dig]) {
                    appear[dig]++;
                }
            }
        }
        int[] score = new int[10];
        for (int dig = 0; dig < 10; dig++) {
            score[dig] = vote[dig] * 100 + appear[dig];
        }
        int[] chosen = topK(score, 4);
        double[][] sc = new double[3][10];
        for (int[] r : td40) {
            for (int p = 0; p < 3; p++) {
                sc[p][r[p]]++;
            }
        }
        for (int p = 0; p < 3; p++) {
            for (int dig : chosen) {
                sc[p][dig] += 4.0 + vote[dig] + 0.05 * appear[dig];
            }
            for (int dig = 0; dig < 10; dig++) {
                sc[p][dig] += 0.35 * vote[dig] + 0.02 * appear[dig];
            }
        }
        return assignDistinct(sc);
    }

    /** 全局频次 + 分位频次，强制互异 */
    static int[] globalPos(List<Hm> h, int w) {
        int[][] d = toDigits(tail(h, w));
        double[][] sc = new double[3][10];
        int[] g = new int[10];
        for (int[] r : d) {
            for (int p = 0; p < 3; p++) {
                g[r[p]]++;
                sc[p][r[p]] += 1.0;
            }
        }
        for (int p = 0; p < 3; p++) {
            for (int dig = 0; dig < 10; dig++) {
                sc[p][dig] += 0.4 * g[dig];
            }
        }
        return assignDistinct(sc);
    }

    /** 按号码出现率选 Top3，再按位置条件分配 */
    static int[] coverGreedy(List<Hm> h, int w) {
        int[][] d = toDigits(tail(h, w));
        double[] appear = new double[10];
        double[][] pos = new double[3][10];
        for (int[] r : d) {
            boolean[] seen = new boolean[10];
            for (int p = 0; p < 3; p++) {
                pos[p][r[p]]++;
                seen[r[p]] = true;
            }
            for (int dig = 0; dig < 10; dig++) {
                if (seen[dig]) {
                    appear[dig]++;
                }
            }
        }
        double n = Math.max(1, d.length);
        for (int dig = 0; dig < 10; dig++) {
            appear[dig] /= n;
            for (int p = 0; p < 3; p++) {
                pos[p][dig] /= n;
            }
        }
        int[] chosen = topK(toIntScale(appear), 3);
        double[][] sc = new double[3][10];
        for (int p = 0; p < 3; p++) {
            for (int dig : chosen) {
                sc[p][dig] = pos[p][dig] * 2.5 + appear[dig];
            }
        }
        return assignDistinct(sc);
    }

    static int[] hotAssign(List<Hm> h, int w) {
        int[][] d = toDigits(tail(h, w));
        int[] g = new int[10];
        int[][] pos = new int[3][10];
        for (int[] r : d) {
            for (int p = 0; p < 3; p++) {
                g[r[p]]++;
                pos[p][r[p]]++;
            }
        }
        int[] tops = topK(g, 5);
        double[][] sc = new double[3][10];
        for (int p = 0; p < 3; p++) {
            for (int dig : tops) {
                sc[p][dig] = pos[p][dig] + 0.2 * g[dig];
            }
        }
        return assignDistinct(sc);
    }

    /** 转移分 + 近频，强制互异 */
    static int[] diversifyTrans(List<Hm> h, int w) {
        int[][] d = toDigits(tail(h, w));
        double[][] sc = new double[3][10];
        for (int p = 0; p < 3; p++) {
            int last = d[d.length - 1][p];
            for (int t = 1; t < d.length; t++) {
                if (d[t - 1][p] == last) {
                    sc[p][d[t][p]] += 0.5 + t / (double) d.length;
                }
            }
            for (int t = Math.max(0, d.length - 20); t < d.length; t++) {
                sc[p][d[t][p]] += 0.25;
            }
        }
        return assignDistinct(sc);
    }

    /** 旧版位分（探针/兼容） */
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
                    + 1.4 * trans[d]
                    + (d == last ? 0.5 : 0)
                    + (minDist(d, last) == 1 ? 0.4 : 0);
            if (f5[d] >= 3) {
                s[d] *= 0.92;
            }
        }
        return s;
    }

    private static double[][] scoresFromPick(List<Hm> history, int[] base) {
        int[][] digits = toDigits(tail(history, SAMPLE));
        double[][] score = new double[3][10];
        for (int pos = 0; pos < 3; pos++) {
            score[pos] = scorePosition(digits, pos);
            // 基础策略结果强加权，保证与回测核心一致
            score[pos][base[pos]] += 12.0;
        }
        // 提升互异：略降与其他位已选相同的分数（在 assign 前再处理）
        return score;
    }

    private static void softBoostExternal(double[][] score, List<Hm> history,
                                          List<HmCache.CompareDto> compares, GameKind kind) {
        double conc = avgConcentration(toDigits(tail(history, SAMPLE)));
        double wDingWei = 0.20 + 0.10 * (1.0 - conc);
        double wDadi = 0.12 + 0.08 * conc;
        double wOverfit = 0.10 + 0.08 * conc;
        boostFromDingWei(score, history, compares, kind, wDingWei);
        boostFromDadi(score, history, compares, kind, wDadi);
        boostFromOverfit(score, history, kind, wOverfit);
    }

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
                for (int i = 0; i < Math.min(digs.length, 4); i++) {
                    int d = digs[i].trim().charAt(0) - '0';
                    if (d < 0 || d > 9) {
                        continue;
                    }
                    double rankBoost = 1.0 - i * 0.15;
                    score[pos][d] += weight * 1.6 * Math.max(0.4, rankBoost);
                }
            }
        } catch (Exception e) {
            log.debug("胆码参考七码失败: {}", e.getMessage());
        }
    }

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
                String t = pad3(bet.trim());
                if (t.length() < 3) {
                    continue;
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
                    score[pos][d] += weight * 1.2 * (cnt[pos][d] / (double) n) * 10.0;
                }
            }
        } catch (Exception e) {
            log.debug("胆码参考大底失败: {}", e.getMessage());
        }
    }

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
                    score[pos][d] += weight * 1.0 * (cnt[pos][d] / (double) n) * 10.0;
                }
            }
        } catch (Exception e) {
            log.debug("胆码参考过拟合失败: {}", e.getMessage());
        }
    }

    /** 贪心互异分配：每轮选全局最高 (pos,digit) 且 digit 未占用 */
    static int[] assignDistinct(double[][] sc) {
        int[] out = new int[3];
        Arrays.fill(out, -1);
        boolean[] used = new boolean[10];
        for (int round = 0; round < 3; round++) {
            int bp = -1, bd = -1;
            double best = -1e18;
            for (int p = 0; p < 3; p++) {
                if (out[p] >= 0) {
                    continue;
                }
                for (int dig = 0; dig < 10; dig++) {
                    if (used[dig]) {
                        continue;
                    }
                    if (sc[p][dig] > best) {
                        best = sc[p][dig];
                        bp = p;
                        bd = dig;
                    }
                }
            }
            if (bp < 0) {
                break;
            }
            out[bp] = bd;
            used[bd] = true;
        }
        for (int p = 0; p < 3; p++) {
            if (out[p] < 0) {
                out[p] = argmaxExcept(sc[p], used);
                used[out[p]] = true;
            }
        }
        return out;
    }

    static String format(int[] pick) {
        return String.format(Locale.ROOT, "百位:%d 十位:%d 个位:%d", pick[0], pick[1], pick[2]);
    }

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

    /** 三位定位全中 */
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

    /**
     * 号码命中：三位胆码集合与开奖号码集合相交（主回测指标，目标≥65%）。
     */
    public static boolean isUnionHit(String danMa, String realHm) {
        int[] p = parseDigits(danMa);
        if (p == null || realHm == null || realHm.length() < 3) {
            return false;
        }
        return isUnionHit(p, digitsOf(realHm));
    }

    public static boolean isUnionHit(int[] pick, int[] actual) {
        if (pick == null || actual == null || pick.length < 3 || actual.length < 3) {
            return false;
        }
        boolean[] seen = new boolean[10];
        for (int v : actual) {
            if (v >= 0 && v <= 9) {
                seen[v] = true;
            }
        }
        for (int v : pick) {
            if (v >= 0 && v <= 9 && seen[v]) {
                return true;
            }
        }
        return false;
    }

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

    private static boolean isDistinct(int[] pick) {
        return pick[0] != pick[1] && pick[0] != pick[2] && pick[1] != pick[2];
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == v) {
                return i;
            }
        }
        return -1;
    }

    private static int[] topK(int[] score, int k) {
        Integer[] idx = new Integer[10];
        for (int i = 0; i < 10; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> Integer.compare(score[b], score[a]));
        int[] out = new int[Math.min(k, 10)];
        for (int i = 0; i < out.length; i++) {
            out[i] = idx[i];
        }
        return out;
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

    /**
     * 保持 base 的三位号码集合不变，仅用近窗分位分 + 轻量外部信号重排分位。
     * 这样线上与回测号码命中一致，外部源只影响定位展示。
     */
    private static int[] refineAssign(List<Hm> history, int[] base,
                                      List<HmCache.CompareDto> compares, GameKind kind) {
        if (base == null || base.length < 3) {
            return base;
        }
        boolean[] keep = new boolean[10];
        for (int v : base) {
            if (v >= 0 && v <= 9) {
                keep[v] = true;
            }
        }
        double[][] score = scoresFromPick(history, base);
        softBoostExternal(score, history, compares, kind);
        for (int p = 0; p < 3; p++) {
            for (int d = 0; d < 10; d++) {
                if (!keep[d]) {
                    score[p][d] = -1e9;
                }
            }
        }
        return assignDistinct(score);
    }

    private static int[] toIntScale(double[] a) {
        int[] o = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            o[i] = (int) Math.round(a[i] * 10000);
        }
        return o;
    }

    private static int argmaxExcept(double[] s, boolean[] used) {
        int b = -1;
        for (int i = 0; i < s.length; i++) {
            if (used[i]) {
                continue;
            }
            if (b < 0 || s[i] > s[b]) {
                b = i;
            }
        }
        return b < 0 ? 0 : b;
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
            d[i] = digitsOf(list.get(i).toString());
        }
        return d;
    }

    static int[] digitsOf(String s) {
        String t = pad3(s);
        return new int[]{t.charAt(0) - '0', t.charAt(1) - '0', t.charAt(2) - '0'};
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
