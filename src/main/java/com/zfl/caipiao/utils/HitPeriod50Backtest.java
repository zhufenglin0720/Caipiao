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
 * 近 50 期回测：三码 10 注 / 过拟合 150 组 / 七码 / 胆码。
 * 主目标：命中期数最多（直选优先，组选/定位为辅）。
 */
public final class HitPeriod50Backtest {

    public static final int EVAL = 50;
    private static final int WARMUP = 40;

    private HitPeriod50Backtest() {
    }

    public static void main(String[] args) throws Exception {
        muteLogs();
        int eval = EVAL;
        if (args != null && args.length > 0) {
            eval = Integer.parseInt(args[0]);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("========== 近").append(eval).append("期命中期数回测 ==========\n");
        sb.append("三码=10注直/组  过拟合=").append(Overfit20PredictUtils.MAX_TICKETS)
                .append("组直/组  七码=三位全中  胆码=至少1位/三位全中\n");
        sb.append("去重：剔除与上一期完全一致的预测号码后再评估\n\n");

        Game sd = runOne("福彩3D", HistoryDataLoader.load3d(),
                RuleBasedPredictUtils.GameKind.SD_3D,
                Overfit20PredictUtils.GameKind.SD,
                RuleBasedDingWeiUtils.GameKind.SD_3D,
                RuleBasedDanMaUtils.GameKind.SD_3D, eval, sb);
        sb.append('\n');
        Game pl3 = runOne("排列三", HistoryDataLoader.loadPl3(),
                RuleBasedPredictUtils.GameKind.PL3,
                Overfit20PredictUtils.GameKind.PL3,
                RuleBasedDingWeiUtils.GameKind.PL3,
                RuleBasedDanMaUtils.GameKind.PL3, eval, sb);

        sb.append("\n========== 汇总（命中期数） ==========\n");
        sb.append(String.format(Locale.ROOT,
                "%-8s | 三码直 | 三码组 | 过拟合直 | 过拟合组 | 七码全中 | 胆码1位 | 胆码全中 | 上期复用%n",
                "彩种"));
        appendRow(sb, sd);
        appendRow(sb, pl3);
        sb.append(String.format(Locale.ROOT,
                "合计     | %4d | %4d | %6d | %6d | %6d | %6d | %6d%n",
                sd.sanmaZx + pl3.sanmaZx, sd.sanmaGrp + pl3.sanmaGrp,
                sd.ofZx + pl3.ofZx, sd.ofGrp + pl3.ofGrp,
                sd.dwFull + pl3.dwFull, sd.danAny + pl3.danAny, sd.danFull + pl3.danFull));

        Path out = Path.of("reports/hitperiod_50.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        sb.append("\n结果已写入: ").append(out.toAbsolutePath()).append('\n');
        System.out.println(sb);
    }

    private static void appendRow(StringBuilder sb, Game g) {
        sb.append(String.format(Locale.ROOT,
                "%-8s | %4d/%d | %4d/%d | %6d/%d | %6d/%d | %6d/%d | %6d/%d | %6d/%d | %d%n",
                g.name, g.sanmaZx, g.n, g.sanmaGrp, g.n, g.ofZx, g.n, g.ofGrp, g.n,
                g.dwFull, g.n, g.danAny, g.n, g.danFull, g.n, g.dup));
    }

    static Game runOne(String name, List<Hm> all,
                       RuleBasedPredictUtils.GameKind predKind,
                       Overfit20PredictUtils.GameKind ofKind,
                       RuleBasedDingWeiUtils.GameKind dwKind,
                       RuleBasedDanMaUtils.GameKind danKind,
                       int eval, StringBuilder out) {
        out.append("---------- ").append(name).append(" ----------\n");
        Game g = new Game(name);
        if (all == null || all.size() < WARMUP + eval + 10) {
            out.append("历史不足\n");
            return g;
        }
        out.append("历史期数=").append(all.size())
                .append(" 最近期=").append(all.get(all.size() - 1).getQh())
                .append(" 开奖=").append(pad3(all.get(all.size() - 1).toString())).append('\n');

        int start = all.size() - eval;
        List<HmCache.CompareDto> compares = new ArrayList<>();
        int warmFrom = Math.max(30, start - WARMUP);
        for (int i = warmFrom; i < start; i++) {
            step(all, i, predKind, ofKind, dwKind, danKind, compares, null);
        }

        g.n = eval;
        long t0 = System.currentTimeMillis();
        List<String> missOf = new ArrayList<>();
        List<String> missDw = new ArrayList<>();
        for (int i = start; i < all.size(); i++) {
            Step s = step(all, i, predKind, ofKind, dwKind, danKind, compares, g);
            if (!s.ofZx) {
                missOf.add(s.qh + "=" + s.actual + " last=" + s.lastReal
                        + (s.ofNear ? " ±1" : "") + (s.ofGrp ? " 组中" : " 未中"));
            }
            if (!s.dwFull) {
                missDw.add(s.qh + "=" + s.actual + " 缺" + s.dwMiss);
            }
            int done = i - start + 1;
            if (done % 10 == 0 || done == eval) {
                System.out.printf(Locale.ROOT,
                        "%s 进度 %d/%d | 三码直%d 过拟合直%d 七码%d 胆码%d 复用%d%n",
                        name, done, eval, g.sanmaZx, g.ofZx, g.dwFull, g.danAny, g.dup);
            }
        }
        long cost = System.currentTimeMillis() - t0;
        out.append(String.format(Locale.ROOT, "评估完成：%d期 耗时=%dms%n", g.n, cost));
        out.append(String.format(Locale.ROOT,
                "三码10注：直选=%d/%d  组选=%d/%d  与上期完全相同=%d%n",
                g.sanmaZx, g.n, g.sanmaGrp, g.n, g.dupSanma));
        out.append(String.format(Locale.ROOT,
                "过拟合%d组：直选=%d/%d  组选=%d/%d  池均=%.1f  与上期完全相同=%d%n",
                Overfit20PredictUtils.MAX_TICKETS, g.ofZx, g.n, g.ofGrp, g.n,
                g.ofSize / (double) g.n, g.dupOf));
        out.append(String.format(Locale.ROOT,
                "七码：全中=%d/%d  百=%d 十=%d 个=%d  与上期完全相同=%d%n",
                g.dwFull, g.n, g.dwPos[0], g.dwPos[1], g.dwPos[2], g.dupDw));
        out.append(String.format(Locale.ROOT,
                "胆码：至少1位=%d/%d  三位全中=%d/%d  与上期完全相同=%d%n",
                g.danAny, g.n, g.danFull, g.n, g.dupDan));
        if (!missOf.isEmpty()) {
            out.append("过拟合直选未中（最多12期）: ")
                    .append(String.join(" ; ", missOf.subList(0, Math.min(12, missOf.size()))))
                    .append('\n');
        }
        if (!missDw.isEmpty()) {
            out.append("七码未全中（最多12期）: ")
                    .append(String.join(" ; ", missDw.subList(0, Math.min(12, missDw.size()))))
                    .append('\n');
        }
        return g;
    }

    private static Step step(List<Hm> all, int i,
                             RuleBasedPredictUtils.GameKind predKind,
                             Overfit20PredictUtils.GameKind ofKind,
                             RuleBasedDingWeiUtils.GameKind dwKind,
                             RuleBasedDanMaUtils.GameKind danKind,
                             List<HmCache.CompareDto> compares, Game g) {
        List<Hm> hist = all.subList(0, i);
        String actual = pad3(all.get(i).toString());
        String lastReal = i > 0 ? pad3(all.get(i - 1).toString()) : "";
        String prevSanma = PrevPeriodDedup.lastField(compares, dto ->
                dto.getAiRecommendHm() != null && !dto.getAiRecommendHm().isBlank()
                        ? dto.getAiRecommendHm() : dto.getAiHm());
        String prevOf = PrevPeriodDedup.lastField(compares, HmCache.CompareDto::getAiOverfitHm);
        String prevDw = PrevPeriodDedup.lastField(compares, HmCache.CompareDto::getAiDingWeiHm);
        String prevDan = PrevPeriodDedup.lastField(compares, HmCache.CompareDto::getAiDanMaHm);

        Overfit20PredictUtils.PredictResult of = Overfit20PredictUtils.predictResult(hist, ofKind, compares);
        String ofCsv = of.poolCsv();
        String raw = RuleBasedPredictUtils.predict(hist, compares, predKind, ofCsv);
        String sanma = RecommendBetUtils.pickRecommendBets(raw, compares, ofCsv,
                predKind == RuleBasedPredictUtils.GameKind.PL3);
        String dw = RuleBasedDingWeiUtils.predict(hist, compares, dwKind);
        String dan = RuleBasedDanMaUtils.predict(hist, compares, danKind);

        Step s = new Step();
        s.qh = all.get(i).getQh();
        s.actual = actual;
        s.lastReal = lastReal;
        s.ofZx = containsZx(ofCsv, actual);
        s.ofGrp = containsGrp(ofCsv, actual);
        s.ofNear = Overfit20PredictUtils.isPlusMinus1NearMiss(of.pool, actual);
        boolean[] dwh = dingWeiHits(dw, actual);
        s.dwFull = dwh[0] && dwh[1] && dwh[2];
        s.dwMiss = (dwh[0] ? "" : "百") + (dwh[1] ? "" : "十") + (dwh[2] ? "" : "个");

        if (g != null) {
            if (containsZx(sanma, actual)) {
                g.sanmaZx++;
            }
            if (containsGrp(sanma, actual)) {
                g.sanmaGrp++;
            }
            if (s.ofZx) {
                g.ofZx++;
            }
            if (s.ofGrp) {
                g.ofGrp++;
            }
            g.ofSize += of.pool.size();
            if (s.dwFull) {
                g.dwFull++;
            }
            for (int p = 0; p < 3; p++) {
                if (dwh[p]) {
                    g.dwPos[p]++;
                }
            }
            boolean[] danH = RuleBasedDanMaUtils.posHits(dan, actual);
            if (danH != null) {
                int hp = 0;
                for (boolean b : danH) {
                    if (b) {
                        hp++;
                    }
                }
                if (hp > 0) {
                    g.danAny++;
                }
                if (hp == 3) {
                    g.danFull++;
                }
            }
            if (PrevPeriodDedup.sameTicketSet(sanma, prevSanma)) {
                g.dupSanma++;
                g.dup++;
            }
            if (PrevPeriodDedup.sameTicketSet(ofCsv, prevOf)) {
                g.dupOf++;
                g.dup++;
            }
            if (prevDw != null && !prevDw.isBlank() && prevDw.equals(dw)) {
                g.dupDw++;
                g.dup++;
            }
            if (prevDan != null && !prevDan.isBlank() && prevDan.equals(dan)) {
                g.dupDan++;
                g.dup++;
            }
        }

        compares.add(new HmCache.CompareDto()
                .setQh(all.get(i).getQh())
                .setAiHm(sanma)
                .setAiRecommendHm(sanma)
                .setAiFullHm(raw)
                .setAiOverfitHm(ofCsv)
                .setAiDingWeiHm(dw)
                .setAiDanMaHm(dan)
                .setRealHm(actual));
        while (compares.size() > RecommendBetUtils.HIT_LOOKBACK + 5) {
            compares.remove(0);
        }
        return s;
    }

    private static boolean containsZx(String pred, String actual) {
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

    private static boolean containsGrp(String pred, String actual) {
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

    private static boolean[] dingWeiHits(String dw, String actual) {
        boolean[] hit = new boolean[3];
        String[] parts = RuleBasedDingWeiUtils.parseParts(dw);
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
        }
    }

    static final class Game {
        final String name;
        int n;
        int sanmaZx;
        int sanmaGrp;
        int ofZx;
        int ofGrp;
        int ofSize;
        int dwFull;
        final int[] dwPos = new int[3];
        int danAny;
        int danFull;
        int dup;
        int dupSanma;
        int dupOf;
        int dupDw;
        int dupDan;

        Game(String name) {
            this.name = name;
        }
    }

    private static final class Step {
        String qh;
        String actual;
        String lastReal;
        boolean ofZx;
        boolean ofGrp;
        boolean ofNear;
        boolean dwFull;
        String dwMiss;
    }
}
