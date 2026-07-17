package com.zfl.caipiao.utils;

import java.util.Arrays;

/**
 * 近窗统计特征：上期重号、各位→下期转移、和值、跨度。
 * 转移匹配用近 60 期，和值/跨度用近 20 期，不用全历史。
 */
final class RecentFeatureStats {

    static final int TRANS_LOOKBACK = 60;
    static final int SHAPE_LOOKBACK = 20;

    final int[] last;
    /** 同位置转移：历史上「该位=上期该位」时，下期同位置数字频次 */
    final int[][] samePosNext;
    /** 跨位转移：历史上「某位=上期该位」时，下期各位置数字频次 */
    final int[][] afterPosNext;
    /** 重号：上期号码数字集合在下期各位出现频次 */
    final int[][] repeatNext;
    final int[] sumFreq;
    final int[] spanFreq;
    final int[] topSums;
    final int[] topSpans;
    final double recentRepeatRate;

    /** 历史上与上期「同位数字」匹配时，下期完整开奖号（最多保留 24 个，近窗优先） */
    final int[][] nextFullCodes;
    final int nextFullCount;

    private RecentFeatureStats(int[] last, int[][] samePosNext, int[][] afterPosNext, int[][] repeatNext,
                               int[] sumFreq, int[] spanFreq, double recentRepeatRate,
                               int[][] nextFullCodes, int nextFullCount) {
        this.last = last;
        this.samePosNext = samePosNext;
        this.afterPosNext = afterPosNext;
        this.repeatNext = repeatNext;
        this.sumFreq = sumFreq;
        this.spanFreq = spanFreq;
        this.topSums = topK(sumFreq, 4);
        this.topSpans = topK(spanFreq, 3);
        this.recentRepeatRate = recentRepeatRate;
        this.nextFullCodes = nextFullCodes;
        this.nextFullCount = nextFullCount;
    }

    static RecentFeatureStats of(int[][] digits) {
        int n = digits.length;
        int[] last = digits[n - 1];
        int[][] samePosNext = new int[3][10];
        int[][] afterPosNext = new int[3][10];
        int[][] repeatNext = new int[3][10];
        int[] sumFreq = new int[28];
        int[] spanFreq = new int[10];
        int[][] nextFull = new int[24][4]; // [a,b,c,weight]
        int nextCnt = 0;

        int from = Math.max(1, n - TRANS_LOOKBACK);
        int digitSlots = 0;
        int repeatSlots = 0;

        for (int i = from; i < n; i++) {
            int[] prev = digits[i - 1];
            int[] cur = digits[i];

            boolean[] inPrev = new boolean[10];
            inPrev[prev[0]] = true;
            inPrev[prev[1]] = true;
            inPrev[prev[2]] = true;

            int matchPos = 0;
            for (int pos = 0; pos < 3; pos++) {
                if (prev[pos] == last[pos]) {
                    samePosNext[pos][cur[pos]]++;
                    matchPos++;
                }
                if (inPrev[cur[pos]]) {
                    repeatNext[pos][cur[pos]]++;
                    repeatSlots++;
                }
                digitSlots++;
            }

            for (int src = 0; src < 3; src++) {
                if (prev[src] != last[src]) {
                    continue;
                }
                for (int dst = 0; dst < 3; dst++) {
                    afterPosNext[dst][cur[dst]]++;
                }
            }

            // 同位≥1 保留宽样本，靠评分与截取配额控噪
            if (matchPos >= 1) {
                int w = matchPos * 10 + (i - from);
                boolean merged = false;
                for (int k = 0; k < nextCnt; k++) {
                    if (nextFull[k][0] == cur[0] && nextFull[k][1] == cur[1] && nextFull[k][2] == cur[2]) {
                        nextFull[k][3] += w;
                        merged = true;
                        break;
                    }
                }
                if (!merged) {
                    if (nextCnt < nextFull.length) {
                        nextFull[nextCnt][0] = cur[0];
                        nextFull[nextCnt][1] = cur[1];
                        nextFull[nextCnt][2] = cur[2];
                        nextFull[nextCnt][3] = w;
                        nextCnt++;
                    } else {
                        // 替换权重最低
                        int worst = 0;
                        for (int k = 1; k < nextFull.length; k++) {
                            if (nextFull[k][3] < nextFull[worst][3]) {
                                worst = k;
                            }
                        }
                        if (w > nextFull[worst][3]) {
                            nextFull[worst][0] = cur[0];
                            nextFull[worst][1] = cur[1];
                            nextFull[worst][2] = cur[2];
                            nextFull[worst][3] = w;
                        }
                    }
                }
            }
        }

        int shapeFrom = Math.max(0, n - SHAPE_LOOKBACK);
        for (int i = shapeFrom; i < n; i++) {
            int a = digits[i][0], b = digits[i][1], c = digits[i][2];
            int sum = a + b + c;
            int span = Math.max(a, Math.max(b, c)) - Math.min(a, Math.min(b, c));
            if (sum >= 0 && sum < sumFreq.length) {
                sumFreq[sum]++;
            }
            if (span >= 0 && span < spanFreq.length) {
                spanFreq[span]++;
            }
        }

        // 按权重排序 nextFull
        Integer[] order = new Integer[nextCnt];
        for (int i = 0; i < nextCnt; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(nextFull[b][3], nextFull[a][3]));
        int[][] sorted = new int[nextCnt][4];
        for (int i = 0; i < nextCnt; i++) {
            sorted[i] = nextFull[order[i]];
        }

        double rate = digitSlots == 0 ? 0.3 : (double) repeatSlots / digitSlots;
        return new RecentFeatureStats(last, samePosNext, afterPosNext, repeatNext, sumFreq, spanFreq, rate, sorted, nextCnt);
    }

    int digitBonus(int pos, int d) {
        int s = samePosNext[pos][d] * 4 + afterPosNext[pos][d] * 2 + repeatNext[pos][d] * 3;
        if (d == last[0] || d == last[1] || d == last[2]) {
            s += recentRepeatRate >= 0.28 ? 6 : 3;
        }
        if (d == last[pos]) {
            s += 4;
        }
        int nb1 = (last[pos] + 9) % 10;
        int nb2 = (last[pos] + 1) % 10;
        if (d == nb1 || d == nb2) {
            s += 3;
        }
        return s;
    }

    int shapeBonus(int a, int b, int c) {
        int sum = a + b + c;
        int span = Math.max(a, Math.max(b, c)) - Math.min(a, Math.min(b, c));
        int bonus = 0;
        if (sum >= 0 && sum < sumFreq.length) {
            bonus += sumFreq[sum] * 5;
        }
        if (span >= 0 && span < spanFreq.length) {
            bonus += spanFreq[span] * 6;
        }
        if (isPreferredSum(sum)) {
            bonus += 25;
        }
        if (isPreferredSpan(span)) {
            bonus += 18;
        }
        boolean[] lastSet = new boolean[10];
        lastSet[last[0]] = true;
        lastSet[last[1]] = true;
        lastSet[last[2]] = true;
        int chong = (lastSet[a] ? 1 : 0) + (lastSet[b] ? 1 : 0) + (lastSet[c] ? 1 : 0);
        bonus += chong * 14;
        return bonus;
    }

    boolean isPreferredSum(int sum) {
        for (int s : topSums) {
            if (s == sum) {
                return true;
            }
        }
        return false;
    }

    boolean isPreferredSpan(int span) {
        for (int s : topSpans) {
            if (s == span) {
                return true;
            }
        }
        return false;
    }

    int[] topTransitionDigits(int pos, int k) {
        int[] combined = new int[10];
        for (int d = 0; d < 10; d++) {
            combined[d] = digitBonus(pos, d);
        }
        return topK(combined, k);
    }

    private static int[] topK(int[] freq, int k) {
        Integer[] idx = new Integer[freq.length];
        for (int i = 0; i < freq.length; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> Integer.compare(freq[b], freq[a]));
        int take = Math.min(k, freq.length);
        // 跳过频次全 0 的尾部
        int[] r = new int[take];
        for (int i = 0; i < take; i++) {
            r[i] = idx[i];
        }
        return r;
    }
}
