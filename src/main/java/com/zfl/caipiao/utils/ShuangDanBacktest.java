package com.zfl.caipiao.utils;

import com.zfl.caipiao.export.Hm;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** 双胆近 N 期 any-group 命中回测 */
public final class ShuangDanBacktest {
    private ShuangDanBacktest() {
    }

    public static void main(String[] args) throws Exception {
        int eval = args != null && args.length > 0 ? Integer.parseInt(args[0]) : 50;
        StringBuilder sb = new StringBuilder();
        sb.append("========== 双胆近").append(eval).append("期回测 ==========\n");
        sb.append("指标：至少 1 组两码都出现在开奖中\n\n");
        Result sd = run("福彩3D", HistoryDataLoader.load3d(), eval);
        Result pl = run("排列三", HistoryDataLoader.loadPl3(), eval);
        append(sb, sd);
        append(sb, pl);
        sb.append(String.format(Locale.ROOT, "合计 any=%d/%d (%.1f%%)  两组+=%d  三组全中=%d%n",
                sd.any + pl.any, sd.n + pl.n,
                100.0 * (sd.any + pl.any) / Math.max(1, sd.n + pl.n),
                sd.two + pl.two, sd.three + pl.three));
        Path out = Path.of("reports/shuangdan_backtest_" + eval + ".txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println(sb);
        System.out.println("写入 " + out.toAbsolutePath());
    }

    private static void append(StringBuilder sb, Result r) {
        sb.append(String.format(Locale.ROOT,
                "%s: any=%d/%d (%.1f%%)  ≥2组=%d  三组全中=%d%n",
                r.name, r.any, r.n, 100.0 * r.any / Math.max(1, r.n), r.two, r.three));
    }

    static Result run(String name, List<Hm> all, int eval) {
        Result r = new Result(name);
        if (all == null || all.size() < 80 + eval) {
            return r;
        }
        int start = all.size() - eval;
        for (int i = start; i < all.size(); i++) {
            String pred = RuleBasedShuangDanUtils.predict(all.subList(0, i), name.contains("排"));
            String actual = pad3(all.get(i).toString());
            boolean[] hits = RuleBasedShuangDanUtils.groupHits(pred, actual);
            r.n++;
            if (hits == null) {
                continue;
            }
            int c = 0;
            for (boolean h : hits) {
                if (h) {
                    c++;
                }
            }
            if (c >= 1) {
                r.any++;
            }
            if (c >= 2) {
                r.two++;
            }
            if (c >= 3) {
                r.three++;
            }
        }
        return r;
    }

    private static String pad3(String s) {
        if (s == null) {
            return "000";
        }
        String t = s.trim();
        return t.length() >= 3 ? t.substring(t.length() - 3) : "0".repeat(3 - t.length()) + t;
    }

    static final class Result {
        final String name;
        int n;
        int any;
        int two;
        int three;

        Result(String name) {
            this.name = name;
        }
    }
}
