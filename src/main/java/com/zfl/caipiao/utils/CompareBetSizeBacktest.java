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
 * 近500期注数对比回测：
 * 原始：150注大底 + 前10注
 * 优化：200注大底 + 前7注（邮件推送）
 */
public class CompareBetSizeBacktest {

    private static final int EVAL_PERIODS = 500;
    private static final int WARMUP_MIN = 60;

    public static void main(String[] args) throws Exception {
        muteLogs();
        int eval = EVAL_PERIODS;
        if (args != null && args.length > 0) {
            eval = Integer.parseInt(args[0]);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("========== 近").append(eval).append("期 注数对比回测 ==========\n");
        sb.append("原始：150注大底 + 前10注\n");
        sb.append("优化：200注大底 + 前7注（邮件）\n\n");

        List<Hm> sd = HistoryDataLoader.load3d();
        List<Hm> pl3 = HistoryDataLoader.loadPl3();

        sb.append("----- 原始 150注 / 10注 -----\n");
        Result sdOld = runConfig("福彩3D", sd, RuleBasedPredictUtils.GameKind.SD_3D, 150, 10, eval, sb);
        Result pl3Old = runConfig("排列三", pl3, RuleBasedPredictUtils.GameKind.PL3, 150, 10, eval, sb);

        sb.append("\n----- 优化 200注 / 7注 -----\n");
        Result sdNew = runConfig("福彩3D", sd, RuleBasedPredictUtils.GameKind.SD_3D, 200, 7, eval, sb);
        Result pl3New = runConfig("排列三", pl3, RuleBasedPredictUtils.GameKind.PL3, 200, 7, eval, sb);

        RuleBasedPredictUtils.setBetLimitOverride(0);

        sb.append("\n========== 对比汇总 ==========\n");
        sb.append(String.format(Locale.ROOT,
                "%-8s | 配置 | 邮件N注直选 | 邮件N注组选 | 大底直选 | 大底组选 | 大底注数%n", "彩种"));
        appendRow(sb, sdOld);
        appendRow(sb, sdNew);
        appendRow(sb, pl3Old);
        appendRow(sb, pl3New);

        sb.append("\n========== 并排对照 ==========\n");
        sb.append(String.format(Locale.ROOT,
                "%-8s | 10注直选 | 7注直选 | 150注直选 | 200注直选 | 10注组选 | 7注组选 | 150注组选 | 200注组选%n",
                "彩种"));
        sb.append(String.format(Locale.ROOT,
                "%-8s | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%)%n",
                "福彩3D",
                sdOld.mailZx, sdOld.n, pct(sdOld.mailZx, sdOld.n),
                sdNew.mailZx, sdNew.n, pct(sdNew.mailZx, sdNew.n),
                sdOld.dadiZx, sdOld.n, pct(sdOld.dadiZx, sdOld.n),
                sdNew.dadiZx, sdNew.n, pct(sdNew.dadiZx, sdNew.n),
                sdOld.mailGroup, sdOld.n, pct(sdOld.mailGroup, sdOld.n),
                sdNew.mailGroup, sdNew.n, pct(sdNew.mailGroup, sdNew.n),
                sdOld.dadiGroup, sdOld.n, pct(sdOld.dadiGroup, sdOld.n),
                sdNew.dadiGroup, sdNew.n, pct(sdNew.dadiGroup, sdNew.n)));
        sb.append(String.format(Locale.ROOT,
                "%-8s | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%)%n",
                "排列三",
                pl3Old.mailZx, pl3Old.n, pct(pl3Old.mailZx, pl3Old.n),
                pl3New.mailZx, pl3New.n, pct(pl3New.mailZx, pl3New.n),
                pl3Old.dadiZx, pl3Old.n, pct(pl3Old.dadiZx, pl3Old.n),
                pl3New.dadiZx, pl3New.n, pct(pl3New.dadiZx, pl3New.n),
                pl3Old.mailGroup, pl3Old.n, pct(pl3Old.mailGroup, pl3Old.n),
                pl3New.mailGroup, pl3New.n, pct(pl3New.mailGroup, pl3New.n),
                pl3Old.dadiGroup, pl3Old.n, pct(pl3Old.dadiGroup, pl3Old.n),
                pl3New.dadiGroup, pl3New.n, pct(pl3New.dadiGroup, pl3New.n)));

        Path out = Path.of("reports/bet_size_compare_500.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        Files.createDirectories(Path.of("data/lottery"));
        Files.writeString(Path.of("data/lottery/bet_size_compare_500.txt"), sb.toString(), StandardCharsets.UTF_8);
        sb.append("\n结果已写入: ").append(out.toAbsolutePath()).append('\n');
        System.out.println(sb);
    }

    private static void appendRow(StringBuilder sb, Result r) {
        sb.append(String.format(Locale.ROOT,
                "%-8s | %s | %4d/%d (%5.1f%%) | %4d/%d (%5.1f%%) | %4d/%d (%5.1f%%) | %4d/%d (%5.1f%%) | %d%n",
                r.name, r.label,
                r.mailZx, r.n, pct(r.mailZx, r.n),
                r.mailGroup, r.n, pct(r.mailGroup, r.n),
                r.dadiZx, r.n, pct(r.dadiZx, r.n),
                r.dadiGroup, r.n, pct(r.dadiGroup, r.n),
                r.betLimit));
    }

    private static double pct(int hit, int n) {
        return n == 0 ? 0 : hit * 100.0 / n;
    }

    private static Result runConfig(String name, List<Hm> all, RuleBasedPredictUtils.GameKind kind,
                                    int betLimit, int mailN, int evalPeriods, StringBuilder out) {
        RuleBasedPredictUtils.setBetLimitOverride(betLimit);
        out.append(String.format(Locale.ROOT, ">> %s 大底=%d 邮件前%d注%n", name, betLimit, mailN));
        if (all == null || all.isEmpty()) {
            out.append("无数据\n");
            return Result.empty(name, betLimit, mailN);
        }
        int start = all.size() - evalPeriods;
        if (start < WARMUP_MIN) {
            out.append("历史过短\n");
            return Result.empty(name, betLimit, mailN);
        }

        List<HmCache.CompareDto> compares = new ArrayList<>();
        int warmStart = Math.max(WARMUP_MIN, start - 25);
        for (int i = warmStart; i < start; i++) {
            String pred = RuleBasedPredictUtils.predict(all.subList(0, i), compares, kind);
            compares.add(new HmCache.CompareDto()
                    .setQh(all.get(i).getQh())
                    .setAiHm(pred == null ? "" : pred)
                    .setRealHm(pad3(all.get(i).toString())));
            trim(compares);
        }

        Result r = new Result(name, betLimit, mailN);
        r.n = evalPeriods;
        long t0 = System.currentTimeMillis();
        int maxBetN = 0;
        for (int i = start; i < all.size(); i++) {
            String actual = pad3(all.get(i).toString());
            String pred = RuleBasedPredictUtils.predict(all.subList(0, i), compares, kind);
            int betN = pred == null || pred.isEmpty() ? 0 : pred.split(",").length;
            maxBetN = Math.max(maxBetN, betN);
            String mail = takeTopN(pred, mailN);

            if (isZxHit(mail, actual)) {
                r.mailZx++;
            }
            if (isGroupHit(mail, actual)) {
                r.mailGroup++;
            }
            if (isZxHit(pred, actual)) {
                r.dadiZx++;
            }
            if (isGroupHit(pred, actual)) {
                r.dadiGroup++;
            }

            compares.add(new HmCache.CompareDto()
                    .setQh(all.get(i).getQh())
                    .setAiHm(pred == null ? "" : pred)
                    .setRealHm(actual));
            trim(compares);

            int done = i - start + 1;
            if (done % 50 == 0 || done == evalPeriods) {
                System.out.printf("%s[%d/%d] %d/%d 邮件直%d 大底直%d 最大注数=%d%n",
                        name, betLimit, mailN, done, evalPeriods, r.mailZx, r.dadiZx, maxBetN);
            }
        }
        long cost = System.currentTimeMillis() - t0;
        out.append(String.format(Locale.ROOT,
                "%s 完成：%d期 耗时=%dms 最大注数=%d | 邮件前%d注 直选=%d/%d (%.2f%%) 组选=%d/%d (%.2f%%) | 大底直选=%d/%d (%.2f%%) 组选=%d/%d (%.2f%%)%n",
                name, r.n, cost, maxBetN, mailN,
                r.mailZx, r.n, pct(r.mailZx, r.n), r.mailGroup, r.n, pct(r.mailGroup, r.n),
                r.dadiZx, r.n, pct(r.dadiZx, r.n), r.dadiGroup, r.n, pct(r.dadiGroup, r.n)));
        return r;
    }

    private static void trim(List<HmCache.CompareDto> compares) {
        int max = BiasSeedCorrector.LOOKBACK + 5;
        while (compares.size() > max) {
            compares.remove(0);
        }
    }

    private static String takeTopN(String pred, int n) {
        if (pred == null || pred.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String p : pred.split(",")) {
            String t = pad3(p.trim());
            if (t.length() != 3) {
                continue;
            }
            if (count > 0) {
                sb.append(',');
            }
            sb.append(t);
            count++;
            if (count >= n) {
                break;
            }
        }
        return sb.toString();
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
        final int betLimit;
        final int mailN;
        final String label;
        int n;
        int mailZx;
        int mailGroup;
        int dadiZx;
        int dadiGroup;

        Result(String name, int betLimit, int mailN) {
            this.name = name;
            this.betLimit = betLimit;
            this.mailN = mailN;
            this.label = betLimit + "注/" + mailN + "注";
        }

        static Result empty(String name, int betLimit, int mailN) {
            return new Result(name, betLimit, mailN);
        }
    }
}
