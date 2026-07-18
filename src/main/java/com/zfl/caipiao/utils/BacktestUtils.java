package com.zfl.caipiao.utils;

import com.alibaba.excel.EasyExcel;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 近 100 期滚动回测：3D / 排三分开专项预测。
 * 目标：直选≥20、组选≥50；注数默认≤100，不够可扩到 150。
 */
public class BacktestUtils {

    private static final int EVAL_PERIODS = 100;
    private static final int ZX_TARGET = 20;
    private static final int GROUP_TARGET = 50;
    private static final int WARMUP_MIN = 80;
    private static final String DIR_3D = "D:\\彩票\\3D.xlsx";
    private static final String DIR_PL3 = "D:\\彩票\\排列三.xlsx";
    private static final Path OUT = Path.of("D:\\彩票\\backtest_result.txt");

    public static void main(String[] args) throws Exception {
        muteLogs();
        StringBuilder sb = new StringBuilder();
        sb.append("说明：评估最近 ").append(EVAL_PERIODS).append(" 期；注数≤")
                .append(RuleBasedPredictUtils.maxBetLimit())
                .append("；3D/排三分专项；目标直选≥").append(ZX_TARGET)
                .append(" 组选≥").append(GROUP_TARGET).append("\n\n");
        runOne("福彩3D", DIR_3D, RuleBasedPredictUtils.GameKind.SD_3D, sb);
        sb.append('\n');
        runOne("排列三", DIR_PL3, RuleBasedPredictUtils.GameKind.PL3, sb);
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

    private static void runOne(String name, String path, RuleBasedPredictUtils.GameKind kind, StringBuilder out) {
        out.append("========== ").append(name).append(" 近").append(EVAL_PERIODS)
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
            String pred = RuleBasedPredictUtils.predict(hist, compares, kind);
            String actual = pad3(all.get(i).toString());
            compares.add(new HmCache.CompareDto()
                    .setQh(all.get(i).getQh())
                    .setAiHm(pred == null ? "" : pred)
                    .setRealHm(actual));
            if (compares.size() > BiasSeedCorrector.LOOKBACK) {
                compares.remove(0);
            }
        }
        out.append("预热后偏差纠偏: ").append(BiasSeedCorrector.of(compares).describe()).append('\n');
        out.append("预热后").append(HitRankStats.of(toDigits(all.subList(0, start))).describe()).append('\n');

        int zxHit = 0, groupHit = 0;
        int maxBetN = 0;
        List<String> details = new ArrayList<>();

        long t0 = System.currentTimeMillis();
        for (int i = start; i < all.size(); i++) {
            List<Hm> hist = all.subList(0, i);
            Hm actualHm = all.get(i);
            String actual = pad3(actualHm.toString());

            BiasSeedCorrector periodSeed = BiasSeedCorrector.of(compares);
            String pred = RuleBasedPredictUtils.predict(hist, compares, kind);

            boolean zx = isZxHit(pred, actual);
            boolean group = isGroupHit(pred, actual);
            if (zx) {
                zxHit++;
            }
            if (group) {
                groupHit++;
            }
            int betN = pred == null || pred.isEmpty() ? 0 : pred.split(",").length;
            maxBetN = Math.max(maxBetN, betN);
            details.add(String.format("期号=%s 开奖=%s 直选命中=%s 组选命中=%s | 注数=%d 纠偏%s | 预测=%s",
                    actualHm.getQh(), actual, zx ? "是" : "否", group ? "是" : "否",
                    betN, Arrays.toString(periodSeed.primarySeed),
                    pred == null || pred.isEmpty() ? "(空)" : pred));

            compares.add(new HmCache.CompareDto()
                    .setAiHm(pred == null ? "" : pred)
                    .setRealHm(actual)
                    .setQh(actualHm.getQh()));
            if (compares.size() > BiasSeedCorrector.LOOKBACK) {
                compares.remove(0);
            }
        }
        long cost = System.currentTimeMillis() - t0;
        int n = EVAL_PERIODS;
        out.append(String.format("回测完成：评估=%d期 耗时=%dms 最大注数=%d%n", n, cost, maxBetN));
        out.append(String.format("总命中：直选=%d/%d 组选=%d/%d%n", zxHit, n, groupHit, n));

        out.append("--- 逐期明细（预测 / 开奖 / 是否命中）---\n");
        for (String d : details) {
            out.append(d).append('\n');
        }

        boolean pass = zxHit >= ZX_TARGET && groupHit >= GROUP_TARGET;
        out.append(String.format("--- 达标：直选≥%d 组选≥%d → 实际直选=%d 组选=%d → %s%n",
                ZX_TARGET, GROUP_TARGET, zxHit, groupHit, pass ? "达标" : "未达标"));
        out.append(String.format("【汇总】%s 直选%d/%d 组选%d/%d %s%n",
                name, zxHit, n, groupHit, n, pass ? "【达标】" : "【未达标】"));
    }

    private static int[][] toDigits(List<Hm> list) {
        int[][] d = new int[list.size()][3];
        for (int i = 0; i < list.size(); i++) {
            String s = pad3(list.get(i).toString());
            d[i][0] = s.charAt(0) - '0';
            d[i][1] = s.charAt(1) - '0';
            d[i][2] = s.charAt(2) - '0';
        }
        return d;
    }

    private static boolean isZxHit(String pred, String actual) {
        if (pred == null || actual == null || pred.isEmpty()) {
            return false;
        }
        for (String p : pred.split(",")) {
            if (pad3(p.trim()).equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGroupHit(String pred, String actual) {
        if (pred == null || actual == null || pred.isEmpty() || actual.length() != 3) {
            return false;
        }
        String key = sortedKey(actual);
        for (String p : pred.split(",")) {
            String t = pad3(p.trim());
            if (t.length() == 3 && sortedKey(t).equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static String sortedKey(String code) {
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
}
