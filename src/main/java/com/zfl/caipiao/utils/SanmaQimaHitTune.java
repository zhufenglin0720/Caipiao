package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 三码 10 注 + 七码全中：近窗回测，只保留不降命中的改动。
 */
public final class SanmaQimaHitTune {

    private static final int QIMA_EVAL = 200;
    private static final int SANMA_EVAL = 50;
    private static final int WARMUP = 40;
    /** 近 50 期当前 main 实测（本机 HitPeriod50） */
    private static final int BASE_SANMA_GRP = 5;
    private static final int BASE_SANMA_ZX = 0;
    private static final int BASE_QIMA_50 = 26;
    private static final int BASE_OF_ZX = 17;
    private static final int BASE_DAN = 78;

    private SanmaQimaHitTune() {
    }

    public static void main(String[] args) throws Exception {
        muteLogs();
        String mode = args != null && args.length > 0 ? args[0] : "eval";
        StringBuilder sb = new StringBuilder();
        dumpKnobs(sb);

        if ("sweep".equals(mode)) {
            sweep(sb);
        } else if ("sanma".equals(mode)) {
            evalSanma(sb);
        } else if ("qima50".equals(mode)) {
            evalQima(sb, 50);
        } else if ("qima200".equals(mode)) {
            evalQima(sb, QIMA_EVAL);
        } else if ("base200".equals(mode)) {
            applyMainKnobs();
            runFull(sb, 200, "main对照200");
            applyWinnerKnobs();
        } else if ("main200".equals(mode)) {
            applyMainKnobs();
            runFull(sb, 200, "main对照200");
            applyWinnerKnobs();
        } else {
            evalQima(sb, QIMA_EVAL);
            evalSanma(sb);
        }

        Path out = Path.of("reports/sanma_qima_hit_tune.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println(sb);
        System.out.println("写入 " + out.toAbsolutePath());
    }

    private static void dumpKnobs(StringBuilder sb) {
        sb.append("HABIT_SCALE=").append(RuleBasedDingWeiUtils.HABIT_SCALE)
                .append(" ENSURE_LAST_MAX_RANK=").append(RuleBasedDingWeiUtils.ENSURE_LAST_MAX_RANK)
                .append(" CROSS_LAST=").append(RuleBasedDingWeiUtils.CROSS_LAST)
                .append(" ENSURE_NEIGH_MAX_RANK=").append(RuleBasedDingWeiUtils.ENSURE_NEIGH_MAX_RANK)
                .append(" HABIT_RESERVE=").append(RecommendBetUtils.HABIT_RESERVE)
                .append(" OF_MAX_SLOTS=").append(RecommendBetUtils.OF_MAX_SLOTS)
                .append(" PIN_TOP1=").append(RecommendBetUtils.PIN_TOP1)
                .append(" RECENT_GRP=").append(RecommendBetUtils.RECENT_GRP)
                .append(" REALIGN=").append(RecommendBetUtils.REALIGN_POOL_RANK)
                .append('\n');
    }

    private static void evalQima(StringBuilder sb, int eval) {
        sb.append("\n===== 七码近").append(eval).append("期 对照(旧习惯全量) =====\n");
        double keepScale = RuleBasedDingWeiUtils.HABIT_SCALE;
        int keepRank = RuleBasedDingWeiUtils.ENSURE_LAST_MAX_RANK;
        boolean keepCross = RuleBasedDingWeiUtils.CROSS_LAST;
        int keepNeigh = RuleBasedDingWeiUtils.ENSURE_NEIGH_MAX_RANK;
        RuleBasedDingWeiUtils.HABIT_SCALE = 1.0;
        RuleBasedDingWeiUtils.ENSURE_LAST_MAX_RANK = 10;
        RuleBasedDingWeiUtils.CROSS_LAST = false;
        RuleBasedDingWeiUtils.ENSURE_NEIGH_MAX_RANK = 0;
        Qima sdOld = qima("福彩3D-旧", HistoryDataLoader.load3d(), RuleBasedDingWeiUtils.GameKind.SD_3D, eval);
        Qima plOld = qima("排列三-旧", HistoryDataLoader.loadPl3(), RuleBasedDingWeiUtils.GameKind.PL3, eval);
        lineQ(sb, sdOld);
        lineQ(sb, plOld);
        sb.append(String.format(Locale.ROOT, "旧合计全中 %d/%d%n", sdOld.full + plOld.full, eval * 2));

        RuleBasedDingWeiUtils.HABIT_SCALE = keepScale;
        RuleBasedDingWeiUtils.ENSURE_LAST_MAX_RANK = keepRank;
        RuleBasedDingWeiUtils.CROSS_LAST = keepCross;
        RuleBasedDingWeiUtils.ENSURE_NEIGH_MAX_RANK = keepNeigh;
        sb.append("\n===== 七码近").append(eval).append("期 新 =====\n");
        Qima sdQ = qima("福彩3D-新", HistoryDataLoader.load3d(), RuleBasedDingWeiUtils.GameKind.SD_3D, eval);
        Qima plQ = qima("排列三-新", HistoryDataLoader.loadPl3(), RuleBasedDingWeiUtils.GameKind.PL3, eval);
        lineQ(sb, sdQ);
        lineQ(sb, plQ);
        sb.append(String.format(Locale.ROOT, "七码合计全中 %d/%d%n",
                sdQ.full + plQ.full, eval * 2));
    }

    private static void evalSanma(StringBuilder sb) {
        sb.append("\n===== 三码10注近").append(SANMA_EVAL).append("期 =====\n");
        Sanma sdS = sanma("福彩3D", HistoryDataLoader.load3d(),
                RuleBasedPredictUtils.GameKind.SD_3D, Overfit20PredictUtils.GameKind.SD, false);
        Sanma plS = sanma("排列三", HistoryDataLoader.loadPl3(),
                RuleBasedPredictUtils.GameKind.PL3, Overfit20PredictUtils.GameKind.PL3, true);
        lineS(sb, sdS);
        lineS(sb, plS);
        sb.append(String.format(Locale.ROOT, "三码合计 直选%d 组选%d / %d  (基线直%d组%d)%n",
                sdS.zx + plS.zx, sdS.grp + plS.grp, SANMA_EVAL * 2, BASE_SANMA_ZX, BASE_SANMA_GRP));
    }

    private static void sweep(StringBuilder sb) {
        sb.append("\n===== 完整近50期扫描（与 HitPeriod50 同一流水线） =====\n");
        sb.append("基线 本机main：三码组").append(BASE_SANMA_GRP)
                .append(" 七码").append(BASE_QIMA_50)
                .append(" 过拟合直").append(BASE_OF_ZX)
                .append(" 胆码1位").append(BASE_DAN).append('\n');
        Object[][] cfgs = {
                // scale, lastRank, cross, neigh, pinTop1, recentGrp, ignoreDw, realign, label
                {1.00, 10, false, 0, false, 0, false, false, "true-main"},
                {1.00, 10, false, 0, false, 0, false, true, "realign"},
                {1.00, 10, true, 0, false, 0, false, true, "realign+cross"},
                {1.00, 10, true, 6, false, 0, false, true, "realign+cross邻"},
                {0.85, 10, false, 0, false, 0, false, true, "realign+scale0.85"},
                {1.00, 9, false, 0, false, 0, false, true, "realign+last9"},
                {0.35, 8, false, 0, false, 0, false, true, "realign+七码缩"},
                {1.00, 10, true, 0, false, 0, false, false, "cross-only"},
        };
        int bestI = 0;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < cfgs.length; i++) {
            applyCfg(cfgs[i]);
            StringBuilder dump = new StringBuilder();
            HitPeriod50Backtest.Game sd = HitPeriod50Backtest.runOne("福彩3D", HistoryDataLoader.load3d(),
                    RuleBasedPredictUtils.GameKind.SD_3D, Overfit20PredictUtils.GameKind.SD,
                    RuleBasedDingWeiUtils.GameKind.SD_3D, RuleBasedDanMaUtils.GameKind.SD_3D, 50, dump);
            HitPeriod50Backtest.Game pl = HitPeriod50Backtest.runOne("排列三", HistoryDataLoader.loadPl3(),
                    RuleBasedPredictUtils.GameKind.PL3, Overfit20PredictUtils.GameKind.PL3,
                    RuleBasedDingWeiUtils.GameKind.PL3, RuleBasedDanMaUtils.GameKind.PL3, 50, dump);
            int sanmaZx = sd.sanmaZx + pl.sanmaZx;
            int sanmaGrp = sd.sanmaGrp + pl.sanmaGrp;
            int qima = sd.dwFull + pl.dwFull;
            int ofZx = sd.ofZx + pl.ofZx;
            int dan = sd.danAny + pl.danAny;
            boolean drop = sanmaGrp < BASE_SANMA_GRP || sanmaZx < BASE_SANMA_ZX
                    || qima < BASE_QIMA_50 || ofZx < BASE_OF_ZX || dan < BASE_DAN;
            int score = drop ? -1000 : qima * 100 + sanmaGrp * 10 + sanmaZx;
            String line = String.format(Locale.ROOT,
                    "%s | 三码直%d组%d 七码%d(3D%d/排%d) 过拟合直%d 胆1位%d%s%n",
                    cfgs[i][8], sanmaZx, sanmaGrp, qima, sd.dwFull, pl.dwFull, ofZx, dan,
                    drop ? " DROP" : "");
            sb.append(line);
            System.out.print(line);
            if (score > bestScore) {
                bestScore = score;
                bestI = i;
            }
        }
        applyCfg(cfgs[bestI]);
        sb.append("选用 ").append(cfgs[bestI][8]).append(" score=").append(bestScore).append('\n');
        dumpKnobs(sb);
    }

    private static void applyCfg(Object[] c) {
        RuleBasedDingWeiUtils.HABIT_SCALE = (Double) c[0];
        RuleBasedDingWeiUtils.ENSURE_LAST_MAX_RANK = (Integer) c[1];
        RuleBasedDingWeiUtils.CROSS_LAST = (Boolean) c[2];
        RuleBasedDingWeiUtils.ENSURE_NEIGH_MAX_RANK = (Integer) c[3];
        RecommendBetUtils.PIN_TOP1 = (Boolean) c[4];
        RecommendBetUtils.RECENT_GRP = (Integer) c[5];
        RecommendBetUtils.REALIGN_POOL_RANK = (Boolean) c[7];
    }

    private static void applyMainKnobs() {
        applyCfg(new Object[]{1.00, 10, false, 0, false, 0, false, false, "main"});
    }

    private static void applyWinnerKnobs() {
        applyCfg(new Object[]{1.00, 10, true, 0, false, 0, false, true, "winner"});
    }

    private static void runFull(StringBuilder sb, int eval, String label) {
        dumpKnobs(sb);
        StringBuilder dump = new StringBuilder();
        HitPeriod50Backtest.Game sd = HitPeriod50Backtest.runOne("福彩3D", HistoryDataLoader.load3d(),
                RuleBasedPredictUtils.GameKind.SD_3D, Overfit20PredictUtils.GameKind.SD,
                RuleBasedDingWeiUtils.GameKind.SD_3D, RuleBasedDanMaUtils.GameKind.SD_3D, eval, dump);
        HitPeriod50Backtest.Game pl = HitPeriod50Backtest.runOne("排列三", HistoryDataLoader.loadPl3(),
                RuleBasedPredictUtils.GameKind.PL3, Overfit20PredictUtils.GameKind.PL3,
                RuleBasedDingWeiUtils.GameKind.PL3, RuleBasedDanMaUtils.GameKind.PL3, eval, dump);
        String line = String.format(Locale.ROOT,
                "%s | 三码直%d组%d 七码%d(3D%d/排%d) 过拟合直%d 胆1位%d /%d%n",
                label, sd.sanmaZx + pl.sanmaZx, sd.sanmaGrp + pl.sanmaGrp,
                sd.dwFull + pl.dwFull, sd.dwFull, pl.dwFull,
                sd.ofZx + pl.ofZx, sd.danAny + pl.danAny, eval);
        sb.append(line);
        System.out.print(line);
    }

    private static void lineQ(StringBuilder sb, Qima q) {
        sb.append(String.format(Locale.ROOT,
                "%s 全中=%d/%d (%.1f%%) 百=%d 十=%d 个=%d%n",
                q.name, q.full, q.n, q.full * 100.0 / q.n, q.pos[0], q.pos[1], q.pos[2]));
    }

    private static void lineS(StringBuilder sb, Sanma s) {
        sb.append(String.format(Locale.ROOT,
                "%s 直选=%d/%d 组选=%d/%d%n", s.name, s.zx, s.n, s.grp, s.n));
    }

    static Qima qima(String name, List<Hm> all, RuleBasedDingWeiUtils.GameKind kind) {
        return qima(name, all, kind, QIMA_EVAL);
    }

    static Qima qima(String name, List<Hm> all, RuleBasedDingWeiUtils.GameKind kind, int eval) {
        Qima q = new Qima(name);
        int start = all.size() - eval;
        List<HmCache.CompareDto> cmp = new ArrayList<>();
        int warm = Math.max(30, start - WARMUP);
        for (int i = warm; i < all.size(); i++) {
            String dw = RuleBasedDingWeiUtils.predict(all.subList(0, i), cmp, kind);
            String act = pad3(all.get(i).toString());
            boolean[] h = hits(dw, act);
            if (i >= start) {
                q.n++;
                if (h[0] && h[1] && h[2]) {
                    q.full++;
                }
                for (int p = 0; p < 3; p++) {
                    if (h[p]) {
                        q.pos[p]++;
                    }
                }
            }
            cmp.add(new HmCache.CompareDto().setAiDingWeiHm(dw).setRealHm(act).setQh(all.get(i).getQh()));
            while (cmp.size() > 80) {
                cmp.remove(0);
            }
        }
        System.out.printf(Locale.ROOT, "%s 七码 %d/%d%n", name, q.full, q.n);
        return q;
    }

    static Sanma sanma(String name, List<Hm> all, RuleBasedPredictUtils.GameKind pk,
                       Overfit20PredictUtils.GameKind ok, boolean pl3) {
        Sanma s = new Sanma(name);
        int start = all.size() - SANMA_EVAL;
        List<HmCache.CompareDto> cmp = new ArrayList<>();
        int warm = Math.max(30, start - WARMUP);
        for (int i = warm; i < all.size(); i++) {
            List<Hm> hist = all.subList(0, i);
            String of = Overfit20PredictUtils.predictResult(hist, ok, cmp).poolCsv();
            String raw = RuleBasedPredictUtils.predict(hist, cmp, pk, of);
            String rec = RecommendBetUtils.pickRecommendBets(raw, cmp, of, pl3);
            String act = pad3(all.get(i).toString());
            if (i >= start) {
                s.n++;
                if (zx(rec, act)) {
                    s.zx++;
                }
                if (grp(rec, act)) {
                    s.grp++;
                }
            }
            cmp.add(new HmCache.CompareDto()
                    .setAiHm(rec).setAiRecommendHm(rec).setAiFullHm(raw)
                    .setAiOverfitHm(of).setRealHm(act).setQh(all.get(i).getQh()));
            while (cmp.size() > 80) {
                cmp.remove(0);
            }
            if ((i - warm + 1) % 20 == 0) {
                System.out.printf(Locale.ROOT, "%s 三码进度 %d 直%d 组%d%n", name, s.n, s.zx, s.grp);
            }
        }
        return s;
    }

    private static boolean[] hits(String dw, String actual) {
        boolean[] hit = new boolean[3];
        String[] parts = RuleBasedDingWeiUtils.parseParts(dw);
        if (parts == null || actual == null || actual.length() != 3) {
            return hit;
        }
        for (int pos = 0; pos < 3; pos++) {
            char t = actual.charAt(pos);
            for (String d : parts[pos].split(",")) {
                if (d.trim().length() == 1 && d.trim().charAt(0) == t) {
                    hit[pos] = true;
                    break;
                }
            }
        }
        return hit;
    }

    private static boolean zx(String pred, String actual) {
        if (pred == null || actual == null) {
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

    private static boolean grp(String pred, String actual) {
        if (pred == null) {
            return false;
        }
        String key = sort3(pad3(actual));
        for (String p : pred.split(",")) {
            String t = pad3(p.trim());
            if (t.length() == 3 && sort3(t).equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static String sort3(String s) {
        char[] c = s.toCharArray();
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
        } catch (Throwable ignored) {
        }
    }

    static final class Qima {
        final String name;
        int n, full;
        final int[] pos = new int[3];

        Qima(String name) {
            this.name = name;
        }
    }

    static final class Sanma {
        final String name;
        int n, zx, grp;

        Sanma(String name) {
            this.name = name;
        }
    }
}
