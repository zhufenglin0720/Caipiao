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
 * 近500期：200注大底固定；对比
 * - 基线：预测列表前10注
 * - 优化：RecommendBetUtils 新挑选逻辑的10注（与邮件一致）
 */
public class MailPickBacktest {

    private static final int EVAL_PERIODS = 500;
    private static final int WARMUP_MIN = 60;
    private static final int BET_LIMIT = 200;
    private static final int MAIL_N = 10;

    public static void main(String[] args) throws Exception {
        muteLogs();
        int eval = EVAL_PERIODS;
        if (args != null && args.length > 0) {
            eval = Integer.parseInt(args[0]);
        }

        RuleBasedPredictUtils.setBetLimitOverride(BET_LIMIT);
        StringBuilder sb = new StringBuilder();
        sb.append("========== 近").append(eval).append("期 邮件10注挑选对比（大底")
                .append(BET_LIMIT).append("注）==========\n");
        sb.append("基线：预测序前10注\n");
        sb.append("优化：RecommendBetUtils 密集位次带+密度打分 Top10\n\n");

        Result sd = runOne("福彩3D", HistoryDataLoader.load3d(), RuleBasedPredictUtils.GameKind.SD_3D, eval, sb);
        sb.append('\n');
        Result pl3 = runOne("排列三", HistoryDataLoader.loadPl3(), RuleBasedPredictUtils.GameKind.PL3, eval, sb);

        RuleBasedPredictUtils.setBetLimitOverride(0);

        sb.append("\n========== 汇总 ==========\n");
        sb.append(String.format(Locale.ROOT,
                "%-8s | 前10直选 | 优化10直选 | 前10组选 | 优化10组选 | 200注直选 | 200注组选%n", "彩种"));
        append(sb, sd);
        append(sb, pl3);

        Path out = Path.of("reports/mail_pick_compare_500.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        sb.append("\n结果已写入: ").append(out.toAbsolutePath()).append('\n');
        System.out.println(sb);
    }

    private static void append(StringBuilder sb, Result r) {
        sb.append(String.format(Locale.ROOT,
                "%-8s | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %4d/%d (%.1f%%)%n",
                r.name,
                r.baseZx, r.n, pct(r.baseZx, r.n),
                r.optZx, r.n, pct(r.optZx, r.n),
                r.baseGroup, r.n, pct(r.baseGroup, r.n),
                r.optGroup, r.n, pct(r.optGroup, r.n),
                r.dadiZx, r.n, pct(r.dadiZx, r.n),
                r.dadiGroup, r.n, pct(r.dadiGroup, r.n)));
    }

    private static double pct(int hit, int n) {
        return n == 0 ? 0 : hit * 100.0 / n;
    }

    private static Result runOne(String name, List<Hm> all, RuleBasedPredictUtils.GameKind kind,
                                 int evalPeriods, StringBuilder out) {
        out.append(">> ").append(name).append('\n');
        if (all == null || all.isEmpty()) {
            out.append("无数据\n");
            return Result.empty(name);
        }
        int start = all.size() - evalPeriods;
        if (start < WARMUP_MIN) {
            out.append("历史过短\n");
            return Result.empty(name);
        }

        List<HmCache.CompareDto> compares = new ArrayList<>();
        int warmStart = Math.max(WARMUP_MIN, start - RecommendBetUtils.HIT_LOOKBACK);
        for (int i = warmStart; i < start; i++) {
            String pred = RuleBasedPredictUtils.predict(all.subList(0, i), compares, kind);
            compares.add(new HmCache.CompareDto()
                    .setQh(all.get(i).getQh())
                    .setAiHm(pred == null ? "" : pred)
                    .setRealHm(pad3(all.get(i).toString())));
            trim(compares);
        }

        Result r = new Result(name);
        r.n = evalPeriods;
        long t0 = System.currentTimeMillis();
        for (int i = start; i < all.size(); i++) {
            String actual = pad3(all.get(i).toString());
            String pred = RuleBasedPredictUtils.predict(all.subList(0, i), compares, kind);
            String baseMail = takeTopN(pred, MAIL_N);
            String optMail = RecommendBetUtils.pickRecommendBets(pred, compares);

            if (isZxHit(baseMail, actual)) {
                r.baseZx++;
            }
            if (isGroupHit(baseMail, actual)) {
                r.baseGroup++;
            }
            if (isZxHit(optMail, actual)) {
                r.optZx++;
            }
            if (isGroupHit(optMail, actual)) {
                r.optGroup++;
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
                    .setAiRecommendHm(optMail)
                    .setRealHm(actual));
            trim(compares);

            int done = i - start + 1;
            if (done % 50 == 0 || done == evalPeriods) {
                System.out.printf("%s %d/%d 前10直%d 优化10直%d 大底直%d%n",
                        name, done, evalPeriods, r.baseZx, r.optZx, r.dadiZx);
            }
        }
        long cost = System.currentTimeMillis() - t0;
        out.append(String.format(Locale.ROOT,
                "%s 完成：%d期 耗时=%dms | 前10直选=%d/%d (%.2f%%) 组选=%d/%d (%.2f%%) | 优化10直选=%d/%d (%.2f%%) 组选=%d/%d (%.2f%%) | 大底直选=%d/%d (%.2f%%) 组选=%d/%d (%.2f%%)%n",
                name, r.n, cost,
                r.baseZx, r.n, pct(r.baseZx, r.n), r.baseGroup, r.n, pct(r.baseGroup, r.n),
                r.optZx, r.n, pct(r.optZx, r.n), r.optGroup, r.n, pct(r.optGroup, r.n),
                r.dadiZx, r.n, pct(r.dadiZx, r.n), r.dadiGroup, r.n, pct(r.dadiGroup, r.n)));
        return r;
    }

    private static void trim(List<HmCache.CompareDto> compares) {
        int max = RecommendBetUtils.HIT_LOOKBACK + 5;
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
        int n;
        int baseZx;
        int baseGroup;
        int optZx;
        int optGroup;
        int dadiZx;
        int dadiGroup;

        Result(String name) {
            this.name = name;
        }

        static Result empty(String name) {
            return new Result(name);
        }
    }
}
