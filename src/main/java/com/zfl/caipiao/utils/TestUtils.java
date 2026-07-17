package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 本地验证规则预测（不调用 AI）。
 */
public class TestUtils {

    public static void main(String[] args) {
        List<String> history = Arrays.asList(
                "164", "900", "255", "817", "079", "012", "495", "431", "826", "695", "373", "032", "673", "617", "830",
                "689", "086", "878", "061", "718", "126", "509", "248", "920", "030", "522", "477", "833", "202", "215",
                "112", "896", "848", "095", "263", "255", "495", "268", "181", "291", "084", "499", "613", "811", "786",
                "730", "141", "216", "717", "788", "724", "789", "852", "009", "639", "795", "179", "051", "144", "703",
                "330", "097", "592", "435", "045", "649", "744", "655", "537", "119", "479", "590", "209", "118", "347",
                "315", "723", "528", "851", "807", "428", "840", "114", "131", "551", "115", "325", "406", "437", "419",
                "780", "634", "195", "770", "690", "048", "903", "004", "020", "334", "070", "474", "216", "954", "202",
                "761", "998", "938", "568", "643", "226", "974", "424", "207", "090", "490", "901", "786", "217", "343",
                "154", "656", "088", "343", "877", "652", "925", "017", "333", "951", "091", "272", "222", "296", "040",
                "747", "955", "785", "867", "835", "936", "512", "683", "070", "968", "443", "543", "515", "939", "466",
                "544", "263", "198", "421", "215", "916", "744", "462", "993", "154", "590", "065", "325", "340", "983",
                "151", "389", "473", "485", "641", "830", "911", "557", "551", "562", "369", "346", "715", "294", "229",
                "012", "192", "884", "417", "048", "771", "259", "157", "838", "584", "826", "837", "396", "005", "124",
                "313", "352", "142", "785", "665"
        );

        List<HmCache.CompareDto> compares = buildCompares(
                new String[]{"198,524,863,370,041", "771"},
                new String[]{"512,713,592,341,086", "259"},
                new String[]{"519,252,746,380,091", "157"},
                new String[]{"212,219,252,159,712", "838"},
                new String[]{"218,307,549,762,813", "584"},
                new String[]{"327,581,904,163,745", "826"},
                new String[]{"814,297,528,156,043", "837"},
                new String[]{"817,214,824,237,598", "396"},
                new String[]{"824,315,592,071,643", "005"},
                new String[]{"817,059,274,356,047", "124"},
                new String[]{"184,857,036,392,017", "313"},
                new String[]{"814,834,127,152,296", "352"},
                new String[]{"660,960,468,650,967,380,414", "142"},
                new String[]{"145,037,251,365,821,152,065", "785"},
                new String[]{"785,874,694,325,146,724,843", "665"}
        );

        // 回测近5期：用当期之前历史生成预测，检查是否覆盖实际
        System.out.println("===== 近5期偏差回测（规则引擎）=====");
        String[] recentActual = {"313", "352", "142", "785", "665"};
        for (int k = 0; k < recentActual.length; k++) {
            int endExclusive = history.size() - recentActual.length + k;
            List<String> sub = history.subList(0, endExclusive);
            List<HmCache.CompareDto> subCmp = compares.subList(0, compares.size() - recentActual.length + k);
            String pred = RuleBasedPredictUtils.predictFromCodes(sub, subCmp);
            String actual = recentActual[k];
            boolean hit = pred != null && Arrays.asList(pred.split(",")).contains(actual);
            boolean[] pos = posCover(pred, actual);
            System.out.printf("回测期%d 实际=%s 预测=%s 直选命中=%s 位覆盖[百,十,个]=%s%n",
                    k + 1, actual, pred, hit, Arrays.toString(pos));
        }

        System.out.println("===== 下期预测（基于全部历史）=====");
        String next = RuleBasedPredictUtils.predictFromCodes(history, compares);
        System.out.println(next);

        System.out.println("===== 近5期七码定位回测 =====");
        for (int k = 0; k < recentActual.length; k++) {
            int endExclusive = history.size() - recentActual.length + k;
            List<String> sub = history.subList(0, endExclusive);
            String dw = RuleBasedDingWeiUtils.predictFromCodes(sub, RuleBasedDingWeiUtils.GameKind.PL3);
            String actual = recentActual[k];
            boolean[] hit = dingWeiHit(dw, actual);
            int hitCnt = (hit[0] ? 1 : 0) + (hit[1] ? 1 : 0) + (hit[2] ? 1 : 0);
            System.out.printf("回测期%d 实际=%s 七码=%s 位命中[百,十,个]=%s 命中位数=%d%n",
                    k + 1, actual, dw, Arrays.toString(hit), hitCnt);
        }
        System.out.println("===== 下期七码定位 =====");
        System.out.println(RuleBasedDingWeiUtils.predictFromCodes(history, RuleBasedDingWeiUtils.GameKind.PL3));
    }

    private static List<HmCache.CompareDto> buildCompares(String[]... pairs) {
        List<HmCache.CompareDto> list = new ArrayList<>();
        for (String[] p : pairs) {
            list.add(new HmCache.CompareDto().setAiHm(p[0]).setRealHm(p[1]));
        }
        return list;
    }

    private static boolean[] posCover(String pred, String actual) {
        boolean[] hit = new boolean[3];
        if (pred == null || actual == null || actual.length() != 3) {
            return hit;
        }
        for (String p : pred.split(",")) {
            if (p.length() != 3) {
                continue;
            }
            for (int i = 0; i < 3; i++) {
                if (p.charAt(i) == actual.charAt(i)) {
                    hit[i] = true;
                }
            }
        }
        return hit;
    }

    private static boolean[] dingWeiHit(String dingWei, String actual) {
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
}
