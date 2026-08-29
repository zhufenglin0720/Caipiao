package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 出号习惯修复自检：胆码跟期变化、七码含热号、过拟合钉近 3 期。
 */
public final class HabitPredictCheck {

    private HabitPredictCheck() {
    }

    public static void main(String[] args) {
        int fail = 0;
        List<String> codes = new ArrayList<>(Arrays.asList(
                "164", "900", "255", "817", "079", "012", "495", "431", "826", "695",
                "373", "032", "673", "617", "830", "689", "086", "878", "061", "718",
                "126", "509", "248", "920", "030", "522", "477", "833", "202", "215",
                "112", "896", "848", "095", "263", "255", "495", "268", "181", "291"));
        fail += check("胆码两期不同", danMaChanges(codes));
        fail += check("七码含上期同位或邻号", dingWeiNearLast(codes));
        fail += check("过拟合含近3期本体或邻号", overfitNearRecent(codes));
        fail += check("过拟合150组", overfitSize150(codes));
        fail += check("过拟合不含上期开奖号", overfitDropsLastDraw(codes));
        fail += check("习惯分重号>豹子", habitPrefersChong());
        if (fail > 0) {
            System.err.println("FAILED " + fail);
            System.exit(1);
        }
        System.out.println("HabitPredictCheck OK");
    }

    private static boolean danMaChanges(List<String> codes) {
        String a = RuleBasedDanMaUtils.predict(toHm(codes), null, RuleBasedDanMaUtils.GameKind.PL3);
        List<String> next = new ArrayList<>(codes);
        next.add("777");
        List<HmCache.CompareDto> cmp = List.of(new HmCache.CompareDto().setAiDanMaHm(a).setRealHm("123"));
        String b = RuleBasedDanMaUtils.predict(toHm(next), cmp, RuleBasedDanMaUtils.GameKind.PL3);
        System.out.println("danMa A=" + a);
        System.out.println("danMa B=" + b);
        return a != null && b != null && !a.equals(b);
    }

    private static boolean dingWeiNearLast(List<String> codes) {
        String dw = RuleBasedDingWeiUtils.predictFromCodes(codes, RuleBasedDingWeiUtils.GameKind.PL3);
        String last = codes.get(codes.size() - 1);
        String[] parts = RuleBasedDingWeiUtils.parseParts(dw);
        if (parts == null) {
            return false;
        }
        boolean near = false;
        for (int p = 0; p < 3; p++) {
            int d = last.charAt(p) - '0';
            int n1 = (d + 1) % 10;
            int n9 = (d + 9) % 10;
            for (String s : parts[p].split(",")) {
                int v = Integer.parseInt(s.trim());
                if (v == d || v == n1 || v == n9) {
                    near = true;
                }
            }
        }
        System.out.println("dingWei=" + dw + " last=" + last + " near=" + near);
        return near;
    }

    private static boolean overfitNearRecent(List<String> codes) {
        Overfit20PredictUtils.PredictResult r =
                Overfit20PredictUtils.predictResult(toHm(codes), Overfit20PredictUtils.GameKind.PL3);
        if (r.pool.isEmpty()) {
            return false;
        }
        for (int k = 1; k <= 3; k++) {
            String seed = codes.get(codes.size() - k);
            if (r.pool.contains(seed)) {
                System.out.println("overfit contains last-" + k + " " + seed);
                return true;
            }
            for (int pos = 0; pos < 3; pos++) {
                for (int delta : new int[]{1, 9}) {
                    char[] c = seed.toCharArray();
                    c[pos] = (char) ('0' + ((c[pos] - '0' + delta) % 10));
                    if (r.pool.contains(new String(c))) {
                        System.out.println("overfit contains neighbor " + new String(c));
                        return true;
                    }
                }
            }
        }
        System.out.println("overfit pool head=" + r.pool.subList(0, Math.min(8, r.pool.size())));
        return false;
    }

    private static boolean overfitSize150(List<String> codes) {
        Overfit20PredictUtils.PredictResult r =
                Overfit20PredictUtils.predictResult(toHm(codes), Overfit20PredictUtils.GameKind.PL3);
        System.out.println("overfit size=" + r.pool.size() + " cap=" + Overfit20PredictUtils.MAX_TICKETS);
        return r.pool.size() == Overfit20PredictUtils.MAX_TICKETS;
    }

    private static boolean overfitDropsLastDraw(List<String> codes) {
        Overfit20PredictUtils.PredictResult r =
                Overfit20PredictUtils.predictResult(toHm(codes), Overfit20PredictUtils.GameKind.PL3);
        String last = codes.get(codes.size() - 1);
        boolean has = r.pool.contains(last);
        System.out.println("overfit has lastDraw " + last + "=" + has + " size=" + r.pool.size());
        return !has && r.pool.size() == Overfit20PredictUtils.MAX_TICKETS;
    }

    private static boolean habitPrefersChong() {
        int[][] d = {
                {1, 2, 3}, {4, 5, 6}, {1, 5, 9}, {2, 3, 8}, {1, 2, 7},
                {8, 1, 4}, {3, 6, 9}, {2, 2, 5}, {1, 4, 7}, {1, 2, 3}
        };
        DrawHabit h = DrawHabit.of(d);
        int chong = h.ticketBonus(1, 2, 4);
        int bao = h.ticketBonus(0, 0, 0);
        System.out.println("habit chong=" + chong + " bao=" + bao);
        return chong > bao;
    }

    private static int check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        return ok ? 0 : 1;
    }

    private static List<Hm> toHm(List<String> codes) {
        List<Hm> list = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            String c = codes.get(i);
            while (c.length() < 3) {
                c = "0" + c;
            }
            list.add(Hm.builder().qh(String.valueOf(i + 1))
                    .q1(String.valueOf(c.charAt(0)))
                    .q2(String.valueOf(c.charAt(1)))
                    .q3(String.valueOf(c.charAt(2)))
                    .build());
        }
        return list;
    }
}
