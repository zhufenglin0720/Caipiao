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
 * 近 500 期综合命中率回测：
 * - 10注三码（RecommendBetUtils 邮件推荐）直选 / 组选
 * - 200注大底 直选 / 组选
 * - 七码定位 三位全中 / 分位命中
 * <p>
 * 数据优先 F:\彩票 Excel，其次项目 data/lottery 或 17500 文本。
 */
public class FullHitRateBacktest {

    private static final int EVAL_PERIODS = 500;
    private static final int WARMUP_MIN = 60;

    public static void main(String[] args) throws Exception {
        muteLogs();
        int eval = EVAL_PERIODS;
        if (args != null && args.length > 0) {
            eval = Integer.parseInt(args[0]);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("========== 近").append(eval).append("期综合命中率回测 ==========\n");
        sb.append("指标：10注三码(直/组)、200注大底(直/组)、七码(全中/分位)\n");
        sb.append("数据源优先：F:\\彩票 → D:\\彩票 → data/lottery → 17500\n\n");

        Result sd = runOne("福彩3D", HistoryDataLoader.load3d(),
                RuleBasedPredictUtils.GameKind.SD_3D, RuleBasedDingWeiUtils.GameKind.SD_3D, eval, sb);
        sb.append('\n');
        Result pl3 = runOne("排列三", HistoryDataLoader.loadPl3(),
                RuleBasedPredictUtils.GameKind.PL3, RuleBasedDingWeiUtils.GameKind.PL3, eval, sb);

        sb.append("\n========== 汇总表 ==========\n");
        sb.append(String.format(Locale.ROOT,
                "%-8s | 10注直选 | 10注组选 | 200注直选 | 200注组选 | 七码全中 | 七码百 | 七码十 | 七码个%n",
                "彩种"));
        appendSummaryRow(sb, sd);
        appendSummaryRow(sb, pl3);

        Path out1 = Path.of("data/lottery/hitrate_500_result.txt");
        Files.createDirectories(out1.getParent());
        Files.writeString(out1, sb.toString(), StandardCharsets.UTF_8);
        sb.append("\n结果已写入: ").append(out1.toAbsolutePath()).append('\n');

        // 仅当本地 Windows 彩票目录真实存在时再写一份
        for (String dir : List.of("F:\\彩票", "D:\\彩票")) {
            try {
                Path parent = Path.of(dir);
                if (!Files.isDirectory(parent)) {
                    continue;
                }
                Path p = parent.resolve("hitrate_500_result.txt");
                Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
                sb.append("结果已写入: ").append(p.toAbsolutePath()).append('\n');
            } catch (Exception ignored) {
                // 云端无本地盘时忽略
            }
        }
        System.out.println(sb);
    }

    private static void appendSummaryRow(StringBuilder sb, Result r) {
        sb.append(String.format(Locale.ROOT,
                "%-8s | %4d/%d (%5.1f%%) | %4d/%d (%5.1f%%) | %4d/%d (%5.1f%%) | %4d/%d (%5.1f%%) | %4d/%d (%5.1f%%) | %4d | %4d | %4d%n",
                r.name,
                r.mailZx, r.n, pct(r.mailZx, r.n),
                r.mailGroup, r.n, pct(r.mailGroup, r.n),
                r.dadiZx, r.n, pct(r.dadiZx, r.n),
                r.dadiGroup, r.n, pct(r.dadiGroup, r.n),
                r.dwFull, r.n, pct(r.dwFull, r.n),
                r.dwPos[0], r.dwPos[1], r.dwPos[2]));
    }

    private static double pct(int hit, int n) {
        return n == 0 ? 0 : hit * 100.0 / n;
    }

    private static Result runOne(String name, List<Hm> all,
                                 RuleBasedPredictUtils.GameKind predKind,
                                 RuleBasedDingWeiUtils.GameKind dwKind,
                                 int evalPeriods, StringBuilder out) {
        out.append("---------- ").append(name).append(" ----------\n");
        if (all == null || all.isEmpty()) {
            out.append("无数据\n");
            return Result.empty(name);
        }
        out.append("历史期数=").append(all.size())
                .append(" 最近期=").append(all.get(all.size() - 1).getQh())
                .append(" 开奖=").append(pad3(all.get(all.size() - 1).toString())).append('\n');

        int start = all.size() - evalPeriods;
        if (start < WARMUP_MIN) {
            out.append("历史过短，无法评估近").append(evalPeriods).append("期（需预热≥")
                    .append(WARMUP_MIN).append("）\n");
            return Result.empty(name);
        }

        List<HmCache.CompareDto> compares = new ArrayList<>();
        // 预热：用评估起点前若干期建立偏差/推荐位次样本
        int warmStart = Math.max(WARMUP_MIN, start - RecommendBetUtils.HIT_LOOKBACK);
        out.append("预热 ").append(start - warmStart).append(" 期…\n");
        for (int i = warmStart; i < start; i++) {
            List<Hm> hist = all.subList(0, i);
            String pred = RuleBasedPredictUtils.predict(hist, compares, predKind);
            String dw = RuleBasedDingWeiUtils.predict(hist, compares, dwKind);
            String actual = pad3(all.get(i).toString());
            compares.add(new HmCache.CompareDto()
                    .setQh(all.get(i).getQh())
                    .setAiHm(pred == null ? "" : pred)
                    .setAiDingWeiHm(dw == null ? "" : dw)
                    .setRealHm(actual));
            trimCompares(compares);
            if ((i - warmStart + 1) % 10 == 0) {
                System.out.printf("%s 预热进度 %d/%d%n", name, i - warmStart + 1, start - warmStart);
            }
        }

        Result r = new Result(name);
        r.n = evalPeriods;
        long t0 = System.currentTimeMillis();
        for (int i = start; i < all.size(); i++) {
            List<Hm> hist = all.subList(0, i);
            Hm actualHm = all.get(i);
            String actual = pad3(actualHm.toString());

            String pred = RuleBasedPredictUtils.predict(hist, compares, predKind);
            String dw = RuleBasedDingWeiUtils.predict(hist, compares, dwKind);
            String mailTop = RecommendBetUtils.pickRecommendBets(pred, compares);

            if (isZxHit(mailTop, actual)) {
                r.mailZx++;
            }
            if (isGroupHit(mailTop, actual)) {
                r.mailGroup++;
            }
            if (isZxHit(pred, actual)) {
                r.dadiZx++;
            }
            if (isGroupHit(pred, actual)) {
                r.dadiGroup++;
            }
            boolean[] pos = dingWeiPosHits(dw, actual);
            if (pos[0] && pos[1] && pos[2]) {
                r.dwFull++;
            }
            for (int p = 0; p < 3; p++) {
                if (pos[p]) {
                    r.dwPos[p]++;
                }
            }

            compares.add(new HmCache.CompareDto()
                    .setQh(actualHm.getQh())
                    .setAiHm(pred == null ? "" : pred)
                    .setAiDingWeiHm(dw == null ? "" : dw)
                    .setRealHm(actual));
            trimCompares(compares);

            int done = i - start + 1;
            if (done % 25 == 0 || done == evalPeriods) {
                long cost = System.currentTimeMillis() - t0;
                System.out.printf("%s 评估进度 %d/%d 耗时%dms | 10直%d 200直%d 七码%d%n",
                        name, done, evalPeriods, cost, r.mailZx, r.dadiZx, r.dwFull);
            }
        }
        long cost = System.currentTimeMillis() - t0;
        out.append(String.format(Locale.ROOT, "评估完成：%d期 耗时=%dms%n", r.n, cost));
        out.append(String.format(Locale.ROOT,
                "10注三码：直选=%d/%d (%.2f%%)  组选=%d/%d (%.2f%%)%n",
                r.mailZx, r.n, pct(r.mailZx, r.n), r.mailGroup, r.n, pct(r.mailGroup, r.n)));
        out.append(String.format(Locale.ROOT,
                "200注大底：直选=%d/%d (%.2f%%)  组选=%d/%d (%.2f%%)%n",
                r.dadiZx, r.n, pct(r.dadiZx, r.n), r.dadiGroup, r.n, pct(r.dadiGroup, r.n)));
        out.append(String.format(Locale.ROOT,
                "七码定位：全中=%d/%d (%.2f%%)  百=%d/%d (%.2f%%)  十=%d/%d (%.2f%%)  个=%d/%d (%.2f%%)%n",
                r.dwFull, r.n, pct(r.dwFull, r.n),
                r.dwPos[0], r.n, pct(r.dwPos[0], r.n),
                r.dwPos[1], r.n, pct(r.dwPos[1], r.n),
                r.dwPos[2], r.n, pct(r.dwPos[2], r.n)));
        return r;
    }

    private static void trimCompares(List<HmCache.CompareDto> compares) {
        int max = Math.max(BiasSeedCorrector.LOOKBACK, RecommendBetUtils.HIT_LOOKBACK) + 5;
        while (compares.size() > max) {
            compares.remove(0);
        }
    }

    private static String takeTopN(String pred, int n) {
        if (pred == null || pred.isBlank()) {
            return "";
        }
        String[] parts = pred.split(",");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String p : parts) {
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

    private static boolean[] dingWeiPosHits(String dingWei, String actual) {
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
            // ignore
        }
    }

    private static final class Result {
        final String name;
        int n;
        int mailZx;
        int mailGroup;
        int dadiZx;
        int dadiGroup;
        int dwFull;
        final int[] dwPos = new int[3];

        Result(String name) {
            this.name = name;
        }

        static Result empty(String name) {
            return new Result(name);
        }
    }
}
