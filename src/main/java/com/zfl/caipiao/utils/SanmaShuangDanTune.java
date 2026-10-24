package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 近 50 期对照：三码 10 注五种选法、双胆四种选法。不改生产逻辑，只打分。
 */
public final class SanmaShuangDanTune {

    private static final int EVAL = 50;
    private static final int WARMUP = 40;
    private static final String[] SANMA = {"A_expert", "B_front10", "C_density", "D_ofPool", "E_hybrid"};
    private static final String[] SHUANG = {"A_cooccur", "B_hot6", "C_loo", "D_shareHot"};

    private SanmaShuangDanTune() {
    }

    public static void main(String[] args) throws Exception {
        muteLogs();
        StringBuilder sb = new StringBuilder();
        sb.append("========== 三码/双胆选法对照 近").append(EVAL).append("期 ==========\n");
        sb.append("三码指标=10注直选/组选。双胆指标=三组里至少一组两码都出现。\n");
        sb.append("A专家=当前pickRecommendBets  B前10个不同组  C密度带scoreRanks\n");
        sb.append("D过拟合∩大底按大底名次取满10组  E大底前30组取6 + 过拟合交集取4\n");
        sb.append("双胆A=当前共现  B热号前6拆成3对  C留出20期预测式验证  D最热号与其后3个热号成对\n\n");

        List<Hm> sd = HistoryDataLoader.load3d();
        List<Hm> pl3 = HistoryDataLoader.loadPl3();
        Score sdSan = sanma("福彩3D", sd, RuleBasedPredictUtils.GameKind.SD_3D, Overfit20PredictUtils.GameKind.SD, false, sb);
        Score plSan = sanma("排列三", pl3, RuleBasedPredictUtils.GameKind.PL3, Overfit20PredictUtils.GameKind.PL3, true, sb);
        Score sdDan = shuangDan("福彩3D", sd, sb);
        Score plDan = shuangDan("排列三", pl3, sb);

        sb.append("\n========== 合计（直选 / 组选 或 any-group） ==========\n");
        sb.append(String.format(Locale.ROOT, "%-12s", "三码"));
        for (String n : SANMA) {
            sb.append(String.format(Locale.ROOT, " | %s", n));
        }
        sb.append('\n');
        sb.append(String.format(Locale.ROOT, "%-12s", "直选"));
        for (int k = 0; k < SANMA.length; k++) {
            sb.append(String.format(Locale.ROOT, " | %d/%d", sdSan.zx[k] + plSan.zx[k], sdSan.n + plSan.n));
        }
        sb.append('\n');
        sb.append(String.format(Locale.ROOT, "%-12s", "组选"));
        for (int k = 0; k < SANMA.length; k++) {
            sb.append(String.format(Locale.ROOT, " | %d/%d", sdSan.grp[k] + plSan.grp[k], sdSan.n + plSan.n));
        }
        sb.append("\n\n");
        sb.append(String.format(Locale.ROOT, "%-12s", "双胆any"));
        for (String n : SHUANG) {
            sb.append(String.format(Locale.ROOT, " | %s", n));
        }
        sb.append('\n');
        sb.append(String.format(Locale.ROOT, "%-12s", "合计"));
        for (int k = 0; k < SHUANG.length; k++) {
            sb.append(String.format(Locale.ROOT, " | %d/%d", sdDan.zx[k] + plDan.zx[k], sdDan.n + plDan.n));
        }
        sb.append('\n');

        int sanWin = best(sdSan.zx, plSan.zx, sdSan.grp, plSan.grp);
        int danWin = best(sdDan.zx, plDan.zx, null, null);
        sb.append("\n三码优先直选、其次组选，领先：").append(SANMA[sanWin]).append('\n');
        sb.append("双胆 any-group 领先：").append(SHUANG[danWin]).append('\n');

        Path out = Path.of("reports/sanma_shuangdan_tune.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println(sb);
    }

    private static int best(int[] a, int[] b, int[] a2, int[] b2) {
        int win = 0;
        for (int k = 1; k < a.length; k++) {
            int zx = a[k] + b[k];
            int bestZx = a[win] + b[win];
            int g = a2 == null ? 0 : a2[k] + b2[k];
            int bestG = a2 == null ? 0 : a2[win] + b2[win];
            if (zx > bestZx || (zx == bestZx && g > bestG)) {
                win = k;
            }
        }
        return win;
    }

    private static Score sanma(String name, List<Hm> all,
                               RuleBasedPredictUtils.GameKind predKind,
                               Overfit20PredictUtils.GameKind ofKind,
                               boolean pl3, StringBuilder out) {
        out.append("---------- 三码 ").append(name).append(" ----------\n");
        Score sc = new Score(SANMA.length);
        if (all == null || all.size() < WARMUP + EVAL + 10) {
            out.append("历史不足\n");
            return sc;
        }
        int start = all.size() - EVAL;
        List<HmCache.CompareDto> compares = new ArrayList<>();
        int warmFrom = Math.max(30, start - WARMUP);
        for (int i = warmFrom; i < start; i++) {
            pools(all, i, predKind, ofKind, pl3, compares);
        }
        int[] rankBucket = new int[6];
        int inPool = 0;
        int inBoth = 0;
        sc.n = EVAL;
        long t0 = System.currentTimeMillis();
        for (int i = start; i < all.size(); i++) {
            String[] pack = pools(all, i, predKind, ofKind, pl3, compares);
            String raw = pack[0];
            String ofCsv = pack[1];
            String actual = pad3(all.get(i).toString());
            List<String> bets = parseBets(raw);
            Set<String> overfit = new LinkedHashSet<>(parseBets(ofCsv));
            List<HmCache.CompareDto> prior = compares.subList(0, compares.size() - 1);
            String[] picks = new String[]{
                    RecommendBetUtils.pickExpertBets(raw, prior, ofCsv, pl3),
                    join(frontUnique(bets, 10)),
                    join(densityPick(bets, compares.subList(0, compares.size() - 1))),
                    join(overfitThenPool(bets, overfit, 10)),
                    join(hybridPick(bets, overfit))
            };
            for (int k = 0; k < picks.length; k++) {
                if (containsZx(picks[k], actual)) {
                    sc.zx[k]++;
                }
                if (containsGrp(picks[k], actual)) {
                    sc.grp[k]++;
                }
            }
            int rank = indexOf(bets, actual);
            if (rank >= 1) {
                inPool++;
                if (rank <= 10) {
                    rankBucket[0]++;
                } else if (rank <= 30) {
                    rankBucket[1]++;
                } else if (rank <= 60) {
                    rankBucket[2]++;
                } else if (rank <= 120) {
                    rankBucket[3]++;
                } else if (rank <= 200) {
                    rankBucket[4]++;
                } else {
                    rankBucket[5]++;
                }
                if (overfit.contains(actual)) {
                    inBoth++;
                }
            }
            int done = i - start + 1;
            if (done % 10 == 0 || done == EVAL) {
                System.out.printf(Locale.ROOT,
                        "%s 三码 %d/%d | A直%d B直%d C直%d D直%d E直%d 池直%d%n",
                        name, done, EVAL, sc.zx[0], sc.zx[1], sc.zx[2], sc.zx[3], sc.zx[4], inPool);
            }
        }
        out.append(String.format(Locale.ROOT, "耗时=%dms 大底直选在池=%d/%d 其中也在过拟合=%d%n",
                System.currentTimeMillis() - t0, inPool, sc.n, inBoth));
        out.append("在池名次桶 1-10/11-30/31-60/61-120/121-200/201+: ")
                .append(Arrays.toString(rankBucket)).append('\n');
        for (int k = 0; k < SANMA.length; k++) {
            out.append(String.format(Locale.ROOT, "%-12s 直选=%d/%d  组选=%d/%d%n",
                    SANMA[k], sc.zx[k], sc.n, sc.grp[k], sc.n));
        }
        out.append('\n');
        return sc;
    }

    /** 生成大底和过拟合，并把本期写进 compares（与 HitPeriod50Backtest 同一套三码字段）。返回 [raw, ofCsv]。 */
    private static String[] pools(List<Hm> all, int i,
                                  RuleBasedPredictUtils.GameKind predKind,
                                  Overfit20PredictUtils.GameKind ofKind,
                                  boolean pl3,
                                  List<HmCache.CompareDto> compares) {
        List<Hm> hist = all.subList(0, i);
        String actual = pad3(all.get(i).toString());
        Overfit20PredictUtils.PredictResult of = Overfit20PredictUtils.predictResult(hist, ofKind, compares);
        String ofCsv = of.poolCsv();
        String raw = RuleBasedPredictUtils.predict(hist, compares, predKind, ofCsv);
        String feedback = RecommendBetUtils.pickExpertBets(raw, compares, ofCsv, pl3);
        compares.add(new HmCache.CompareDto()
                .setQh(all.get(i).getQh())
                .setAiHm(feedback)
                .setAiRecommendHm(feedback)
                .setAiFullHm(raw)
                .setAiOverfitHm(ofCsv)
                .setRealHm(actual));
        while (compares.size() > RecommendBetUtils.HIT_LOOKBACK + 5) {
            compares.remove(0);
        }
        return new String[]{raw, ofCsv};
    }

    private static Score shuangDan(String name, List<Hm> all, StringBuilder out) {
        out.append("---------- 双胆 ").append(name).append(" ----------\n");
        Score sc = new Score(SHUANG.length);
        if (all == null || all.size() < 80) {
            out.append("历史不足\n");
            return sc;
        }
        int start = all.size() - EVAL;
        sc.n = EVAL;
        for (int i = start; i < all.size(); i++) {
            List<Hm> hist = all.subList(0, i);
            String actual = all.get(i).toString();
            int[][][] picks = new int[][][]{
                    RuleBasedShuangDanUtils.pickTopPairs(hist),
                    hot6Pairs(hist),
                    looPairs(hist),
                    shareHotPairs(hist)
            };
            for (int k = 0; k < picks.length; k++) {
                if (anyPairHit(picks[k], actual)) {
                    sc.zx[k]++;
                }
            }
        }
        for (int k = 0; k < SHUANG.length; k++) {
            out.append(String.format(Locale.ROOT, "%-12s any-group=%d/%d%n", SHUANG[k], sc.zx[k], sc.n));
        }
        out.append('\n');
        return sc;
    }

    static List<String> frontUnique(List<String> all, int n) {
        List<String> picked = new ArrayList<>(n);
        Set<String> used = new LinkedHashSet<>();
        for (String bet : all) {
            if (picked.size() >= n) {
                break;
            }
            if (bet != null && bet.length() == 3 && used.add(digitKey(bet))) {
                picked.add(bet);
            }
        }
        return picked;
    }

    static List<String> densityPick(List<String> all, List<HmCache.CompareDto> history) {
        List<String> picked = new ArrayList<>(10);
        if (all.isEmpty()) {
            return picked;
        }
        int n = Math.min(all.size(), 200);
        double[] scores = RecommendBetUtils.scoreRanks(n, history);
        int[] band = RecommendBetUtils.discoverDenseBand(scores, n, 18);
        String banned = lastReal(history);
        Set<String> used = new LinkedHashSet<>();
        List<Integer> ranks = new ArrayList<>();
        for (int r = band[0]; r <= band[1] && r <= n; r++) {
            ranks.add(r);
        }
        ranks.sort(Comparator.<Integer>comparingDouble(r -> scores[r]).reversed().thenComparingInt(r -> r));
        for (int r : ranks) {
            if (picked.size() >= 10) {
                break;
            }
            take(all.get(r - 1), banned, used, picked);
        }
        List<Integer> rest = new ArrayList<>();
        for (int r = 1; r <= n; r++) {
            rest.add(r);
        }
        rest.sort(Comparator.<Integer>comparingDouble(r -> scores[r]).reversed().thenComparingInt(r -> r));
        for (int r : rest) {
            if (picked.size() >= 10) {
                break;
            }
            take(all.get(r - 1), banned, used, picked);
        }
        for (String bet : all) {
            if (picked.size() >= 10) {
                break;
            }
            take(bet, banned, used, picked);
        }
        return picked;
    }

    /** 过拟合∩大底，按大底顺序取满；不够再按大底顺序补。 */
    static List<String> overfitThenPool(List<String> all, Set<String> overfit, int n) {
        List<String> picked = new ArrayList<>(n);
        Set<String> used = new LinkedHashSet<>();
        if (overfit != null) {
            for (String bet : all) {
                if (picked.size() >= n) {
                    break;
                }
                if (bet != null && overfit.contains(bet) && used.add(digitKey(bet))) {
                    picked.add(bet);
                }
            }
        }
        for (String bet : all) {
            if (picked.size() >= n) {
                break;
            }
            if (bet != null && bet.length() == 3 && used.add(digitKey(bet))) {
                picked.add(bet);
            }
        }
        return picked;
    }

    /** 大底前 30 个不同组取 6，再从过拟合∩大底按名次补 4，最后用大底前部补满。 */
    static List<String> hybridPick(List<String> all, Set<String> overfit) {
        List<String> picked = new ArrayList<>(10);
        Set<String> used = new LinkedHashSet<>();
        int groups = 0;
        for (String bet : all) {
            if (picked.size() >= 6 || groups >= 30) {
                break;
            }
            if (bet == null || bet.length() != 3) {
                continue;
            }
            String key = digitKey(bet);
            if (used.contains(key)) {
                continue;
            }
            groups++;
            used.add(key);
            picked.add(bet);
        }
        int ofGot = 0;
        if (overfit != null) {
            for (String bet : all) {
                if (ofGot >= 4 || picked.size() >= 10) {
                    break;
                }
                if (overfit.contains(bet) && used.add(digitKey(bet))) {
                    picked.add(bet);
                    ofGot++;
                }
            }
        }
        for (String bet : all) {
            if (picked.size() >= 10) {
                break;
            }
            if (bet != null && bet.length() == 3 && used.add(digitKey(bet))) {
                picked.add(bet);
            }
        }
        return picked;
    }

    /** 多窗热度前 6 个数字，按热度相邻拆成 3 个互不重复的对。 */
    static int[][] hot6Pairs(List<Hm> history) {
        int[] order = hottest(history, 6);
        int[][] out = new int[3][2];
        for (int i = 0; i < 3; i++) {
            out[i][0] = order[i * 2];
            out[i][1] = order[i * 2 + 1];
        }
        return out;
    }

    /** 最热数字分别与第 2/3/4 热的数字成对，允许共用最热数字。 */
    static int[][] shareHotPairs(List<Hm> history) {
        int[] order = hottest(history, 4);
        int[][] out = new int[3][2];
        for (int i = 0; i < 3; i++) {
            out[i][0] = order[0];
            out[i][1] = order[i + 1];
        }
        return out;
    }

    /**
     * 近 40 期里后 20 期做 holdout。每个数字对在某一 holdout 期的贡献 =
     * 该期是否命中 × 仅用该期之前 20 期算出的共现率。再贪心取 3 个互不重复的对。
     */
    static int[][] looPairs(List<Hm> history) {
        int n = history.size();
        int hold = 20;
        int prior = 20;
        double[][] score = new double[10][10];
        int[][] holdHits = new int[10][10];
        int from = Math.max(prior, n - hold);
        for (int t = from; t < n; t++) {
            int p0 = Math.max(0, t - prior);
            int priorN = Math.max(1, t - p0);
            int[][] priorHits = pairHits(history, p0, t);
            boolean[] present = RuleBasedShuangDanUtils.digitSet(history.get(t).toString());
            for (int a = 0; a < 10; a++) {
                for (int b = a + 1; b < 10; b++) {
                    if (present[a] && present[b]) {
                        holdHits[a][b]++;
                        score[a][b] += priorHits[a][b] / (double) priorN;
                    }
                }
            }
        }
        double[] heat = digitHeat(history);
        List<int[]> cands = new ArrayList<>(45);
        for (int a = 0; a < 10; a++) {
            for (int b = a + 1; b < 10; b++) {
                cands.add(new int[]{a, b});
            }
        }
        cands.sort(Comparator
                .<int[]>comparingDouble(p -> score[p[0]][p[1]]).reversed()
                .thenComparingInt(p -> -holdHits[p[0]][p[1]])
                .thenComparingDouble(p -> -(heat[p[0]] + heat[p[1]]))
                .thenComparingInt(p -> p[0])
                .thenComparingInt(p -> p[1]));
        return greedyPairs(cands);
    }

    private static int[][] greedyPairs(List<int[]> cands) {
        int[][] out = new int[3][2];
        boolean[] used = new boolean[10];
        int filled = 0;
        for (int[] c : cands) {
            if (filled >= 3) {
                break;
            }
            if (used[c[0]] || used[c[1]]) {
                continue;
            }
            out[filled][0] = c[0];
            out[filled][1] = c[1];
            used[c[0]] = true;
            used[c[1]] = true;
            filled++;
        }
        if (filled < 3) {
            for (int[] c : cands) {
                if (filled >= 3) {
                    break;
                }
                if (already(out, filled, c[0], c[1])) {
                    continue;
                }
                if (used[c[0]] && used[c[1]]) {
                    continue;
                }
                out[filled][0] = c[0];
                out[filled][1] = c[1];
                used[c[0]] = true;
                used[c[1]] = true;
                filled++;
            }
        }
        return out;
    }

    private static boolean already(int[][] out, int n, int a, int b) {
        int x = Math.min(a, b);
        int y = Math.max(a, b);
        for (int i = 0; i < n; i++) {
            int u = Math.min(out[i][0], out[i][1]);
            int v = Math.max(out[i][0], out[i][1]);
            if (u == x && v == y) {
                return true;
            }
        }
        return false;
    }

    private static int[][] pairHits(List<Hm> history, int from, int to) {
        int[][] hits = new int[10][10];
        for (int t = from; t < to; t++) {
            boolean[] present = RuleBasedShuangDanUtils.digitSet(history.get(t).toString());
            for (int a = 0; a < 10; a++) {
                if (!present[a]) {
                    continue;
                }
                for (int b = a + 1; b < 10; b++) {
                    if (present[b]) {
                        hits[a][b]++;
                    }
                }
            }
        }
        return hits;
    }

    private static int[] hottest(List<Hm> history, int k) {
        double[] heat = digitHeat(history);
        Integer[] idx = new Integer[10];
        for (int i = 0; i < 10; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, Comparator.<Integer>comparingDouble(d -> heat[d]).reversed().thenComparingInt(d -> d));
        int[] out = new int[k];
        for (int i = 0; i < k; i++) {
            out[i] = idx[i];
        }
        return out;
    }

    private static double[] digitHeat(List<Hm> history) {
        double[] s = new double[10];
        int[] windows = {8, 12, 20, 30};
        int n = history.size();
        for (int w : windows) {
            int from = Math.max(0, n - w);
            double weight = 40.0 / w;
            int len = n - from;
            for (int i = from; i < n; i++) {
                double decay = 1.0 + 0.4 * Math.exp(-0.08 * (n - 1 - i));
                boolean[] present = RuleBasedShuangDanUtils.digitSet(history.get(i).toString());
                for (int d = 0; d < 10; d++) {
                    if (present[d]) {
                        s[d] += weight * decay;
                    }
                }
            }
            if (len == 0) {
                continue;
            }
        }
        if (n > 0) {
            boolean[] last = RuleBasedShuangDanUtils.digitSet(history.get(n - 1).toString());
            for (int d = 0; d < 10; d++) {
                if (last[d]) {
                    s[d] += 2.5;
                }
            }
        }
        return s;
    }

    private static boolean anyPairHit(int[][] pairs, String real) {
        boolean[] present = RuleBasedShuangDanUtils.digitSet(real);
        if (pairs == null) {
            return false;
        }
        for (int[] p : pairs) {
            if (p[0] != p[1] && present[p[0]] && present[p[1]]) {
                return true;
            }
        }
        return false;
    }

    private static void take(String bet, String banned, Set<String> used, List<String> picked) {
        if (bet == null || bet.length() != 3 || bet.equals(banned)) {
            return;
        }
        if (used.add(digitKey(bet))) {
            picked.add(bet);
        }
    }

    private static String lastReal(List<HmCache.CompareDto> history) {
        if (history == null) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            HmCache.CompareDto dto = history.get(i);
            if (dto != null && dto.getRealHm() != null && dto.getRealHm().length() == 3) {
                return pad3(dto.getRealHm());
            }
        }
        return null;
    }

    private static String join(List<String> bets) {
        return String.join(",", bets);
    }

    private static boolean containsZx(String pred, String actual) {
        if (pred == null || pred.isEmpty()) {
            return false;
        }
        String a = pad3(actual);
        for (String p : pred.split(",")) {
            if (pad3(p.trim()).equals(a)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsGrp(String pred, String actual) {
        if (pred == null || pred.isEmpty()) {
            return false;
        }
        String key = digitKey(pad3(actual));
        for (String p : pred.split(",")) {
            String t = pad3(p.trim());
            if (t.length() == 3 && digitKey(t).equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(List<String> bets, String actual) {
        String a = pad3(actual);
        for (int i = 0; i < bets.size(); i++) {
            if (bets.get(i).equals(a)) {
                return i + 1;
            }
        }
        return -1;
    }

    private static List<String> parseBets(String pred) {
        List<String> list = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (pred == null || pred.isBlank()) {
            return list;
        }
        for (String p : pred.split(",")) {
            String t = pad3(p.trim());
            if (t.length() == 3 && seen.add(t)) {
                list.add(t);
            }
        }
        return list;
    }

    private static String digitKey(String code) {
        char[] c = code.toCharArray();
        Arrays.sort(c);
        return new String(c);
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

    private static void muteLogs() {
        try {
            ch.qos.logback.classic.Logger root =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.setLevel(ch.qos.logback.classic.Level.ERROR);
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.zfl.caipiao"))
                    .setLevel(ch.qos.logback.classic.Level.ERROR);
        } catch (Throwable ignored) {
        }
    }

    private static final class Score {
        final int[] zx;
        final int[] grp;
        int n;

        Score(int k) {
            zx = new int[k];
            grp = new int[k];
        }
    }
}
