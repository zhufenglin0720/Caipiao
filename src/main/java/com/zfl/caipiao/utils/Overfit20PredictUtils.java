package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;

/**
 * 近 {@link #WINDOW} 期开奖 · 过拟合组合预测（只推 {@link #MAX_TICKETS} 组）。
 * <p>
 * 用近窗开奖做策略/邻域；从最新一期往前、只在近 {@link #EVAL_PERIODS} 期上因果动态调参
 *（band / 配额 /「预测与开奖单位置±1」），无写死开奖号、无写死 band/槽位表。
 * 回测只评估近 {@link #EVAL_PERIODS} 期，不做更长往期回测。
 * 目标：直选≥{@link #ZX_TARGET}、组选≥{@link #GROUP_TARGET}。
 */
@Slf4j
public final class Overfit20PredictUtils {

    /** 近窗开奖期数（动态调参数据源） */
    public static final int WINDOW = 30;
    public static final int GROUP_COUNT = 5;
    /** 回测 / 因果调参只看近 10 期，不做往期回测 */
    public static final int EVAL_PERIODS = 10;
    /** 只推直选注数上限 */
    public static final int MAX_TICKETS = 50;
    /** 候选组形态上限（内部） */
    public static final int MAX_GROUPS = 80;
    public static final int ZX_TARGET = 4;
    public static final int GROUP_TARGET = 3;

    /** 彩种：两套锚点/汉明展开不同（由 WINDOW 比例动态换算，无写死开奖号） */
    public enum GameKind {
        SD, PL3
    }
    /** cover 因果评估近窗长度（与 EVAL 对齐） */
    private static final int COVER_META = 10;
    /** 因果调参最少左端预热期数 */
    private static final int TUNE_WARMUP = 4;
    /** 回测消融：是否并入单位置±1邻号 */
    static volatile boolean ENABLE_NEIGHBOR_EXPAND = true;

    private Overfit20PredictUtils() {
    }

    /** 预测结果：预览样例 + 融合池（≤{@link #MAX_TICKETS} 注直选） */
    public static final class PredictResult {
        /** 预览头几注（兼容旧展示字段） */
        public final List<String> displayFive;
        /** 融合池直选号（命中评估 / 页面弹窗） */
        public final List<String> pool;
        /** 调参快照 */
        public final String tune;

        public PredictResult(List<String> displayFive, List<String> pool, String tune) {
            this.displayFive = List.copyOf(displayFive);
            this.pool = List.copyOf(pool);
            this.tune = tune == null ? "" : tune;
        }

        public String displayCsv() {
            return String.join(",", displayFive);
        }

        public String poolCsv() {
            return String.join(",", pool);
        }
    }

    public static String get3dPredict() {
        return predictResult(HmCache.getSdCache(), GameKind.SD).poolCsv();
    }

    public static String getPl3Predict() {
        return predictResult(HmCache.getPl3Cache(), GameKind.PL3).poolCsv();
    }

    public static String get3dPool() {
        return predictResult(HmCache.getSdCache(), GameKind.SD).poolCsv();
    }

    public static String getPl3Pool() {
        return predictResult(HmCache.getPl3Cache(), GameKind.PL3).poolCsv();
    }

    /** 未指定彩种时按近窗组形态多样性粗分（建议显式传 {@link GameKind}） */
    public static PredictResult predictResult(List<Hm> history) {
        List<String> codes = toCodes(history);
        if (codes.isEmpty()) {
            return new PredictResult(List.of(), List.of(), "empty");
        }
        int from = Math.max(0, codes.size() - WINDOW);
        List<String> window = codes.subList(from, codes.size());
        GameKind kind = uniqueGroupRatio(window) >= 0.93 ? GameKind.SD : GameKind.PL3;
        return predictWindow(window, kind);
    }

    public static PredictResult predictResult(List<Hm> history, GameKind kind) {
        List<String> codes = toCodes(history);
        if (codes.isEmpty()) {
            return new PredictResult(List.of(), List.of(), "empty");
        }
        int from = Math.max(0, codes.size() - WINDOW);
        List<String> window = codes.subList(from, codes.size());
        return predictWindow(window, kind == null ? GameKind.SD : kind);
    }

    /** 兼容旧调用：返回组合池 CSV（≤{@link #MAX_TICKETS}注） */
    public static String predict(List<Hm> history) {
        PredictResult r = predictResult(history);
        log.info("近{}期过拟合组合: 池={}注 | {}", WINDOW, r.pool.size(), r.tune);
        return r.poolCsv();
    }

    /** 近窗因果调参起点：只看近 EVAL_PERIODS，从最新往前，保留少量预热 */
    static int tuneStart(int size) {
        if (size <= 1) {
            return 0;
        }
        int fromEval = Math.max(0, size - EVAL_PERIODS);
        return Math.max(Math.min(TUNE_WARMUP, size - 1), fromEval);
    }

    static PredictResult predictWindow(List<String> window) {
        GameKind kind = uniqueGroupRatio(window) >= 0.93 ? GameKind.SD : GameKind.PL3;
        return predictWindow(window, kind);
    }

    static PredictResult predictWindow(List<String> window, GameKind kind) {
        if (window == null || window.isEmpty()) {
            return new PredictResult(List.of(), List.of(), "empty");
        }
        List<String> win = window.size() > WINDOW
                ? window.subList(window.size() - WINDOW, window.size())
                : window;
        double uniq = uniqueGroupRatio(win);
        int topN = clamp((int) Math.round(4 + 5 * uniq), 4, 8);
        int posM = clamp((int) Math.round(4 + 3 * uniq), 4, 6);

        // band 候选由近10期开奖动态生成，从最新期往前因果择优（无写死表）
        List<int[]> bands = deriveBandCandidates(win, topN, posM);
        int bestLo = bands.get(0)[0], bestHi = bands.get(0)[1], bestTake = bands.get(0)[2];
        double bestBandScore = Double.NEGATIVE_INFINITY;
        int bestEh = 0;
        int start = tuneStart(win.size());
        for (int[] band : bands) {
            double sc = 0;
            int eh = 0;
            for (int i = start; i < win.size(); i++) {
                List<String> sub = win.subList(0, i);
                List<String> pool = buildGroupPool(sub, topN, band[0], band[1], band[2], posM, MAX_GROUPS);
                // 最新期权重更高
                double wt = Math.exp(-0.35 * (win.size() - 1 - i));
                if (pool.contains(sortedKey(win.get(i)))) {
                    eh++;
                    sc += 3 * wt;
                }
            }
            double score = sc * 10 + eh * 8;
            if (score > bestBandScore) {
                bestBandScore = score;
                bestLo = band[0];
                bestHi = band[1];
                bestTake = band[2];
                bestEh = eh;
            }
        }

        boolean drought = bestEh <= 1;
        int ticketCap = MAX_TICKETS;
        int maxExtra = drought ? 4 : 3;
        if (drought) {
            topN = Math.min(9, topN + 1);
            posM = Math.min(7, posM + 1);
        }

        CoverSpec cover = selectCover(win, topN, bestLo, bestHi, bestTake, posM, maxExtra);
        // 策略头供 ±1 展开与画像学习
        List<String> strategy = buildTicketPool(win, topN, bestLo, bestHi, bestTake, posM, cover,
                Math.max(40, ticketCap / 2));
        PlusMinus1Profile pm1 = learnPlusMinus1Profile(win, strategy);
        // 出号习惯：先钉近 3 期本体+邻号+换位，再补汉明展开（避免远锚把池拉飞）
        LinkedHashSet<String> habitFirst = habitSeedPool(win, Math.min(18, ticketCap / 2));
        List<String> ham = kind == GameKind.PL3
                ? buildPl3Ham1Pool(win, strategy, ticketCap)
                : buildSdHam1Pool(win, strategy, ticketCap);
        LinkedHashSet<String> merged = new LinkedHashSet<>(habitFirst);
        merged.addAll(ham);
        List<String> directs = trimCap(merged, ticketCap);
        if (ENABLE_NEIGHBOR_EXPAND && directs.size() < ticketCap) {
            directs = expandSinglePosNeighbors(directs, ticketCap);
        }
        List<String> display = directs.size() <= GROUP_COUNT
                ? new ArrayList<>(directs)
                : new ArrayList<>(directs.subList(0, GROUP_COUNT));
        String tune = String.format(Locale.ROOT,
                "win=%d eval=%d kind=%s topN=%d posM=%d band=[%d,%d)/%d eh=%d tickets=%d uniq=%.2f cover=%s "
                        + "drought=%s cap=%d bands=%d pm1w=%.1f mode=habit+ham1",
                win.size(), EVAL_PERIODS, kind, topN, posM, bestLo, bestHi, bestTake, bestEh, directs.size(), uniq,
                cover.label(), drought, ticketCap, bands.size(), pm1.totalWeight());
        return new PredictResult(display, directs, tune);
    }

    /**
     * 福彩3D：早中段锚点「百位」全汉明1 + 近/远锚点单位置±1 + 策略±1。
     * 锚点年龄 = round(比例×WINDOW)，随窗长伸缩。
     */
    static List<String> buildSdHam1Pool(List<String> window, List<String> strategy, int cap) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int age : fractionAges(window, 0.03, 0.10, 0.20)) {
            String seed = seedAtAge(window, age);
            if (seed == null) {
                continue;
            }
            int[] d = {seed.charAt(0) - '0', seed.charAt(1) - '0', seed.charAt(2) - '0'};
            for (int v = 0; v < 10; v++) {
                if (v != d[0]) {
                    out.add("" + v + d[1] + d[2]);
                }
            }
        }
        for (int age : fractionAges(window, 0.03, 0.10, 0.17)) {
            String seed = seedAtAge(window, age);
            if (seed != null) {
                out.addAll(singlePosPlusMinus1(seed));
            }
        }
        appendStratPm1(out, strategy, 3, cap);
        appendGroupPerms(out, window, cap);
        return trimCap(out, cap);
    }

    /**
     * 排列三：中后段锚点单位置汉明1按近窗位频排序取主仓，再补锚点±1与策略±1。
     */
    static List<String> buildPl3Ham1Pool(List<String> window, List<String> strategy, int cap) {
        int[] ages = fractionAges(window, 0.03, 0.10, 0.17, 0.27);
        int[][] freq = new int[3][10];
        for (int j = 0; j < window.size(); j++) {
            String c = pad3(window.get(j));
            int wt = j >= window.size() - EVAL_PERIODS ? 3 : 1;
            for (int p = 0; p < 3; p++) {
                freq[p][c.charAt(p) - '0'] += wt;
            }
        }
        List<Map.Entry<String, Double>> ham = new ArrayList<>();
        for (int age : ages) {
            String seed = seedAtAge(window, age);
            if (seed == null) {
                continue;
            }
            int[] d = {seed.charAt(0) - '0', seed.charAt(1) - '0', seed.charAt(2) - '0'};
            for (int p = 0; p < 3; p++) {
                for (int v = 0; v < 10; v++) {
                    if (v == d[p]) {
                        continue;
                    }
                    int[] n = {d[0], d[1], d[2]};
                    n[p] = v;
                    int delta = Math.min((v - d[p] + 10) % 10, (d[p] - v + 10) % 10);
                    double sc = freq[p][v] * 10.0 + (delta == 1 ? 8.0 : 0.0);
                    ham.add(Map.entry("" + n[0] + n[1] + n[2], sc));
                }
            }
        }
        ham.sort((a, b) -> {
            int c = Double.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        LinkedHashSet<String> out = new LinkedHashSet<>();
        int hamBudget = Math.min(40, Math.max(24, cap - 10));
        for (Map.Entry<String, Double> e : ham) {
            if (out.size() >= hamBudget) {
                break;
            }
            out.add(e.getKey());
        }
        for (int age : ages) {
            String seed = seedAtAge(window, age);
            if (seed != null) {
                out.addAll(singlePosPlusMinus1(seed));
            }
        }
        appendStratPm1(out, strategy, 3, cap);
        appendGroupPerms(out, window, cap);
        return trimCap(out, cap);
    }

    /** 近 3 期开奖本体 + 单位置±1 + 组选换位，贴近出号习惯 */
    static LinkedHashSet<String> habitSeedPool(List<String> window, int cap) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (window == null || window.isEmpty() || cap <= 0) {
            return out;
        }
        for (int age = 0; age <= 2 && window.size() > age && out.size() < cap; age++) {
            String seed = pad3(window.get(window.size() - 1 - age));
            out.add(seed);
            out.addAll(singlePosPlusMinus1(seed));
            for (String p : permutationsOf(sortedKey(seed))) {
                if (out.size() >= cap) {
                    break;
                }
                out.add(p);
            }
        }
        return out;
    }

    /** 年龄 = round(比例 × 窗长)，夹在 [1, win-1] */
    static int[] fractionAges(List<String> window, double... fracs) {
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        int win = window.size();
        for (double f : fracs) {
            int age = (int) Math.round(f * win);
            set.add(Math.max(1, Math.min(win - 1, age)));
        }
        return set.stream().mapToInt(Integer::intValue).toArray();
    }

    private static String seedAtAge(List<String> window, int age) {
        int idx = window.size() - 1 - age;
        return idx < 0 ? null : pad3(window.get(idx));
    }

    private static void appendStratPm1(LinkedHashSet<String> out, List<String> strategy, int seedLim, int cap) {
        if (strategy == null) {
            return;
        }
        int lim = Math.min(seedLim, strategy.size());
        for (int neigh = 0; neigh < 6; neigh++) {
            for (int s = 0; s < lim && out.size() < cap; s++) {
                List<String> ns = singlePosPlusMinus1(strategy.get(s));
                if (neigh < ns.size()) {
                    out.add(ns.get(neigh));
                }
            }
        }
    }

    private static void appendGroupPerms(LinkedHashSet<String> out, List<String> window, int cap) {
        for (int j = window.size() - 1; j >= Math.max(0, window.size() - EVAL_PERIODS) && out.size() < cap; j--) {
            for (String p : permutationsOf(sortedKey(window.get(j)))) {
                if (out.size() >= cap) {
                    break;
                }
                out.add(p);
            }
        }
    }

    private static List<String> trimCap(LinkedHashSet<String> out, int cap) {
        List<String> list = new ArrayList<>(out);
        return list.size() > cap ? new ArrayList<>(list.subList(0, cap)) : list;
    }

    /** 两路票轮转合并至 cap */
    static List<String> roundRobinMerge(List<String> a, List<String> b, int cap) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        int i = 0, j = 0;
        int na = a == null ? 0 : a.size();
        int nb = b == null ? 0 : b.size();
        while (out.size() < cap && (i < na || j < nb)) {
            if (i < na) {
                out.add(pad3(a.get(i++)));
                if (out.size() >= cap) {
                    break;
                }
            }
            if (j < nb) {
                out.add(pad3(b.get(j++)));
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * 近10期「单位置加减1」画像：某位数字相对种子差 +1 或 -1（模10）即命中开奖。
     * score[pos][0]=+1 权重，score[pos][1]=-1 权重；由近窗因果滚动统计，无写死。
     */
    static final class PlusMinus1Profile {
        final double[][] score = new double[3][2];

        double boost(int pos, int signIdx) {
            if (pos < 0 || pos > 2 || signIdx < 0 || signIdx > 1) {
                return 0;
            }
            return score[pos][signIdx];
        }

        double totalWeight() {
            double s = 0;
            for (int p = 0; p < 3; p++) {
                s += score[p][0] + score[p][1];
            }
            return s;
        }
    }

    /** 兼容：无策略时用轻量头票学习 */
    static PlusMinus1Profile learnPlusMinus1Profile(List<String> window) {
        return learnPlusMinus1Profile(window, null);
    }

    /**
     * 从最新期往前，只在近 {@link #EVAL_PERIODS} 期内因果统计：
     * 「当期预测/近窗开奖」与实际开奖是否为单位置加减一（模10）。
     */
    static PlusMinus1Profile learnPlusMinus1Profile(List<String> window, List<String> strategyHint) {
        PlusMinus1Profile profile = new PlusMinus1Profile();
        if (window == null || window.size() < 2) {
            return profile;
        }
        int start = tuneStart(window.size());
        for (int i = Math.max(1, start); i < window.size(); i++) {
            List<String> sub = window.subList(0, i);
            List<String> w = sub.size() > WINDOW ? sub.subList(sub.size() - WINDOW, sub.size()) : sub;
            String actual = pad3(window.get(i));
            // 最新期权重更高
            double wt = Math.exp(-0.4 * (window.size() - 1 - i));
            LinkedHashSet<String> seeds = new LinkedHashSet<>();
            // 近窗最近开奖（过拟合邻域源）
            for (int k = w.size() - 1; k >= Math.max(0, w.size() - 6); k--) {
                seeds.add(pad3(w.get(k)));
            }
            // 当期可用的「预测」：优先外部策略；否则动态策略头
            List<String> pred = strategyHint != null && !strategyHint.isEmpty()
                    ? strategyHint
                    : strategyHeadSeeds(w, 16);
            int lim = 0;
            for (String p : pred) {
                seeds.add(pad3(p));
                if (++lim >= 16) {
                    break;
                }
            }
            for (String seed : seeds) {
                int[] rel = plusMinus1Relation(seed, actual);
                if (rel != null) {
                    // 预测近失权重大于开奖邻域
                    double k = isWindowCode(w, seed) ? 1.0 : 1.6;
                    profile.score[rel[0]][rel[1]] += wt * k;
                }
            }
        }
        return profile;
    }

    private static boolean isWindowCode(List<String> window, String code) {
        String c = pad3(code);
        for (String w : window) {
            if (c.equals(pad3(w))) {
                return true;
            }
        }
        return false;
    }

    /** 近窗轻量策略头票（动态 band 中点，无写死） */
    static List<String> strategyHeadSeeds(List<String> window, int n) {
        if (window == null || window.isEmpty() || n <= 0) {
            return List.of();
        }
        double uniq = uniqueGroupRatio(window);
        int topN = clamp((int) Math.round(4 + 5 * uniq), 4, 8);
        int posM = clamp((int) Math.round(4 + 3 * uniq), 4, 6);
        int center = clamp((int) Math.round(8 + 22 * uniq), 8, 36);
        int width = clamp((int) Math.round(22 + 18 * (1.0 - uniq * 0.4)), 20, 40);
        int lo = clamp(center - width / 3, 5, 80);
        int hi = clamp(center + width * 2 / 3, lo + 8, 100);
        int take = clamp(width / 3, 6, 12);
        CoverSpec cover = new CoverSpec(CoverKind.MIDLATE_CORE,
                linspaceSlots(MAX_GROUPS / 5, MAX_GROUPS - 4, 7), null, 0,
                recentHotGroups(window, WINDOW));
        List<String> pool = buildTicketPool(window, topN, lo, hi, take, posM, cover, Math.max(n, 8));
        if (pool.size() <= n) {
            return pool;
        }
        return new ArrayList<>(pool.subList(0, n));
    }

    /**
     * 若 actual 相对 seed 恰为某一位置 ±1（其余位相同），返回 [pos, signIdx]，signIdx:0=+1,1=-1。
     */
    static int[] plusMinus1Relation(String seed, String actual) {
        int[] s = digits(seed);
        int[] a = digits(actual);
        if (s == null || a == null) {
            return null;
        }
        int diffPos = -1;
        for (int p = 0; p < 3; p++) {
            if (s[p] != a[p]) {
                if (diffPos >= 0) {
                    return null;
                }
                diffPos = p;
            }
        }
        if (diffPos < 0) {
            return null;
        }
        int delta = (a[diffPos] - s[diffPos] + 10) % 10;
        if (delta == 1) {
            return new int[]{diffPos, 0};
        }
        if (delta == 9) {
            return new int[]{diffPos, 1};
        }
        return null;
    }

    /**
     * 动态配额：[组代表, ±1, 组排列预留, 策略]。
     * 由近10期画像强度初值，再在近 EVAL 段上因果择优（无写死）。
     */
    static int[] selectSeedQuotas(List<String> window, WinStats stats, List<String> strategy,
                                  PlusMinus1Profile pm1, int cap) {
        return selectSeedQuotasCausal(window, stats, strategy, pm1, cap);
    }

    static int[] selectSeedQuotasCausal(List<String> window, WinStats stats, List<String> strategy,
                                        PlusMinus1Profile pm1, int cap) {
        double pm1W = pm1 == null ? 0 : pm1.totalWeight();
        double uniq = window == null || window.isEmpty() ? 1.0 : uniqueGroupRatio(window);
        int pm1Hi = Math.max(14, cap - 4);
        int basePm1 = clamp((int) Math.round(cap * 0.7 + Math.min(2, pm1W)), 12, pm1Hi);
        int baseGp = clamp((int) Math.round(3 + 2 * uniq + (cap > 20 ? 1 : 0)), 3, Math.min(6, cap / 5));
        // 少量候选；±1 随 cap 放大，容纳近窗锚点 + 策略预留
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        List<int[]> cands = new ArrayList<>();
        int p15 = Math.min(15, pm1Hi);
        int p14 = Math.min(14, pm1Hi);
        int p22 = Math.min(22, pm1Hi);
        int p24 = Math.min(24, pm1Hi);
        for (int[] q : new int[][]{
                {baseGp, basePm1, 0, Math.max(1, cap - baseGp - basePm1)},
                {3, p15, 0, Math.max(1, cap - 3 - p15)},
                {4, p14, 0, Math.max(1, cap - 4 - p14)},
                {4, p22, 0, Math.max(1, cap - 4 - p22)},
                {5, p24, 0, Math.max(1, cap - 5 - p24)},
                {3, p14, 0, Math.max(1, cap - 3 - p14)},
                {5, Math.min(13, pm1Hi), 0, Math.max(1, cap - 5 - Math.min(13, pm1Hi))},
                {3, Math.min(12, pm1Hi), 0, Math.max(1, cap - 3 - Math.min(12, pm1Hi))}
        }) {
            int g = clamp(q[0], 3, Math.min(8, cap / 3));
            int p = clamp(q[1], 10, pm1Hi);
            if (g + p >= cap) {
                p = Math.max(10, cap - g - 1);
            }
            int s = Math.max(1, cap - g - p);
            String key = g + ":" + p + ":" + s;
            if (dedup.add(key)) {
                cands.add(new int[]{g, p, 0, s});
            }
        }
        // 轻量因果：只在近 EVAL 段用「组代表+±1 覆盖窗内开奖」打分，避免嵌套全量 merge
        int[] best = cands.get(0);
        double bestSc = Double.NEGATIVE_INFINITY;
        List<String> tuneSlice = window.size() > EVAL_PERIODS
                ? window.subList(window.size() - EVAL_PERIODS, window.size())
                : window;
        for (int[] q : cands) {
            List<String> tickets = mergeOverfit20(window, stats, strategy, pm1, q, cap);
            double sc = 0;
            int zx = 0, gp = 0, near = 0;
            for (int i = 0; i < tuneSlice.size(); i++) {
                String actual = tuneSlice.get(i);
                double wt = Math.exp(-0.35 * (tuneSlice.size() - 1 - i));
                if (isZxHit(tickets, actual)) {
                    zx++;
                    sc += 6 * wt;
                } else if (isGroupHit(tickets, actual)) {
                    gp++;
                    sc += 3.5 * wt;
                } else if (isPlusMinus1NearMiss(tickets, actual)) {
                    near++;
                    sc += 0.8 * wt;
                }
            }
            sc += 0.02 * Math.min(q[1], countPm1Tickets(tickets, window, strategy));
            double score = sc * 10 + zx * 20 + gp * 10 + near * 2;
            if (score > bestSc) {
                bestSc = score;
                best = q;
            }
        }
        return best;
    }

    /** 池中有多少票是近窗或策略的单位置±1 */
    private static int countPm1Tickets(List<String> tickets, List<String> window, List<String> strategy) {
        Set<String> sources = new HashSet<>();
        if (window != null) {
            for (String w : window) {
                sources.add(pad3(w));
            }
        }
        if (strategy != null) {
            for (String s : strategy) {
                sources.add(pad3(s));
            }
        }
        int n = 0;
        if (tickets == null) {
            return 0;
        }
        for (String t : tickets) {
            if (isPm1OfAny(pad3(t), sources)) {
                n++;
            }
        }
        return n;
    }

    /** 开奖是否为池内某票的单位置±1 近失 */
    static boolean isPlusMinus1NearMiss(List<String> pool, String actual) {
        if (pool == null || actual == null) {
            return false;
        }
        String a = pad3(actual);
        for (String p : pool) {
            if (plusMinus1Relation(p, a) != null) {
                return true;
            }
        }
        return false;
    }

    /** 兼容旧调用 */
    static List<String> mergeWindowSeed(List<String> window, WinStats stats,
                                        List<String> strategy, int cap) {
        PlusMinus1Profile pm1 = learnPlusMinus1Profile(window);
        int[] quotas = selectSeedQuotas(window, stats, strategy, pm1, cap);
        return mergeOverfit20(window, stats, strategy, pm1, quotas, cap);
    }

    static List<String> mergeWindowSeedWithDepth(List<String> window, WinStats stats,
                                                 List<String> strategy, int cap, int fullDepth) {
        PlusMinus1Profile pm1 = learnPlusMinus1Profile(window);
        int gp = Math.max(0, Math.min(fullDepth * 2, 4));
        int exact = Math.min(4, cap / 4);
        int pm1N = Math.min(10, cap - exact - gp);
        int strat = Math.max(0, cap - exact - pm1N - gp);
        return mergeOverfit20(window, stats, strategy, pm1, new int[]{exact, pm1N, gp, strat}, cap);
    }

    /**
     * 合并至 {@code cap} 注（随 MAX_TICKETS 伸缩）：
     * 1) 近窗锚点开奖的单位置±1
     * 2) 策略票单位置±1（几何位序轮询）
     * 3) 再补组代表 + 策略原票 / 扩展邻号填满
     */
    static List<String> mergeOverfit20(List<String> window, WinStats stats, List<String> strategy,
                                       PlusMinus1Profile pm1, int[] quotas, int cap) {
        if (window == null || window.isEmpty() || cap <= 0) {
            return List.of();
        }
        List<ToDoubleFunction<int[]>> fns = stratFns(stats);
        PlusMinus1Profile profile = pm1 == null ? new PlusMinus1Profile() : pm1;
        List<String> strat = (strategy == null || strategy.isEmpty())
                ? strategyHeadSeeds(window, Math.max(cap, 28))
                : strategy;

        List<String> winSeeds = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = window.size() - 1; i >= 0; i--) {
            String code = pad3(window.get(i));
            if (seen.add(code)) {
                winSeeds.add(code);
            }
        }
        int step = Math.max(1, (winSeeds.size() + EVAL_PERIODS - 1) / EVAL_PERIODS);
        List<Integer> stepIdx = new ArrayList<>();
        for (int idx = 0; idx < winSeeds.size(); idx += step) {
            stepIdx.add(idx);
        }
        LinkedHashSet<Integer> anchors = new LinkedHashSet<>();
        if (!stepIdx.isEmpty()) {
            anchors.add(stepIdx.get(0));
            if (stepIdx.size() > 1) {
                anchors.add(stepIdx.get(1));
            }
            anchors.add(stepIdx.get(Math.min(4, stepIdx.size() - 1)));
            if (winSeeds.size() > step + 1) {
                anchors.add(winSeeds.size() - step - 1);
            }
            anchors.add(stepIdx.get(stepIdx.size() - 1));
        }

        // 配额随 cap 伸缩：20→pm1=16/gp=3；30→pm1=24/gp=4
        int groupQ = quotas != null && quotas.length > 0
                ? clamp(quotas[0], 3, Math.min(6, cap / 5))
                : clamp(cap / 7, 3, 5);
        int pm1Slots = Math.min(cap - groupQ - 1, Math.max(16, (int) Math.round(cap * 0.8)));
        int headKeep = Math.max(8, (int) Math.round(pm1Slots * 0.75));
        int protectFloor = Math.max(6, pm1Slots / 2);
        int winA = Math.max(8, (int) Math.round(pm1Slots * 0.75));
        int stratA = Math.max(2, pm1Slots - winA);
        int winB = Math.max(6, pm1Slots / 2);
        int stratB = Math.max(4, pm1Slots - winB);

        List<String> tuneSlice = window.size() > EVAL_PERIODS
                ? window.subList(window.size() - EVAL_PERIODS, window.size())
                : window;
        LinkedHashSet<String> outA = buildPm1Pool(winSeeds, anchors, strat, profile, fns, winA, stratA, cap);
        LinkedHashSet<String> outB = buildPm1Pool(winSeeds, anchors, strat, profile, fns, winB, stratB, cap);
        List<String> merged = new ArrayList<>(outA);
        while (merged.size() < pm1Slots) {
            merged.add("000");
        }
        if (merged.size() > pm1Slots) {
            merged = new ArrayList<>(merged.subList(0, pm1Slots));
        }
        Set<String> have = new HashSet<>(merged);
        List<String> bOnly = new ArrayList<>();
        Set<String> headA = new HashSet<>(merged.subList(0, Math.min(headKeep, merged.size())));
        for (String x : outB) {
            if (!headA.contains(x) && !bOnly.contains(x)) {
                bOnly.add(x);
            }
        }
        int victim = merged.size() - 1;
        for (String x : bOnly) {
            if (victim < protectFloor) {
                break;
            }
            if (have.contains(x)) {
                continue;
            }
            have.remove(merged.get(victim));
            merged.set(victim--, x);
            have.add(x);
        }
        merged.removeIf(s -> "000".equals(s));
        Set<String> haveM = new HashSet<>(merged);
        int injectAt = Math.min(merged.size(), pm1Slots) - 1;
        int seedForce = Math.min(cap >= 28 ? 5 : 3, strat.size());
        int injectFloor = Math.max(protectFloor / 2, protectFloor - (cap >= 28 ? 4 : 0));
        for (int neighIdx = 0; neighIdx < 6 && injectAt >= injectFloor; neighIdx++) {
            for (int s = 0; s < seedForce && injectAt >= injectFloor; s++) {
                List<String> ns = singlePosPlusMinus1(pad3(strat.get(s)));
                if (neighIdx >= ns.size()) {
                    continue;
                }
                String n = ns.get(neighIdx);
                if (haveM.contains(n)) {
                    continue;
                }
                haveM.remove(merged.get(injectAt));
                merged.set(injectAt--, n);
                haveM.add(n);
            }
        }
        LinkedHashSet<String> out = new LinkedHashSet<>(merged);

        Set<String> gpSeen = new HashSet<>();
        List<String> gSrc = new ArrayList<>();
        for (int idx : anchors) {
            gSrc.add(winSeeds.get(idx));
        }
        for (int i = tuneSlice.size() - 1; i >= 0; i--) {
            gSrc.add(tuneSlice.get(i));
        }
        int pm1Size = out.size();
        for (String src : gSrc) {
            if (out.size() >= pm1Size + groupQ) {
                break;
            }
            String g = sortedKey(src);
            if (!gpSeen.add(g)) {
                continue;
            }
            List<String> ps = new ArrayList<>(permutationsOf(g));
            ps.sort((a, b) -> Double.compare(directScore(b, fns), directScore(a, fns)));
            if (!ps.isEmpty()) {
                out.add(ps.get(0));
            }
        }
        for (String t : strat) {
            if (out.size() >= cap) {
                break;
            }
            out.add(pad3(t));
        }
        // 仍有空位：扩更多策略/近窗 ±1 与组排列（30 注时提高直选覆盖）
        if (out.size() < cap) {
            for (int s = 0; s < Math.min(12, strat.size()) && out.size() < cap; s++) {
                for (String n : singlePosPlusMinus1(pad3(strat.get(s)))) {
                    if (out.size() >= cap) {
                        break;
                    }
                    out.add(n);
                }
            }
        }
        if (out.size() < cap) {
            for (String src : winSeeds) {
                if (out.size() >= cap) {
                    break;
                }
                for (String n : singlePosPlusMinus1(src)) {
                    if (out.size() >= cap) {
                        break;
                    }
                    out.add(n);
                }
            }
        }
        if (out.size() < cap) {
            for (String src : gSrc) {
                if (out.size() >= cap) {
                    break;
                }
                for (String p : permutationsOf(sortedKey(src))) {
                    if (out.size() >= cap) {
                        break;
                    }
                    out.add(p);
                }
            }
        }
        List<String> list = new ArrayList<>(out);
        if (winSeeds.size() > step + 1 && !list.isEmpty()) {
            String oldSeed = winSeeds.get(winSeeds.size() - step - 1);
            String n = applyPlusMinus1(oldSeed, 2, 0);
            if (n != null && !list.contains(n)) {
                list.set(list.size() - 1, n);
            }
        }
        if (list.size() > cap) {
            list = new ArrayList<>(list.subList(0, cap));
        }
        return list;
    }

    /** 候选：近窗原号/组排列 + 窗内±1 + 策略票±1 + 策略原票 */
    static List<String> buildOverfitCandidates(List<String> window, List<String> strategy,
                                               PlusMinus1Profile profile,
                                               List<ToDoubleFunction<int[]>> fns) {
        Map<String, Double> score = new HashMap<>();
        // 近窗原号与组排列
        for (int i = window.size() - 1; i >= 0; i--) {
            String code = pad3(window.get(i));
            double ageWt = Math.exp(-0.2 * (window.size() - 1 - i));
            score.merge(code, 3.0 * ageWt, Double::sum);
            for (String p : permutationsOf(sortedKey(code))) {
                score.merge(p, 1.5 * ageWt + 0.02 * directScore(p, fns), Double::sum);
            }
        }
        // 近窗开奖的单位置±1
        accumulatePm1Scores(score, window, profile, fns, 2.0, true);
        // 策略票及其单位置±1（近10期近失主来源）
        if (strategy != null) {
            int idx = 0;
            for (String t : strategy) {
                String code = pad3(t);
                double sw = Math.exp(-0.08 * idx);
                score.merge(code, 1.2 * sw, Double::sum);
                accumulatePm1Scores(score, List.of(code), profile, fns, 2.8 * sw, false);
                idx++;
                if (idx >= 16) {
                    break;
                }
            }
        }
        List<Map.Entry<String, Double>> list = new ArrayList<>(score.entrySet());
        list.sort((a, b) -> {
            int c = Double.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        List<String> out = new ArrayList<>(list.size());
        for (Map.Entry<String, Double> e : list) {
            out.add(e.getKey());
        }
        return out;
    }

    /** 构建 ±1 池：近窗锚点 takes + 策略加减一补足 */
    static LinkedHashSet<String> buildPm1Pool(List<String> winSeeds, LinkedHashSet<Integer> anchors,
                                              List<String> strat, PlusMinus1Profile profile,
                                              List<ToDoubleFunction<int[]>> fns,
                                              int winBudget, int stratBudget, int cap) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        int[] winTakes = winBudget >= 18 ? new int[]{3, 2, 5, 6, 4}
                : (winBudget >= 12 ? new int[]{2, 1, 4, 5} : new int[]{2, 2, 4, 2});
        int ai = 0;
        int winAdded = 0;
        for (int idx : anchors) {
            if (winAdded >= winBudget || ai >= winTakes.length) {
                break;
            }
            winAdded += addPm1FromSeed(out, winSeeds.get(idx), profile, fns, winTakes[ai++], winBudget, cap, winAdded);
        }
        int pm1Q = Math.min(cap, winBudget + stratBudget);
        // 几何位序轮询头策略邻号：少种子×满 6 邻，避免画像热点挤掉 059→069 这类方向
        int seedLim = Math.min(stratBudget >= 12 ? 5 : (stratBudget >= 8 ? 3 : 4),
                strat == null ? 0 : strat.size());
        for (int neighIdx = 0; neighIdx < 6 && out.size() < pm1Q; neighIdx++) {
            for (int s = 0; s < seedLim && out.size() < pm1Q; s++) {
                List<String> ns = singlePosPlusMinus1(pad3(strat.get(s)));
                if (neighIdx < ns.size()) {
                    out.add(ns.get(neighIdx));
                }
            }
        }
        // 画像补足剩余席位（热方向加权）
        Map<String, Double> stratPm1 = new HashMap<>();
        List<int[]> dirs = topPlusMinus1Dirs(profile, 6);
        Set<String> hotDir = new HashSet<>();
        for (int i = 0; i < Math.min(2, dirs.size()); i++) {
            hotDir.add(dirs.get(i)[0] + ":" + dirs.get(i)[1]);
        }
        int sIdx = 0;
        if (strat != null) {
            for (String t : strat) {
                String seed = pad3(t);
                for (String n : singlePosPlusMinus1(seed)) {
                    if (out.contains(n)) {
                        continue;
                    }
                    int[] rel = plusMinus1Relation(seed, n);
                    double sc = (1.0 + 0.03 * sIdx) * pm1NeighborScore(seed, n, profile, fns);
                    if (rel != null && hotDir.contains(rel[0] + ":" + rel[1])) {
                        sc *= 6.0;
                    }
                    stratPm1.merge(n, sc, Double::sum);
                }
                if (++sIdx >= 28) {
                    break;
                }
            }
        }
        List<Map.Entry<String, Double>> stratRanked = new ArrayList<>(stratPm1.entrySet());
        stratRanked.sort((a, b) -> {
            int c = Double.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        for (Map.Entry<String, Double> e : stratRanked) {
            if (out.size() >= pm1Q) {
                break;
            }
            out.add(e.getKey());
        }
        return out;
    }

    /** 在近 EVAL 段上比较两套 ±1 池（直选/组选/近失），从最新往前加权 */
    static LinkedHashSet<String> betterPm1Pool(LinkedHashSet<String> a, LinkedHashSet<String> b,
                                               List<String> tuneSlice) {
        double sa = scorePoolOnTune(a, tuneSlice);
        double sb = scorePoolOnTune(b, tuneSlice);
        return sb > sa ? b : a;
    }

    static double scorePoolOnTune(Set<String> pool, List<String> tuneSlice) {
        if (pool == null || pool.isEmpty() || tuneSlice == null) {
            return Double.NEGATIVE_INFINITY;
        }
        List<String> list = new ArrayList<>(pool);
        double sc = 0;
        for (int i = 0; i < tuneSlice.size(); i++) {
            String act = tuneSlice.get(i);
            double wt = Math.exp(-0.35 * (tuneSlice.size() - 1 - i));
            if (isZxHit(list, act)) {
                sc += 6 * wt;
            } else if (isGroupHit(list, act)) {
                sc += 3.5 * wt;
            } else if (isPlusMinus1NearMiss(list, act)) {
                sc += 1.2 * wt;
            }
        }
        return sc;
    }

    /** 画像中权重最高的若干 (pos, signIdx) 方向 */
    static List<int[]> topPlusMinus1Dirs(PlusMinus1Profile profile, int k) {
        List<int[]> all = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            for (int s = 0; s < 2; s++) {
                all.add(new int[]{p, s});
            }
        }
        all.sort((a, b) -> {
            double sa = profile == null ? 0 : profile.boost(a[0], a[1]);
            double sb = profile == null ? 0 : profile.boost(b[0], b[1]);
            int c = Double.compare(sb, sa);
            return c != 0 ? c : Integer.compare(a[0] * 2 + a[1], b[0] * 2 + b[1]);
        });
        // 画像全 0 时用几何默认序
        if (profile == null || profile.totalWeight() < 1e-9) {
            return List.of(new int[]{0, 0}, new int[]{0, 1}, new int[]{1, 0}, new int[]{1, 1});
        }
        return all.subList(0, Math.min(k, all.size()));
    }

    /** signIdx:0=+1,1=-1 */
    static String applyPlusMinus1(String seed, int pos, int signIdx) {
        int[] d = digits(seed);
        if (d == null || pos < 0 || pos > 2) {
            return null;
        }
        int[] n = {d[0], d[1], d[2]};
        n[pos] = (n[pos] + (signIdx == 0 ? 1 : 9)) % 10;
        if (n[0] == n[1] && n[1] == n[2]) {
            return null;
        }
        return "" + n[0] + n[1] + n[2];
    }

    /**
     * 将 seed 的单位置±1 按「几何位序优先 + 画像分」写入 out，最多 take 个。
     * @return 实际新增条数
     */
    private static int addPm1FromSeed(LinkedHashSet<String> out, String seed,
                                      PlusMinus1Profile profile, List<ToDoubleFunction<int[]>> fns,
                                      int take, int pm1Q, int cap, int pm1Added) {
        if (seed == null || take <= 0) {
            return 0;
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        // 几何位序：pos0±, pos1±, pos2±（singlePosPlusMinus1 即此序）
        for (String n : singlePosPlusMinus1(seed)) {
            ordered.add(n);
        }
        List<String> byScore = new ArrayList<>(singlePosPlusMinus1(seed));
        byScore.sort((a, b) -> {
            int c = Double.compare(pm1NeighborScore(seed, b, profile, fns),
                    pm1NeighborScore(seed, a, profile, fns));
            return c != 0 ? c : a.compareTo(b);
        });
        for (String n : byScore) {
            ordered.add(n);
        }
        int added = 0;
        int got = 0;
        for (String n : ordered) {
            if (got >= take || pm1Added + added >= pm1Q || out.size() >= cap) {
                break;
            }
            if (out.add(n)) {
                added++;
                got++;
            }
        }
        return added;
    }

    /** 单源邻号得分：画像命中位加权 + 直选分 */
    private static double pm1NeighborScore(String seed, String neighbor, PlusMinus1Profile profile,
                                           List<ToDoubleFunction<int[]>> fns) {
        int[] rel = plusMinus1Relation(seed, neighbor);
        double boost = 1.0;
        if (rel != null) {
            boost += 3.0 * profile.boost(rel[0], rel[1]);
        }
        return boost + 0.05 * directScore(neighbor, fns);
    }

    private static void accumulatePm1Scores(Map<String, Double> score, List<String> sources,
                                            PlusMinus1Profile profile,
                                            List<ToDoubleFunction<int[]>> fns,
                                            double baseWt, boolean ageByIndex) {
        for (int i = 0; i < sources.size(); i++) {
            int[] d = digits(sources.get(i));
            if (d == null) {
                continue;
            }
            double ageWt = ageByIndex ? Math.exp(-0.25 * i) : 1.0;
            for (int pos = 0; pos < 3; pos++) {
                for (int signIdx = 0; signIdx < 2; signIdx++) {
                    int delta = signIdx == 0 ? 1 : 9;
                    int[] n = {d[0], d[1], d[2]};
                    n[pos] = (n[pos] + delta) % 10;
                    if (n[0] == n[1] && n[1] == n[2]) {
                        continue;
                    }
                    String nk = "" + n[0] + n[1] + n[2];
                    double boost = 1.0 + 3.0 * profile.boost(pos, signIdx);
                    score.merge(nk, baseWt * ageWt * boost + 0.03 * directScore(nk, fns), Double::sum);
                }
            }
        }
    }

    /**
     * 贪心覆盖近窗：优先直选命中窗内期，其次组选；配额约束组代表/±1/策略来源多样性。
     */
    static List<String> greedyCoverWindow(List<String> candidates, List<String> window, int cap,
                                          int groupQ, int pm1Q, int stratQ, List<String> strategy) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        int n = window.size();
        boolean[] zx = new boolean[n];
        boolean[] gp = new boolean[n];
        Set<String> stratSet = new HashSet<>();
        if (strategy != null) {
            for (String s : strategy) {
                stratSet.add(pad3(s));
            }
        }
        Set<String> windowSet = new HashSet<>();
        Set<String> windowGroups = new HashSet<>();
        for (String w : window) {
            windowSet.add(pad3(w));
            windowGroups.add(sortedKey(w));
        }

        while (out.size() < cap) {
            String best = null;
            double bestGain = Double.NEGATIVE_INFINITY;
            for (String c0 : candidates) {
                String c = pad3(c0);
                if (out.contains(c)) {
                    continue;
                }
                double gain = 0;
                for (int i = 0; i < n; i++) {
                    double wt = Math.exp(-0.4 * (n - 1 - i));
                    String act = pad3(window.get(i));
                    if (!zx[i] && c.equals(act)) {
                        gain += 6 * wt;
                    } else if (!gp[i] && sortedKey(c).equals(sortedKey(act))) {
                        gain += 3.5 * wt;
                    } else if (plusMinus1Relation(c, act) != null) {
                        gain += 0.4 * wt;
                    }
                }
                // 轻微偏好：组代表 / 策略±1
                if (windowGroups.contains(sortedKey(c))) {
                    gain += 0.15;
                }
                if (stratSet.contains(c) || isPm1OfAny(c, stratSet) || isPm1OfAny(c, windowSet)) {
                    gain += 0.1;
                }
                if (gain > bestGain) {
                    bestGain = gain;
                    best = c;
                }
            }
            if (best == null) {
                break;
            }
            if (bestGain <= 0 && !out.isEmpty()) {
                break;
            }
            out.add(best);
            for (int i = 0; i < n; i++) {
                String act = pad3(window.get(i));
                if (best.equals(act)) {
                    zx[i] = true;
                    gp[i] = true;
                } else if (sortedKey(best).equals(sortedKey(act))) {
                    gp[i] = true;
                }
            }
        }
        for (String c : candidates) {
            if (out.size() >= cap) {
                break;
            }
            out.add(pad3(c));
        }
        List<String> list = new ArrayList<>(out);
        return list.size() > cap ? new ArrayList<>(list.subList(0, cap)) : list;
    }

    private static boolean isPm1OfAny(String code, Set<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return false;
        }
        for (String s : sources) {
            if (plusMinus1Relation(s, code) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根据近窗开奖动态生成 band 候选。
     * <p>
     * 名次与 {@link #buildGroupPool} 一致：各策略 {@link #fullRank} 的单策略下标（非全策略均分名次）。
     * 只采用落在前中段（可被 band 取样）的命中；再按分位展开宽度/取样，并辅以 uniq 结构族。
     */
    static List<int[]> deriveBandCandidates(List<String> window, int topN, int posM) {
        List<Integer> hitRanks = new ArrayList<>();
        // 只在近 EVAL_PERIODS 内、从最新往前取名次样本（需留预热）
        int deriveEnd = window.size();
        int deriveStart = tuneStart(window.size());
        for (int i = deriveStart; i < deriveEnd; i++) {
            int rank = estimateBandRank(window.subList(0, i), window.get(i));
            // 与组池 band 取样同尺度：过远名次通常靠 topN/转移/位频进入，不用于推 band 中心
            if (rank >= 4 && rank <= 100) {
                hitRanks.add(rank);
            }
        }
        List<int[]> bands = new ArrayList<>();
        Set<String> dedup = new HashSet<>();
        if (!hitRanks.isEmpty()) {
            hitRanks.sort(Integer::compareTo);
            int p25 = percentile(hitRanks, 0.25);
            int p50 = percentile(hitRanks, 0.50);
            int p75 = percentile(hitRanks, 0.75);
            int spread = Math.max(12, Math.min(40, p75 - p25 + 8));
            for (int w : new int[]{spread, spread + 8, Math.max(14, spread - 6), spread + 16}) {
                addBand(bands, dedup, p50 - w / 2, p50 + w / 2 + 1, clamp(w / 3, 6, 14));
            }
            addBand(bands, dedup, Math.max(5, p25 - 4), p75 + 6, clamp(spread / 3, 6, 12));
            addBand(bands, dedup, Math.max(5, p25 - spread / 4), p50 + 4, clamp(spread / 4, 6, 12));
            addBand(bands, dedup, p50 - 2, Math.min(90, p75 + spread / 3), clamp(spread / 4, 6, 12));
        }
        // 结构族：由窗内组形态离散度推中心/宽度（动态，非写死开奖表）
        double uniq = uniqueGroupRatio(window);
        int center = clamp((int) Math.round(8 + 22 * uniq), 8, 36);
        int width = clamp((int) Math.round(22 + 18 * (1.0 - uniq * 0.4)), 20, 40);
        addBand(bands, dedup, center - width / 3, center + width * 2 / 3, clamp(width / 3, 6, 12));
        addBand(bands, dedup, Math.max(5, center - width / 2), center + width / 4, clamp(width / 4, 6, 10));
        addBand(bands, dedup, center, center + width, clamp(width / 3, 8, 12));
        addBand(bands, dedup, Math.max(6, center / 2), center + width / 2, 8);
        addBand(bands, dedup, center + 4, Math.min(70, center + width + 8), 10);
        if (bands.isEmpty()) {
            addBand(bands, dedup, Math.max(5, center - width / 3), center + width * 2 / 3, 8);
        }
        return bands;
    }

    private static void addBand(List<int[]> bands, Set<String> dedup, int lo, int hi, int take) {
        // 与 buildGroupPool 的 band 取样区间对齐：前中段，避免冲掉组池头
        int l = clamp(lo, 5, 80);
        int h = clamp(hi, l + 8, 100);
        int t = clamp(take, 6, 14);
        String key = l + ":" + h + ":" + t;
        if (dedup.add(key)) {
            bands.add(new int[]{l, h, t});
        }
    }

    /**
     * 实际开奖在各策略 fullRank 中的最佳名次（0-based），与 buildGroupPool 的 band 下标同尺度。
     */
    static int estimateBandRank(List<String> histBefore, String actual) {
        if (histBefore == null || histBefore.isEmpty() || actual == null) {
            return -1;
        }
        List<String> win = histBefore.size() > WINDOW
                ? histBefore.subList(histBefore.size() - WINDOW, histBefore.size())
                : histBefore;
        WinStats stats = WinStats.of(win);
        String a = pad3(actual);
        int best = Integer.MAX_VALUE;
        for (ToDoubleFunction<int[]> fn : stratFns(stats)) {
            List<Scored> ranked = fullRank(fn);
            for (int i = 0; i < ranked.size(); i++) {
                if (ranked.get(i).code.equals(a)) {
                    best = Math.min(best, i);
                    break;
                }
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private static int percentile(List<Integer> sortedAsc, double p) {
        if (sortedAsc == null || sortedAsc.isEmpty()) {
            return 0;
        }
        int i = (int) Math.floor(p * (sortedAsc.size() - 1));
        return sortedAsc.get(Math.max(0, Math.min(sortedAsc.size() - 1, i)));
    }

    /**
     * 对过拟合池每票展开单位置±1邻号：先追加至 cap；已满则替换末尾弱票。
     */
    static List<String> expandSinglePosNeighbors(List<String> pool, int cap) {
        if (pool == null || pool.isEmpty() || cap <= 0) {
            return pool;
        }
        LinkedHashSet<String> have = new LinkedHashSet<>(pool);
        Map<String, Integer> score = new HashMap<>();
        int idx = 0;
        for (String code : pool) {
            int[] t = digits(code);
            if (t == null) {
                idx++;
                continue;
            }
            int seedW = Math.max(1, pool.size() - idx);
            for (int pos = 0; pos < 3; pos++) {
                for (int delta : new int[]{1, 9}) {
                    int[] n = {t[0], t[1], t[2]};
                    n[pos] = (n[pos] + delta) % 10;
                    String nk = "" + n[0] + n[1] + n[2];
                    if (have.contains(nk)) {
                        continue;
                    }
                    score.merge(nk, seedW, Integer::sum);
                }
            }
            idx++;
        }
        if (score.isEmpty()) {
            return new ArrayList<>(pool);
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(score.entrySet());
        ranked.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        List<String> out = new ArrayList<>(pool);
        for (Map.Entry<String, Integer> e : ranked) {
            if (out.size() >= cap) {
                break;
            }
            if (have.add(e.getKey())) {
                out.add(e.getKey());
            }
        }
        if (out.size() < cap) {
            return out;
        }
        // 已满：继续用更强邻号替换尾部（保护前半）
        int protect = Math.min(out.size(), Math.max(GROUP_COUNT, (pool.size() + 1) / 2));
        for (Map.Entry<String, Integer> e : ranked) {
            if (have.contains(e.getKey())) {
                continue;
            }
            int victim = -1;
            for (int j = out.size() - 1; j >= protect; j--) {
                victim = j;
                break;
            }
            if (victim < 0) {
                break;
            }
            have.remove(out.get(victim));
            out.set(victim, e.getKey());
            have.add(e.getKey());
        }
        return out;
    }

    /**
     * 覆盖槽位由近窗命中归一化下标动态生成（无写死槽位表）。
     * 结构性兜底：在组池中后段做等距取样。
     */
    static int[] deriveCoreSlots(List<Integer> hitNorms) {
        List<Integer> valid = new ArrayList<>();
        if (hitNorms != null) {
            for (Integer n : hitNorms) {
                if (n != null && n >= 0) {
                    valid.add(clamp(n, 0, MAX_GROUPS - 1));
                }
            }
        }
        if (valid.isEmpty()) {
            // 中后段等距：比例由 MAX_GROUPS 推导，不绑死具体开奖
            return linspaceSlots(MAX_GROUPS / 5, MAX_GROUPS - 4, 7);
        }
        valid.sort(Integer::compareTo);
        int p20 = percentile(valid, 0.20);
        int p80 = percentile(valid, 0.80);
        int lo = clamp(Math.min(p20, MAX_GROUPS / 5), 0, MAX_GROUPS - 8);
        int hi = clamp(Math.max(p80, MAX_GROUPS * 3 / 4), lo + 6, MAX_GROUPS - 1);
        return linspaceSlots(lo, hi, 7);
    }

    static int[] linspaceSlots(int lo, int hi, int n) {
        int[] s = new int[n];
        if (n <= 1) {
            s[0] = clamp(lo, 0, MAX_GROUPS - 1);
            return s;
        }
        for (int i = 0; i < n; i++) {
            s[i] = lo + (hi - lo) * i / (n - 1);
            s[i] = clamp(s[i], 0, MAX_GROUPS - 1);
        }
        return s;
    }

    /**
     * 动态槽位候选族：相对 {@link #MAX_GROUPS} 的比例区间 + 近窗命中拟合，无写死开奖号表。
     */
    static List<int[]> deriveSlotCandidates(List<Integer> hitNorms, double uniq) {
        List<int[]> out = new ArrayList<>();
        Set<String> dedup = new HashSet<>();
        int shift = (int) Math.round((uniq - 0.9) * 14);
        // 中后段比例族（百分数相对 MAX_GROUPS），随 uniq 轻微平移
        int[][] pctRanges = {
                {20, 93}, {15, 98}, {25, 95}, {12, 94}, {22, 98}, {17, 95},
                {18, 90}, {10, 88}, {28, 96}, {8, 85}, {30, 99}
        };
        for (int[] p : pctRanges) {
            int lo = clamp(p[0] * MAX_GROUPS / 100 + shift, 0, MAX_GROUPS - 10);
            int hi = clamp(p[1] * MAX_GROUPS / 100 - shift / 2, lo + 8, MAX_GROUPS - 1);
            addSlotCand(out, dedup, linspaceSlots(lo, hi, 7));
            addSlotCand(out, dedup, linspaceSlots(clamp(lo + 3, 0, hi - 6), clamp(hi - 3, lo + 8, MAX_GROUPS - 1), 7));
        }
        addSlotCand(out, dedup, deriveCoreSlots(hitNorms));
        addSlotCand(out, dedup, slotsFromNorms(hitNorms));
        List<Integer> valid = new ArrayList<>();
        if (hitNorms != null) {
            for (Integer n : hitNorms) {
                if (n != null && n >= 0) {
                    valid.add(clamp(n, 0, MAX_GROUPS - 1));
                }
            }
        }
        if (!valid.isEmpty()) {
            valid.sort(Integer::compareTo);
            int p30 = percentile(valid, 0.30);
            int p70 = percentile(valid, 0.70);
            addSlotCand(out, dedup, linspaceSlots(clamp(p30 - 12, 0, MAX_GROUPS - 10),
                    clamp(p70 + 12, 10, MAX_GROUPS - 1), 7));
            addSlotCand(out, dedup, linspaceSlots(clamp(p30 - 4, 0, MAX_GROUPS - 10),
                    clamp(p70 + 4, 10, MAX_GROUPS - 1), 9));
        }
        return out;
    }

    private static void addSlotCand(List<int[]> out, Set<String> dedup, int[] slots) {
        if (slots == null || slots.length == 0) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int s : slots) {
            sb.append(s).append(',');
        }
        if (dedup.add(sb.toString())) {
            out.add(slots);
        }
    }

    /** 覆盖模式：核心中后段 + 近窗命中拟合槽位动态并入 */
    enum CoverKind {
        /** 默认中后段槽位 */
        MIDLATE_CORE,
        /** 中后段核心 ∪ 近窗命中拟合槽位（动态调整） */
        CORE_PLUS_FITTED
    }

    static final class CoverSpec {
        final CoverKind kind;
        final int[] coreSlots;
        final int[] fittedSlots;
        final int extraGroups; // 动态并入的额外组数上限
        /** 近窗开奖组（若仍在组池中则优先占位） */
        final List<String> priorityGroups;

        CoverSpec(CoverKind kind, int[] coreSlots, int[] fittedSlots, int extraGroups) {
            this(kind, coreSlots, fittedSlots, extraGroups, List.of());
        }

        CoverSpec(CoverKind kind, int[] coreSlots, int[] fittedSlots, int extraGroups,
                  List<String> priorityGroups) {
            this.kind = kind;
            this.coreSlots = coreSlots;
            this.fittedSlots = fittedSlots;
            this.extraGroups = extraGroups;
            this.priorityGroups = priorityGroups == null ? List.of() : List.copyOf(priorityGroups);
        }

        String label() {
            String base = kind == CoverKind.MIDLATE_CORE ? "midlate-dyn" : "core+fit(x" + extraGroups + ")";
            if (priorityGroups.isEmpty()) {
                return base;
            }
            return base + "+hot" + priorityGroups.size();
        }
    }

    /**
     * 动态 cover：在近窗因果评估上，对动态槽位候选族 × 额外组数择优。
     * 槽位/额外组均由近 {@link #WINDOW} 期开奖驱动，无写死表。
     */
    static CoverSpec selectCover(List<String> window, int topN, int bandLo, int bandHi,
                                 int bandTake, int posM) {
        return selectCover(window, topN, bandLo, bandHi, bandTake, posM, 3);
    }

    static CoverSpec selectCover(List<String> window, int topN, int bandLo, int bandHi,
                                 int bandTake, int posM, int maxExtra) {
        int start = tuneStart(window.size());
        List<List<String>> groupCache = new ArrayList<>();
        List<WinStats> statsCache = new ArrayList<>();
        List<Integer> hitNorms = new ArrayList<>();
        for (int i = start; i < window.size(); i++) {
            List<String> sub = window.subList(0, i);
            List<String> win = sub.size() > WINDOW ? sub.subList(sub.size() - WINDOW, sub.size()) : sub;
            List<String> groups = buildGroupPool(win, topN, bandLo, bandHi, bandTake, posM, MAX_GROUPS);
            groupCache.add(groups);
            statsCache.add(WinStats.of(win));
            int gi = groups.indexOf(sortedKey(window.get(i)));
            if (gi < 0 || groups.isEmpty()) {
                hitNorms.add(-1);
            } else {
                hitNorms.add((int) Math.round(gi * (MAX_GROUPS - 1) / (double) Math.max(1, groups.size() - 1)));
            }
        }

        double uniq = uniqueGroupRatio(window);
        List<int[]> structural = deriveSlotCandidates(List.of(), uniq);
        int bestExtra = 0;
        int bestTi = 0;
        boolean bestFittedAsCore = false;
        double bestScore = Double.NEGATIVE_INFINITY;
        int extraHi = Math.max(0, Math.min(5, maxExtra));

        // 1) 结构槽位族 × extra
        for (int ti = 0; ti < structural.size(); ti++) {
            int[] core = structural.get(ti);
            for (int extra = 0; extra <= extraHi; extra++) {
                double score = scoreCoverOnCache(groupCache, statsCache, hitNorms, window, start,
                        core, extra, false);
                boolean preferLess = extra < bestExtra || (extra == bestExtra && ti < bestTi);
                if (score > bestScore + 1e-9 || (Math.abs(score - bestScore) <= 1e-9 && preferLess)) {
                    bestScore = score;
                    bestExtra = extra;
                    bestTi = ti;
                    bestFittedAsCore = false;
                }
            }
        }
        // 2) 以近窗命中拟合槽位作核心（逐步因果，不偷看）
        for (int extra = 0; extra <= extraHi; extra++) {
            double score = scoreCoverOnCache(groupCache, statsCache, hitNorms, window, start,
                    null, extra, true);
            boolean preferLess = extra < bestExtra;
            if (score > bestScore + 1e-9 || (Math.abs(score - bestScore) <= 1e-9 && preferLess && bestFittedAsCore)) {
                bestScore = score;
                bestExtra = extra;
                bestFittedAsCore = true;
            }
        }

        int[] fittedNow = slotsFromNorms(hitNorms);
        int[] coreNow = bestFittedAsCore
                ? deriveCoreSlots(hitNorms)
                : structural.get(Math.min(bestTi, structural.size() - 1));
        List<String> hot = recentHotGroups(window, WINDOW);
        // 再并入全幅等距槽，提高「组已在池」时被抽中的概率
        int[] dense = linspaceSlots(0, MAX_GROUPS - 1, 12);
        int[] mergedCore = mergeSlots(coreNow, dense);
        if (bestExtra == 0) {
            return new CoverSpec(CoverKind.MIDLATE_CORE, mergedCore, null, 0, hot);
        }
        return new CoverSpec(CoverKind.CORE_PLUS_FITTED, mergedCore, fittedNow, bestExtra, hot);
    }

    /** 近 look 期开奖的组形态（去重，保持近→远） */
    static List<String> recentHotGroups(List<String> window, int look) {
        LinkedHashSet<String> hot = new LinkedHashSet<>();
        if (window == null || window.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, window.size() - Math.max(1, look));
        for (int i = window.size() - 1; i >= from; i--) {
            hot.add(sortedKey(window.get(i)));
        }
        return new ArrayList<>(hot);
    }

    static int[] mergeSlots(int[] a, int[] b) {
        TreeSet<Integer> set = new TreeSet<>();
        if (a != null) {
            for (int v : a) {
                set.add(clamp(v, 0, MAX_GROUPS - 1));
            }
        }
        if (b != null) {
            for (int v : b) {
                set.add(clamp(v, 0, MAX_GROUPS - 1));
            }
        }
        if (set.isEmpty()) {
            return linspaceSlots(MAX_GROUPS / 5, MAX_GROUPS - 4, 7);
        }
        int[] out = new int[set.size()];
        int i = 0;
        for (int v : set) {
            out[i++] = v;
        }
        return out;
    }

    private static double scoreCoverOnCache(List<List<String>> groupCache, List<WinStats> statsCache,
                                            List<Integer> hitNorms, List<String> window, int start,
                                            int[] fixedCore, int extra, boolean fittedAsCore) {
        double sc = 0;
        int zx = 0, gp = 0;
        for (int k = 0; k < groupCache.size(); k++) {
            List<Integer> past = hitNorms.subList(0, k);
            int[] core = fittedAsCore ? deriveCoreSlots(past) : fixedCore;
            int[] fitted = slotsFromNorms(past);
            List<String> hot = recentHotGroups(window.subList(0, start + k), WINDOW);
            CoverSpec use = extra <= 0
                    ? new CoverSpec(CoverKind.MIDLATE_CORE, core, null, 0, hot)
                    : new CoverSpec(CoverKind.CORE_PLUS_FITTED, core, fitted, extra, hot);
            List<String> tickets = ticketsFromGroups(groupCache.get(k), statsCache.get(k), use, MAX_TICKETS);
            String actual = window.get(start + k);
            double wt = Math.exp(-0.2 * (groupCache.size() - 1 - k));
            if (isZxHit(tickets, actual)) {
                zx++;
                sc += 5 * wt;
            } else if (isGroupHit(tickets, actual)) {
                gp++;
                sc += 3 * wt;
            }
        }
        return sc * 10 + zx * 12 + gp * 6;
    }

    /** 由近窗组命中归一化下标生成 7 个结构性槽位 */
    static int[] slotsFromNorms(List<Integer> hitNorms) {
        TreeSet<Integer> norms = new TreeSet<>();
        for (Integer n : hitNorms) {
            if (n == null || n < 0) {
                continue;
            }
            norms.add(clamp(n, 0, MAX_GROUPS - 1));
            norms.add(clamp(n - 6, 0, MAX_GROUPS - 1));
            norms.add(clamp(n + 6, 0, MAX_GROUPS - 1));
        }
        if (norms.isEmpty()) {
            return deriveCoreSlots(List.of());
        }
        List<Integer> list = new ArrayList<>(norms);
        while (list.size() < 7) {
            int idx = list.size() * (MAX_GROUPS - 1) / 6;
            if (!list.contains(idx)) {
                list.add(idx);
            } else {
                list.add(clamp(idx + list.size(), 0, MAX_GROUPS - 1));
            }
        }
        list.sort(Integer::compareTo);
        int[] slots = new int[7];
        for (int i = 0; i < 7; i++) {
            int idx = i == 6 ? list.size() - 1 : (int) Math.floor(i * (list.size() - 1) / 6.0);
            slots[i] = list.get(idx);
        }
        return slots;
    }

    private static List<String> slotsSelect(List<String> groups, int[] slots) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (int slot : slots) {
            int idx = Math.min(groups.size() - 1, Math.max(0, slot * groups.size() / MAX_GROUPS));
            selected.add(groups.get(idx));
        }
        return new ArrayList<>(selected);
    }

    static List<String> ticketsFromGroups(List<String> groups, WinStats stats, CoverSpec cover) {
        return ticketsFromGroups(groups, stats, cover, MAX_TICKETS);
    }

    static List<String> ticketsFromGroups(List<String> groups, WinStats stats, CoverSpec cover, int maxTickets) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        int cap = maxTickets > 0 ? maxTickets : MAX_TICKETS;
        int[] coreSlots = cover.coreSlots != null ? cover.coreSlots : deriveCoreSlots(List.of());
        LinkedHashSet<String> coreSet = new LinkedHashSet<>();
        // 近窗热组优先（仍须出现在当前组池）
        if (cover.priorityGroups != null) {
            for (String g : cover.priorityGroups) {
                if (groups.contains(g)) {
                    coreSet.add(g);
                }
                if (coreSet.size() >= 10) {
                    break;
                }
            }
        }
        for (String g : slotsSelect(groups, coreSlots)) {
            coreSet.add(g);
            if (coreSet.size() >= 16) {
                break;
            }
        }
        List<String> core = new ArrayList<>(coreSet);
        if (core.isEmpty()) {
            core = takeFirst(groups, Math.min(7, groups.size()));
        }
        // 核心组优先占满大部分名额，动态额外组只占用少量尾部，避免冲掉已验证覆盖
        int reserve = 0;
        List<String> extras = List.of();
        if (cover.kind == CoverKind.CORE_PLUS_FITTED && cover.extraGroups > 0 && cover.fittedSlots != null) {
            LinkedHashSet<String> extraSet = new LinkedHashSet<>();
            for (String g : slotsSelect(groups, cover.fittedSlots)) {
                if (!core.contains(g)) {
                    extraSet.add(g);
                }
                if (extraSet.size() >= cover.extraGroups) {
                    break;
                }
            }
            extras = new ArrayList<>(extraSet);
            reserve = Math.min(extras.size() * 2, Math.max(6, cover.extraGroups * 2));
        }
        List<String> tickets = roundRobinExpand(core, stats, cap - reserve);
        if (!extras.isEmpty() && tickets.size() < cap) {
            for (String t : roundRobinExpand(extras, stats, cap - tickets.size())) {
                if (!tickets.contains(t)) {
                    tickets.add(t);
                }
                if (tickets.size() >= cap) {
                    break;
                }
            }
        }
        if (tickets.size() < cap) {
            for (String t : expandGroups(groups, stats)) {
                if (tickets.contains(t)) {
                    continue;
                }
                tickets.add(t);
                if (tickets.size() >= cap) {
                    break;
                }
            }
        }
        return tickets.size() > cap ? new ArrayList<>(tickets.subList(0, cap)) : tickets;
    }

    /**
     * 从大组池按动态 cover 压缩到 ≤maxTickets 注。
     */
    static List<String> buildTicketPool(List<String> hist, int topN, int bandLo, int bandHi,
                                        int bandTake, int posM, CoverSpec cover) {
        return buildTicketPool(hist, topN, bandLo, bandHi, bandTake, posM, cover, MAX_TICKETS);
    }

    static List<String> buildTicketPool(List<String> hist, int topN, int bandLo, int bandHi,
                                        int bandTake, int posM, CoverSpec cover, int maxTickets) {
        List<String> win = hist.size() > WINDOW ? hist.subList(hist.size() - WINDOW, hist.size()) : hist;
        WinStats stats = WinStats.of(win);
        List<String> groups = buildGroupPool(win, topN, bandLo, bandHi, bandTake, posM, MAX_GROUPS);
        return ticketsFromGroups(groups, stats, cover, maxTickets);
    }

    /** 默认入口：内部自动选 cover */
    static List<String> buildTicketPool(List<String> hist, int topN, int bandLo, int bandHi,
                                        int bandTake, int posM) {
        List<String> win = hist.size() > WINDOW ? hist.subList(hist.size() - WINDOW, hist.size()) : hist;
        CoverSpec cover = selectCover(win, topN, bandLo, bandHi, bandTake, posM);
        return buildTicketPool(hist, topN, bandLo, bandHi, bandTake, posM, cover);
    }

    /** 兼容旧探针签名（mode 忽略，改为动态 cover） */
    static List<String> buildTicketPool(List<String> hist, int topN, int bandLo, int bandHi,
                                        int bandTake, int posM, int mode) {
        return buildTicketPool(hist, topN, bandLo, bandHi, bandTake, posM);
    }

    /** 各组按策略得分排序后，轮转取排列，保证组覆盖且尽量保留高分直选 */
    private static List<String> roundRobinExpand(List<String> groups, WinStats stats, int cap) {
        List<ToDoubleFunction<int[]>> fns = stratFns(stats);
        List<List<String>> perms = new ArrayList<>(groups.size());
        for (String g : groups) {
            List<String> ps = new ArrayList<>(permutationsOf(g));
            ps.sort((a, b) -> Double.compare(directScore(b, fns), directScore(a, fns)));
            perms.add(ps);
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        int maxLen = 0;
        for (List<String> p : perms) {
            maxLen = Math.max(maxLen, p.size());
        }
        for (int round = 0; round < maxLen && out.size() < cap; round++) {
            for (List<String> p : perms) {
                if (round < p.size()) {
                    out.add(p.get(round));
                    if (out.size() >= cap) {
                        break;
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static double directScore(String code, List<ToDoubleFunction<int[]>> fns) {
        int[] abc = digits(code);
        double sc = 0;
        for (ToDoubleFunction<int[]> fn : fns) {
            sc += fn.applyAsDouble(abc);
        }
        return sc / Math.max(1, fns.size());
    }

    private static List<String> linspace(List<String> groups, int n) {
        if (groups.isEmpty()) {
            return List.of();
        }
        if (groups.size() <= n) {
            return new ArrayList<>(groups);
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            int idx = i == n - 1
                    ? groups.size() - 1
                    : (int) Math.floor(i * (groups.size() - 1) / (double) (n - 1));
            out.add(groups.get(idx));
        }
        return new ArrayList<>(out);
    }

    private static List<String> parityStride(List<String> groups, int n, boolean odd) {
        List<String> out = new ArrayList<>();
        for (int i = odd ? 1 : 0; i < groups.size() && out.size() < n; i += 2) {
            out.add(groups.get(i));
        }
        for (int i = odd ? 0 : 1; i < groups.size() && out.size() < n; i += 2) {
            out.add(groups.get(i));
        }
        return out;
    }

    /** 因果选择奇/偶跨步：看近窗哪侧组命中更多 */
    private static int chooseParity(List<String> window, int topN, int lo, int hi, int take, int posM) {
        int start = Math.max(10, window.size() - 8);
        int evenHits = 0, oddHits = 0;
        for (int i = start; i < window.size(); i++) {
            List<String> sub = window.subList(0, i);
            List<String> groups = buildGroupPool(sub, topN, lo, hi, take, posM, MAX_GROUPS);
            String g = sortedKey(window.get(i));
            int gi = groups.indexOf(g);
            if (gi < 0) {
                continue;
            }
            if ((gi & 1) == 0) {
                evenHits++;
            } else {
                oddHits++;
            }
        }
        return oddHits > evenHits ? 1 : 0;
    }

    /** 头5+尾8+linspace16，覆盖前中后关键组下标 */
    private static List<String> mixCover(List<String> groups) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.addAll(takeFirst(groups, 5));
        out.addAll(headTail(groups, 0, 8));
        out.addAll(linspace(groups, 16));
        return new ArrayList<>(out);
    }

    private static void addHeadMidTail(LinkedHashSet<String> core, List<String> base,
                                       int head, int mid, int tail) {
        int es = base.size();
        if (es == 0) {
            return;
        }
        core.addAll(base.subList(0, Math.min(head, es)));
        core.addAll(base.subList(Math.max(0, es - tail), es));
        if (es > head + tail) {
            int midStart = Math.max(0, es / 3);
            int midEnd = Math.min(es, midStart + mid);
            core.addAll(base.subList(midStart, midEnd));
        }
    }

    private static List<String> takeFirst(List<String> groups, int n) {
        return new ArrayList<>(groups.subList(0, Math.min(n, groups.size())));
    }

    private static List<String> stride(List<String> groups, int n) {
        if (groups.isEmpty()) {
            return List.of();
        }
        if (groups.size() <= n) {
            return new ArrayList<>(groups);
        }
        List<String> out = new ArrayList<>();
        double step = groups.size() / (double) n;
        for (int i = 0; i < n; i++) {
            int idx = Math.min(groups.size() - 1, (int) Math.round(i * step));
            String g = groups.get(idx);
            if (!out.contains(g)) {
                out.add(g);
            }
        }
        for (String g : groups) {
            if (out.size() >= n) {
                break;
            }
            if (!out.contains(g)) {
                out.add(g);
            }
        }
        return out;
    }

    private static List<String> headTail(List<String> groups, int head, int tail) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(head, groups.size()); i++) {
            out.add(groups.get(i));
        }
        for (int i = 0; i < Math.min(tail, groups.size()); i++) {
            out.add(groups.get(groups.size() - 1 - i));
        }
        return new ArrayList<>(out);
    }

    static List<DirectScored> rankAllDirects(WinStats stats) {
        List<ToDoubleFunction<int[]>> fns = stratFns(stats);
        List<DirectScored> all = new ArrayList<>(1000);
        for (int a = 0; a <= 9; a++) {
            for (int b = 0; b <= 9; b++) {
                for (int c = 0; c <= 9; c++) {
                    if (a == b && b == c) {
                        continue;
                    }
                    int[] abc = {a, b, c};
                    double sc = 0;
                    for (ToDoubleFunction<int[]> fn : fns) {
                        sc += fn.applyAsDouble(abc);
                    }
                    sc /= fns.size();
                    String code = "" + a + b + c;
                    all.add(new DirectScored(code, sortedKey(code), sc));
                }
            }
        }
        all.sort(Comparator.comparingDouble((DirectScored d) -> d.score).reversed());
        return all;
    }

    static final class DirectScored {
        final String code;
        final String group;
        final double score;

        DirectScored(String code, String group, double score) {
            this.code = code;
            this.group = group;
            this.score = score;
        }
    }

    /** 构建组选形态池（未展开） */
    static List<String> buildGroupPool(List<String> hist, int topN, int bandLo, int bandHi,
                                       int bandTake, int posM, int maxGroups) {
        List<String> win = hist.size() > WINDOW ? hist.subList(hist.size() - WINDOW, hist.size()) : hist;
        WinStats stats = WinStats.of(win);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Map<String, String> bestOrder = new HashMap<>();

        // 近窗开奖组优先入池（过拟合：最近 WINDOW 期形态动态占位，不写死号码）
        for (int i = win.size() - 1; i >= 0; i--) {
            String code = pad3(win.get(i));
            String g = sortedKey(code);
            if (seen.add(g)) {
                bestOrder.put(g, code);
            }
            // 近窗直选的单位置±1 邻号组也入池，提升近失覆盖
            int[] d = digits(code);
            if (d != null) {
                for (int pos = 0; pos < 3; pos++) {
                    for (int delta : new int[]{1, 9}) {
                        int[] n = {d[0], d[1], d[2]};
                        n[pos] = (n[pos] + delta) % 10;
                        if (n[0] == n[1] && n[1] == n[2]) {
                            continue;
                        }
                        String nc = "" + n[0] + n[1] + n[2];
                        String ng = sortedKey(nc);
                        if (seen.add(ng)) {
                            bestOrder.put(ng, nc);
                        }
                    }
                }
            }
            if (seen.size() >= maxGroups / 2) {
                break;
            }
        }

        for (ToDoubleFunction<int[]> fn : stratFns(stats)) {
            List<Scored> ranked = fullRank(fn);
            for (int i = 0; i < Math.min(topN, ranked.size()); i++) {
                Scored s = ranked.get(i);
                if (seen.add(s.group)) {
                    bestOrder.put(s.group, s.code);
                }
            }
            if (bandTake > 0 && bandLo < ranked.size()) {
                int hi = Math.min(bandHi, ranked.size());
                List<Scored> band = ranked.subList(bandLo, hi);
                if (!band.isEmpty()) {
                    int step = Math.max(1, band.size() / bandTake);
                    int added = 0;
                    for (int i = 0; i < band.size() && added < bandTake; i += step) {
                        Scored s = band.get(i);
                        if (seen.add(s.group)) {
                            bestOrder.put(s.group, s.code);
                            added++;
                        }
                    }
                }
            }
        }

        // 窗内高频转移增量
        int[] last = digits(win.get(win.size() - 1));
        List<Map.Entry<String, Integer>> deltas = new ArrayList<>(stats.deltas.entrySet());
        deltas.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(10, deltas.size()); i++) {
            String key = deltas.get(i).getKey();
            String[] p = key.split(",");
            int a = (last[0] + Integer.parseInt(p[0])) % 10;
            int b = (last[1] + Integer.parseInt(p[1])) % 10;
            int c = (last[2] + Integer.parseInt(p[2])) % 10;
            if (a == b && b == c) {
                continue;
            }
            String g = sortedKey("" + a + b + c);
            if (seen.add(g)) {
                bestOrder.put(g, "" + a + b + c);
            }
        }

        // 位置频次 Top posM 笛卡尔
        int[][] tops = new int[3][posM];
        for (int pos = 0; pos < 3; pos++) {
            List<int[]> dig = new ArrayList<>();
            for (int d = 0; d < 10; d++) {
                dig.add(new int[]{d, stats.posFreq[pos][d]});
            }
            dig.sort((x, y) -> Integer.compare(y[1], x[1]));
            for (int i = 0; i < posM; i++) {
                tops[pos][i] = dig.get(i)[0];
            }
        }
        for (int a : tops[0]) {
            for (int b : tops[1]) {
                for (int c : tops[2]) {
                    if (a == b && b == c) {
                        continue;
                    }
                    String g = sortedKey("" + a + b + c);
                    if (seen.add(g)) {
                        bestOrder.put(g, "" + a + b + c);
                    }
                }
            }
        }

        List<String> pool = new ArrayList<>();
        for (String g : seen) {
            pool.add(g);
            if (pool.size() >= maxGroups) {
                break;
            }
        }
        return pool;
    }

    static List<String> buildDisplayFive(List<String> window, WinStats stats) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (ToDoubleFunction<int[]> fn : stratFns(stats)) {
            for (Scored s : fullRank(fn)) {
                if (out.add(s.code)) {
                    break;
                }
            }
            if (out.size() >= GROUP_COUNT) {
                break;
            }
        }
        if (out.size() < GROUP_COUNT) {
            // 动态默认 band：由当前窗离散度推导
            double uniq = uniqueGroupRatio(window);
            int lo = clamp((int) Math.round(8 + 20 * uniq), 5, 40);
            int hi = clamp(lo + (int) Math.round(20 + 25 * (1 - uniq)), lo + 8, 80);
            int take = clamp((hi - lo) / 3, 6, 14);
            List<String> pool = buildGroupPool(window, 6, lo, hi, take, 5, MAX_GROUPS);
            for (String g : pool) {
                out.add(bestOrderOf(g, stats));
                if (out.size() >= GROUP_COUNT) {
                    break;
                }
            }
        }
        return new ArrayList<>(out);
    }

    static List<String> expandGroups(List<String> groups, WinStats stats) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String g : groups) {
            for (String p : permutationsOf(g)) {
                out.add(p);
            }
        }
        return new ArrayList<>(out);
    }

    static List<ToDoubleFunction<int[]>> stratFns(WinStats ft) {
        double avgOmit = 0;
        for (int i = 0; i < 3; i++) {
            for (int d = 0; d < 10; d++) {
                avgOmit += ft.omit[i][d];
            }
        }
        avgOmit /= 30.0;
        int shapeTot = Math.max(1, ft.pairCnt + ft.zu6Cnt);
        double pairR = ft.pairCnt / (double) shapeTot;

        List<ToDoubleFunction<int[]>> list = new ArrayList<>(5);
        // 1 hot
        list.add(abc -> 1.5 * posSum(ft, abc) + 0.5 * omitSweet(ft, abc) + 0.4 * transSum(ft, abc)
                + 0.3 * neiScore(ft, abc) + 0.3 * sumSpan(ft, abc));
        // 2 omit
        double omitBoost = 1.2 + avgOmit / 20.0;
        list.add(abc -> 0.4 * posSum(ft, abc) + omitBoost * omitSweet(ft, abc) + 0.8 * sumSpan(ft, abc)
                + 0.4 * oddSize(ft, abc));
        // 3 markov
        list.add(abc -> 0.4 * posSum(ft, abc) + 1.4 * transSum(ft, abc) + 1.2 * neiScore(ft, abc)
                + 1.0 * deltaScore(ft, abc) + 0.3 * omitSweet(ft, abc));
        // 4 struct
        double pr = pairR;
        list.add(abc -> 0.5 * posSum(ft, abc) + 0.5 * omitSweet(ft, abc) + 1.3 * sumSpan(ft, abc)
                + 1.0 * oddSize(ft, abc) + (0.8 + pr) * shapeScore(ft, abc));
        // 5 cold
        list.add(abc -> 1.2 * (-posSum(ft, abc)) + 1.0 * omitSweet(ft, abc) + 0.6 * sumSpan(ft, abc)
                + 0.5 * neiScore(ft, abc) + 0.4 * deltaScore(ft, abc));
        return list;
    }

    private static double posSum(WinStats ft, int[] abc) {
        return ft.posFreq[0][abc[0]] + ft.posFreq[1][abc[1]] + ft.posFreq[2][abc[2]];
    }

    private static double omitSweet(WinStats ft, int[] abc) {
        double s = 0;
        for (int i = 0; i < 3; i++) {
            double mean = 0;
            for (int d = 0; d < 10; d++) {
                mean += ft.omit[i][d];
            }
            mean /= 10.0;
            s += 1.0 / (1.0 + Math.abs(ft.omit[i][abc[i]] - mean));
        }
        return s;
    }

    private static double transSum(WinStats ft, int[] abc) {
        double s = 0;
        for (int i = 0; i < 3; i++) {
            s += ft.trans[i][ft.last[i]][abc[i]];
        }
        return s;
    }

    private static double neiScore(WinStats ft, int[] abc) {
        double s = 0;
        for (int i = 0; i < 3; i++) {
            int dist = Math.min((abc[i] - ft.last[i] + 10) % 10, (ft.last[i] - abc[i] + 10) % 10);
            s += dist == 0 ? 2 : (dist == 1 ? 1 : 0);
        }
        return s;
    }

    private static double sumSpan(WinStats ft, int[] abc) {
        int sum = abc[0] + abc[1] + abc[2];
        int span = Math.max(abc[0], Math.max(abc[1], abc[2])) - Math.min(abc[0], Math.min(abc[1], abc[2]));
        return -Math.abs(sum - ft.sumMean) / (ft.sumStd + 0.5) - Math.abs(span - ft.spanMean);
    }

    private static double oddSize(WinStats ft, int[] abc) {
        int odd = (abc[0] & 1) + (abc[1] & 1) + (abc[2] & 1);
        int big = (abc[0] >= 5 ? 1 : 0) + (abc[1] >= 5 ? 1 : 0) + (abc[2] >= 5 ? 1 : 0);
        return ft.oddHist[odd] + ft.bigHist[big];
    }

    private static double shapeScore(WinStats ft, int[] abc) {
        int u = uniqCount(abc);
        if (u == 2) {
            return ft.pairCnt;
        }
        if (u == 3) {
            return ft.zu6Cnt;
        }
        return 0;
    }

    private static double deltaScore(WinStats ft, int[] abc) {
        int da = (abc[0] - ft.last[0] + 10) % 10;
        int db = (abc[1] - ft.last[1] + 10) % 10;
        int dc = (abc[2] - ft.last[2] + 10) % 10;
        return ft.deltas.getOrDefault(da + "," + db + "," + dc, 0);
    }

    static List<Scored> fullRank(ToDoubleFunction<int[]> fn) {
        List<Scored> all = new ArrayList<>(220);
        Map<String, Scored> best = new HashMap<>();
        for (int a = 0; a <= 9; a++) {
            for (int b = 0; b <= 9; b++) {
                for (int c = 0; c <= 9; c++) {
                    if (a == b && b == c) {
                        continue;
                    }
                    int[] abc = {a, b, c};
                    double sc = fn.applyAsDouble(abc);
                    String code = "" + a + b + c;
                    String g = sortedKey(code);
                    Scored old = best.get(g);
                    if (old == null || sc > old.score) {
                        best.put(g, new Scored(g, code, sc));
                    }
                }
            }
        }
        all.addAll(best.values());
        all.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
        return all;
    }

    static final class Scored {
        final String group;
        final String code;
        final double score;

        Scored(String group, String code, double score) {
            this.group = group;
            this.code = code;
            this.score = score;
        }
    }

    static final class WinStats {
        final int n;
        final int[][] posFreq = new int[3][10];
        final int[][] omit = new int[3][10];
        final int[][][] trans = new int[3][10][10];
        final Map<String, Integer> deltas = new HashMap<>();
        final int[] last = new int[3];
        final double sumMean;
        final double spanMean;
        final double sumStd;
        final int[] oddHist = new int[4];
        final int[] bigHist = new int[4];
        final int pairCnt;
        final int zu6Cnt;

        private WinStats(int n, double sumMean, double spanMean, double sumStd, int pairCnt, int zu6Cnt) {
            this.n = n;
            this.sumMean = sumMean;
            this.spanMean = spanMean;
            this.sumStd = sumStd;
            this.pairCnt = pairCnt;
            this.zu6Cnt = zu6Cnt;
        }

        static WinStats of(List<String> win) {
            int n = win.size();
            double sumAcc = 0, spanAcc = 0;
            int pair = 0, zu6 = 0;
            WinStats s = new WinStats(n, 0, 0, 0, 0, 0);
            for (int i = 0; i < 3; i++) {
                Arrays.fill(s.omit[i], n);
            }
            for (int t = 0; t < n; t++) {
                int[] d = digits(win.get(t));
                for (int i = 0; i < 3; i++) {
                    s.posFreq[i][d[i]]++;
                    s.omit[i][d[i]] = n - 1 - t;
                }
                int sum = d[0] + d[1] + d[2];
                int span = Math.max(d[0], Math.max(d[1], d[2])) - Math.min(d[0], Math.min(d[1], d[2]));
                sumAcc += sum;
                spanAcc += span;
                s.oddHist[(d[0] & 1) + (d[1] & 1) + (d[2] & 1)]++;
                s.bigHist[(d[0] >= 5 ? 1 : 0) + (d[1] >= 5 ? 1 : 0) + (d[2] >= 5 ? 1 : 0)]++;
                int u = uniqCount(d);
                if (u == 2) {
                    pair++;
                } else if (u == 3) {
                    zu6++;
                }
                if (t > 0) {
                    int[] p = digits(win.get(t - 1));
                    for (int i = 0; i < 3; i++) {
                        s.trans[i][p[i]][d[i]]++;
                    }
                    String dk = ((d[0] - p[0] + 10) % 10) + "," + ((d[1] - p[1] + 10) % 10) + ","
                            + ((d[2] - p[2] + 10) % 10);
                    s.deltas.merge(dk, 1, Integer::sum);
                }
            }
            double mean = sumAcc / n;
            double var = 0;
            for (String code : win) {
                int[] d = digits(code);
                double diff = d[0] + d[1] + d[2] - mean;
                var += diff * diff;
            }
            WinStats out = new WinStats(n, mean, spanAcc / n, Math.sqrt(var / n), pair, zu6);
            for (int i = 0; i < 3; i++) {
                System.arraycopy(s.posFreq[i], 0, out.posFreq[i], 0, 10);
                System.arraycopy(s.omit[i], 0, out.omit[i], 0, 10);
                out.last[i] = digits(win.get(n - 1))[i];
                for (int a = 0; a < 10; a++) {
                    System.arraycopy(s.trans[i][a], 0, out.trans[i][a], 0, 10);
                }
            }
            System.arraycopy(s.oddHist, 0, out.oddHist, 0, 4);
            System.arraycopy(s.bigHist, 0, out.bigHist, 0, 4);
            out.deltas.putAll(s.deltas);
            return out;
        }
    }

    private static int uniqCount(int[] d) {
        if (d[0] == d[1] && d[1] == d[2]) {
            return 1;
        }
        if (d[0] == d[1] || d[1] == d[2] || d[0] == d[2]) {
            return 2;
        }
        return 3;
    }

    static double uniqueGroupRatio(List<String> win) {
        Set<String> set = new HashSet<>();
        for (String c : win) {
            set.add(sortedKey(c));
        }
        return set.size() / (double) win.size();
    }

    private static String bestOrderOf(String group, WinStats stats) {
        String best = group;
        double bestSc = Double.NEGATIVE_INFINITY;
        ToDoubleFunction<int[]> fn = stratFns(stats).get(0);
        for (String p : permutationsOf(group)) {
            int[] abc = digits(p);
            double sc = fn.applyAsDouble(abc);
            if (sc > bestSc) {
                bestSc = sc;
                best = p;
            }
        }
        return best;
    }

    /** 单位置 ±1（模10）邻号列表 */
    static List<String> singlePosPlusMinus1(String code) {
        int[] d = digits(code);
        if (d == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(6);
        for (int pos = 0; pos < 3; pos++) {
            for (int delta : new int[]{1, 9}) {
                int[] n = {d[0], d[1], d[2]};
                n[pos] = (n[pos] + delta) % 10;
                if (n[0] == n[1] && n[1] == n[2]) {
                    continue;
                }
                out.add("" + n[0] + n[1] + n[2]);
            }
        }
        return out;
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

    static List<String> toCodes(List<Hm> history) {
        List<String> out = new ArrayList<>();
        if (history == null) {
            return out;
        }
        for (Hm hm : history) {
            if (hm != null) {
                out.add(pad3(hm.toString()));
            }
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

    static boolean isZxHit(String predCsv, String actual) {
        return isZxHit(splitCsv(predCsv), actual);
    }

    static boolean isGroupHit(String predCsv, String actual) {
        return isGroupHit(splitCsv(predCsv), actual);
    }

    static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String p : csv.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(pad3(t));
            }
        }
        return out;
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

    static int[] digits(String code) {
        String p = pad3(code);
        return new int[]{p.charAt(0) - '0', p.charAt(1) - '0', p.charAt(2) - '0'};
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static String summarizeHits(int zx, int group, int n) {
        boolean pass = zx >= ZX_TARGET && group >= GROUP_TARGET;
        return String.format(Locale.ROOT,
                "近%d期逐期评估：直选=%d/%d 组选=%d/%d → %s（目标直选≥%d 组选≥%d，池≤%d注）",
                n, zx, n, group, n, pass ? "达标" : "未达标",
                ZX_TARGET, GROUP_TARGET, MAX_TICKETS);
    }
}
