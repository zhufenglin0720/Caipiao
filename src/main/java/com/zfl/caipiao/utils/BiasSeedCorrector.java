package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;

import java.util.Arrays;
import java.util.List;

/**
 * 预测偏差纠偏：逐期取「最接近实开」的一注，统计各位 (actual-pred) mod10 频次，
 * 出现更频繁的非零偏差作为主纠偏因子，后续预测对该位做 ± 纠偏。
 * 默认回看近 15 期（短期），可由自适应调参覆盖。
 */
final class BiasSeedCorrector {

    static final int LOOKBACK = 15;

    /** 每位 Top 偏差种子（模10差值 actual-pred），最多2个 */
    final int[][] seeds;
    /** 每位主纠偏因子（出现次数最多的非零偏差） */
    final int[] primarySeed;
    /** 主纠偏因子出现次数（用于加权） */
    final int[] primaryFreq;

    private BiasSeedCorrector(int[][] seeds, int[] primarySeed, int[] primaryFreq) {
        this.seeds = seeds;
        this.primarySeed = primarySeed;
        this.primaryFreq = primaryFreq;
    }

    static BiasSeedCorrector of(List<HmCache.CompareDto> compares) {
        return of(compares, LOOKBACK);
    }

    static BiasSeedCorrector of(List<HmCache.CompareDto> compares, int lookback) {
        int[][] deltaCnt = new int[3][10];
        int lb = Math.max(8, lookback);
        if (compares != null) {
            int end = compares.size();
            int start = Math.max(0, end - lb);
            for (int i = start; i < end; i++) {
                HmCache.CompareDto dto = compares.get(i);
                if (dto == null || dto.getAiHm() == null || dto.getAiHm().isBlank()
                        || dto.getRealHm() == null || dto.getRealHm().length() != 3) {
                    continue;
                }
                int[] real = parse(dto.getRealHm());
                if (real == null) {
                    continue;
                }
                // 只取最接近实开的一注，避免多注稀释真实偏差
                int[] bestBet = closestBet(dto.getAiHm(), real);
                if (bestBet == null) {
                    continue;
                }
                for (int pos = 0; pos < 3; pos++) {
                    int delta = (real[pos] - bestBet[pos] + 10) % 10;
                    deltaCnt[pos][delta]++;
                }
            }
        }

        int[][] seeds = new int[3][2];
        int[] primary = new int[3];
        int[] primaryFreq = new int[3];
        for (int pos = 0; pos < 3; pos++) {
            Integer[] order = new Integer[10];
            for (int d = 0; d < 10; d++) {
                order[d] = d;
            }
            int[] cnt = deltaCnt[pos];
            Arrays.sort(order, (a, b) -> {
                // 非零优先于同频的零；再比频次
                if (cnt[a] != cnt[b]) {
                    return Integer.compare(cnt[b], cnt[a]);
                }
                if (a == 0 && b != 0) {
                    return 1;
                }
                if (b == 0 && a != 0) {
                    return -1;
                }
                return Integer.compare(a, b);
            });

            int filled = 0;
            for (int k = 0; k < 10 && filled < 2; k++) {
                int d = order[k];
                if (d == 0) {
                    continue;
                }
                if (cnt[d] <= 0 && filled > 0) {
                    break;
                }
                seeds[pos][filled++] = d;
            }
            if (filled == 0) {
                seeds[pos][0] = 1;
                seeds[pos][1] = 9;
            } else if (filled == 1) {
                seeds[pos][1] = (10 - seeds[pos][0]) % 10;
                if (seeds[pos][1] == 0) {
                    seeds[pos][1] = seeds[pos][0] == 1 ? 2 : 1;
                }
            }
            primary[pos] = seeds[pos][0];
            primaryFreq[pos] = Math.max(1, cnt[primary[pos]]);
        }
        return new BiasSeedCorrector(seeds, primary, primaryFreq);
    }

    /** 对单个数字应用 ± 各种子 */
    void expandDigit(int digit, int pos, boolean[] dest) {
        dest[digit] = true;
        for (int s : seeds[pos]) {
            if (s <= 0) {
                continue;
            }
            dest[(digit + s) % 10] = true;
            dest[(digit - s + 10) % 10] = true;
        }
    }

    int shiftAdd(int digit, int pos) {
        return (digit + primarySeed[pos]) % 10;
    }

    int shiftSub(int digit, int pos) {
        return (digit - primarySeed[pos] + 10) % 10;
    }

    /** 把主纠偏因子写进各位得分：高频偏差方向加权 */
    void boostScores(int[][] scores) {
        if (scores == null) {
            return;
        }
        for (int pos = 0; pos < 3; pos++) {
            int seed = primarySeed[pos];
            int w = 2 + Math.min(6, primaryFreq[pos]);
            int[] boosted = Arrays.copyOf(scores[pos], 10);
            for (int d = 0; d < 10; d++) {
                // 原高分码的 ±seed 方向抬分
                if (scores[pos][d] <= 0) {
                    continue;
                }
                boosted[(d + seed) % 10] += w;
                boosted[(d - seed + 10) % 10] += w / 2;
            }
            scores[pos] = boosted;
        }
    }

    String describe() {
        return String.format("百纠偏=%d(×%d) 十纠偏=%d(×%d) 个纠偏=%d(×%d) | 种子百%s 十%s 个%s",
                primarySeed[0], primaryFreq[0],
                primarySeed[1], primaryFreq[1],
                primarySeed[2], primaryFreq[2],
                Arrays.toString(seeds[0]), Arrays.toString(seeds[1]), Arrays.toString(seeds[2]));
    }

    private static int[] closestBet(String aiHm, int[] real) {
        int[] best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String p : aiHm.split(",")) {
            int[] bet = parse(p.trim());
            if (bet == null) {
                continue;
            }
            int dist = 0;
            for (int pos = 0; pos < 3; pos++) {
                int d = Math.abs(bet[pos] - real[pos]);
                dist += Math.min(d, 10 - d);
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = bet;
            }
        }
        return best;
    }

    private static int[] parse(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim();
        while (c.length() < 3) {
            c = "0" + c;
        }
        if (c.length() != 3) {
            return null;
        }
        try {
            return new int[]{c.charAt(0) - '0', c.charAt(1) - '0', c.charAt(2) - '0'};
        } catch (Exception e) {
            return null;
        }
    }
}
