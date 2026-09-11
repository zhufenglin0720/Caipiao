package com.zfl.caipiao.utils;

import com.zfl.caipiao.export.Hm;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * 当前 250 注大底：近窗命中 vs 20% 门槛，以及直选投入盈亏。
 */
public final class InvestWorthCheck {

    private static final int STAKE = 2;
    private static final int ZX_PRIZE = 1040;
    private static final int COST = Overfit20PredictUtils.MAX_TICKETS * STAKE;

    private InvestWorthCheck() {
    }

    public static void main(String[] args) {
        muteLogs();
        eval("福彩3D", HistoryDataLoader.load3d(), Overfit20PredictUtils.GameKind.SD);
        System.out.println();
        eval("排列三", HistoryDataLoader.loadPl3(), Overfit20PredictUtils.GameKind.PL3);
    }

    private static void eval(String name, List<Hm> all, Overfit20PredictUtils.GameKind kind) {
        int n = all.size();
        Hm last = all.get(n - 1);
        Overfit20PredictUtils.PredictResult next =
                Overfit20PredictUtils.predictResult(all, kind);
        int[] w20 = hits(all, kind, 20);
        int[] w50 = hits(all, kind, 50);
        int[] w99 = hits(all, kind, 99);

        System.out.printf(Locale.ROOT, "======== %s ========%n", name);
        System.out.printf(Locale.ROOT, "最新开奖期=%s 号码=%s  下期预测=%d注%n",
                last.getQh(), pad3(last.toString()), next.pool.size());
        System.out.printf(Locale.ROOT, "预测头10注: %s%n", next.displayCsv());
        line("近20期大底直选", w20);
        line("近50期大底直选", w50);
        line("近99期大底直选", w99);

        double p99 = w99[1] == 0 ? 0 : w99[0] * 1.0 / w99[1];
        double p20bench = 0.20;
        System.out.printf(Locale.ROOT,
                "20%%门槛: %s   回测%.1f%% %s 20%%%n",
                p99 + 1e-12 >= p20bench ? "命中率达标" : "命中率未达",
                p99 * 100, p99 >= p20bench ? ">=" : "<");
        System.out.printf(Locale.ROOT,
                "若买满%d注直选: 成本=%d元  直选奖金=%d元  保本命中率=%.1f%%%n",
                Overfit20PredictUtils.MAX_TICKETS, COST, ZX_PRIZE, 100.0 * COST / ZX_PRIZE);
        ev("按20%命中", p20bench);
        ev("按近99期命中", p99);
        ev("按近20期命中", w20[1] == 0 ? 0 : w20[0] * 1.0 / w20[1]);
        boolean worthRate = p99 >= p20bench;
        boolean worthEv = p99 * ZX_PRIZE > COST;
        System.out.printf(Locale.ROOT,
                "结论: 相对20%%命中率=%s；相对真金投入=%s（期望%s%.0f元/期）%n",
                worthRate ? "可以谈覆盖" : "偏弱",
                worthEv ? "值得买" : "不值得买",
                (p99 * ZX_PRIZE - COST) >= 0 ? "+" : "",
                p99 * ZX_PRIZE - COST);
    }

    private static void ev(String label, double p) {
        double e = p * ZX_PRIZE - COST;
        System.out.printf(Locale.ROOT, "%s: 期望奖金=%.0f  期望盈亏=%+.0f元/期%n",
                label, p * ZX_PRIZE, e);
    }

    private static void line(String label, int[] h) {
        double p = h[1] == 0 ? 0 : h[0] * 100.0 / h[1];
        System.out.printf(Locale.ROOT, "%s %d/%d (%.1f%%)  对比20%%: %s%n",
                label, h[0], h[1], p, p + 1e-9 >= 20 ? "高于" : "低于");
    }

    private static int[] hits(List<Hm> all, Overfit20PredictUtils.GameKind kind, int eval) {
        int start = all.size() - eval;
        int hit = 0;
        int n = 0;
        for (int i = start; i < all.size(); i++) {
            List<Hm> hist = all.subList(0, i);
            String act = pad3(all.get(i).toString());
            Overfit20PredictUtils.PredictResult r =
                    Overfit20PredictUtils.predictResult(hist, kind);
            n++;
            if (r.pool.contains(act)) {
                hit++;
            }
        }
        return new int[]{hit, n};
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
        } catch (Throwable ignored) {
        }
    }
}
