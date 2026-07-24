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
 * 近 20 期过拟合五组 · 近 10 期命中回测。
 * <p>
 * 评估方式（过拟合）：用最近 20 期拟合出 5 组，再在这 20 期内最近 10 期上统计直选/组选。
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
        sb.append("========== 近20期过拟合五组 · 近10期回测 ==========\n");
        sb.append("规则：仅用近").append(Overfit20PredictUtils.WINDOW)
                .append("期拟合恰好").append(Overfit20PredictUtils.GROUP_COUNT)
                .append("组；在窗内近").append(Overfit20PredictUtils.EVAL_PERIODS)
                .append("期评估；目标直选≥").append(ZX_TARGET)
                .append("、组选≥").append(GROUP_TARGET).append('\n');
        sb.append("禁止硬编码开奖号码；权重/候选均由窗口数据动态计算。\n\n");

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
        if (all.size() < Overfit20PredictUtils.WINDOW) {
            out.append("历史不足 ").append(Overfit20PredictUtils.WINDOW).append(" 期\n");
            return Result.fail(name);
        }

        List<Hm> windowHm = all.subList(all.size() - Overfit20PredictUtils.WINDOW, all.size());
        List<String> window = Overfit20PredictUtils.toCodes(windowHm);
        List<String> pred = Overfit20PredictUtils.fitFiveGroups(window);
        out.append("拟合窗期号=").append(windowHm.get(0).getQh())
                .append(" ~ ").append(windowHm.get(windowHm.size() - 1).getQh()).append('\n');
        out.append("过拟合五组=").append(String.join(",", pred)).append('\n');

        int evalFrom = window.size() - Overfit20PredictUtils.EVAL_PERIODS;
        int zx = 0;
        int group = 0;
        List<String> details = new ArrayList<>();
        for (int i = evalFrom; i < window.size(); i++) {
            String actual = window.get(i);
            String qh = windowHm.get(i).getQh();
            boolean hitZx = Overfit20PredictUtils.isZxHit(pred, actual);
            boolean hitGp = Overfit20PredictUtils.isGroupHit(pred, actual);
            if (hitZx) {
                zx++;
            }
            if (hitGp) {
                group++;
            }
            details.add(String.format(Locale.ROOT,
                    "期号=%s 开奖=%s 直选=%s 组选=%s",
                    qh, actual, hitZx ? "是" : "否", hitGp ? "是" : "否"));
        }

        int n = Overfit20PredictUtils.EVAL_PERIODS;
        boolean pass = zx >= ZX_TARGET && group >= GROUP_TARGET;
        out.append(Overfit20PredictUtils.summarizeHits(zx, group, n)).append('\n');
        out.append("--- 近").append(n).append("期明细 ---\n");
        for (String d : details) {
            out.append(d).append('\n');
        }
        out.append(String.format(Locale.ROOT, "【%s】直选%d 组选%d %s%n",
                name, zx, group, pass ? "达标" : "未达标"));
        return new Result(name, zx, group, n, pass);
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
