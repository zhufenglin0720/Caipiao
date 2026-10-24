package com.zfl.caipiao.utils;

/**
 * 出号习惯：上期重号、同位邻号、近窗频次、和值/跨度通道。
 * 供三码 / 过拟合 / 七码 / 胆码共用，避免各模块各追各的带。
 */
final class DrawHabit {

    final int[] last;
    final boolean[] lastSet = new boolean[10];
    final int lastSum;
    final int lastSpan;
    /** 近 8 期同位重开比例 */
    final double samePosRepeat8;
    final int[][] freq8 = new int[3][10];
    final int[][] omit = new int[3][10];

    private DrawHabit(int[] last, double samePosRepeat8) {
        this.last = last;
        this.lastSet[last[0]] = true;
        this.lastSet[last[1]] = true;
        this.lastSet[last[2]] = true;
        this.lastSum = last[0] + last[1] + last[2];
        this.lastSpan = Math.max(last[0], Math.max(last[1], last[2]))
                - Math.min(last[0], Math.min(last[1], last[2]));
        this.samePosRepeat8 = samePosRepeat8;
    }

    static DrawHabit of(int[][] digits) {
        if (digits == null || digits.length == 0) {
            return new DrawHabit(new int[]{0, 0, 0}, 0);
        }
        int n = digits.length;
        int[] last = digits[n - 1];
        int from8 = Math.max(1, n - 8);
        int slots = 0;
        int same = 0;
        for (int i = from8; i < n; i++) {
            for (int p = 0; p < 3; p++) {
                slots++;
                if (digits[i][p] == digits[i - 1][p]) {
                    same++;
                }
            }
        }
        DrawHabit h = new DrawHabit(last, slots == 0 ? 0 : (double) same / slots);
        int fromF = Math.max(0, n - 8);
        for (int i = fromF; i < n; i++) {
            for (int p = 0; p < 3; p++) {
                h.freq8[p][digits[i][p]]++;
            }
        }
        for (int p = 0; p < 3; p++) {
            java.util.Arrays.fill(h.omit[p], n);
            for (int d = 0; d < 10; d++) {
                for (int i = n - 1; i >= 0; i--) {
                    if (digits[i][p] == d) {
                        h.omit[p][d] = n - 1 - i;
                        break;
                    }
                }
            }
        }
        return h;
    }

    /** 该位数字是否贴上期（重号或 ±1 邻号） */
    boolean nearLast(int pos, int d) {
        int b = last[pos];
        return d == b || d == (b + 1) % 10 || d == (b + 9) % 10;
    }

    /** 位分习惯加成：同位重号 > 邻号 > 跨位重号 > 近窗频 > 中遗漏 */
    int posBonus(int pos, int d) {
        int s = 0;
        if (d == last[pos]) {
            s += samePosRepeat8 >= 0.18 ? 10 : 6;
        }
        if (d == (last[pos] + 1) % 10 || d == (last[pos] + 9) % 10) {
            s += 7;
        }
        if (lastSet[d] && d != last[pos]) {
            s += 4;
        }
        s += freq8[pos][d] * 3;
        int om = omit[pos][d];
        if (om >= 2 && om <= 7) {
            s += 3;
        } else if (om >= 12) {
            s -= 2;
        }
        return s;
    }

    /** 整注习惯分：重号个数、邻号、和值/跨度通道 */
    int ticketBonus(int a, int b, int c) {
        int[] t = {a, b, c};
        int s = 0;
        int chong = 0;
        int neigh = 0;
        for (int p = 0; p < 3; p++) {
            if (lastSet[t[p]]) {
                chong++;
            }
            if (nearLast(p, t[p])) {
                neigh++;
            }
            s += posBonus(p, t[p]) / 2;
        }
        s += chong * 16;
        s += neigh * 10;
        int sum = a + b + c;
        int span = Math.max(a, Math.max(b, c)) - Math.min(a, Math.min(b, c));
        if (sum >= 8 && sum <= 19) {
            s += 12;
        }
        if (Math.abs(sum - lastSum) <= 4) {
            s += 8;
        }
        if (span >= 2 && span <= 8) {
            s += 8;
        }
        if (a == b && b == c) {
            s -= 40;
        }
        return s;
    }
}
