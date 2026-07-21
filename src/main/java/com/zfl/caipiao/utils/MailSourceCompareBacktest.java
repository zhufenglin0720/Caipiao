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
 * 近500期：邮件10注挑选来源对比
 * A) 基于原始200注大底挑选
 * B) 基于组选去重后大底挑选
 */
public class MailSourceCompareBacktest {

    private static final int EVAL = 500;
    private static final int WARMUP_MIN = 60;

    public static void main(String[] args) throws Exception {
        muteLogs();
        RuleBasedPredictUtils.setBetLimitOverride(200);

        StringBuilder sb = new StringBuilder();
        sb.append("========== 近").append(EVAL).append("期 邮件10注来源对比 ==========\n");
        sb.append("A=基于原始200注大底挑选；B=基于组选去重后大底挑选\n");
        sb.append("历史位次统计与挑选来源一致（A用原始列表写aiHm，B用去重列表写aiHm）\n\n");

        Result sd = runBoth("福彩3D", HistoryDataLoader.load3d(), RuleBasedPredictUtils.GameKind.SD_3D, sb);
        sb.append('\n');
        Result pl3 = runBoth("排列三", HistoryDataLoader.loadPl3(), RuleBasedPredictUtils.GameKind.PL3, sb);

        RuleBasedPredictUtils.setBetLimitOverride(0);

        sb.append("\n========== 汇总 ==========\n");
        sb.append(String.format(Locale.ROOT,
                "%-8s | 来源 | 10注直选 | 10注组选 | 大底注数(均) | 大底直选 | 大底组选%n", "彩种"));
        append(sb, sd, true);
        append(sb, sd, false);
        append(sb, pl3, true);
        append(sb, pl3, false);

        sb.append("\n推荐：\n");
        recommend(sb, "福彩3D", sd);
        recommend(sb, "排列三", pl3);

        Path out = Path.of("reports/mail_source_compare_500.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        sb.append("\n结果已写入: ").append(out.toAbsolutePath()).append('\n');
        System.out.println(sb);
    }

    private static void recommend(StringBuilder sb, String name, Result r) {
        boolean preferRaw = score(r.rawMailZx, r.rawMailGroup) >= score(r.dedupMailZx, r.dedupMailGroup);
        sb.append(String.format(Locale.ROOT,
                "%s → 邮件10注建议基于【%s】大底（直选 %d vs %d，组选 %d vs %d）%n",
                name, preferRaw ? "原始200注" : "去重后",
                r.rawMailZx, r.dedupMailZx, r.rawMailGroup, r.dedupMailGroup));
    }

    private static int score(int zx, int group) {
        // 直选优先，其次组选
        return zx * 1000 + group;
    }

    private static void append(StringBuilder sb, Result r, boolean raw) {
        if (raw) {
            sb.append(String.format(Locale.ROOT,
                    "%-8s | 原始200 | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %6.1f | %4d/%d | %4d/%d%n",
                    r.name,
                    r.rawMailZx, r.n, pct(r.rawMailZx, r.n),
                    r.rawMailGroup, r.n, pct(r.rawMailGroup, r.n),
                    r.rawAvgSize,
                    r.rawDadiZx, r.n, r.rawDadiGroup, r.n));
        } else {
            sb.append(String.format(Locale.ROOT,
                    "%-8s | 去重后  | %4d/%d (%.1f%%) | %4d/%d (%.1f%%) | %6.1f | %4d/%d | %4d/%d%n",
                    r.name,
                    r.dedupMailZx, r.n, pct(r.dedupMailZx, r.n),
                    r.dedupMailGroup, r.n, pct(r.dedupMailGroup, r.n),
                    r.dedupAvgSize,
                    r.dedupDadiZx, r.n, r.dedupDadiGroup, r.n));
        }
    }

    private static double pct(int hit, int n) {
        return n == 0 ? 0 : hit * 100.0 / n;
    }

    private static Result runBoth(String name, List<Hm> all, RuleBasedPredictUtils.GameKind kind,
                                  StringBuilder out) {
        out.append(">> ").append(name).append('\n');
        Result r = new Result(name);
        if (all == null || all.isEmpty()) {
            return r;
        }
        int start = all.size() - EVAL;
        if (start < WARMUP_MIN) {
            return r;
        }

        List<HmCache.CompareDto> histRaw = new ArrayList<>();
        List<HmCache.CompareDto> histDedup = new ArrayList<>();
        int warmStart = Math.max(WARMUP_MIN, start - RecommendBetUtils.HIT_LOOKBACK);
        for (int i = warmStart; i < start; i++) {
            String pred = RuleBasedPredictUtils.predict(all.subList(0, i), histRaw, kind);
            String dedup = RecommendBetUtils.dedupeByGroupKeepFirst(pred);
            String actual = pad3(all.get(i).toString());
            histRaw.add(new HmCache.CompareDto().setAiHm(pred == null ? "" : pred).setRealHm(actual));
            histDedup.add(new HmCache.CompareDto().setAiHm(dedup).setRealHm(actual));
            trim(histRaw);
            trim(histDedup);
        }

        r.n = EVAL;
        long sizeRaw = 0;
        long sizeDedup = 0;
        for (int i = start; i < all.size(); i++) {
            String actual = pad3(all.get(i).toString());
            // 两边用各自 history 做连挂配额；预测本身对 PL3 不读 compares 打分
            String pred = RuleBasedPredictUtils.predict(all.subList(0, i), histRaw, kind);
            String dedup = RecommendBetUtils.dedupeByGroupKeepFirst(pred);
            int nRaw = count(pred);
            int nDedup = count(dedup);
            sizeRaw += nRaw;
            sizeDedup += nDedup;

            String mailRaw = RecommendBetUtils.pickRecommendBets(pred, histRaw);
            String mailDedup = RecommendBetUtils.pickRecommendBets(dedup, histDedup);

            if (isZx(mailRaw, actual)) {
                r.rawMailZx++;
            }
            if (isGroup(mailRaw, actual)) {
                r.rawMailGroup++;
            }
            if (isZx(mailDedup, actual)) {
                r.dedupMailZx++;
            }
            if (isGroup(mailDedup, actual)) {
                r.dedupMailGroup++;
            }
            if (isZx(pred, actual)) {
                r.rawDadiZx++;
            }
            if (isGroup(pred, actual)) {
                r.rawDadiGroup++;
            }
            if (isZx(dedup, actual)) {
                r.dedupDadiZx++;
            }
            if (isGroup(dedup, actual)) {
                r.dedupDadiGroup++;
            }

            histRaw.add(new HmCache.CompareDto()
                    .setAiHm(pred == null ? "" : pred)
                    .setAiRecommendHm(mailRaw)
                    .setRealHm(actual));
            histDedup.add(new HmCache.CompareDto()
                    .setAiHm(dedup)
                    .setAiRecommendHm(mailDedup)
                    .setRealHm(actual));
            trim(histRaw);
            trim(histDedup);

            int done = i - start + 1;
            if (done % 100 == 0 || done == EVAL) {
                System.out.printf("%s %d/%d raw邮直%d dedup邮直%d raw大底直%d dedup大底直%d%n",
                        name, done, EVAL, r.rawMailZx, r.dedupMailZx, r.rawDadiZx, r.dedupDadiZx);
            }
        }
        r.rawAvgSize = sizeRaw * 1.0 / EVAL;
        r.dedupAvgSize = sizeDedup * 1.0 / EVAL;

        out.append(String.format(Locale.ROOT,
                "原始200：邮件直选=%d/%d (%.2f%%) 组选=%d/%d (%.2f%%) | 大底均%.1f注 直选=%d 组选=%d%n",
                r.rawMailZx, r.n, pct(r.rawMailZx, r.n), r.rawMailGroup, r.n, pct(r.rawMailGroup, r.n),
                r.rawAvgSize, r.rawDadiZx, r.rawDadiGroup));
        out.append(String.format(Locale.ROOT,
                "去重后 ：邮件直选=%d/%d (%.2f%%) 组选=%d/%d (%.2f%%) | 大底均%.1f注 直选=%d 组选=%d%n",
                r.dedupMailZx, r.n, pct(r.dedupMailZx, r.n), r.dedupMailGroup, r.n, pct(r.dedupMailGroup, r.n),
                r.dedupAvgSize, r.dedupDadiZx, r.dedupDadiGroup));
        return r;
    }

    private static int count(String pred) {
        if (pred == null || pred.isBlank()) {
            return 0;
        }
        return pred.split(",").length;
    }

    private static void trim(List<HmCache.CompareDto> compares) {
        int max = RecommendBetUtils.HIT_LOOKBACK + 5;
        while (compares.size() > max) {
            compares.remove(0);
        }
    }

    private static boolean isZx(String pred, String actual) {
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

    private static boolean isGroup(String pred, String actual) {
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
        int rawMailZx;
        int rawMailGroup;
        int dedupMailZx;
        int dedupMailGroup;
        int rawDadiZx;
        int rawDadiGroup;
        int dedupDadiZx;
        int dedupDadiGroup;
        double rawAvgSize;
        double dedupAvgSize;

        Result(String name) {
            this.name = name;
        }
    }
}
