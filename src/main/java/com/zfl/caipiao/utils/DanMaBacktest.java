package com.zfl.caipiao.utils;

import com.zfl.caipiao.export.Hm;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 胆码近 500 期回测。
 * <p>
 * 主指标「号码命中」：三位胆码集合 ∩ 开奖号码集合 ≠ ∅，目标 ≥65%。
 * 回测走稳定覆盖内核（强制互异 + 因果选优）；与线上核心一致。
 */
public final class DanMaBacktest {

    private static final int EVAL_PERIODS = 500;
    private static final double UNION_TARGET = 65.0;

    private DanMaBacktest() {
    }

    public static void main(String[] args) throws Exception {
        muteLogs();
        int eval = EVAL_PERIODS;
        if (args != null && args.length > 0) {
            eval = Integer.parseInt(args[0]);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("========== 胆码近").append(eval).append("期回测 ==========\n");
        sb.append("主指标：号码命中（三位胆码 ∩ 开奖号 ≠ ∅）目标≥")
                .append((int) UNION_TARGET).append("%\n");
        sb.append("策略：多窗热号覆盖 + 强制三位互异 + 近窗因果选优\n");
        sb.append("说明：随机三位互异基线≈65.5%；重复选码会显著低于基线。\n\n");

        Result sd = runOne("福彩3D", HistoryDataLoader.load3d(), eval, sb);
        sb.append('\n');
        Result pl3 = runOne("排列三", HistoryDataLoader.loadPl3(), eval, sb);

        sb.append("\n========== 汇总 ==========\n");
        sb.append(String.format(Locale.ROOT, "%-8s | 号码命中 | 分位均 | 至少1位定位 | 三位全中 | 结果%n", "彩种"));
        appendRow(sb, sd);
        appendRow(sb, pl3);
        boolean pass = sd.pass && pl3.pass;
        sb.append(pass ? "\n【全部达标】号码命中≥65%\n" : "\n【存在未达标】\n");

        Path out = Path.of("reports/danma_backtest_500.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        sb.append("结果已写入: ").append(out.toAbsolutePath()).append('\n');
        System.out.println(sb);

        if (!pass) {
            System.exit(2);
        }
    }

    private static void appendRow(StringBuilder sb, Result r) {
        sb.append(String.format(Locale.ROOT,
                "%-8s | %4d/%d (%5.1f%%) | %5.2f%% | %4d/%d (%5.1f%%) | %3d/%d | %s%n",
                r.name, r.union, r.n, pct(r.union, r.n),
                r.posSum * 100.0 / (r.n * 3.0),
                r.anyPos, r.n, pct(r.anyPos, r.n),
                r.full, r.n,
                r.pass ? "达标" : "未达标"));
    }

    static Result runOne(String name, List<Hm> all, int eval, StringBuilder out) {
        out.append("---------- ").append(name).append(" ----------\n");
        if (all == null || all.isEmpty()) {
            out.append("无数据\n");
            return Result.fail(name, eval);
        }
        out.append("历史期数=").append(all.size())
                .append(" 最近期=").append(all.get(all.size() - 1).getQh()).append('\n');
        int start = all.size() - eval;
        if (start < 80) {
            out.append("历史过短\n");
            return Result.fail(name, eval);
        }

        Result r = new Result(name, eval);
        long t0 = System.currentTimeMillis();
        int distinctCount = 0;
        for (int i = start; i < all.size(); i++) {
            List<Hm> hist = all.subList(0, i);
            int[] pick = RuleBasedDanMaUtils.adaptCover(hist, 40);
            int[] act = RuleBasedDanMaUtils.digitsOf(all.get(i).toString());
            if (pick[0] != pick[1] && pick[0] != pick[2] && pick[1] != pick[2]) {
                distinctCount++;
            }
            if (RuleBasedDanMaUtils.isUnionHit(pick, act)) {
                r.union++;
            }
            int hp = 0;
            for (int p = 0; p < 3; p++) {
                if (pick[p] == act[p]) {
                    r.posSum++;
                    r.pos[p]++;
                    hp++;
                }
            }
            if (hp > 0) {
                r.anyPos++;
            }
            if (hp == 3) {
                r.full++;
            }
            if ((i - start + 1) % 100 == 0) {
                System.out.printf(Locale.ROOT, "%s 进度 %d/%d 号码命中=%d%n",
                        name, i - start + 1, eval, r.union);
            }
        }
        long cost = System.currentTimeMillis() - t0;
        r.pass = pct(r.union, r.n) >= UNION_TARGET;
        out.append(String.format(Locale.ROOT, "回测完成：%d期 耗时=%dms 三位互异=%d/%d%n",
                r.n, cost, distinctCount, r.n));
        out.append(String.format(Locale.ROOT,
                "号码命中=%d/%d (%.2f%%) %s  目标≥%.0f%%%n",
                r.union, r.n, pct(r.union, r.n), r.pass ? "达标" : "未达标", UNION_TARGET));
        out.append(String.format(Locale.ROOT,
                "分位命中：均=%.2f%% 百=%d 十=%d 个=%d | 至少1位定位=%d | 三位全中=%d%n",
                r.posSum * 100.0 / (r.n * 3.0), r.pos[0], r.pos[1], r.pos[2], r.anyPos, r.full));
        return r;
    }

    private static double pct(int hit, int n) {
        return n == 0 ? 0 : hit * 100.0 / n;
    }

    private static void muteLogs() {
        try {
            ch.qos.logback.classic.Logger root =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.setLevel(ch.qos.logback.classic.Level.ERROR);
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.zfl.caipiao.utils"))
                    .setLevel(ch.qos.logback.classic.Level.ERROR);
        } catch (Throwable ignored) {
        }
    }

    static final class Result {
        final String name;
        final int n;
        int union;
        int posSum;
        final int[] pos = new int[3];
        int anyPos;
        int full;
        boolean pass;

        Result(String name, int n) {
            this.name = name;
            this.n = n;
        }

        static Result fail(String name, int n) {
            Result r = new Result(name, n);
            r.pass = false;
            return r;
        }
    }
}
