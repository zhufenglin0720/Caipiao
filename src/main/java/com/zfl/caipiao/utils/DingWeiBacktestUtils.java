package com.zfl.caipiao.utils;

import com.alibaba.excel.EasyExcel;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 短期七码定位回测：3D / 排三分开专项。
 * 命中定义：百/十/个三位数字均落入对应 Top7（与前端 checkDingWeiMatch 一致）。
 * 评估近 30 期（贴合短期走势自适应）。
 */
public class DingWeiBacktestUtils {

    private static final int EVAL_PERIODS = 30;
    private static final int HIT_TARGET = 18;
    private static final int WARMUP_MIN = 40;
    private static final String DIR_3D = "D:\\彩票\\3D.xlsx";
    private static final String DIR_PL3 = "D:\\彩票\\排列三.xlsx";
    private static final Path OUT = Path.of("D:\\彩票\\dingwei_backtest_result.txt");

    public static void main(String[] args) throws Exception {
        muteLogs();
        StringBuilder sb = new StringBuilder();
        sb.append("说明：七码定位评估最近 ").append(EVAL_PERIODS).append(" 期（短期自适应）；")
                .append("3D/排三分专项；参考三位全中≥").append(HIT_TARGET).append("\n\n");
        runOne("福彩3D", DIR_3D, RuleBasedDingWeiUtils.GameKind.SD_3D, sb);
        sb.append('\n');
        runOne("排列三", DIR_PL3, RuleBasedDingWeiUtils.GameKind.PL3, sb);
        Files.writeString(OUT, sb.toString(), StandardCharsets.UTF_8);
        System.out.println(sb);
        System.out.println("结果已写入: " + OUT.toAbsolutePath());
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

    private static void runOne(String name, String path, RuleBasedDingWeiUtils.GameKind kind, StringBuilder out) {
        out.append("========== ").append(name).append(" 七码近").append(EVAL_PERIODS)
                .append("期回测 [").append(kind).append("] ==========\n");
        List<Hm> all = EasyExcel.read(path).head(Hm.class).doReadAllSync();
        if (all == null || all.isEmpty()) {
            out.append("读取失败或无数据: ").append(path).append('\n');
            return;
        }
        out.append("历史期数=").append(all.size())
                .append(" 最近期=").append(all.get(all.size() - 1)).append('\n');

        int start = all.size() - EVAL_PERIODS;
        if (start < WARMUP_MIN) {
            out.append("历史过短，无法回测\n");
            return;
        }

        int warmStart = Math.max(WARMUP_MIN, start - BiasSeedCorrector.LOOKBACK);
        List<HmCache.CompareDto> compares = new ArrayList<>();
        for (int i = warmStart; i < start; i++) {
            List<Hm> hist = all.subList(0, i);
            String pred = RuleBasedDingWeiUtils.predict(hist, compares, kind);
            String actual = pad3(all.get(i).toString());
            compares.add(new HmCache.CompareDto()
                    .setQh(all.get(i).getQh())
                    .setAiDingWeiHm(pred == null ? "" : pred)
                    .setRealHm(actual));
            if (compares.size() > BiasSeedCorrector.LOOKBACK) {
                compares.remove(0);
            }
        }

        int fullHit = 0;
        int[] posHit = new int[3];
        List<String> details = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int i = start; i < all.size(); i++) {
            List<Hm> hist = all.subList(0, i);
            Hm actualHm = all.get(i);
            String actual = pad3(actualHm.toString());
            String pred = RuleBasedDingWeiUtils.predict(hist, compares, kind);
            boolean[] hits = posHits(pred, actual);
            boolean allHit = hits[0] && hits[1] && hits[2];
            if (allHit) {
                fullHit++;
            }
            for (int p = 0; p < 3; p++) {
                if (hits[p]) {
                    posHit[p]++;
                }
            }
            details.add(String.format("期号=%s 开奖=%s 全中=%s 位命中[百,十,个]=[%s,%s,%s] | %s",
                    actualHm.getQh(), actual, allHit ? "是" : "否",
                    hits[0] ? "Y" : "N", hits[1] ? "Y" : "N", hits[2] ? "Y" : "N",
                    pred == null ? "(空)" : pred));

            compares.add(new HmCache.CompareDto()
                    .setAiDingWeiHm(pred == null ? "" : pred)
                    .setRealHm(actual)
                    .setQh(actualHm.getQh()));
            if (compares.size() > BiasSeedCorrector.LOOKBACK) {
                compares.remove(0);
            }
        }
        long cost = System.currentTimeMillis() - t0;
        int n = EVAL_PERIODS;
        out.append(String.format("回测完成：评估=%d期 耗时=%dms%n", n, cost));
        out.append(String.format("总命中：三位全中=%d/%d 百=%d 十=%d 个=%d%n",
                fullHit, n, posHit[0], posHit[1], posHit[2]));
        out.append("--- 逐期明细 ---\n");
        for (String d : details) {
            out.append(d).append('\n');
        }
        boolean pass = fullHit >= HIT_TARGET;
        out.append(String.format("--- 达标：三位全中近%d期≥%d → 实际=%d → %s%n",
                EVAL_PERIODS, HIT_TARGET, fullHit, pass ? "达标" : "未达标"));
        out.append(String.format("【汇总】%s 七码全中%d/%d %s%n",
                name, fullHit, n, pass ? "【达标】" : "【未达标】"));
    }

    private static boolean[] posHits(String dingWei, String actual) {
        boolean[] hit = new boolean[3];
        String[] parts = RuleBasedDingWeiUtils.parseParts(dingWei);
        if (parts == null || actual == null || actual.length() != 3) {
            return hit;
        }
        for (int pos = 0; pos < 3; pos++) {
            char target = actual.charAt(pos);
            for (String d : parts[pos].split(",")) {
                if (d.trim().length() == 1 && d.trim().charAt(0) == target) {
                    hit[pos] = true;
                    break;
                }
            }
        }
        return hit;
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
}
