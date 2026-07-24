package com.zfl.caipiao.utils;

import com.zfl.caipiao.export.Hm;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 近 20 期五组策略自适应 · 近 10 期逐期回测。
 * <p>
 * 每期仅用该期之前近 20 期，预测前自动调参；命中按融合池统计。
 * 达标：直选 ≥ 2 且 组选 ≥ 4。
 */
public final class Overfit20Backtest {

    private static final int ZX_TARGET = 2;
    private static final int GROUP_TARGET = 4;

    private Overfit20Backtest() {
    }

    public static void main(String[] args) throws Exception {
        muteLogs();
        StringBuilder sb = new StringBuilder();
        sb.append("========== 近20期五组策略自适应 · 近10期逐期回测 ==========\n");
        sb.append("规则：每期仅用之前近").append(Overfit20PredictUtils.WINDOW)
                .append("期；预测前窗内因果校验自动调参；五策略融合池≤")
                .append(Overfit20PredictUtils.MAX_GROUPS)
                .append("组形态并展开直选；展示五组=五策略头名\n");
        sb.append("目标：直选≥").append(ZX_TARGET).append("、组选≥").append(GROUP_TARGET).append('\n');
        sb.append("禁止硬编码开奖号码。\n\n");

        Result sd = runOne("福彩3D", HistoryDataLoader.load3d(), sb);
        sb.append('\n');
        Result pl3 = runOne("排列三", HistoryDataLoader.loadPl3(), sb);

        sb.append("\n========== 汇总 ==========\n");
        sb.append(String.format(Locale.ROOT, "%-8s | 直选 | 组选 | 结果%n", "彩种"));
        appendRow(sb, sd);
        appendRow(sb, pl3);
        boolean allPass = sd.pass && pl3.pass;
        sb.append(allPass ? "\n【全部达标】\n" : "\n【存在未达标】\n");

        Path out = Path.of("reports/overfit20_backtest.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        sb.append("结果已写入: ").append(out.toAbsolutePath()).append('\n');
        System.out.println(sb);

        if (!allPass) {
            System.exit(2);
        }
    }

    private static void appendRow(StringBuilder sb, Result r) {
        sb.append(String.format(Locale.ROOT, "%-8s | %d/%d | %d/%d | %s%n",
                r.name, r.zx, r.n, r.group, r.n, r.pass ? "达标" : "未达标"));
    }

    static Result runOne(String name, List<Hm> all, StringBuilder out) {
        out.append("---------- ").append(name).append(" ----------\n");
        if (all == null || all.isEmpty()) {
            out.append("无数据\n");
            return Result.fail(name);
        }
        if (all.size() < Overfit20PredictUtils.WINDOW + Overfit20PredictUtils.EVAL_PERIODS) {
            out.append("历史不足\n");
            return Result.fail(name);
        }

        int eval = Overfit20PredictUtils.EVAL_PERIODS;
        int zx = 0, group = 0;
        List<String> details = new ArrayList<>();
        long t0 = System.currentTimeMillis();

        for (int i = all.size() - eval; i < all.size(); i++) {
            List<Hm> hist = all.subList(Math.max(0, i - Overfit20PredictUtils.WINDOW), i);
            Overfit20PredictUtils.PredictResult pred = Overfit20PredictUtils.predictResult(hist);
            String actual = Overfit20PredictUtils.pad3(all.get(i).toString());
            String qh = all.get(i).getQh();

            boolean hitZx = Overfit20PredictUtils.isZxHit(pred.pool, actual);
            boolean hitGp = Overfit20PredictUtils.isGroupHit(pred.pool, actual);
            if (hitZx) {
                zx++;
            }
            if (hitGp) {
                group++;
            }
            details.add(String.format(Locale.ROOT,
                    "期号=%s 展示五组=%s 开奖=%s 直选=%s 组选=%s | 池=%d注 %s",
                    qh, pred.displayCsv(), actual,
                    hitZx ? "是" : "否", hitGp ? "是" : "否",
                    pred.pool.size(), pred.tune));
        }

        long cost = System.currentTimeMillis() - t0;
        boolean pass = zx >= ZX_TARGET && group >= GROUP_TARGET;
        out.append(String.format(Locale.ROOT, "回测完成：评估=%d期 耗时=%dms%n", eval, cost));
        out.append(Overfit20PredictUtils.summarizeHits(zx, group, eval)).append('\n');
        out.append("--- 逐期明细（展示五组 / 开奖 / 融合池命中）---\n");
        for (String d : details) {
            out.append(d).append('\n');
        }
        out.append(String.format(Locale.ROOT, "【%s】直选%d 组选%d %s%n",
                name, zx, group, pass ? "达标" : "未达标"));
        return new Result(name, zx, group, eval, pass);
    }

    private static void muteLogs() {
        try {
            ch.qos.logback.classic.Logger root =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.setLevel(ch.qos.logback.classic.Level.WARN);
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.zfl.caipiao.utils"))
                    .setLevel(ch.qos.logback.classic.Level.WARN);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    static final class Result {
        final String name;
        final int zx;
        final int group;
        final int n;
        final boolean pass;

        Result(String name, int zx, int group, int n, boolean pass) {
            this.name = name;
            this.zx = zx;
            this.group = group;
            this.n = n;
            this.pass = pass;
        }

        static Result fail(String name) {
            return new Result(name, 0, 0, Overfit20PredictUtils.EVAL_PERIODS, false);
        }
    }
}
