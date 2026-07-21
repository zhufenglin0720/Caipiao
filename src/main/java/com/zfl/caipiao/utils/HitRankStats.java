package com.zfl.caipiao.utils;

import java.util.Arrays;

/**
 * 统计近窗开奖号在「各位位分名次」上的分布，找出大多数开奖落在的名次带，
 * 选号时优先该带，而不是死拿每位 Top1/Top2。
 * 默认回看近 20 期短期走势，可由自适应调参覆盖。
 */
final class HitRankStats {

    static final int LOOKBACK = 20;

    /** 名次带下界（含），1=最高分 */
    final int bandLo;
    /** 名次带上界（含） */
    final int bandHi;
    /** 各位名次频次 [pos][rank1..10] */
    final int[][] rankFreq;

    private HitRankStats(int bandLo, int bandHi, int[][] rankFreq) {
        this.bandLo = bandLo;
        this.bandHi = bandHi;
        this.rankFreq = rankFreq;
    }

    /**
     * 用历史开奖滚动重算各位得分，统计实际开奖落在第几名。
     */
    static HitRankStats of(int[][] digits) {
        return of(digits, LOOKBACK);
    }

    static HitRankStats of(int[][] digits, int lookback) {
        int[][] rankFreq = new int[3][11];
        if (digits == null || digits.length < 20) {
            return new HitRankStats(3, 8, rankFreq);
        }
        int n = digits.length;
        int lb = Math.max(12, lookback);
        int from = Math.max(12, n - lb);
        for (int i = from; i < n; i++) {
            int[][] hist = Arrays.copyOfRange(digits, 0, i);
            for (int pos = 0; pos < 3; pos++) {
                int[] score = lightweightPosScore(hist, pos);
                int rank = rankOf(score, digits[i][pos]);
                if (rank >= 1 && rank <= 10) {
                    rankFreq[pos][rank]++;
                }
            }
        }
        int[] totalByRank = new int[11];
        int total = 0;
        for (int pos = 0; pos < 3; pos++) {
            for (int r = 1; r <= 10; r++) {
                totalByRank[r] += rankFreq[pos][r];
                total += rankFreq[pos][r];
            }
        }
        if (total == 0) {
            return new HitRankStats(3, 8, rankFreq);
        }
        // 找覆盖 ≥55% 样本的最短连续名次带；偏好避开纯 Top1-2
        int bestLo = 3, bestHi = 8, bestWidth = 10;
        int need = (total * 55 + 99) / 100;
        for (int lo = 1; lo <= 10; lo++) {
            int sum = 0;
            for (int hi = lo; hi <= 10; hi++) {
                sum += totalByRank[hi];
                if (sum >= need) {
                    int width = hi - lo;
                    // 惩罚纯头部带 1-2
                    int penalty = (hi <= 2) ? 3 : (lo <= 2 && hi <= 4 ? 1 : 0);
                    int score = width + penalty;
                    if (score < bestWidth || (score == bestWidth && lo >= 3 && bestLo < 3)) {
                        bestWidth = score;
                        bestLo = lo;
                        bestHi = hi;
                    }
                    break;
                }
            }
        }
        // 带宽至少 3，至多 6，便于组号
        if (bestHi - bestLo + 1 < 3) {
            bestHi = Math.min(10, bestLo + 2);
        }
        if (bestHi - bestLo + 1 > 6) {
            // 收缩到频次最高的连续 5 档
            int peak = 1;
            for (int r = 2; r <= 10; r++) {
                if (totalByRank[r] > totalByRank[peak]) {
                    peak = r;
                }
            }
            bestLo = Math.max(1, peak - 2);
            bestHi = Math.min(10, bestLo + 4);
        }
        return new HitRankStats(bestLo, bestHi, rankFreq);
    }

    boolean inBand(int rank) {
        return rank >= bandLo && rank <= bandHi;
    }

    String describe() {
        return String.format("命中名次带=%d~%d (多数开奖落此区间，非死拿Top1/2)", bandLo, bandHi);
    }

    private static int[] lightweightPosScore(int[][] digits, int pos) {
        int n = digits.length;
        int[] freq10 = new int[10];
        int[] freq20 = new int[10];
        int from10 = Math.max(0, n - 10);
        int from20 = Math.max(0, n - 20);
        for (int i = from20; i < n; i++) {
            freq20[digits[i][pos]]++;
            if (i >= from10) {
                freq10[digits[i][pos]]++;
            }
        }
        int last = digits[n - 1][pos];
        int[] omit = new int[10];
        Arrays.fill(omit, n);
        for (int i = n - 1; i >= 0; i--) {
            int d = digits[i][pos];
            if (omit[d] == n) {
                omit[d] = n - 1 - i;
            }
        }
        int[] score = new int[10];
        for (int d = 0; d < 10; d++) {
            int s = freq10[d] * 3 + freq20[d];
            if (omit[d] >= 2 && omit[d] <= 6) {
                s += 4;
            }
            if (d == last) {
                s -= 1;
            }
            if (d == (last + 1) % 10 || d == (last + 9) % 10) {
                s += 2;
            }
            score[d] = s;
        }
        return score;
    }

    private static int rankOf(int[] score, int digit) {
        int better = 0;
        for (int d = 0; d < 10; d++) {
            if (score[d] > score[digit] || (score[d] == score[digit] && d < digit)) {
                better++;
            }
        }
        return better + 1;
    }
}
