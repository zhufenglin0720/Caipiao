package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * 命中率元调参：读近窗对比，按连挂 / 短窗命中输出配额与名次带增量。
 * <p>
 * 不重拟合 LINEAR·PROFILE，只给增量与软加权倍率。干旱等级 L0–L3；
 * 过拟合尾部注入仅极端 L3 启用，幅度保守以免反向扰动原标定。
 */
@Slf4j
public final class HitRateMetaTuner {

    /** 连挂回看（与 {@link RuleBasedPredictUtils} 近窗一致） */
    static final int STREAK_LOOKBACK = 15;
    /** 短窗命中统计 */
    static final int SHORT_WINDOW = 8;
    static final int EXTREME_WINDOW = 12;

    private HitRateMetaTuner() {
    }

    /**
     * @param compares 历史对比（可只含大底、只含七码，或两者都有；无实开的待定条跳过）
     * @param pl3      true=排列三，false=福彩3D
     */
    public static Snapshot analyze(List<HmCache.CompareDto> compares, boolean pl3) {
        if (compares == null || compares.isEmpty()) {
            return Snapshot.neutral();
        }
        List<Period> periods = toPeriods(compares);
        if (periods.isEmpty()) {
            return Snapshot.neutral();
        }

        int missZx = tailMiss(periods, p -> p.hasTicket, p -> p.zx);
        int missGroup = tailMiss(periods, p -> p.hasTicket, p -> p.group);
        int missDingWei = tailMiss(periods, p -> p.hasDingWei, p -> p.dwFull);
        int[] missDwPos = new int[3];
        for (int pos = 0; pos < 3; pos++) {
            int p = pos;
            missDwPos[pos] = tailMiss(periods, x -> x.hasDingWei, x -> x.dwPos[p]);
        }

        int zxHits8 = countWhere(periods, SHORT_WINDOW, p -> p.hasTicket, p -> p.zx);
        int zxSamples8 = countWhere(periods, SHORT_WINDOW, p -> p.hasTicket, p -> true);
        int zxHits12 = countWhere(periods, EXTREME_WINDOW, p -> p.hasTicket, p -> p.zx);
        int zxSamples12 = countWhere(periods, EXTREME_WINDOW, p -> p.hasTicket, p -> true);
        int groupHits8 = countWhere(periods, SHORT_WINDOW, p -> p.hasTicket, p -> p.group);
        int dwHits8 = countWhere(periods, SHORT_WINDOW, p -> p.hasDingWei, p -> p.dwFull);

        int droughtLevel = droughtLevel(missZx, missGroup, missDingWei,
                zxHits8, zxSamples8, zxHits12, zxSamples12, groupHits8);

        Snapshot snap = Snapshot.fromSignals(droughtLevel, missZx, missGroup, missDingWei,
                missDwPos, pl3);

        log.info("元调参: {} pl3={} win8 zx={}/{} grp={} dw={} win12 zx={}/{}",
                snap.describe(), pl3, zxHits8, zxSamples8, groupHits8, dwHits8, zxHits12, zxSamples12);
        return snap;
    }

    /** 连挂 + 短窗命中合成干旱等级；短窗 0 中才抬到 L2/L3，避免正常波动误触发 */
    static int droughtLevel(int missZx, int missGroup, int missDw,
                            int zxHits8, int zxSamples8, int zxHits12, int zxSamples12,
                            int groupHits8) {
        int level = 0;
        if (missZx >= 3 || missGroup >= 2 || missDw >= 2) {
            level = 1;
        }
        if (missZx >= 5 || missGroup >= 4 || missDw >= 3) {
            level = 2;
        }
        if (missZx >= 8 || missGroup >= 6 || missDw >= 5) {
            level = 3;
        }
        if (zxSamples8 >= 6 && zxHits8 == 0) {
            level = Math.max(level, 2);
        }
        if (zxSamples8 >= 6 && zxHits8 <= 1 && groupHits8 == 0) {
            level = Math.max(level, 2);
        }
        if (zxSamples12 >= 10 && zxHits12 == 0) {
            level = Math.max(level, 3);
        }
        return level;
    }

    private static int tailMiss(List<Period> periods, Predicate<Period> sample, Predicate<Period> hit) {
        int miss = 0;
        int scanned = 0;
        for (int i = periods.size() - 1; i >= 0 && scanned < STREAK_LOOKBACK; i--) {
            Period p = periods.get(i);
            if (!sample.test(p)) {
                continue;
            }
            scanned++;
            if (hit.test(p)) {
                break;
            }
            miss++;
        }
        return miss;
    }

    private static int countWhere(List<Period> periods, int window,
                                  Predicate<Period> sample, Predicate<Period> hit) {
        int n = 0;
        int scanned = 0;
        for (int i = periods.size() - 1; i >= 0 && scanned < window; i--) {
            Period p = periods.get(i);
            if (!sample.test(p)) {
                continue;
            }
            scanned++;
            if (hit.test(p)) {
                n++;
            }
        }
        return n;
    }

    private static List<Period> toPeriods(List<HmCache.CompareDto> compares) {
        List<Period> out = new ArrayList<>();
        for (HmCache.CompareDto dto : compares) {
            if (dto == null || dto.getRealHm() == null || dto.getRealHm().isBlank()) {
                continue;
            }
            String actual = pad3(dto.getRealHm());
            if (actual.length() != 3) {
                continue;
            }
            Period p = new Period();
            String tickets = ticketList(dto);
            if (tickets != null && !tickets.isBlank()) {
                p.hasTicket = true;
                boolean[] hits = ticketHits(tickets, actual);
                p.zx = hits[0];
                p.group = hits[1];
            }
            if (dto.getAiDingWeiHm() != null && !dto.getAiDingWeiHm().isBlank()) {
                // 解析失败则不当作七码样本，避免把脏数据算成连挂
                if (RuleBasedDingWeiUtils.parseParts(dto.getAiDingWeiHm()) != null) {
                    boolean[] pos = dingWeiPosHits(dto.getAiDingWeiHm(), actual);
                    p.hasDingWei = true;
                    p.dwPos = pos;
                    p.dwFull = pos[0] && pos[1] && pos[2];
                }
            }
            if (p.hasTicket || p.hasDingWei) {
                out.add(p);
            }
        }
        return out;
    }

    /** 优先原始 200 注大底，否则落盘去重池 */
    private static String ticketList(HmCache.CompareDto dto) {
        if (dto.getAiFullHm() != null && !dto.getAiFullHm().isBlank()) {
            return dto.getAiFullHm();
        }
        return dto.getAiHm();
    }

    /** [0]=直选 [1]=组选 */
    private static boolean[] ticketHits(String pred, String actual) {
        boolean zx = false;
        boolean group = false;
        char[] ak = actual.toCharArray();
        Arrays.sort(ak);
        String aKey = new String(ak);
        for (String part : pred.split(",")) {
            String t = pad3(part.trim());
            if (t.length() != 3) {
                continue;
            }
            if (t.equals(actual)) {
                zx = true;
                group = true;
                break;
            }
            char[] ck = t.toCharArray();
            Arrays.sort(ck);
            if (new String(ck).equals(aKey)) {
                group = true;
            }
        }
        return new boolean[]{zx, group};
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
                String s = d.trim();
                if (s.length() == 1 && s.charAt(0) == target) {
                    hit[pos] = true;
                    break;
                }
            }
        }
        return hit;
    }

    private static String pad3(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        while (t.length() < 3) {
            t = "0" + t;
        }
        if (t.length() > 3) {
            t = t.substring(t.length() - 3);
        }
        return t;
    }

    private static final class Period {
        boolean hasTicket;
        boolean hasDingWei;
        boolean zx;
        boolean group;
        boolean dwFull;
        boolean[] dwPos = new boolean[3];
    }

    /**
     * 调参快照。字段均为增量 / 倍率，调用方做钳制。
     */
    public static final class Snapshot {
        public final int droughtLevel;
        public final int missZx;
        public final int missGroup;
        public final int missDingWei;
        /** 三码命中带下界增量（干旱时略降，扩带） */
        public final int rankBandLoDelta;
        /** 三码命中带上界增量（干旱时略升） */
        public final int rankBandHiDelta;
        public final int groupUniqueBoost;
        public final int pairQuotaBoost;
        public final int permExpandBoost;
        public final int pl3ScatterBoost;
        public final int pl3ExpandBoost;
        /** 仅 L3 大于 0：过拟合直选尾部注入注数 */
        public final int overfitInject;
        public final double softNeighMul;
        public final double softOmitMul;
        /** 七码各位带下界增量 */
        public final int[] dwBandLoDelta;
        /** 七码各位带上界增量 */
        public final int[] dwBandHiDelta;

        private Snapshot(int droughtLevel, int missZx, int missGroup, int missDingWei,
                         int rankBandLoDelta, int rankBandHiDelta,
                         int groupUniqueBoost, int pairQuotaBoost, int permExpandBoost,
                         int pl3ScatterBoost, int pl3ExpandBoost, int overfitInject,
                         double softNeighMul, double softOmitMul,
                         int[] dwBandLoDelta, int[] dwBandHiDelta) {
            this.droughtLevel = droughtLevel;
            this.missZx = missZx;
            this.missGroup = missGroup;
            this.missDingWei = missDingWei;
            this.rankBandLoDelta = rankBandLoDelta;
            this.rankBandHiDelta = rankBandHiDelta;
            this.groupUniqueBoost = groupUniqueBoost;
            this.pairQuotaBoost = pairQuotaBoost;
            this.permExpandBoost = permExpandBoost;
            this.pl3ScatterBoost = pl3ScatterBoost;
            this.pl3ExpandBoost = pl3ExpandBoost;
            this.overfitInject = overfitInject;
            this.softNeighMul = softNeighMul;
            this.softOmitMul = softOmitMul;
            this.dwBandLoDelta = dwBandLoDelta;
            this.dwBandHiDelta = dwBandHiDelta;
        }

        static Snapshot neutral() {
            return new Snapshot(0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0,
                    1.0, 1.0, new int[3], new int[3]);
        }

        static Snapshot fromSignals(int level, int missZx, int missGroup, int missDw,
                                    int[] missDwPos, boolean pl3) {
            int rankLo = 0;
            int rankHi = 0;
            if (level >= 2) {
                rankHi = 1;
            }
            if (level >= 3) {
                rankLo = -1;
                rankHi = 1;
            }

            int groupBoost = 0;
            int pairBoost = 0;
            int permBoost = 0;
            int scatter = 0;
            int expand = 0;
            int inject = 0;
            if (level >= 1) {
                groupBoost = pl3 ? 6 : 4;
                if (missGroup >= 2) {
                    pairBoost = pl3 ? 2 : 3;
                }
                if (missZx >= 3) {
                    permBoost = pl3 ? 4 : 6;
                }
            }
            if (level >= 2) {
                groupBoost = pl3 ? 10 : 8;
                pairBoost = Math.max(pairBoost, pl3 ? 2 : 4);
                permBoost = Math.max(permBoost, pl3 ? 6 : 8);
                if (pl3) {
                    scatter = 6;
                    expand = 4;
                }
            }
            if (level >= 3) {
                groupBoost = pl3 ? 16 : 12;
                pairBoost = Math.max(pairBoost, pl3 ? 4 : 6);
                permBoost = Math.max(permBoost, pl3 ? 8 : 10);
                if (pl3) {
                    scatter = 8;
                    expand = 6;
                }
                inject = pl3 ? 6 : 4;
                if (missZx >= 10) {
                    inject += 2;
                }
            }

            double neighMul = 1.0 + 0.15 * level;
            double omitMul = 1.0 + 0.10 * level;

            int[] dwLo = new int[3];
            int[] dwHi = new int[3];
            for (int p = 0; p < 3; p++) {
                int posMiss = missDwPos == null ? 0 : missDwPos[p];
                if (posMiss >= 2 || level >= 2) {
                    dwHi[p] = 1;
                }
                if (posMiss >= 4 || level >= 3) {
                    dwLo[p] = -1;
                    dwHi[p] = 1;
                }
            }

            return new Snapshot(level, missZx, missGroup, missDw,
                    rankLo, rankHi, groupBoost, pairBoost, permBoost,
                    scatter, expand, inject, neighMul, omitMul, dwLo, dwHi);
        }

        public String describe() {
            return String.format(
                    "drought=L%d missZx=%d missGrp=%d missDw=%d bandΔ=%+d/%+d "
                            + "quota(+g%d +p%d +e%d) of=%d pl3(+s%d +e%d) "
                            + "dwBand=%s/%s soft=%.2f/%.2f",
                    droughtLevel, missZx, missGroup, missDingWei,
                    rankBandLoDelta, rankBandHiDelta,
                    groupUniqueBoost, pairQuotaBoost, permExpandBoost,
                    overfitInject, pl3ScatterBoost, pl3ExpandBoost,
                    Arrays.toString(dwBandLoDelta), Arrays.toString(dwBandHiDelta),
                    softNeighMul, softOmitMul);
        }
    }
}
