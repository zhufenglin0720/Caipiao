package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link HitRateMetaTuner} 契约自检（不依赖 Spring）。
 */
public final class HitRateMetaTunerCheck {

    private HitRateMetaTunerCheck() {
    }

    public static void main(String[] args) {
        int fail = 0;
        fail += check("null compares", checkNeutral(HitRateMetaTuner.analyze(null, false)));
        fail += check("empty compares", checkNeutral(HitRateMetaTuner.analyze(List.of(), true)));

        List<HmCache.CompareDto> pending = List.of(
                new HmCache.CompareDto().setAiHm("123,456").setRealHm(null));
        fail += check("pending realHm", checkNeutral(HitRateMetaTuner.analyze(pending, false)));

        List<HmCache.CompareDto> hit = List.of(
                ticket("123,456,789", "123"),
                ticket("111,222,333", "333"));
        HitRateMetaTuner.Snapshot s0 = HitRateMetaTuner.analyze(hit, false);
        fail += check("recent hit drought L0", s0.droughtLevel == 0 && s0.overfitInject == 0 && s0.missZx == 0);

        List<HmCache.CompareDto> miss3 = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            miss3.add(ticket("123,456,789", "000"));
        }
        HitRateMetaTuner.Snapshot s1 = HitRateMetaTuner.analyze(miss3, false);
        fail += check("3 zx miss → L1", s1.droughtLevel == 1 && s1.missZx == 3 && s1.overfitInject == 0);
        fail += check("L1 3D 配额", s1.groupUniqueBoost == 4 && s1.permExpandBoost == 6 && s1.rankBandHiDelta == 0);

        List<HmCache.CompareDto> miss8 = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            miss8.add(ticket("123,456,789", "000"));
        }
        HitRateMetaTuner.Snapshot s3 = HitRateMetaTuner.analyze(miss8, true);
        fail += check("8 zx miss PL3 → L3", s3.droughtLevel == 3 && s3.overfitInject == 6);
        fail += check("L3 扩带", s3.rankBandLoDelta == -1 && s3.rankBandHiDelta == 1);
        fail += check("L3 PL3 scatter", s3.pl3ScatterBoost == 8 && s3.pl3ExpandBoost == 6);
        fail += check("L3 soft mul", s3.softNeighMul > 1.3 && s3.softOmitMul > 1.2);

        List<HmCache.CompareDto> dwMiss = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            dwMiss.add(dingWei("百位:1,2,3,4,5,6,7 十位:1,2,3,4,5,6,7 个位:1,2,3,4,5,6,7", "999"));
        }
        HitRateMetaTuner.Snapshot dw = HitRateMetaTuner.analyze(dwMiss, false);
        fail += check("七码连挂3 → L2", dw.droughtLevel == 2 && dw.missDingWei == 3);
        fail += check("七码带上扩", dw.dwBandHiDelta[0] == 1 && dw.dwBandHiDelta[1] == 1);

        fail += check("droughtLevel 公式 L0", HitRateMetaTuner.droughtLevel(0, 0, 0, 2, 8, 3, 12, 4) == 0);
        fail += check("droughtLevel 公式 L3", HitRateMetaTuner.droughtLevel(8, 0, 0, 0, 8, 0, 12, 1) == 3);

        if (fail > 0) {
            System.err.println("FAILED " + fail);
            System.exit(1);
        }
        System.out.println("HitRateMetaTunerCheck OK");
    }

    private static int check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        return ok ? 0 : 1;
    }

    private static boolean checkNeutral(HitRateMetaTuner.Snapshot s) {
        return s != null && s.droughtLevel == 0 && s.overfitInject == 0
                && s.groupUniqueBoost == 0 && s.softNeighMul == 1.0
                && s.dwBandLoDelta.length == 3 && s.dwBandHiDelta.length == 3
                && s.describe() != null && !s.describe().isBlank();
    }

    private static HmCache.CompareDto ticket(String ai, String real) {
        return new HmCache.CompareDto().setAiHm(ai).setRealHm(real);
    }

    private static HmCache.CompareDto dingWei(String dw, String real) {
        return new HmCache.CompareDto().setAiDingWeiHm(dw).setRealHm(real);
    }
}
