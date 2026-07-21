package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 纯规则预测。3D / 排列三分专项配置；注数最多 150。
 * 纠偏：近窗最频繁偏差 ± 因子；命中名次带优先；按开奖走势加权。
 * 目标：近100期直选≥20、组选≥50。
 */
@Slf4j
public final class RuleBasedPredictUtils {

    /** 彩种专项 */
    public enum GameKind {
        /** 福彩3D：组三偏多、名次偏中后段，优先组三展开 */
        SD_3D,
        /** 排列三：组六为主，优先组六散开+全排列 */
        PL3
    }

    /** 注数上限：优先 100，覆盖不足时扩到 150 */
    private static final int MAX_BET_LIMIT = 150;
    private static int MIN_BET = 60;
    private static int TARGET_BET = 150;
    private static final int TOP_N = 8;
    private static int GROUP_DIGIT_POOL = 10;
    private static int RANK_BAND_LO = 3;
    private static int RANK_BAND_HI = 8;
    private static int GROUP_UNIQUE_TARGET = 50;
    private static int PAIR_GROUP_QUOTA = 28;
    private static int PERM_EXPAND_GROUPS = 40;
    private static final int SHAPE_PROB_WINDOW = 20;
    /** 展开时是否优先组三（3D=true，排三=false） */
    private static boolean PREFER_PAIR_EXPAND = true;
    private static GameKind CURRENT_KIND = GameKind.SD_3D;
    /** 排三 fillWithTopGroupPerms 调参；≤0 表示用默认 72/30 */
    static int TUNE_SCATTER = 0;
    static int TUNE_EXPAND = 0;

    private RuleBasedPredictUtils() {
    }

    public static int maxBetLimit() {
        return MAX_BET_LIMIT;
    }

    public static String get3dPredict() {
        CURRENT_KIND = GameKind.SD_3D;
        return predict(HmCache.getSdCache(), HmCache.getSdCompareCache(), GameKind.SD_3D);
    }

    public static String getPl3Predict() {
        CURRENT_KIND = GameKind.PL3;
        return predict(HmCache.getPl3Cache(), HmCache.getPl3CompareCache(), GameKind.PL3);
    }

    public static String predict(List<Hm> history, List<HmCache.CompareDto> compares) {
        return predict(history, compares, GameKind.SD_3D);
    }

    public static String predict(List<Hm> history, List<HmCache.CompareDto> compares, GameKind kind) {
        if (history == null || history.size() < 20) {
            log.warn("历史数据不足，无法规则预测，size={}", history == null ? 0 : history.size());
            return null;
        }
        applyGameProfile(kind == null ? GameKind.SD_3D : kind);

        // 排三：历史对比纠偏会显著拉低近100期直选，评分与选号均不使用 compares
        // 连挂配额仍可用 compares（只调配额、不进位分/纠偏种子）
        List<HmCache.CompareDto> streakCompares = compares;
        if (CURRENT_KIND == GameKind.PL3) {
            compares = null;
        }

        int[][] digits = toDigitMatrix(history);
        ShapeProb shapeProb = shapeProb(digits);
        // 形态只调配比，不再跳过整期（否则直选上限被门控锁死）
        log.info("{} 走势：组三倾向={} 组六倾向={} (近窗组三{}% 组六{}%)",
                CURRENT_KIND, shapeProb.pairScore, shapeProb.zu6Score, shapeProb.pairPct, shapeProb.zu6Pct);

        logBiasDiagnostics(compares);
        RecentFeatureStats feat = RecentFeatureStats.of(digits);
        BiasSeedCorrector seeds = BiasSeedCorrector.of(compares);
        HitRankStats hitRank = HitRankStats.of(digits);
        // 彩种默认带与统计带取交集偏置
        RANK_BAND_LO = Math.max(hitRank.bandLo, RANK_BAND_LO);
        RANK_BAND_HI = Math.min(hitRank.bandHi, RANK_BAND_HI);
        if (RANK_BAND_LO > RANK_BAND_HI) {
            RANK_BAND_LO = hitRank.bandLo;
            RANK_BAND_HI = hitRank.bandHi;
        }
        log.info("偏差纠偏: {}", seeds.describe());
        log.info("{} {}", CURRENT_KIND, hitRank.describe());

        int[][] scores = new int[3][10];
        int[][] topPos = new int[3][TOP_N];
        for (int pos = 0; pos < 3; pos++) {
            scores[pos] = scoreAllDigits(digits, pos, compares, feat);
            topPos[pos] = buildBandAwareTop(scores[pos], TOP_N);
            topPos[pos] = applyBiasSeedToTop(topPos[pos], pos, seeds, scores[pos]);
            log.info("位置{} 名次池{}(命中带{}-{})={}", posName(pos), TOP_N, RANK_BAND_LO, RANK_BAND_HI,
                    Arrays.toString(topPos[pos]));
        }
        seeds.boostScores(scores);
        applyTrendBoost(digits, scores, shapeProb);

        // 按走势动态微调配额
        adjustQuotasByShape(shapeProb);
        // 命中率优先：根据近窗对比连挂，小幅抬升覆盖配额（不改打分主链路）
        applyMissStreakQuotaBoost(streakCompares);

        List<int[]> selected = selectGroupFirst(digits, scores, topPos, feat, seeds);
        if (selected == null || selected.size() < MIN_BET) {
            List<int[]> pool = buildLoosePool(topPos, scores, feat);
            selected = takeTopUnique(pool, TARGET_BET);
        }
        if (selected == null || selected.size() < MIN_BET) {
            log.error("规则预测无法凑齐{}注", MIN_BET);
            return null;
        }

        selected = applyBiasCorrectTickets(selected, scores, feat, seeds);
        if (CURRENT_KIND == GameKind.PL3) {
            if (TUNE_SCATTER <= 0) {
                TUNE_SCATTER = 70;
            }
            if (TUNE_EXPAND <= 0) {
                TUNE_EXPAND = 22;
            }
            selected = fillWithTopGroupPerms(selected, scores, feat);
            // 已有约70散组时仍继续用多余排列换更高覆盖的新组
            selected = ensureGroupCoverageKeepPerms(selected, scores, feat, 95, 2);
            TUNE_SCATTER = 0;
            TUNE_EXPAND = 0;
        }

        StringBuilder sb = new StringBuilder();
        int n = Math.min(TARGET_BET, selected.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            int[] t = selected.get(i);
            sb.append(t[0]).append(t[1]).append(t[2]);
        }
        String result = sb.toString();
        log.info("{} 规则预测结果(≤{}注): {}", CURRENT_KIND, TARGET_BET, result);
        return result;
    }

    /** 3D / 排三专项参数（面向近100期：直选≥20、组选≥50） */
    private static void applyGameProfile(GameKind kind) {
        CURRENT_KIND = kind;
        TARGET_BET = MAX_BET_LIMIT;
        MIN_BET = 80;
        GROUP_DIGIT_POOL = 10;
        if (kind == GameKind.SD_3D) {
            RANK_BAND_LO = 3;
            RANK_BAND_HI = 9;
            GROUP_UNIQUE_TARGET = 75;
            PAIR_GROUP_QUOTA = 28;
            PERM_EXPAND_GROUPS = 28;
            PREFER_PAIR_EXPAND = true;
        } else {
            RANK_BAND_LO = 2;
            RANK_BAND_HI = 9;
            GROUP_UNIQUE_TARGET = 90;
            PAIR_GROUP_QUOTA = 14;
            PERM_EXPAND_GROUPS = 20;
            PREFER_PAIR_EXPAND = false;
        }
    }

    /** 走势微调：组三热则加组三配额，组六热则保证展开名额 */
    private static void adjustQuotasByShape(ShapeProb shape) {
        if (CURRENT_KIND == GameKind.PL3) {
            if (shape.pairHigher) {
                PAIR_GROUP_QUOTA = Math.min(22, PAIR_GROUP_QUOTA + 4);
                PERM_EXPAND_GROUPS = Math.min(36, PERM_EXPAND_GROUPS + 6);
            } else {
                // 组六热：提高散组目标，但不要压到 55 以下（会伤组选覆盖）
                GROUP_UNIQUE_TARGET = Math.min(100, Math.max(GROUP_UNIQUE_TARGET, GROUP_UNIQUE_TARGET + 6));
                PERM_EXPAND_GROUPS = Math.min(28, Math.max(PERM_EXPAND_GROUPS, 18));
            }
            return;
        }
        if (shape.pairHigher) {
            PAIR_GROUP_QUOTA = Math.min(42, PAIR_GROUP_QUOTA + 6);
            PERM_EXPAND_GROUPS = Math.min(60, PERM_EXPAND_GROUPS + 6);
        } else {
            GROUP_UNIQUE_TARGET = Math.min(85, Math.max(GROUP_UNIQUE_TARGET, GROUP_UNIQUE_TARGET + 6));
            PAIR_GROUP_QUOTA = Math.max(16, PAIR_GROUP_QUOTA - 2);
            PERM_EXPAND_GROUPS = Math.min(55, PERM_EXPAND_GROUPS + 6);
        }
    }

    /**
     * 近窗连挂时抬升覆盖：组选连挂→散组/组三配额；直选连挂→排列展开。
     * 幅度保守，避免反向扰动原标定。
     */
    private static void applyMissStreakQuotaBoost(List<HmCache.CompareDto> compares) {
        if (compares == null || compares.isEmpty()) {
            return;
        }
        int missZx = 0;
        int missGroup = 0;
        boolean stopZx = false;
        boolean stopGroup = false;
        for (int i = compares.size() - 1; i >= 0 && i >= compares.size() - 15; i--) {
            HmCache.CompareDto dto = compares.get(i);
            if (dto == null || dto.getAiHm() == null || dto.getAiHm().isBlank()
                    || dto.getRealHm() == null || dto.getRealHm().length() != 3) {
                continue;
            }
            String actual = dto.getRealHm().trim();
            while (actual.length() < 3) {
                actual = "0" + actual;
            }
            char[] ak = actual.toCharArray();
            Arrays.sort(ak);
            String aKey = new String(ak);
            boolean zx = false;
            boolean group = false;
            for (String p : dto.getAiHm().split(",")) {
                String t = p.trim();
                while (t.length() < 3) {
                    t = "0" + t;
                }
                if (t.length() != 3) {
                    continue;
                }
                if (t.equals(actual)) {
                    zx = true;
                    group = true;
                    break;
                }
                char[] ck = t.toCharArray();
                Arrays.sort(ck);
                if (new String(ck).equals(aKey)) {
                    group = true;
                }
            }
            if (!stopZx) {
                if (zx) {
                    stopZx = true;
                } else {
                    missZx++;
                }
            }
            if (!stopGroup) {
                if (group) {
                    stopGroup = true;
                } else {
                    missGroup++;
                }
            }
            if (stopZx && stopGroup) {
                break;
            }
        }

        if (missGroup >= 2) {
            GROUP_UNIQUE_TARGET = Math.min(CURRENT_KIND == GameKind.PL3 ? 105 : 90,
                    GROUP_UNIQUE_TARGET + 6 + Math.min(6, missGroup));
            PAIR_GROUP_QUOTA = Math.min(CURRENT_KIND == GameKind.PL3 ? 24 : 45,
                    PAIR_GROUP_QUOTA + 3);
            log.info("组选连挂{}期 → 散组目标={} 组三配额={}", missGroup, GROUP_UNIQUE_TARGET, PAIR_GROUP_QUOTA);
        }
        if (missZx >= 3) {
            PERM_EXPAND_GROUPS = Math.min(CURRENT_KIND == GameKind.PL3 ? 40 : 65,
                    PERM_EXPAND_GROUPS + 4 + Math.min(8, missZx));
            log.info("直选连挂{}期 → 排列展开组数={}", missZx, PERM_EXPAND_GROUPS);
        }
    }

    /**
     * 开奖走势加权：和值通道、奇偶偏置、重号/邻号延续。
     */
    private static void applyTrendBoost(int[][] digits, int[][] scores, ShapeProb shape) {
        int n = digits.length;
        int from = Math.max(0, n - 12);
        int[] sumCnt = new int[28];
        int[] oddCnt = new int[4];
        for (int i = from; i < n; i++) {
            int a = digits[i][0], b = digits[i][1], c = digits[i][2];
            int sum = a + b + c;
            if (sum >= 0 && sum < 28) {
                sumCnt[sum]++;
            }
            oddCnt[(a % 2) + (b % 2) + (c % 2)]++;
        }
        // 近窗 Top 和值 ±1 通道内的数字抬分
        Integer[] sumOrder = new Integer[28];
        for (int i = 0; i < 28; i++) {
            sumOrder[i] = i;
        }
        Arrays.sort(sumOrder, (x, y) -> Integer.compare(sumCnt[y], sumCnt[x]));
        boolean[] hotSum = new boolean[28];
        for (int i = 0; i < 4; i++) {
            int s = sumOrder[i];
            if (sumCnt[s] <= 0) {
                break;
            }
            hotSum[s] = true;
            if (s > 0) {
                hotSum[s - 1] = true;
            }
            if (s < 27) {
                hotSum[s + 1] = true;
            }
        }
        int[] digitInHotSum = new int[10];
        for (int a = 0; a < 10; a++) {
            for (int b = 0; b < 10; b++) {
                for (int c = 0; c < 10; c++) {
                    if (hotSum[a + b + c]) {
                        digitInHotSum[a]++;
                        digitInHotSum[b]++;
                        digitInHotSum[c]++;
                    }
                }
            }
        }
        int boost = CURRENT_KIND == GameKind.PL3 ? 2 : 1;
        for (int pos = 0; pos < 3; pos++) {
            for (int d = 0; d < 10; d++) {
                scores[pos][d] += Math.min(8, digitInHotSum[d] / 30) * boost;
            }
        }
        int preferOddSlots = 0;
        for (int k = 1; k <= 3; k++) {
            if (oddCnt[k] > oddCnt[preferOddSlots]) {
                preferOddSlots = k;
            }
        }
        if (CURRENT_KIND == GameKind.PL3 && preferOddSlots >= 2) {
            for (int pos = 0; pos < 3; pos++) {
                for (int d = 1; d < 10; d += 2) {
                    scores[pos][d] += 3;
                }
            }
        }
        int[] last = digits[n - 1];
        for (int pos = 0; pos < 3; pos++) {
            scores[pos][last[pos]] += CURRENT_KIND == GameKind.SD_3D ? 2 : 1;
            scores[pos][(last[pos] + 1) % 10] += 4;
            scores[pos][(last[pos] + 9) % 10] += 4;
        }
        if (shape.pairHigher && CURRENT_KIND == GameKind.SD_3D) {
            for (int pos = 0; pos < 3; pos++) {
                scores[pos][last[pos]] += 3;
            }
        }
    }

    /**
     * 排三专用：每组至少保留 keepPerms 注，多出来的换成新散组。
     */
    private static List<int[]> ensureGroupCoverageKeepPerms(List<int[]> picked, int[][] scores,
                                                            RecentFeatureStats feat,
                                                            int needGroups, int keepPerms) {
        if (picked == null || picked.isEmpty()) {
            return picked;
        }
        keepPerms = Math.max(1, keepPerms);
        java.util.Map<String, List<Integer>> byGroup = new java.util.LinkedHashMap<>();
        for (int i = 0; i < picked.size(); i++) {
            int[] t = picked.get(i);
            byGroup.computeIfAbsent(groupKey(t[0], t[1], t[2]), k -> new ArrayList<>()).add(i);
        }
        if (byGroup.size() >= needGroups) {
            return picked.size() > TARGET_BET ? new ArrayList<>(picked.subList(0, TARGET_BET)) : picked;
        }

        List<Integer> replaceable = new ArrayList<>();
        for (List<Integer> idxs : byGroup.values()) {
            // 按票分排序，保留最高的 keepPerms 张
            List<Integer> sorted = new ArrayList<>(idxs);
            sorted.sort((i, j) -> {
                int[] a = picked.get(i), b = picked.get(j);
                return Integer.compare(ticketScore(b, scores, feat), ticketScore(a, scores, feat));
            });
            for (int i = keepPerms; i < sorted.size(); i++) {
                replaceable.add(sorted.get(i));
            }
        }
        // 低分优先替换
        replaceable.sort((i, j) -> Integer.compare(
                ticketScore(picked.get(i), scores, feat),
                ticketScore(picked.get(j), scores, feat)));

        List<int[]> pool = new ArrayList<>();
        for (int a = 0; a < 10; a++) {
            for (int b = 0; b < 10; b++) {
                for (int c = 0; c < 10; c++) {
                    if (!looseMorphOk(new int[]{a, b, c})) {
                        continue;
                    }
                    String gk = groupKey(a, b, c);
                    if (byGroup.containsKey(gk)) {
                        continue;
                    }
                    pool.add(new int[]{a, b, c, ticketScore(new int[]{a, b, c}, scores, feat)});
                }
            }
        }
        pool.sort((x, y) -> Integer.compare(y[3], x[3]));

        List<int[]> out = new ArrayList<>(picked.size());
        for (int[] t : picked) {
            out.add(new int[]{t[0], t[1], t[2]});
        }
        Set<String> usedTicket = new HashSet<>();
        for (int[] t : out) {
            usedTicket.add("" + t[0] + t[1] + t[2]);
        }
        Set<String> groups = new LinkedHashSet<>(byGroup.keySet());
        int poolIdx = 0;
        for (int ri : replaceable) {
            if (groups.size() >= needGroups || poolIdx >= pool.size()) {
                break;
            }
            while (poolIdx < pool.size()) {
                int[] c = pool.get(poolIdx++);
                String gk = groupKey(c[0], c[1], c[2]);
                if (groups.contains(gk)) {
                    continue;
                }
                String k = "" + c[0] + c[1] + c[2];
                if (usedTicket.contains(k)) {
                    continue;
                }
                int[] old = out.get(ri);
                usedTicket.remove("" + old[0] + old[1] + old[2]);
                out.set(ri, new int[]{c[0], c[1], c[2]});
                usedTicket.add(k);
                groups.add(gk);
                break;
            }
        }
        log.info("排三保{}排列补散组后不同组={}", keepPerms, groups.size());
        return out.size() > TARGET_BET ? new ArrayList<>(out.subList(0, TARGET_BET)) : out;
    }

    /**
     * 优先保留不同组≥needGroups：把「同组多排列」的尾票换成新散组。
     */
    private static List<int[]> ensureGroupCoverage(List<int[]> picked, int[][] scores, RecentFeatureStats feat) {
        return ensureGroupCoverage(picked, scores, feat, CURRENT_KIND == GameKind.PL3 ? 52 : 78);
    }

    private static List<int[]> ensureGroupCoverage(List<int[]> picked, int[][] scores, RecentFeatureStats feat,
                                                   int needGroups) {
        if (picked == null || picked.isEmpty()) {
            return picked;
        }
        java.util.Map<String, List<Integer>> byGroup = new java.util.LinkedHashMap<>();
        for (int i = 0; i < picked.size(); i++) {
            int[] t = picked.get(i);
            byGroup.computeIfAbsent(groupKey(t[0], t[1], t[2]), k -> new ArrayList<>()).add(i);
        }
        if (byGroup.size() >= needGroups) {
            return picked.size() > TARGET_BET ? new ArrayList<>(picked.subList(0, TARGET_BET)) : picked;
        }

        // 可替换：每组除第一张外的票（牺牲排列、保散组）
        List<Integer> replaceable = new ArrayList<>();
        for (List<Integer> idxs : byGroup.values()) {
            for (int i = 1; i < idxs.size(); i++) {
                replaceable.add(idxs.get(i));
            }
        }
        replaceable.sort(Integer::compareTo);
        // 从后往前换
        java.util.Collections.reverse(replaceable);

        List<int[]> pool = new ArrayList<>();
        for (int a = 0; a < 10; a++) {
            for (int b = 0; b < 10; b++) {
                for (int c = 0; c < 10; c++) {
                    if (!looseMorphOk(new int[]{a, b, c})) {
                        continue;
                    }
                    String gk = groupKey(a, b, c);
                    if (byGroup.containsKey(gk)) {
                        continue;
                    }
                    pool.add(new int[]{a, b, c, ticketScore(new int[]{a, b, c}, scores, feat)});
                }
            }
        }
        pool.sort((x, y) -> Integer.compare(y[3], x[3]));

        List<int[]> out = new ArrayList<>(picked.size());
        for (int[] t : picked) {
            out.add(new int[]{t[0], t[1], t[2]});
        }
        Set<String> usedTicket = new HashSet<>();
        for (int[] t : out) {
            usedTicket.add("" + t[0] + t[1] + t[2]);
        }
        Set<String> groups = new LinkedHashSet<>(byGroup.keySet());
        int poolIdx = 0;
        for (int ri : replaceable) {
            if (groups.size() >= needGroups || poolIdx >= pool.size()) {
                break;
            }
            while (poolIdx < pool.size()) {
                int[] c = pool.get(poolIdx++);
                String gk = groupKey(c[0], c[1], c[2]);
                if (groups.contains(gk)) {
                    continue;
                }
                String k = "" + c[0] + c[1] + c[2];
                if (usedTicket.contains(k)) {
                    continue;
                }
                int[] old = out.get(ri);
                usedTicket.remove("" + old[0] + old[1] + old[2]);
                out.set(ri, new int[]{c[0], c[1], c[2]});
                usedTicket.add(k);
                groups.add(gk);
                break;
            }
        }
        log.info("补散组后不同组={} (目标≥{})", groups.size(), needGroups);
        return out.size() > TARGET_BET ? new ArrayList<>(out.subList(0, TARGET_BET)) : out;
    }

    /**
     * 散组优先：先铺足够不同组，再给头部组补全排列。
     */
    private static List<int[]> fillWithTopGroupPerms(List<int[]> picked, int[][] scores, RecentFeatureStats feat) {
        if (picked == null || picked.isEmpty()) {
            return picked;
        }
        java.util.Map<String, int[]> best = new java.util.LinkedHashMap<>();
        for (int[] t : picked) {
            String gk = groupKey(t[0], t[1], t[2]);
            int sc = ticketScore(t, scores, feat);
            int[] old = best.get(gk);
            if (old == null || sc > old[3]) {
                best.put(gk, new int[]{t[0], t[1], t[2], sc});
            }
        }
        List<int[]> groups = new ArrayList<>(best.values());
        groups.sort((a, b) -> Integer.compare(b[3], a[3]));

        // 先铺散组（组选），再给头部组加排列（直选）
        List<int[]> out = new ArrayList<>(TARGET_BET);
        Set<String> used = new HashSet<>();
        Set<String> usedGroup = new HashSet<>();

        int scatterTarget = Math.min(TUNE_SCATTER > 0 ? TUNE_SCATTER : 72, groups.size());
        for (int i = 0; i < scatterTarget && out.size() < TARGET_BET; i++) {
            int[] g = groups.get(i);
            String gk = groupKey(g[0], g[1], g[2]);
            if (!usedGroup.add(gk)) {
                continue;
            }
            int[] bestArr = bestArrangementForScores(g[0], g[1], g[2], scores);
            String k = "" + bestArr[0] + bestArr[1] + bestArr[2];
            if (used.add(k)) {
                out.add(bestArr);
            }
        }

        // TUNE_EXPAND==0：剩余名额按「全局票分」补排列；否则按头部组全排列
        if (TUNE_EXPAND == 0) {
            List<int[]> permPool = new ArrayList<>();
            int scan = Math.min(groups.size(), Math.max(scatterTarget, 40));
            for (int i = 0; i < scan; i++) {
                int[] g = groups.get(i);
                for (int[] p : uniquePerms(g[0], g[1], g[2])) {
                    String k = "" + p[0] + p[1] + p[2];
                    if (used.contains(k)) {
                        continue;
                    }
                    permPool.add(new int[]{p[0], p[1], p[2],
                            scores[0][p[0]] + scores[1][p[1]] + scores[2][p[2]]});
                }
            }
            permPool.sort((a, b) -> Integer.compare(b[3], a[3]));
            for (int[] t : permPool) {
                if (out.size() >= TARGET_BET) {
                    break;
                }
                String k = "" + t[0] + t[1] + t[2];
                if (used.add(k)) {
                    out.add(new int[]{t[0], t[1], t[2]});
                }
            }
        } else {
            int expandGroups = Math.min(TUNE_EXPAND > 0 ? TUNE_EXPAND : 30, groups.size());
            for (int i = 0; i < expandGroups && out.size() < TARGET_BET; i++) {
                int[] g = groups.get(i);
                for (int[] p : uniquePerms(g[0], g[1], g[2])) {
                    if (out.size() >= TARGET_BET) {
                        break;
                    }
                    String k = "" + p[0] + p[1] + p[2];
                    if (used.add(k)) {
                        out.add(p);
                    }
                }
            }
        }

        for (int i = scatterTarget; i < groups.size() && out.size() < TARGET_BET; i++) {
            int[] g = groups.get(i);
            String gk = groupKey(g[0], g[1], g[2]);
            if (!usedGroup.add(gk)) {
                continue;
            }
            int[] bestArr = bestArrangementForScores(g[0], g[1], g[2], scores);
            String k = "" + bestArr[0] + bestArr[1] + bestArr[2];
            if (used.add(k)) {
                out.add(bestArr);
            }
        }

        if (out.size() < TARGET_BET) {
            List<int[]> extras = new ArrayList<>();
            for (int[] t : picked) {
                String k = "" + t[0] + t[1] + t[2];
                if (!used.contains(k)) {
                    extras.add(new int[]{t[0], t[1], t[2], ticketScore(t, scores, feat)});
                }
            }
            extras.sort((a, b) -> Integer.compare(b[3], a[3]));
            for (int[] t : extras) {
                if (out.size() >= TARGET_BET) {
                    break;
                }
                String k = "" + t[0] + t[1] + t[2];
                if (used.add(k)) {
                    out.add(new int[]{t[0], t[1], t[2]});
                }
            }
        }
        log.info("排三散组优先: 最终={}注 不同组={}", out.size(), usedGroup.size());
        return out;
    }

    private static int[] bestArrangementForScores(int a, int b, int c, int[][] scores) {
        int[] best = {a, b, c};
        int bestSc = scores[0][a] + scores[1][b] + scores[2][c];
        for (int[] p : uniquePerms(a, b, c)) {
            int sc = scores[0][p[0]] + scores[1][p[1]] + scores[2][p[2]];
            if (sc > bestSc) {
                bestSc = sc;
                best = p;
            }
        }
        return best;
    }

    /**
     * 当期组三/组六「预测倾向」：不是无条件先验（组六先验更高），
     * 而是近窗频率偏离 + 连出切换 + 转移样本形态，判断本期是否更偏组三。
     */
    private static ShapeProb shapeProb(int[][] digits) {
        int n = digits.length;
        int from = Math.max(0, n - SHAPE_PROB_WINDOW);
        int pair = 0, zu6 = 0;
        for (int i = from; i < n; i++) {
            int a = digits[i][0], b = digits[i][1], c = digits[i][2];
            if (a == b && b == c) {
                continue;
            }
            if (isPairSet(a, b, c)) {
                pair++;
            } else {
                zu6++;
            }
        }
        int tot = pair + zu6;
        int pairPct = tot == 0 ? 0 : pair * 100 / tot;
        int zu6Pct = tot == 0 ? 0 : zu6 * 100 / tot;

        // 长期基线约 组三27% / 组六72%，用「相对基线的超额」衡量当期倾向
        int pairScore = (pairPct - 27) * 3;
        int zu6Score = (zu6Pct - 72) * 3;

        // 近5期形态
        int from5 = Math.max(0, n - 5);
        int pair5 = 0, zu65 = 0;
        for (int i = from5; i < n; i++) {
            int a = digits[i][0], b = digits[i][1], c = digits[i][2];
            if (a == b && b == c) {
                continue;
            }
            if (isPairSet(a, b, c)) {
                pair5++;
            } else {
                zu65++;
            }
        }
        pairScore += pair5 * 18;
        zu6Score += zu65 * 12;

        // 上期形态：组六后更易给组三加分（切换），组三连出则组三再加
        int[] last = digits[n - 1];
        boolean lastPair = isPairSet(last[0], last[1], last[2]);
        boolean lastBaozi = last[0] == last[1] && last[1] == last[2];
        if (!lastBaozi) {
            if (lastPair) {
                pairScore += 15; // 组三惯性
                zu6Score += 8;
            } else {
                pairScore += 22; // 组六后回补组三
                zu6Score += 5;
            }
        }

        // 近10期组三遗漏越长，组三倾向越高
        int pairOmit = 0;
        for (int i = n - 1; i >= 0 && pairOmit < 15; i--) {
            int a = digits[i][0], b = digits[i][1], c = digits[i][2];
            if (isPairSet(a, b, c)) {
                break;
            }
            pairOmit++;
        }
        if (pairOmit >= 3) {
            pairScore += Math.min(40, pairOmit * 6);
        }

        return new ShapeProb(pairScore > zu6Score, pairPct, zu6Pct, pairScore, zu6Score);
    }

    private static final class ShapeProb {
        final boolean pairHigher;
        final int pairPct;
        final int zu6Pct;
        final int pairScore;
        final int zu6Score;

        ShapeProb(boolean pairHigher, int pairPct, int zu6Pct, int pairScore, int zu6Score) {
            this.pairHigher = pairHigher;
            this.pairPct = pairPct;
            this.zu6Pct = zu6Pct;
            this.pairScore = pairScore;
            this.zu6Score = zu6Score;
        }
    }

    /**
     * 对已选高分票做 ± 主纠偏因子展开，按位分插入，截断到 TARGET_BET。
     */
    private static List<int[]> applyBiasCorrectTickets(List<int[]> picked, int[][] scores,
                                                       RecentFeatureStats feat, BiasSeedCorrector seeds) {
        if (picked == null || picked.isEmpty()) {
            return picked;
        }
        java.util.Map<String, int[]> map = new java.util.LinkedHashMap<>();
        for (int[] t : picked) {
            int sc = posStraightScore(t, scores, feat);
            map.put("" + t[0] + t[1] + t[2], new int[]{t[0], t[1], t[2], sc});
        }
        int n = Math.min(picked.size(), 30);
        for (int i = 0; i < n; i++) {
            int[] t = picked.get(i);
            int[] add = {seeds.shiftAdd(t[0], 0), seeds.shiftAdd(t[1], 1), seeds.shiftAdd(t[2], 2)};
            int[] sub = {seeds.shiftSub(t[0], 0), seeds.shiftSub(t[1], 1), seeds.shiftSub(t[2], 2)};
            offerScored(map, add, posStraightScore(add, scores, feat) + 15);
            offerScored(map, sub, posStraightScore(sub, scores, feat) + 10);
            // 单位纠偏
            for (int pos = 0; pos < 3; pos++) {
                int[] one = {t[0], t[1], t[2]};
                one[pos] = seeds.shiftAdd(t[pos], pos);
                offerScored(map, one, posStraightScore(one, scores, feat) + 8);
                int[] oneSub = {t[0], t[1], t[2]};
                oneSub[pos] = seeds.shiftSub(t[pos], pos);
                offerScored(map, oneSub, posStraightScore(oneSub, scores, feat) + 5);
            }
        }
        List<int[]> ranked = new ArrayList<>(map.values());
        ranked.sort((a, b) -> Integer.compare(b[3], a[3]));
        List<int[]> out = new ArrayList<>(TARGET_BET);
        for (int[] s : ranked) {
            if (out.size() >= TARGET_BET) {
                break;
            }
            out.add(new int[]{s[0], s[1], s[2]});
        }
        return out;
    }

    private static void offerScored(java.util.Map<String, int[]> map, int[] t, int score) {
        if (t == null || t.length < 3) {
            return;
        }
        if (t[0] == t[1] && t[1] == t[2]) {
            return; // 禁豹子
        }
        String key = "" + t[0] + t[1] + t[2];
        int[] old = map.get(key);
        if (old == null || score > old[3]) {
            map.put(key, new int[]{t[0], t[1], t[2], score});
        }
    }

    // ========================= 直选优先选注（≤200注） =========================

    /**
     * 最多 200 注，直选优先：高分组合全排列展开为主，散组为辅。
     */
    private static List<int[]> selectGroupFirst(int[][] digits, int[][] scores, int[][] topPos,
                                                RecentFeatureStats feat, BiasSeedCorrector seeds) {
        int[] global = globalDigitScore(scores, feat, digits);
        int[] hotPool = buildHotPool(digits, global, feat, GROUP_DIGIT_POOL);
        log.info("直选覆盖热核{}码={}", GROUP_DIGIT_POOL, Arrays.toString(hotPool));

        int[] groupHitProxy = new int[1000];
        int from20 = Math.max(0, digits.length - 20);
        for (int i = from20; i < digits.length; i++) {
            groupHitProxy[sortedKeyCode(digits[i][0], digits[i][1], digits[i][2])]++;
        }

        // ticketKey -> [a,b,c,score]
        java.util.Map<String, int[]> ticketMap = new java.util.LinkedHashMap<>();
        String lastGk = groupKey(feat.last[0], feat.last[1], feat.last[2]);

        // —— 1) 转移：全部 nextFull 全排列 + 宽转移样本 ——
        for (int i = 0; i < feat.nextFullCount; i++) {
            int[] c = feat.nextFullCodes[i];
            if (groupKey(c[0], c[1], c[2]).equals(lastGk)) {
                continue;
            }
            int wBoost = 80 + Math.min(40, c[3] / 2);
            if (CURRENT_KIND == GameKind.PL3) {
                wBoost += 50; // 排三更信转移样本
            }
            for (int[] p : uniquePerms(c[0], c[1], c[2])) {
                offerTicket(ticketMap, p, ticketScore(p, scores, feat) + wBoost);
            }
            offerTicket(ticketMap, c, ticketScore(c, scores, feat) + wBoost + 30);
        }
        for (int[] s : broadTransferCandidates(digits, feat)) {
            if (groupKey(s[0], s[1], s[2]).equals(lastGk)) {
                continue;
            }
            for (int[] p : uniquePerms(s[0], s[1], s[2])) {
                int boost = CURRENT_KIND == GameKind.PL3 ? 70 : 45;
                offerTicket(ticketMap, p, ticketScore(p, scores, feat) + boost);
            }
        }

        // —— 2) 邻变 ±1/±2：本体 + 全排列 ——
        for (int pos = 0; pos < 3; pos++) {
            for (int d = -2; d <= 2; d++) {
                if (d == 0) {
                    continue;
                }
                int[] m = {feat.last[0], feat.last[1], feat.last[2]};
                m[pos] = (m[pos] + d + 10) % 10;
                int boost = Math.abs(d) == 1 ? 35 : 18;
                for (int[] p : uniquePerms(m[0], m[1], m[2])) {
                    offerTicket(ticketMap, p, ticketScore(p, scores, feat) + boost);
                }
            }
        }

        // —— 3) 共性带 3~8 全笛卡尔 ——
        int[][] bandPos = new int[3][];
        for (int p = 0; p < 3; p++) {
            bandPos[p] = digitsInRankBand(scores[p], RANK_BAND_LO, RANK_BAND_HI);
        }
        for (int a : bandPos[0]) {
            for (int b : bandPos[1]) {
                for (int c : bandPos[2]) {
                    int[] t = {a, b, c};
                    offerTicket(ticketMap, t, ticketScore(t, scores, feat) + 25);
                }
            }
        }

        // —— 4) 软带 2~9 笛卡尔 ——
        int[][] softPos = new int[3][];
        for (int p = 0; p < 3; p++) {
            softPos[p] = digitsInRankBand(scores[p], 2, 9);
        }
        for (int a : softPos[0]) {
            for (int b : softPos[1]) {
                for (int c : softPos[2]) {
                    int[] t = {a, b, c};
                    offerTicket(ticketMap, t, ticketScore(t, scores, feat) + 8);
                }
            }
        }

        // —— 5) 和值通道：Top 组选展开全排列 ——
        for (int[] s : sumChannelCandidates(global, feat, groupHitProxy, digits, 90)) {
            int gBoost = isPairSet(s[0], s[1], s[2]) ? 28 : 15;
            for (int[] p : uniquePerms(s[0], s[1], s[2])) {
                offerTicket(ticketMap, p, ticketScore(p, scores, feat) + gBoost);
            }
        }

        // —— 6) 热核组六 + 组三全展开（组三额外加权，回测未中以组三居多）——
        for (int i = 0; i < hotPool.length; i++) {
            for (int j = i + 1; j < hotPool.length; j++) {
                for (int k = j + 1; k < hotPool.length; k++) {
                    for (int[] p : uniquePerms(hotPool[i], hotPool[j], hotPool[k])) {
                        offerTicket(ticketMap, p, ticketScore(p, scores, feat) + 5);
                    }
                }
            }
        }
        for (int i = 0; i < hotPool.length; i++) {
            for (int j = 0; j < hotPool.length; j++) {
                if (i == j) {
                    continue;
                }
                for (int[] p : uniquePerms(hotPool[i], hotPool[i], hotPool[j])) {
                    offerTicket(ticketMap, p, ticketScore(p, scores, feat) + 22);
                }
            }
        }
        // 软带交叉组三：补「码在池但组三集合未入选」
        LinkedHashSet<Integer> pairDigits = new LinkedHashSet<>();
        for (int d : hotPool) {
            pairDigits.add(d);
        }
        for (int p = 0; p < 3; p++) {
            for (int d : softPos[p]) {
                pairDigits.add(d);
            }
        }
        Integer[] pairArr = pairDigits.toArray(new Integer[0]);
        for (int i = 0; i < pairArr.length; i++) {
            for (int j = 0; j < pairArr.length; j++) {
                if (i == j) {
                    continue;
                }
                int rep = pairArr[i], single = pairArr[j];
                for (int[] p : uniquePerms(rep, rep, single)) {
                    offerTicket(ticketMap, p, ticketScore(p, scores, feat) + 18);
                }
            }
        }

        // —— 7) 位池 Top 笛卡尔 + 偏差种子变体 ——
        for (int[] t : buildLoosePool(topPos, scores, feat)) {
            offerTicket(ticketMap, t, t[3] + 10);
        }
        List<int[]> base = new ArrayList<>(ticketMap.values());
        base.sort((x, y) -> Integer.compare(y[3], x[3]));
        int seedN = Math.min(50, base.size());
        for (int i = 0; i < seedN; i++) {
            int[] s = base.get(i);
            int[] add = {seeds.shiftAdd(s[0], 0), seeds.shiftAdd(s[1], 1), seeds.shiftAdd(s[2], 2)};
            int[] sub = {seeds.shiftSub(s[0], 0), seeds.shiftSub(s[1], 1), seeds.shiftSub(s[2], 2)};
            offerTicket(ticketMap, add, ticketScore(add, scores, feat) + 12);
            offerTicket(ticketMap, sub, ticketScore(sub, scores, feat) + 8);
        }

        // —— 截取：组选广散（含组三配额）→ 高分组全排列 → 中段/按分补满 ——
        List<int[]> ranked = new ArrayList<>(ticketMap.values());
        ranked.sort((x, y) -> Integer.compare(y[3], x[3]));

        // 每组保留最高分票，便于按组选打分排序
        java.util.Map<String, int[]> bestByGroup = new java.util.LinkedHashMap<>();
        for (int[] s : ranked) {
            String gk = groupKey(s[0], s[1], s[2]);
            int[] old = bestByGroup.get(gk);
            if (old == null || s[3] > old[3]) {
                bestByGroup.put(gk, s);
            }
        }
        List<int[]> groupRanked = new ArrayList<>(bestByGroup.values());
        groupRanked.sort((x, y) -> Integer.compare(y[3], x[3]));

        List<int[]> picked = new ArrayList<>();
        Set<String> usedTicket = new HashSet<>();
        Set<String> usedGroup = new HashSet<>();
        List<String> expandOrder = new ArrayList<>();

        // 排三：转移样本只先占「每组1注」，全排列放到后面统一展开，避免挤掉散组
        if (CURRENT_KIND == GameKind.PL3) {
            List<int[]> transferGroups = new ArrayList<>();
            for (int i = 0; i < feat.nextFullCount; i++) {
                transferGroups.add(feat.nextFullCodes[i]);
            }
            transferGroups.addAll(broadTransferCandidates(digits, feat));
            for (int[] c : transferGroups) {
                if (usedGroup.size() >= GROUP_UNIQUE_TARGET) {
                    break;
                }
                String gk = groupKey(c[0], c[1], c[2]);
                if (groupKey(feat.last[0], feat.last[1], feat.last[2]).equals(gk)) {
                    continue;
                }
                if (usedGroup.contains(gk)) {
                    continue;
                }
                int[] best = bestArrangementForScores(c[0], c[1], c[2], scores);
                String tKey = "" + best[0] + best[1] + best[2];
                if (usedTicket.add(tKey)) {
                    usedGroup.add(gk);
                    expandOrder.add(gk);
                    bestByGroup.putIfAbsent(gk, new int[]{best[0], best[1], best[2], ticketScore(best, scores, feat) + 100});
                    picked.add(best);
                }
            }
        }

        // A1) 组三专项配额（按不同组计数，不被转移全排列挤占）
        int pairAdded = 0;
        for (int[] s : groupRanked) {
            if (pairAdded >= PAIR_GROUP_QUOTA || usedGroup.size() >= GROUP_UNIQUE_TARGET) {
                break;
            }
            if (!isPairSet(s[0], s[1], s[2])) {
                continue;
            }
            String gk = groupKey(s[0], s[1], s[2]);
            if (usedGroup.contains(gk)) {
                continue;
            }
            pickOne(picked, usedTicket, usedGroup, expandOrder, s);
            pairAdded++;
        }

        // A2) 其余组选散开：尽量铺满 GROUP_UNIQUE_TARGET 个不同组
        for (int[] s : groupRanked) {
            if (usedGroup.size() >= GROUP_UNIQUE_TARGET) {
                break;
            }
            String gk = groupKey(s[0], s[1], s[2]);
            if (usedGroup.contains(gk)) {
                continue;
            }
            // 排三：组六优先入散组列表（已按分排序，这里跳过过多组三）
            if (CURRENT_KIND == GameKind.PL3 && isPairSet(s[0], s[1], s[2])
                    && pairAdded >= PAIR_GROUP_QUOTA) {
                continue;
            }
            pickOne(picked, usedTicket, usedGroup, expandOrder, s);
        }

        // B) 全排列展开：3D优先组三，排三优先组六（把组中直不中转直选）
        List<String> expandPairFirst = new ArrayList<>();
        List<String> expandZu6 = new ArrayList<>();
        for (String gk : expandOrder) {
            int[] best = bestByGroup.get(gk);
            if (best != null && isPairSet(best[0], best[1], best[2])) {
                expandPairFirst.add(gk);
            } else {
                expandZu6.add(gk);
            }
        }
        List<String> expandSeq = new ArrayList<>();
        if (PREFER_PAIR_EXPAND) {
            expandSeq.addAll(expandPairFirst);
            expandSeq.addAll(expandZu6);
        } else {
            expandSeq.addAll(expandZu6);
            expandSeq.addAll(expandPairFirst);
        }
        int expandN = Math.min(PERM_EXPAND_GROUPS, expandSeq.size());
        for (int i = 0; i < expandN && picked.size() < TARGET_BET; i++) {
            String gk = expandSeq.get(i);
            int[] best = bestByGroup.get(gk);
            if (best == null) {
                continue;
            }
            for (int[] p : permsByPosScore(best[0], best[1], best[2], scores, feat)) {
                if (picked.size() >= TARGET_BET) {
                    break;
                }
                String tKey = "" + p[0] + p[1] + p[2];
                if (usedTicket.contains(tKey)) {
                    continue;
                }
                usedTicket.add(tKey);
                picked.add(new int[]{p[0], p[1], p[2]});
            }
        }

        // C) 中段名次票
        List<int[]> midRanked = new ArrayList<>();
        for (int[] s : ranked) {
            int mid = midRankPreferScore(scores, s[0], s[1], s[2]);
            if (mid >= 200) {
                int bonus = 0;
                if (CURRENT_KIND == GameKind.SD_3D && isPairSet(s[0], s[1], s[2])) {
                    bonus = 40;
                } else if (CURRENT_KIND == GameKind.PL3 && !isPairSet(s[0], s[1], s[2])) {
                    bonus = 35;
                }
                midRanked.add(new int[]{s[0], s[1], s[2], mid + bonus});
            }
        }
        midRanked.sort((x, y) -> Integer.compare(y[3], x[3]));
        int midAdded = 0;
        int midQuota = Math.min(40, TARGET_BET / 3);
        for (int[] s : midRanked) {
            if (midAdded >= midQuota || picked.size() >= TARGET_BET) {
                break;
            }
            String tKey = "" + s[0] + s[1] + s[2];
            if (usedTicket.contains(tKey)) {
                continue;
            }
            usedTicket.add(tKey);
            usedGroup.add(groupKey(s[0], s[1], s[2]));
            picked.add(new int[]{s[0], s[1], s[2]});
            midAdded++;
        }

        // D) 按位分补满：组三优先，再组六
        List<int[]> byPos = new ArrayList<>();
        for (int[] s : ranked) {
            int sc = posStraightScore(new int[]{s[0], s[1], s[2]}, scores, feat);
            if (isPairSet(s[0], s[1], s[2])) {
                sc += 80;
            }
            byPos.add(new int[]{s[0], s[1], s[2], sc});
        }
        byPos.sort((x, y) -> Integer.compare(y[3], x[3]));
        for (int[] s : byPos) {
            if (picked.size() >= TARGET_BET) {
                break;
            }
            String tKey = "" + s[0] + s[1] + s[2];
            if (usedTicket.contains(tKey)) {
                continue;
            }
            usedTicket.add(tKey);
            usedGroup.add(groupKey(s[0], s[1], s[2]));
            picked.add(new int[]{s[0], s[1], s[2]});
        }

        if (picked.size() < MIN_BET) {
            List<int[]> fallback = takeTopUnique(buildLoosePool(topPos, scores, feat), TARGET_BET);
            log.warn("直选覆盖不足，回退宽松池 size={}", fallback.size());
            return fallback;
        }

        if (CURRENT_KIND == GameKind.SD_3D) {
            picked = correctByPosRearrange(picked, scores, feat, seeds);
            picked = ensureGroupCoverage(picked, scores, feat);
        }
        // PL3 的重排/补散组在 predict() 末尾做，便于与 3D 分开调参
        log.info("{} 选号完成: {}注 不同组选={} 组三配额={}",
                CURRENT_KIND, picked.size(), countUniqueGroups(picked), pairAdded);
        return picked.size() > TARGET_BET ? new ArrayList<>(picked.subList(0, TARGET_BET)) : picked;
    }

    /**
     * 二次校正（组中后按位分重排）：
     * 对展开优先的「单票组」，补入位分次优排列；名额来自尾部低优先级单票组
     * （牺牲边缘组选覆盖，换高优先级组的直选排列，不改动已有多票组与头部原票）。
     */
    private static List<int[]> correctByPosRearrange(List<int[]> picked, int[][] scores,
                                                     RecentFeatureStats feat, BiasSeedCorrector seeds) {
        if (picked == null || picked.isEmpty()) {
            return picked;
        }

        List<int[]> tickets = new ArrayList<>(picked.size());
        for (int[] t : picked) {
            tickets.add(new int[]{t[0], t[1], t[2]});
        }

        java.util.Map<String, List<Integer>> groupIdx = new java.util.LinkedHashMap<>();
        for (int i = 0; i < tickets.size(); i++) {
            int[] t = tickets.get(i);
            groupIdx.computeIfAbsent(groupKey(t[0], t[1], t[2]), k -> new ArrayList<>()).add(i);
        }
        List<String> groupOrder = new ArrayList<>(groupIdx.keySet());

        Set<String> have = new HashSet<>();
        for (int[] t : tickets) {
            have.add("" + t[0] + t[1] + t[2]);
        }

        // 需要补入：头部优先组的位分次优（原票保留）
        int preferN = Math.min(PERM_EXPAND_GROUPS + 10, groupOrder.size());
        List<int[]> toInject = new ArrayList<>();
        Set<String> injectKeys = new HashSet<>();
        for (int i = 0; i < preferN; i++) {
            String gk = groupOrder.get(i);
            List<Integer> idxs = groupIdx.get(gk);
            if (idxs == null || idxs.size() != 1) {
                continue;
            }
            int[] sample = tickets.get(idxs.get(0));
            List<int[]> ranked = permsByPosScore(sample[0], sample[1], sample[2], scores, feat);
            int addCount = 0;
            int maxAdd = isPairSet(sample[0], sample[1], sample[2]) ? 2 : 1;
            for (int[] p : ranked) {
                String k = "" + p[0] + p[1] + p[2];
                if (have.contains(k) || injectKeys.contains(k)) {
                    continue;
                }
                // 跳过与原票相同
                if (p[0] == sample[0] && p[1] == sample[1] && p[2] == sample[2]) {
                    continue;
                }
                toInject.add(p);
                injectKeys.add(k);
                if (++addCount >= maxAdd) {
                    break;
                }
            }
            // 同组种子变体（若与原票不同）
            if (!ranked.isEmpty() && addCount < maxAdd) {
                int[] best = ranked.get(0);
                int[] add = {seeds.shiftAdd(best[0], 0), seeds.shiftAdd(best[1], 1), seeds.shiftAdd(best[2], 2)};
                if (groupKey(add[0], add[1], add[2]).equals(gk)) {
                    String k = "" + add[0] + add[1] + add[2];
                    if (!have.contains(k) && injectKeys.add(k)) {
                        toInject.add(add);
                    }
                }
            }
        }

        if (toInject.isEmpty()) {
            log.info("位分重排校正: 无需补入");
            return tickets;
        }

        // 牺牲名额：尾部单票组（不碰头部 preferN、不多票组）
        int sacrificeFrom = Math.max(preferN, groupOrder.size() * 2 / 3);
        List<Integer> victims = new ArrayList<>();
        for (int gi = groupOrder.size() - 1; gi >= sacrificeFrom; gi--) {
            String gk = groupOrder.get(gi);
            List<Integer> idxs = groupIdx.get(gk);
            if (idxs != null && idxs.size() == 1) {
                victims.add(idxs.get(0));
            }
        }

        int injected = 0;
        int groupsSacrificed = 0;
        for (int[] need : toInject) {
            String k = "" + need[0] + need[1] + need[2];
            if (have.contains(k) || victims.isEmpty()) {
                continue;
            }
            int vi = victims.remove(0);
            int[] old = tickets.get(vi);
            String oldGk = groupKey(old[0], old[1], old[2]);
            // 确认仍是单票组
            if (groupIdx.getOrDefault(oldGk, List.of()).size() != 1) {
                continue;
            }
            tickets.set(vi, need);
            have.remove("" + old[0] + old[1] + old[2]);
            have.add(k);
            groupIdx.remove(oldGk);
            String newGk = groupKey(need[0], need[1], need[2]);
            groupIdx.computeIfAbsent(newGk, x -> new ArrayList<>()).add(vi);
            injected++;
            groupsSacrificed++;
        }

        log.info("位分重排校正: 补入直选排列={} 牺牲边缘组选={} 剩余组数={}",
                injected, groupsSacrificed, groupIdx.size());
        return tickets;
    }

    /** 纯直选位感分：各位位分 + 转移位奖 + 名次带 */
    private static int posStraightScore(int[] t, int[][] scores, RecentFeatureStats feat) {
        int s = scores[0][t[0]] + scores[1][t[1]] + scores[2][t[2]];
        s += feat.digitBonus(0, t[0]) + feat.digitBonus(1, t[1]) + feat.digitBonus(2, t[2]);
        s += rankBandBonus(scores, t[0], t[1], t[2]);
        return s;
    }

    /** 同一组选的全排列，按位分从高到低 */
    private static List<int[]> permsByPosScore(int a, int b, int c, int[][] scores, RecentFeatureStats feat) {
        List<int[]> list = new ArrayList<>();
        for (int[] p : uniquePerms(a, b, c)) {
            list.add(new int[]{p[0], p[1], p[2], posStraightScore(p, scores, feat)});
        }
        list.sort((x, y) -> Integer.compare(y[3], x[3]));
        List<int[]> out = new ArrayList<>(list.size());
        for (int[] p : list) {
            out.add(new int[]{p[0], p[1], p[2]});
        }
        return out;
    }

    private static int countUniqueGroups(List<int[]> tickets) {
        Set<String> set = new HashSet<>();
        for (int[] t : tickets) {
            set.add(groupKey(t[0], t[1], t[2]));
        }
        return set.size();
    }

    private static void pickOne(List<int[]> picked, Set<String> usedTicket, Set<String> usedGroup,
                                List<String> expandOrder, int[] s) {
        String gk = groupKey(s[0], s[1], s[2]);
        String tKey = "" + s[0] + s[1] + s[2];
        usedTicket.add(tKey);
        usedGroup.add(gk);
        expandOrder.add(gk);
        picked.add(new int[]{s[0], s[1], s[2]});
    }

    private static boolean isPairSet(int a, int b, int c) {
        return (a == b && b != c) || (a == c && a != b) || (b == c && a != b);
    }

    private static void offerTicket(java.util.Map<String, int[]> ticketMap, int[] t, int score) {
        if (t == null || t.length < 3) {
            return;
        }
        String key = "" + t[0] + t[1] + t[2];
        int[] old = ticketMap.get(key);
        if (old == null || score > old[3]) {
            ticketMap.put(key, new int[]{t[0], t[1], t[2], score});
        }
    }

    private static int ticketScore(int[] t, int[][] scores, RecentFeatureStats feat) {
        return scores[0][t[0]] + scores[1][t[1]] + scores[2][t[2]]
                + feat.shapeBonus(t[0], t[1], t[2]) + rankBandBonus(scores, t[0], t[1], t[2]);
    }

    /**
     * 位分名次：1=最高分。近窗开奖大量落在 3~8，据此加权。
     */
    private static int digitRank(int[] score, int digit) {
        int better = 0;
        for (int d = 0; d < 10; d++) {
            if (score[d] > score[digit] || (score[d] == score[digit] && d < digit)) {
                better++;
            }
        }
        return better + 1;
    }

    private static boolean inRankBand(int rank) {
        return rank >= RANK_BAND_LO && rank <= RANK_BAND_HI;
    }

    /** 命中名次带偏好：落在动态统计带内最高，避免死拿 Top1/2 */
    private static int midRankPreferScore(int[][] scores, int a, int b, int c) {
        int sum = 0;
        int[] ds = {a, b, c};
        for (int pos = 0; pos < 3; pos++) {
            int r = digitRank(scores[pos], ds[pos]);
            if (inRankBand(r)) {
                sum += 100;
            } else if (r == RANK_BAND_LO - 1 || r == RANK_BAND_HI + 1) {
                sum += 40;
            } else if (r == 1 || r == 2) {
                sum += 8; // Top1/2 降权，不作为主组合
            }
        }
        return sum;
    }

    /**
     * 命中名次带加成：三位都在动态带内最高；全 Top1/2 过热组合降权。
     */
    private static int rankBandBonus(int[][] scores, int a, int b, int c) {
        int r0 = digitRank(scores[0], a);
        int r1 = digitRank(scores[1], b);
        int r2 = digitRank(scores[2], c);
        int bonus = 0;
        for (int r : new int[]{r0, r1, r2}) {
            if (inRankBand(r)) {
                bonus += 40;
            } else if (r == RANK_BAND_LO - 1 || r == RANK_BAND_HI + 1) {
                bonus += 12;
            } else if (r == 1) {
                bonus -= 8; // 不迷信第一
            } else if (r == 2) {
                bonus -= 2;
            } else {
                bonus -= 6;
            }
        }
        if (inRankBand(r0) && inRankBand(r1) && inRankBand(r2)) {
            bonus += 60;
        }
        // 过热：三位都是前2名，近窗直选偏少
        if (r0 <= 2 && r1 <= 2 && r2 <= 2) {
            bonus -= 70;
        }
        return bonus;
    }

    /** 取出指定名次带内的数字（按原分从高到低） */
    private static int[] digitsInRankBand(int[] score, int lo, int hi) {
        Integer[] order = new Integer[10];
        for (int i = 0; i < 10; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(score[b], score[a]));
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int rank = i + 1;
            if (rank >= lo && rank <= hi) {
                list.add(order[i]);
            }
        }
        int[] r = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            r[i] = list.get(i);
        }
        return r;
    }

    /**
     * 码池构造：优先放入名次带 3~8 的码，再补 2/9，最后才是 1/10。
     * 保证输出不是「只拿排行最前几个」。
     */
    private static int[] buildBandAwareTop(int[] score, int n) {
        Integer[] order = new Integer[10];
        for (int i = 0; i < 10; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(score[b], score[a]));
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        // pass1: 共性带
        for (int i = 0; i < 10 && set.size() < n; i++) {
            int rank = i + 1;
            if (rank >= RANK_BAND_LO && rank <= RANK_BAND_HI) {
                set.add(order[i]);
            }
        }
        // pass2: 次边缘 2、9
        for (int i : new int[]{1, 8}) {
            if (set.size() >= n) {
                break;
            }
            set.add(order[i]);
        }
        // pass3: Top1 / 最末
        for (int i : new int[]{0, 9}) {
            if (set.size() >= n) {
                break;
            }
            set.add(order[i]);
        }
        for (int d : order) {
            if (set.size() >= n) {
                break;
            }
            set.add(d);
        }
        int[] r = new int[Math.min(n, set.size())];
        int i = 0;
        for (int d : set) {
            r[i++] = d;
        }
        return r;
    }

    /** 近窗高频和值 ±1 通道内，按热度取组选候选 */
    private static List<int[]> sumChannelCandidates(int[] global, RecentFeatureStats feat,
                                                    int[] groupHitProxy, int[][] digits, int limit) {
        Set<Integer> sums = new HashSet<>();
        for (int s : feat.topSums) {
            sums.add(s);
            sums.add(Math.max(0, s - 1));
            sums.add(Math.min(27, s + 1));
        }
        List<int[]> sets = new ArrayList<>();
        for (int a = 0; a < 10; a++) {
            for (int b = a; b < 10; b++) {
                for (int c = b; c < 10; c++) {
                    if (!sums.contains(a + b + c)) {
                        continue;
                    }
                    int sc = scoreGroupSet(a, b, c, global, feat, groupHitProxy, digits);
                    sets.add(new int[]{a, b, c, sc});
                }
            }
        }
        sets.sort((x, y) -> Integer.compare(y[3], x[3]));
        if (sets.size() > limit) {
            return new ArrayList<>(sets.subList(0, limit));
        }
        return sets;
    }

    /** 热号池：近15频次 + 位转移，上期只轻加，不强制占满 */
    private static int[] buildHotPool(int[][] digits, int[] global, RecentFeatureStats feat, int size) {
        int n = digits.length;
        int[] omitScore = new int[10];
        int[] lastSeen = new int[10];
        Arrays.fill(lastSeen, -1);
        for (int i = 0; i < n; i++) {
            lastSeen[digits[i][0]] = i;
            lastSeen[digits[i][1]] = i;
            lastSeen[digits[i][2]] = i;
        }
        for (int d = 0; d < 10; d++) {
            int gap = lastSeen[d] < 0 ? n : (n - 1 - lastSeen[d]);
            omitScore[d] = Math.min(gap, 12);
        }
        int[] cnt15 = new int[10];
        int from15 = Math.max(0, n - 15);
        for (int i = from15; i < n; i++) {
            cnt15[digits[i][0]]++;
            cnt15[digits[i][1]]++;
            cnt15[digits[i][2]]++;
        }
        int[] combined = new int[10];
        for (int d = 0; d < 10; d++) {
            combined[d] = global[d] + cnt15[d] * 10 + omitScore[d] * 2 + feat.digitBonus(0, d)
                    + feat.digitBonus(1, d) + feat.digitBonus(2, d);
        }
        for (int p = 0; p < 3; p++) {
            combined[feat.last[p]] += 6;
            combined[(feat.last[p] + 1) % 10] += 10;
            combined[(feat.last[p] + 9) % 10] += 10;
            combined[(feat.last[p] + 2) % 10] += 5;
            combined[(feat.last[p] + 8) % 10] += 5;
        }
        Integer[] order = new Integer[10];
        for (int i = 0; i < 10; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(combined[b], combined[a]));
        int[] r = new int[size];
        for (int i = 0; i < size; i++) {
            r[i] = order[i];
        }
        return r;
    }

    /** 上期同位至少匹配1位时的下期完整号，按权重取候选 */
    private static List<int[]> broadTransferCandidates(int[][] dig, RecentFeatureStats feat) {
        int n = dig.length;
        int[] last = feat.last;
        java.util.Map<String, int[]> best = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> wmap = new java.util.LinkedHashMap<>();
        int from = Math.max(1, n - 60);
        for (int i = from; i < n; i++) {
            int[] prev = dig[i - 1];
            int match = 0;
            for (int p = 0; p < 3; p++) {
                if (prev[p] == last[p]) {
                    match++;
                }
            }
            if (match < 1) {
                continue;
            }
            int[] cur = dig[i];
            String g = groupKey(cur[0], cur[1], cur[2]);
            int w = match * 12 + (i - from);
            Integer old = wmap.get(g);
            if (old == null || w > old) {
                wmap.put(g, w);
                best.put(g, new int[]{cur[0], cur[1], cur[2], w});
            }
        }
        List<int[]> list = new ArrayList<>(best.values());
        list.sort((a, b) -> Integer.compare(b[3], a[3]));
        return list;
    }

    private static int scoreGroupSet(int a, int b, int c, int[] global, RecentFeatureStats feat,
                                     int[] groupHitProxy, int[][] digits) {
        int score = global[a] + global[b] + global[c];
        int key = sortedKeyCode(a, b, c);
        score += groupHitProxy[key] * 25;
        score += feat.shapeBonus(a, b, c);
        // 近10期是否出现过相同组选
        int from = Math.max(0, digits.length - 10);
        for (int i = from; i < digits.length; i++) {
            if (sortedKeyCode(digits[i][0], digits[i][1], digits[i][2]) == key) {
                score += 15;
            }
        }
        // 含上期数字
        boolean[] lastSet = new boolean[10];
        lastSet[feat.last[0]] = true;
        lastSet[feat.last[1]] = true;
        lastSet[feat.last[2]] = true;
        int chong = (lastSet[a] ? 1 : 0) + (lastSet[b] ? 1 : 0) + (lastSet[c] ? 1 : 0);
        score += chong * 10;
        return score;
    }

    private static int[][] uniquePerms(int a, int b, int c) {
        List<int[]> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int[] raw = {a, b, c};
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (j == i) {
                    continue;
                }
                for (int k = 0; k < 3; k++) {
                    if (k == i || k == j) {
                        continue;
                    }
                    int[] p = {raw[i], raw[j], raw[k]};
                    String key = "" + p[0] + p[1] + p[2];
                    if (seen.add(key)) {
                        list.add(p);
                    }
                }
            }
        }
        return list.toArray(new int[0][]);
    }

    /** 宽松形态：只禁豹子；跨度1/全奇偶/全大小均允许（组选友好） */
    private static boolean looseMorphOk(int[] t) {
        int sum = t[0] + t[1] + t[2];
        if (sum < 3 || sum > 27) {
            return false;
        }
        int max = Math.max(t[0], Math.max(t[1], t[2]));
        int min = Math.min(t[0], Math.min(t[1], t[2]));
        return max != min; // 禁豹子
    }

    private static int[] globalDigitScore(int[][] scores, RecentFeatureStats feat, int[][] digits) {
        int[] g = new int[10];
        for (int d = 0; d < 10; d++) {
            g[d] = scores[0][d] + scores[1][d] + scores[2][d];
            g[d] += feat.digitBonus(0, d) + feat.digitBonus(1, d) + feat.digitBonus(2, d);
        }
        // 近20期任意位出现加分
        int from = Math.max(0, digits.length - 20);
        for (int i = from; i < digits.length; i++) {
            g[digits[i][0]] += 2;
            g[digits[i][1]] += 2;
            g[digits[i][2]] += 2;
        }
        return g;
    }

    private static List<int[]> buildLoosePool(int[][] topPos, int[][] scores, RecentFeatureStats feat) {
        List<int[]> pool = new ArrayList<>();
        for (int a : topPos[0]) {
            for (int b : topPos[1]) {
                for (int c : topPos[2]) {
                    int[] t = {a, b, c};
                    if (!looseMorphOk(t)) {
                        continue;
                    }
                    int score = scores[0][a] + scores[1][b] + scores[2][c] + feat.shapeBonus(a, b, c)
                            + rankBandBonus(scores, a, b, c);
                    pool.add(new int[]{a, b, c, score});
                }
            }
        }
        pool.sort((x, y) -> Integer.compare(y[3], x[3]));
        return pool;
    }

    private static List<int[]> takeTopUnique(List<int[]> pool, int n) {
        List<int[]> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Set<String> groups = new HashSet<>();
        for (int[] c : pool) {
            String tKey = "" + c[0] + c[1] + c[2];
            String gKey = groupKey(c[0], c[1], c[2]);
            if (used.contains(tKey)) {
                continue;
            }
            if (groups.contains(gKey) && out.size() >= 3) {
                continue;
            }
            used.add(tKey);
            groups.add(gKey);
            out.add(new int[]{c[0], c[1], c[2]});
            if (out.size() >= n) {
                break;
            }
        }
        return out;
    }

    private static String groupKey(int a, int b, int c) {
        int[] x = {a, b, c};
        Arrays.sort(x);
        return "" + x[0] + x[1] + x[2];
    }

    private static int sortedKeyCode(int a, int b, int c) {
        int[] x = {a, b, c};
        Arrays.sort(x);
        return x[0] * 100 + x[1] * 10 + x[2];
    }

    /** TopN 经偏差种子 ± 扩展后，按分数回截 */
    private static int[] applyBiasSeedToTop(int[] top, int pos, BiasSeedCorrector seeds, int[] score) {
        boolean[] mark = new boolean[10];
        for (int d : top) {
            seeds.expandDigit(d, pos, mark);
        }
        // 原 Top 额外抬分，校正码次之
        Integer[] cands = new Integer[10];
        int n = 0;
        for (int d = 0; d < 10; d++) {
            if (mark[d]) {
                cands[n++] = d;
            }
        }
        Integer[] arr = Arrays.copyOf(cands, n);
        boolean[] inOrig = new boolean[10];
        for (int d : top) {
            inOrig[d] = true;
        }
        Arrays.sort(arr, (a, b) -> {
            int sa = score[a] + (inOrig[a] ? 100 : 0) + biasAlignBonus(a, top, pos, seeds);
            int sb = score[b] + (inOrig[b] ? 100 : 0) + biasAlignBonus(b, top, pos, seeds);
            return Integer.compare(sb, sa);
        });
        int[] result = new int[TOP_N];
        for (int i = 0; i < TOP_N; i++) {
            result[i] = i < arr.length ? arr[i] : top[Math.min(i, top.length - 1)];
        }
        return result;
    }

    private static int biasAlignBonus(int digit, int[] top, int pos, BiasSeedCorrector seeds) {
        // 若 digit = 原Top某码 ± 主种子，给额外分
        for (int d : top) {
            if (seeds.shiftAdd(d, pos) == digit || seeds.shiftSub(d, pos) == digit) {
                return 40;
            }
        }
        return 0;
    }

    private static int[] scoreAllDigits(int[][] digits, int pos, List<HmCache.CompareDto> compares,
                                        RecentFeatureStats feat) {
        int[] freq5 = freq(digits, pos, 5);
        int[] freq10 = freq(digits, pos, 10);
        int[] freq20 = freq(digits, pos, 20);
        int[] omit = omission(digits, pos);
        int last = digits[digits.length - 1][pos];

        int[] actual15 = new int[10];
        int[] pred15 = new int[10];
        fillComparePosStats(compares, pos, actual15, pred15);

        int[] score = new int[10];
        for (int d = 0; d < 10; d++) {
            int s = freq10[d] * 3 + freq20[d];
            if (omit[d] >= 2 && omit[d] <= 6) {
                s += 4;
            } else if (omit[d] >= 7 && omit[d] <= 12) {
                s += 2;
            }
            if (freq5[d] >= 2) {
                s += 3;
            }
            if (d == last) {
                s -= 1;
            }
            if (d == neighbor(last, -1) || d == neighbor(last, 1)) {
                s += 2;
            }
            if (actual15[d] > 0 && pred15[d] <= 1) {
                s += 5;
            }
            if (pred15[d] >= 4 && actual15[d] == 0) {
                s -= 8;
            }
            // 近窗重号 / 转移
            s += feat.digitBonus(pos, d);
            score[d] = s;
        }
        return score;
    }

    // ========================= 偏差诊断日志（近15期） =========================

    private static void logBiasDiagnostics(List<HmCache.CompareDto> compares) {
        if (compares == null || compares.isEmpty()) {
            return;
        }

        List<HmCache.CompareDto> valid = new ArrayList<>();
        for (HmCache.CompareDto dto : compares) {
            if (dto != null && dto.getAiHm() != null && !dto.getAiHm().isBlank()
                    && dto.getRealHm() != null && dto.getRealHm().length() == 3) {
                valid.add(dto);
            }
        }
        if (valid.isEmpty()) {
            return;
        }
        int start = Math.max(0, valid.size() - 15);
        List<HmCache.CompareDto> last15 = valid.subList(start, valid.size());

        int neighborMiss = 0;
        int extremeFail = 0;
        int checked = 0;
        boolean[] posMiss3 = new boolean[3];

        for (HmCache.CompareDto dto : last15) {
            int[] real = parseCode(dto.getRealHm());
            if (real == null) {
                continue;
            }
            checked++;
            Set<Integer> predDigits = new HashSet<>();
            for (String p : dto.getAiHm().split(",")) {
                int[] t = parseCode(p.trim());
                if (t == null) {
                    continue;
                }
                predDigits.add(t[0]);
                predDigits.add(t[1]);
                predDigits.add(t[2]);
            }
            boolean hitNeighbor = false;
            for (int pos = 0; pos < 3; pos++) {
                int d = real[pos];
                if (predDigits.contains(neighbor(d, -1)) || predDigits.contains(neighbor(d, 1)) || predDigits.contains(d)) {
                    hitNeighbor = true;
                    break;
                }
            }
            if (!hitNeighbor) {
                neighborMiss++;
            }

            boolean anyHit = Arrays.asList(dto.getAiHm().split(",")).contains(dto.getRealHm());
            if (!anyHit && isPredExtreme(dto.getAiHm())) {
                extremeFail++;
            }
        }

        int from = Math.max(0, last15.size() - 3);
        for (int pos = 0; pos < 3; pos++) {
            boolean miss3 = last15.size() >= 3;
            for (int i = from; i < last15.size() && miss3; i++) {
                HmCache.CompareDto dto = last15.get(i);
                int[] real = parseCode(dto.getRealHm());
                if (real == null) {
                    miss3 = false;
                    break;
                }
                boolean covered = false;
                for (String p : dto.getAiHm().split(",")) {
                    int[] t = parseCode(p.trim());
                    if (t != null && t[pos] == real[pos]) {
                        covered = true;
                        break;
                    }
                }
                if (covered) {
                    miss3 = false;
                }
            }
            posMiss3[pos] = miss3;
        }

        int recent = Math.min(5, last15.size());
        int posCoverMiss = 0;
        for (int i = last15.size() - recent; i < last15.size(); i++) {
            HmCache.CompareDto dto = last15.get(i);
            int[] real = parseCode(dto.getRealHm());
            if (real == null) {
                continue;
            }
            boolean[] posHit = new boolean[3];
            for (String p : dto.getAiHm().split(",")) {
                int[] t = parseCode(p.trim());
                if (t == null) {
                    continue;
                }
                for (int pos = 0; pos < 3; pos++) {
                    if (t[pos] == real[pos]) {
                        posHit[pos] = true;
                    }
                }
            }
            for (boolean h : posHit) {
                if (!h) {
                    posCoverMiss++;
                }
            }
        }
        log.info("近{}期偏差诊断: 邻号漏检率≈{}/{}, 极端形态失败≈{}/{}, 位覆盖缺失={}, 连续3期避开位={}",
                recent, neighborMiss, checked, extremeFail, checked, posCoverMiss, Arrays.toString(posMiss3));
    }

    private static boolean isPredExtreme(String aiHm) {
        int hotish = 0;
        int coldish = 0;
        int total = 0;
        for (String p : aiHm.split(",")) {
            int[] t = parseCode(p.trim());
            if (t == null) {
                continue;
            }
            total++;
            int sum = t[0] + t[1] + t[2];
            if (sum >= 20) {
                hotish++;
            }
            if (sum <= 8) {
                coldish++;
            }
        }
        return total > 0 && (hotish * 2 >= total || coldish * 2 >= total);
    }

    // ========================= 统计工具 =========================

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

    private static int[] parseCode(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim();
        if (c.length() == 1) {
            c = "00" + c;
        } else if (c.length() == 2) {
            c = "0" + c;
        }
        if (c.length() != 3) {
            return null;
        }
        try {
            return new int[]{c.charAt(0) - '0', c.charAt(1) - '0', c.charAt(2) - '0'};
        } catch (Exception e) {
            return null;
        }
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

    private static void fillComparePosStats(List<HmCache.CompareDto> compares, int pos, int[] actual, int[] pred) {
        if (compares == null) {
            return;
        }
        List<HmCache.CompareDto> valid = new ArrayList<>();
        for (HmCache.CompareDto dto : compares) {
            if (dto != null && dto.getRealHm() != null && dto.getRealHm().length() == 3
                    && dto.getAiHm() != null && !dto.getAiHm().isBlank()) {
                valid.add(dto);
            }
        }
        int start = Math.max(0, valid.size() - 15);
        for (int i = start; i < valid.size(); i++) {
            HmCache.CompareDto dto = valid.get(i);
            int[] real = parseCode(dto.getRealHm());
            if (real != null) {
                actual[real[pos]]++;
            }
            for (String p : dto.getAiHm().split(",")) {
                int[] t = parseCode(p.trim());
                if (t != null) {
                    pred[t[pos]]++;
                }
            }
        }
    }

    private static int neighbor(int d, int delta) {
        return (d + delta + 10) % 10;
    }

    private static String posName(int pos) {
        return switch (pos) {
            case 0 -> "百";
            case 1 -> "十";
            default -> "个";
        };
    }

    /** 供本地用历史数组快速验证 */
    public static String predictFromCodes(List<String> codes, List<HmCache.CompareDto> compares) {
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
        return predict(list, compares, GameKind.PL3);
    }
}
