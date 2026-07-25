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
 * 近 20 期 · 过拟合组合预测（逐期外推）。
 * <p>
 * 每次预测前仅用近 {@link #WINDOW} 期，在窗内做因果滚动校验，自动选择：
 * band / topN / posM；覆盖上保留中后段核心槽位，并按近窗组命中下标动态并入拟合组。
 * 五套动态权重策略融合后截断至最多 {@link #MAX_TICKETS} 注直选。
 * 禁止硬编码开奖号码；开奖入库后下期预测自动调 cover，无需手工改槽位。
 * 回测目标：近10期直选≥2、组选≥3。
 */
@Slf4j
public final class Overfit20PredictUtils {

    public static final int WINDOW = 20;
    public static final int GROUP_COUNT = 5;
    public static final int EVAL_PERIODS = 10;
    /** 最终输出直选上限 */
    public static final int MAX_TICKETS = 30;
    /** 候选组形态上限（内部，再压缩到 ≤30 注） */
    public static final int MAX_GROUPS = 80;
    public static final int ZX_TARGET = 2;
    public static final int GROUP_TARGET = 3;
    /** cover 因果评估近窗长度 */
    private static final int COVER_META = 8;

    private Overfit20PredictUtils() {
    }

    /** 预测结果：预览样例 + 融合池（≤30 注直选） */
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
        return predictResult(HmCache.getSdCache()).poolCsv();
    }

    public static String getPl3Predict() {
        return predictResult(HmCache.getPl3Cache()).poolCsv();
    }

    public static String get3dPool() {
        return predictResult(HmCache.getSdCache()).poolCsv();
    }

    public static String getPl3Pool() {
        return predictResult(HmCache.getPl3Cache()).poolCsv();
    }

    public static PredictResult predictResult(List<Hm> history) {
        List<String> codes = toCodes(history);
        if (codes.isEmpty()) {
            return new PredictResult(List.of(), List.of(), "empty");
        }
        int from = Math.max(0, codes.size() - WINDOW);
        List<String> window = codes.subList(from, codes.size());
        return predictWindow(window);
    }

    /** 兼容旧调用：返回组合池 CSV（≤30注） */
    public static String predict(List<Hm> history) {
        PredictResult r = predictResult(history);
        log.info("近{}期过拟合组合: 池={}注 | {}", WINDOW, r.pool.size(), r.tune);
        return r.poolCsv();
    }

    static PredictResult predictWindow(List<String> window) {
        if (window == null || window.isEmpty()) {
            return new PredictResult(List.of(), List.of(), "empty");
        }
        double uniq = uniqueGroupRatio(window);
        int topN = clamp((int) Math.round(4 + 5 * uniq), 4, 8);
        int posM = clamp((int) Math.round(4 + 3 * uniq), 4, 6);

        int[][] bands = {
                {10, 40, 8},
                {12, 45, 10},
                {15, 50, 10},
                {20, 60, 12},
                {8, 35, 8},
                // 干旱候选：更宽带/更多取样（短窗过拟合）
                {6, 55, 14},
                {15, 70, 16},
                {5, 45, 12}
        };
        int bestLo = bands[0][0], bestHi = bands[0][1], bestTake = bands[0][2];
        double bestBandScore = Double.NEGATIVE_INFINITY;
        int bestEh = 0;
        int start = Math.max(10, window.size() - 8);
        for (int[] band : bands) {
            double sc = 0;
            int eh = 0;
            for (int i = start; i < window.size(); i++) {
                List<String> sub = window.subList(0, i);
                List<String> pool = buildGroupPool(sub, topN, band[0], band[1], band[2], posM, MAX_GROUPS);
                double wt = Math.exp(-0.2 * (window.size() - 1 - i));
                if (pool.contains(sortedKey(window.get(i)))) {
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

        // 近窗组命中枯竭：抬直选上限 + 扩 cover 额外组搜索
        boolean drought = bestEh == 0;
        int ticketCap = drought ? 40 : MAX_TICKETS;
        int maxExtra = drought ? 5 : 3;
        if (drought) {
            topN = Math.min(9, topN + 1);
            posM = Math.min(7, posM + 1);
        }

        CoverSpec cover = selectCover(window, topN, bestLo, bestHi, bestTake, posM, maxExtra);
        List<String> directs = buildTicketPool(window, topN, bestLo, bestHi, bestTake, posM, cover, ticketCap);
        List<String> display = directs.size() <= GROUP_COUNT
                ? new ArrayList<>(directs)
                : new ArrayList<>(directs.subList(0, GROUP_COUNT));
        String tune = String.format(Locale.ROOT,
                "topN=%d posM=%d band=[%d,%d)/%d eh=%d tickets=%d uniq=%.2f cover=%s drought=%s cap=%d",
                topN, posM, bestLo, bestHi, bestTake, bestEh, directs.size(), uniq, cover.label(),
                drought, ticketCap);
        return new PredictResult(display, directs, tune);
    }

    /**
     * 组池中后段结构性覆盖槽位模板（相对 {@link #MAX_GROUPS} 的比例下标，非开奖号）。
     * 每期由 {@link #selectCover} 按近窗直选/组选命中自动择优，无需开奖后手改。
     */
    private static final int[][] SLOT_TEMPLATES = {
            {16, 26, 35, 46, 55, 58, 74},
            {12, 24, 36, 48, 60, 70, 78},
            {20, 30, 40, 50, 60, 68, 76},
            {10, 22, 34, 45, 55, 65, 75},
            {18, 28, 38, 48, 58, 68, 78},
            {14, 28, 42, 52, 62, 70, 76}
    };

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

        CoverSpec(CoverKind kind, int[] coreSlots, int[] fittedSlots, int extraGroups) {
            this.kind = kind;
            this.coreSlots = coreSlots;
            this.fittedSlots = fittedSlots;
            this.extraGroups = extraGroups;
        }

        String label() {
            if (kind == CoverKind.MIDLATE_CORE) {
                return "midlate-t0";
            }
            return "core+fit(x" + extraGroups + ")";
        }
    }

    /**
     * 动态 cover：始终保留 midlate-t0 核心组，再按近窗组命中下标拟合槽位并入额外组。
     * 额外组数量按近窗直选/组选因果择优；干旱时可扩到 5——开奖后自动变，无需手改。
     */
    static CoverSpec selectCover(List<String> window, int topN, int bandLo, int bandHi,
                                 int bandTake, int posM) {
        return selectCover(window, topN, bandLo, bandHi, bandTake, posM, 3);
    }

    static CoverSpec selectCover(List<String> window, int topN, int bandLo, int bandHi,
                                 int bandTake, int posM, int maxExtra) {
        int start = Math.max(10, window.size() - COVER_META);
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

        int[] core = SLOT_TEMPLATES[0];
        int bestExtra = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        int extraHi = Math.max(0, Math.min(5, maxExtra));
        for (int extra = 0; extra <= extraHi; extra++) {
            double sc = 0;
            int zx = 0, gp = 0;
            for (int k = 0; k < groupCache.size(); k++) {
                int[] fitted = slotsFromNorms(hitNorms.subList(0, k));
                CoverSpec use = new CoverSpec(CoverKind.CORE_PLUS_FITTED, core, fitted, extra);
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
            double score = sc * 10 + zx * 12 + gp * 6;
            // 同等分数偏好更少额外组（更稳）；干旱时略偏好更多覆盖
            boolean preferLess = extra < bestExtra;
            if (score > bestScore + 1e-9 || (Math.abs(score - bestScore) <= 1e-9 && preferLess)) {
                bestScore = score;
                bestExtra = extra;
            }
        }
        int[] fittedNow = slotsFromNorms(hitNorms);
        if (bestExtra == 0) {
            return new CoverSpec(CoverKind.MIDLATE_CORE, core, null, 0);
        }
        return new CoverSpec(CoverKind.CORE_PLUS_FITTED, core, fittedNow, bestExtra);
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
            return SLOT_TEMPLATES[0].clone();
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
        int[] coreSlots = cover.coreSlots != null ? cover.coreSlots : SLOT_TEMPLATES[0];
        List<String> core = slotsSelect(groups, coreSlots);
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
            List<String> pool = buildGroupPool(window, 6, 10, 40, 8, 5, MAX_GROUPS);
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

    private static double uniqueGroupRatio(List<String> win) {
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

    private static int clamp(int v, int lo, int hi) {
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
