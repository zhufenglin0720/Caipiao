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
 * main 最新逻辑：分别回测 100 / 300 / 500 期，输出
 * 200注大底直选、10注邮件直选、10注邮件组选、七码全中。
 */
public class MultiWindowHitRateBacktest {

    private static final int WARMUP_MIN = 60;
    private static final int[] WINDOWS = {100, 300, 500};

    public static void main(String[] args) throws Exception {
        muteLogs();
        RuleBasedPredictUtils.setBetLimitOverride(200);

        List<Hm> sd = HistoryDataLoader.load3d();
        List<Hm> pl3 = HistoryDataLoader.loadPl3();

        StringBuilder sb = new StringBuilder();
        sb.append("========== main 最新代码 多窗口回测 ==========\n");
        sb.append("指标：200注大底直选 | 10注邮件直选 | 10注邮件组选 | 七码全中\n");
        sb.append("邮件挑选：RecommendBetUtils（密集位次带）\n\n");

        List<Result> results = new ArrayList<>();
        for (int eval : WINDOWS) {
            System.out.printf("开始窗口 %d 期…%n", eval);
            Result r3d = runOne("福彩3D", sd, RuleBasedPredictUtils.GameKind.SD_3D,
                    RuleBasedDingWeiUtils.GameKind.SD_3D, eval);
            Result rPl3 = runOne("排列三", pl3, RuleBasedPredictUtils.GameKind.PL3,
                    RuleBasedDingWeiUtils.GameKind.PL3, eval);
            results.add(r3d);
            results.add(rPl3);

            sb.append("---------- 近 ").append(eval).append(" 期 ----------\n");
            appendDetail(sb, r3d);
            appendDetail(sb, rPl3);
            sb.append('\n');
            System.out.printf("完成窗口 %d 期%n", eval);
        }

        RuleBasedPredictUtils.setBetLimitOverride(0);

        sb.append("========== 汇总表 ==========\n");
        sb.append(String.format(Locale.ROOT,
                "%-8s | %4s | 200注大底直选 | 10注邮件直选 | 10注邮件组选 | 七码全中%n",
                "彩种", "期数"));
        for (Result r : results) {
            sb.append(String.format(Locale.ROOT,
                    "%-8s | %4d | %4d/%d (%5.1f%%) | %4d/%d (%5.1f%%) | %4d/%d (%5.1f%%) | %4d/%d (%5.1f%%)%n",
                    r.name, r.n,
                    r.dadiZx, r.n, pct(r.dadiZx, r.n),
                    r.mailZx, r.n, pct(r.mailZx, r.n),
                    r.mailGroup, r.n, pct(r.mailGroup, r.n),
                    r.dwFull, r.n, pct(r.dwFull, r.n)));
        }

        Path out = Path.of("reports/multi_window_hitrate.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        sb.append("\n结果已写入: ").append(out.toAbsolutePath()).append('\n');
        System.out.println(sb);
    }

    private static void appendDetail(StringBuilder sb, Result r) {
        sb.append(String.format(Locale.ROOT,
                "%-8s | 200注大底直选=%d/%d (%.1f%%) | 10注邮件直选=%d/%d (%.1f%%) | 10注邮件组选=%d/%d (%.1f%%) | 七码全中=%d/%d (%.1f%%)%n",
                r.name,
                r.dadiZx, r.n, pct(r.dadiZx, r.n),
                r.mailZx, r.n, pct(r.mailZx, r.n),
                r.mailGroup, r.n, pct(r.mailGroup, r.n),
                r.dwFull, r.n, pct(r.dwFull, r.n)));
    }

    private static double pct(int hit, int n) {
        return n == 0 ? 0 : hit * 100.0 / n;
    }

    private static Result runOne(String name, List<Hm> all,
                                 RuleBasedPredictUtils.GameKind predKind,
                                 RuleBasedDingWeiUtils.GameKind dwKind,
                                 int evalPeriods) {
        Result r = new Result(name);
        if (all == null || all.isEmpty()) {
            return r;
        }
        int start = all.size() - evalPeriods;
        if (start < WARMUP_MIN) {
            return r;
        }

        List<HmCache.CompareDto> compares = new ArrayList<>();
        int warmStart = Math.max(WARMUP_MIN, start - RecommendBetUtils.HIT_LOOKBACK);
        for (int i = warmStart; i < start; i++) {
            String pred = RuleBasedPredictUtils.predict(all.subList(0, i), compares, predKind);
            String dw = RuleBasedDingWeiUtils.predict(all.subList(0, i), compares, dwKind);
            compares.add(new HmCache.CompareDto()
                    .setQh(all.get(i).getQh())
                    .setAiHm(pred == null ? "" : pred)
                    .setAiDingWeiHm(dw == null ? "" : dw)
                    .setRealHm(pad3(all.get(i).toString())));
            trim(compares);
        }

        r.n = evalPeriods;
        for (int i = start; i < all.size(); i++) {
            List<Hm> hist = all.subList(0, i);
            String actual = pad3(all.get(i).toString());
            String pred = RuleBasedPredictUtils.predict(hist, compares, predKind);
            String dw = RuleBasedDingWeiUtils.predict(hist, compares, dwKind);
            String mail = RecommendBetUtils.pickRecommendBets(pred, compares);

            if (isZxHit(mail, actual)) {
                r.mailZx++;
            }
            if (isGroupHit(mail, actual)) {
                r.mailGroup++;
            }
            if (isZxHit(pred, actual)) {
                r.dadiZx++;
            }
            if (dingWeiFull(dw, actual)) {
                r.dwFull++;
            }

            compares.add(new HmCache.CompareDto()
                    .setQh(all.get(i).getQh())
                    .setAiHm(pred == null ? "" : pred)
                    .setAiDingWeiHm(dw == null ? "" : dw)
                    .setAiRecommendHm(mail)
                    .setRealHm(actual));
            trim(compares);

            int done = i - start + 1;
            if (done % 100 == 0 || done == evalPeriods) {
                System.out.printf("%s[%d] %d/%d 大底直%d 邮直%d 邮组%d 七码%d%n",
                        name, evalPeriods, done, evalPeriods, r.dadiZx, r.mailZx, r.mailGroup, r.dwFull);
            }
        }
        return r;
    }

    private static void trim(List<HmCache.CompareDto> compares) {
        int max = Math.max(BiasSeedCorrector.LOOKBACK, RecommendBetUtils.HIT_LOOKBACK) + 5;
        while (compares.size() > max) {
            compares.remove(0);
        }
    }

    private static boolean dingWeiFull(String dw, String actual) {
        if (dw == null || actual == null || actual.length() != 3) {
            return false;
        }
        String[] parts = RuleBasedDingWeiUtils.parseParts(dw);
        if (parts == null || parts.length < 3) {
            return false;
        }
        for (int p = 0; p < 3; p++) {
            if (parts[p] == null || parts[p].indexOf(actual.charAt(p)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isZxHit(String pred, String actual) {
        if (pred == null || actual == null || pred.isEmpty()) {
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

    private static boolean isGroupHit(String pred, String actual) {
        if (pred == null || actual == null || pred.isEmpty()) {
            return false;
        }
        String key = sortedKey(pad3(actual));
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

    private static final class Result {
        final String name;
        int n;
        int dadiZx;
        int mailZx;
        int mailGroup;
        int dwFull;

        Result(String name) {
            this.name = name;
        }
    }
}
