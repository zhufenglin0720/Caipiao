package com.zfl.caipiao.utils;

import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 近200期：三码 / 500注大底 / 过拟合 / 七码 旋钮扫描。
 * 有综合提升且主指标不降则写入生产默认；否则保持原逻辑。
 */
public final class Hit200Optimize {

    private static final int EVAL = 200;

    private Hit200Optimize() {
    }

    public static void main(String[] args) throws Exception {
        muteLogs();
        StringBuilder sb = new StringBuilder();
        sb.append("========== 近").append(EVAL).append("期 命中优化扫描 ==========\n");
        sb.append("目标：三码10注、大底500、过拟合、七码；提升则保留，否则还原\n\n");

        // label, neighRank, ofSlots, habitRes, recentGrp, realign, cross, neighInj, ofExpand, hamPl3, hamSd, seedCap
        Object[][] cfgs = {
                {0, 2, 3, 0, true, true, true, true, 1, 2, 48, "baseline"},
                {6, 2, 3, 0, true, true, true, true, 1, 2, 48, "七码邻号保证"},
                {0, 3, 3, 0, true, true, true, true, 1, 2, 48, "三码多占过拟合"},
                {0, 2, 4, 0, true, true, true, true, 1, 2, 48, "三码习惯槽+1"},
                {0, 2, 3, 1, true, true, true, true, 1, 2, 48, "三码隔期组"},
                {0, 3, 4, 0, true, true, true, true, 1, 2, 48, "三码过拟合+习惯"},
                {0, 2, 3, 0, true, true, false, true, 1, 2, 48, "大底关邻号注入"},
                {0, 2, 3, 0, true, true, true, false, 1, 2, 48, "过拟合关邻扩"},
                {0, 2, 3, 0, true, true, true, true, 2, 3, 64, "过拟合加长近窗"},
                {6, 3, 3, 0, true, true, true, true, 2, 2, 48, "七码邻+过拟合槽"},
                {0, 2, 3, 0, true, false, true, true, 1, 2, 48, "七码关跨位上期"},
                {0, 2, 3, 0, false, true, true, true, 1, 2, 48, "三码关realign"},
        };

        Score baseline = null;
        Score best = null;
        int bestI = 0;

        for (int i = 0; i < cfgs.length; i++) {
            apply(cfgs[i]);
            Score s = run((String) cfgs[i][11]);
            line(sb, s);
            if (i == 0) {
                baseline = s;
                best = s;
                bestI = 0;
                continue;
            }
            if (dominates(s, baseline) && scoreOf(s) > scoreOf(best)) {
                best = s;
                bestI = i;
            }
        }

        sb.append('\n');
        if (bestI == 0) {
            apply(cfgs[0]);
            sb.append("结论: 无配置相对基线提升 → 保持原逻辑\n");
            System.out.println("KEEP_BASELINE");
        } else {
            apply(cfgs[bestI]);
            sb.append("结论: 选用 ").append(cfgs[bestI][11])
                    .append(" → 写回生产默认\n");
            writeProductionDefaults(cfgs[bestI], sb);
            System.out.println("KEEP_OPTIMIZED " + cfgs[bestI][11]);
        }
        dumpKnobs(sb);
        Path out = Path.of("reports/hit200_optimize.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println(sb);
        System.out.println("写入 " + out.toAbsolutePath());
    }

    /** 主指标都不降，且总分更高 */
    private static boolean dominates(Score a, Score b) {
        if (a.sanmaZx < b.sanmaZx || a.sanmaGrp < b.sanmaGrp) {
            return false;
        }
        if (a.dadiZx < b.dadiZx || a.dadiGrp < b.dadiGrp) {
            return false;
        }
        if (a.ofZx < b.ofZx || a.qima < b.qima) {
            return false;
        }
        return scoreOf(a) > scoreOf(b);
    }

    private static int scoreOf(Score s) {
        // 直选权重高于组选；七码与大底直选并重
        return s.sanmaZx * 40 + s.sanmaGrp * 12
                + s.dadiZx * 20 + s.dadiGrp * 6
                + s.ofZx * 18 + s.ofGrp * 3
                + s.qima * 16;
    }

    private static void apply(Object[] c) {
        RuleBasedDingWeiUtils.ENSURE_NEIGH_MAX_RANK = (Integer) c[0];
        RecommendBetUtils.OF_MAX_SLOTS = (Integer) c[1];
        RecommendBetUtils.HABIT_RESERVE = (Integer) c[2];
        RecommendBetUtils.RECENT_GRP = (Integer) c[3];
        RecommendBetUtils.REALIGN_POOL_RANK = (Boolean) c[4];
        RuleBasedDingWeiUtils.CROSS_LAST = (Boolean) c[5];
        RuleBasedPredictUtils.ENABLE_NEIGHBOR_INJECT = (Boolean) c[6];
        Overfit20PredictUtils.ENABLE_NEIGHBOR_EXPAND = (Boolean) c[7];
        Overfit20PredictUtils.HABIT_HAM1_AGES_PL3 = (Integer) c[8];
        Overfit20PredictUtils.HABIT_HAM1_AGES_SD = (Integer) c[9];
        Overfit20PredictUtils.HABIT_SEED_CAP = (Integer) c[10];
        RuleBasedDingWeiUtils.HABIT_SCALE = 1.0;
        RuleBasedDingWeiUtils.ENSURE_LAST_MAX_RANK = 10;
        RecommendBetUtils.PIN_TOP1 = false;
    }

    private static Score run(String label) {
        System.out.println(">>> " + label);
        StringBuilder dump = new StringBuilder();
        HitPeriod50Backtest.Game sd = HitPeriod50Backtest.runOne("福彩3D", HistoryDataLoader.load3d(),
                RuleBasedPredictUtils.GameKind.SD_3D, Overfit20PredictUtils.GameKind.SD,
                RuleBasedDingWeiUtils.GameKind.SD_3D, RuleBasedDanMaUtils.GameKind.SD_3D, EVAL, dump);
        HitPeriod50Backtest.Game pl = HitPeriod50Backtest.runOne("排列三", HistoryDataLoader.loadPl3(),
                RuleBasedPredictUtils.GameKind.PL3, Overfit20PredictUtils.GameKind.PL3,
                RuleBasedDingWeiUtils.GameKind.PL3, RuleBasedDanMaUtils.GameKind.PL3, EVAL, dump);
        Score s = new Score();
        s.label = label;
        s.sanmaZx = sd.sanmaZx + pl.sanmaZx;
        s.sanmaGrp = sd.sanmaGrp + pl.sanmaGrp;
        s.dadiZx = sd.dadiZx + pl.dadiZx;
        s.dadiGrp = sd.dadiGrp + pl.dadiGrp;
        s.ofZx = sd.ofZx + pl.ofZx;
        s.ofGrp = sd.ofGrp + pl.ofGrp;
        s.qima = sd.dwFull + pl.dwFull;
        s.dan = sd.danAny + pl.danAny;
        return s;
    }

    private static void line(StringBuilder sb, Score s) {
        String t = String.format(Locale.ROOT,
                "%-16s | 三码直%d组%d 大底直%d组%d 过拟合直%d组%d 七码%d 胆%d score=%d%n",
                s.label, s.sanmaZx, s.sanmaGrp, s.dadiZx, s.dadiGrp,
                s.ofZx, s.ofGrp, s.qima, s.dan, scoreOf(s));
        sb.append(t);
        System.out.print(t);
    }

    private static void dumpKnobs(StringBuilder sb) {
        sb.append(String.format(Locale.ROOT,
                "最终 knobs: NEIGH=%d OF_SLOTS=%d HABIT_RES=%d RECENT_GRP=%d REALIGN=%s CROSS=%s dadiInj=%s ofExpand=%s hamAges=%d/%d seed=%d%n",
                RuleBasedDingWeiUtils.ENSURE_NEIGH_MAX_RANK,
                RecommendBetUtils.OF_MAX_SLOTS,
                RecommendBetUtils.HABIT_RESERVE,
                RecommendBetUtils.RECENT_GRP,
                RecommendBetUtils.REALIGN_POOL_RANK,
                RuleBasedDingWeiUtils.CROSS_LAST,
                RuleBasedPredictUtils.ENABLE_NEIGHBOR_INJECT,
                Overfit20PredictUtils.ENABLE_NEIGHBOR_EXPAND,
                Overfit20PredictUtils.HABIT_HAM1_AGES_PL3,
                Overfit20PredictUtils.HABIT_HAM1_AGES_SD,
                Overfit20PredictUtils.HABIT_SEED_CAP));
    }

    private static void writeProductionDefaults(Object[] c, StringBuilder sb) throws Exception {
        // 只改与 baseline 不同的静态默认，便于回滚对照
        patchFile("src/main/java/com/zfl/caipiao/utils/RuleBasedDingWeiUtils.java",
                "static int ENSURE_NEIGH_MAX_RANK = 0;",
                "static int ENSURE_NEIGH_MAX_RANK = " + c[0] + ";");
        patchFile("src/main/java/com/zfl/caipiao/utils/RuleBasedDingWeiUtils.java",
                "static boolean CROSS_LAST = true;",
                "static boolean CROSS_LAST = " + c[5] + ";");
        patchFile("src/main/java/com/zfl/caipiao/utils/RecommendBetUtils.java",
                "static int OF_MAX_SLOTS = 2;",
                "static int OF_MAX_SLOTS = " + c[1] + ";");
        patchFile("src/main/java/com/zfl/caipiao/utils/RecommendBetUtils.java",
                "static int HABIT_RESERVE = 3;",
                "static int HABIT_RESERVE = " + c[2] + ";");
        patchFile("src/main/java/com/zfl/caipiao/utils/RecommendBetUtils.java",
                "static int RECENT_GRP = 0;",
                "static int RECENT_GRP = " + c[3] + ";");
        patchFile("src/main/java/com/zfl/caipiao/utils/RecommendBetUtils.java",
                "static boolean REALIGN_POOL_RANK = true;",
                "static boolean REALIGN_POOL_RANK = " + c[4] + ";");
        patchFile("src/main/java/com/zfl/caipiao/utils/RuleBasedPredictUtils.java",
                "static volatile boolean ENABLE_NEIGHBOR_INJECT = true;",
                "static volatile boolean ENABLE_NEIGHBOR_INJECT = " + c[6] + ";");
        patchFile("src/main/java/com/zfl/caipiao/utils/Overfit20PredictUtils.java",
                "static volatile boolean ENABLE_NEIGHBOR_EXPAND = true;",
                "static volatile boolean ENABLE_NEIGHBOR_EXPAND = " + c[7] + ";");
        patchFile("src/main/java/com/zfl/caipiao/utils/Overfit20PredictUtils.java",
                "static int HABIT_HAM1_AGES_PL3 = 1;",
                "static int HABIT_HAM1_AGES_PL3 = " + c[8] + ";");
        patchFile("src/main/java/com/zfl/caipiao/utils/Overfit20PredictUtils.java",
                "static int HABIT_HAM1_AGES_SD = 2;",
                "static int HABIT_HAM1_AGES_SD = " + c[9] + ";");
        patchFile("src/main/java/com/zfl/caipiao/utils/Overfit20PredictUtils.java",
                "static int HABIT_SEED_CAP = 48;",
                "static int HABIT_SEED_CAP = " + c[10] + ";");
        sb.append("已写回源码静态默认值\n");
    }

    private static void patchFile(String path, String from, String to) throws Exception {
        if (from.equals(to)) {
            return;
        }
        Path p = Path.of(path);
        String text = Files.readString(p, StandardCharsets.UTF_8);
        if (!text.contains(from)) {
            System.err.println("WARN skip patch missing: " + from + " in " + path);
            return;
        }
        Files.writeString(p, text.replace(from, to), StandardCharsets.UTF_8);
    }

    private static void muteLogs() {
        try {
            ch.qos.logback.classic.Logger root =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.setLevel(ch.qos.logback.classic.Level.ERROR);
        } catch (Throwable ignored) {
        }
    }

    static final class Score {
        String label;
        int sanmaZx, sanmaGrp, dadiZx, dadiGrp, ofZx, ofGrp, qima, dan;
    }
}
