package com.zfl.caipiao.utils;

import com.zfl.caipiao.export.Hm;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 胆码：原近窗拟合 vs 习惯轮换，近 50 期至少 1 位 / 三位全中对比。
 */
public final class DanMaLogicCompare {

    private static final int EVAL = 50;

    private DanMaLogicCompare() {
    }

    public static void main(String[] args) throws Exception {
        muteLogs();
        StringBuilder sb = new StringBuilder();
        sb.append("========== 胆码近").append(EVAL).append("期：原逻辑 vs 习惯轮换 ==========\n");
        sb.append("口径：对应位置命中；主指标至少 1 位定位，并列看三位全中\n\n");

        Score sd = runGame("福彩3D", HistoryDataLoader.load3d(), sb);
        sb.append('\n');
        Score pl3 = runGame("排列三", HistoryDataLoader.loadPl3(), sb);

        int legacyAny = sd.legacyAny + pl3.legacyAny;
        int habitAny = sd.habitAny + pl3.habitAny;
        int legacyFull = sd.legacyFull + pl3.legacyFull;
        int habitFull = sd.habitFull + pl3.habitFull;
        boolean keepLegacy = legacyAny > habitAny
                || (legacyAny == habitAny && legacyFull >= habitFull);

        sb.append("\n========== 汇总 ==========\n");
        sb.append(String.format(Locale.ROOT,
                "%-8s | 原至少1位 | 习至少1位 | 原全中 | 习全中%n", "彩种"));
        row(sb, sd);
        row(sb, pl3);
        sb.append(String.format(Locale.ROOT,
                "合计     | %4d/%d | %4d/%d | %4d | %4d%n",
                legacyAny, EVAL * 2, habitAny, EVAL * 2, legacyFull, habitFull));
        sb.append(keepLegacy ? "结论：保留【原近窗拟合】\n" : "结论：保留【习惯轮换】\n");

        Path out = Path.of("reports/danma_50_compare.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        sb.append("结果已写入: ").append(out.toAbsolutePath()).append('\n');
        System.out.println(sb);
    }

    private static void row(StringBuilder sb, Score s) {
        sb.append(String.format(Locale.ROOT,
                "%-8s | %4d/%d | %4d/%d | %4d | %4d%n",
                s.name, s.legacyAny, s.n, s.habitAny, s.n, s.legacyFull, s.habitFull));
    }

    static Score runGame(String name, List<Hm> all, StringBuilder out) {
        out.append("---------- ").append(name).append(" ----------\n");
        Score s = new Score(name, EVAL);
        if (all == null || all.size() < 120) {
            out.append("历史不足\n");
            return s;
        }
        int start = all.size() - EVAL;
        for (int i = start; i < all.size(); i++) {
            List<Hm> hist = all.subList(0, i);
            int[] act = RuleBasedDanMaUtils.digitsOf(all.get(i).toString());
            int[][] legacy = RuleBasedDanMaUtils.pickPositional(hist);
            int[][] habit = RuleBasedDanMaUtils.pickHabit(hist, null);
            int lh = countPos(legacy, act);
            int hh = countPos(habit, act);
            if (lh > 0) {
                s.legacyAny++;
            }
            if (lh == 3) {
                s.legacyFull++;
            }
            if (hh > 0) {
                s.habitAny++;
            }
            if (hh == 3) {
                s.habitFull++;
            }
        }
        out.append(String.format(Locale.ROOT,
                "原近窗拟合：至少1位=%d/%d (%.1f%%)  三位全中=%d%n",
                s.legacyAny, s.n, pct(s.legacyAny, s.n), s.legacyFull));
        out.append(String.format(Locale.ROOT,
                "习惯轮换  ：至少1位=%d/%d (%.1f%%)  三位全中=%d%n",
                s.habitAny, s.n, pct(s.habitAny, s.n), s.habitFull));
        return s;
    }

    private static int countPos(int[][] pick, int[] act) {
        boolean[] h = RuleBasedDanMaUtils.posHits(pick, act);
        if (h == null) {
            return 0;
        }
        int n = 0;
        for (boolean b : h) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    private static double pct(int hit, int n) {
        return n == 0 ? 0 : hit * 100.0 / n;
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
        final String name;
        final int n;
        int legacyAny;
        int habitAny;
        int legacyFull;
        int habitFull;

        Score(String name, int n) {
            this.name = name;
            this.n = n;
        }
    }
}
