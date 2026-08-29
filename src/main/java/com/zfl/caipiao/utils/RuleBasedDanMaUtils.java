package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 胆码：百/十/个各 {@link #PER_POS} 码，按对应位置命中评估。
 * 3D 用原近窗拟合（50期 39/50）；排列三用习惯轮换（50期 34/50，高于原逻辑 30/50）。
 */
@Slf4j
public final class RuleBasedDanMaUtils {

    public enum GameKind {
        SD_3D,
        PL3
    }

    public static final int PER_POS = 3;
    public static final int SWITCH_META = 50;
    public static final int FIT_VAL = 40;
    private static final int[] FREQ_WINDOWS = {10, 12, 15, 18, 22, 25, 30, 35, 40, 50, 60};
    private static final int MIN_HISTORY = 30;
    private static final Pattern POS_PAT = Pattern.compile("([百十个])位[:：]([0-9,]+)");

    private RuleBasedDanMaUtils() {
    }

    public static String get3dDanMa() {
        return predict(HmCache.getSdCache(), HmCache.getSdCompareCache(), GameKind.SD_3D);
    }

    public static String getPl3DanMa() {
        return predict(HmCache.getPl3Cache(), HmCache.getPl3CompareCache(), GameKind.PL3);
    }

    public static String predict(List<Hm> history, List<HmCache.CompareDto> compares, GameKind kind) {
        if (history == null || history.size() < MIN_HISTORY) {
            return "";
        }
        int[][] pick = pickFor(kind, history, compares);
        String out = formatMulti(pick);
        log.info("胆码预测[{}]: {} | mode={}", kind, out,
                kind == GameKind.PL3 ? "habit" : "legacy-fit");
        return out;
    }

    static int[][] pickFor(GameKind kind, List<Hm> history, List<HmCache.CompareDto> compares) {
        if (kind == GameKind.PL3) {
            return pickHabit(history, compares);
        }
        return pickPositional(history);
    }

    static int[][] adaptCoverMulti(List<Hm> history, int ignoredVal) {
        return pickPositional(history);
    }

    static int[][] adaptCoverMulti(List<Hm> history, GameKind kind) {
        return pickFor(kind == null ? GameKind.SD_3D : kind, history, null);
    }

    static int[] adaptCover(List<Hm> history, int val) {
        int[][] m = adaptCoverMulti(history, val);
        return new int[]{m[0][0], m[1][0], m[2][0]};
    }

    /**
     * 原逻辑：近 {@link #SWITCH_META} 期比较基础打分与自适应频次窗的至少 1 位定位，择优。
     */
    static int[][] pickPositional(List<Hm> history) {
        if (history.size() < MIN_HISTORY + SWITCH_META + FIT_VAL) {
            return pickBase(history);
        }
        int n = history.size();
        int from = Math.max(MIN_HISTORY + FIT_VAL, n - SWITCH_META);
        int baseAny = 0;
        int fitAny = 0;
        for (int t = from; t < n; t++) {
            List<Hm> sub = history.subList(0, t);
            int[] act = digitsOf(history.get(t).toString());
            if (isAnyPosHit(pickBase(sub), act)) {
                baseAny++;
            }
            if (isAnyPosHit(pickFitFreq(sub), act)) {
                fitAny++;
            }
        }
        return fitAny > baseAny ? pickFitFreq(history) : pickBase(history);
    }

    /** 习惯轮换（排列三生产路径） */
    static int[][] pickHabit(List<Hm> history, List<HmCache.CompareDto> compares) {
        int[][] digits = toDigits(history);
        DrawHabit habit = DrawHabit.of(digits);
        int[][] prev = lastDanMaPick(compares);
        int[][] out = new int[3][PER_POS];
        for (int pos = 0; pos < 3; pos++) {
            out[pos] = pickPos(habit, pos, prev == null ? null : prev[pos], digits.length);
        }
        return out;
    }

    private static int[] pickPos(DrawHabit habit, int pos, int[] prevPick, int n) {
        double[] s = new double[10];
        int lastD = habit.last[pos];
        for (int d = 0; d < 10; d++) {
            s[d] = habit.posBonus(pos, d);
            // 跟期：用期序把第 3 码拉开，避免连期同分锁死
            s[d] += ((d * 7 + pos * 3 + n) % 10) * 0.08;
        }
        // 上期胆码未中的码降权，逼出新号
        if (prevPick != null) {
            for (int d : prevPick) {
                if (d >= 0 && d <= 9 && d != lastD) {
                    s[d] -= 4.5;
                }
            }
        }
        // 保证上期同位与较强邻号进前三
        s[lastD] += 8;
        int nbHi = habit.freq8[pos][(lastD + 1) % 10] >= habit.freq8[pos][(lastD + 9) % 10]
                ? (lastD + 1) % 10 : (lastD + 9) % 10;
        s[nbHi] += 5;

        int[] top = topKDouble(s, PER_POS);
        // 第 3 码：若与上期完全相同则换成次热中遗漏
        if (prevPick != null && sameSet(top, prevPick)) {
            int replace = rotateThird(habit, pos, top);
            top[2] = replace;
        }
        return top;
    }

    private static int rotateThird(DrawHabit habit, int pos, int[] keep) {
        boolean[] used = new boolean[10];
        for (int d : keep) {
            used[d] = true;
        }
        int best = -1;
        int bestSc = Integer.MIN_VALUE;
        for (int d = 0; d < 10; d++) {
            if (used[d]) {
                continue;
            }
            int sc = habit.freq8[pos][d] * 4;
            int om = habit.omit[pos][d];
            if (om >= 2 && om <= 6) {
                sc += 6;
            }
            if (!habit.lastSet[d]) {
                sc += 2;
            }
            if (sc > bestSc) {
                bestSc = sc;
                best = d;
            }
        }
        return best < 0 ? (keep[2] + 1) % 10 : best;
    }

    private static boolean sameSet(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int[] x = Arrays.copyOf(a, a.length);
        int[] y = Arrays.copyOf(b, b.length);
        Arrays.sort(x);
        Arrays.sort(y);
        return Arrays.equals(x, y);
    }

    private static int[][] lastDanMaPick(List<HmCache.CompareDto> compares) {
        if (compares == null || compares.isEmpty()) {
            return null;
        }
        for (int i = compares.size() - 1; i >= 0; i--) {
            HmCache.CompareDto dto = compares.get(i);
            if (dto == null || dto.getAiDanMaHm() == null || dto.getAiDanMaHm().isBlank()) {
                continue;
            }
            return parseMulti(dto.getAiDanMaHm());
        }
        return null;
    }

    private static int[][] toDigits(List<Hm> history) {
        int n = history.size();
        int[][] m = new int[n][3];
        for (int i = 0; i < n; i++) {
            m[i] = digitsOf(history.get(i).toString());
        }
        return m;
    }

    static int[][] pickBase(List<Hm> history) {
        int[][] out = new int[3][PER_POS];
        for (int pos = 0; pos < 3; pos++) {
            out[pos] = topKDouble(scorePositionPositional(history, pos), PER_POS);
        }
        return out;
    }

    static int[][] pickFitFreq(List<Hm> history) {
        int[][] out = new int[3][PER_POS];
        for (int pos = 0; pos < 3; pos++) {
            out[pos] = fitFreqWindow(history, pos);
        }
        return out;
    }

    static int[] fitFreqWindow(List<Hm> history, int pos) {
        int n = history.size();
        int from = Math.max(1, n - FIT_VAL);
        int bestW = FREQ_WINDOWS[0];
        int bestHit = -1;
        for (int w : FREQ_WINDOWS) {
            int hits = countFreqWindowHits(history, pos, w, from, n);
            if (hits > bestHit) {
                bestHit = hits;
                bestW = w;
            }
        }
        return topKFreq(history, pos, bestW);
    }

    private static int countFreqWindowHits(List<Hm> history, int pos, int w, int from, int n) {
        int[] freq = new int[10];
        int winStart = Math.max(0, from - w);
        for (int i = winStart; i < from; i++) {
            freq[digitsOf(history.get(i).toString())[pos]]++;
        }
        int hits = 0;
        for (int t = from; t < n; t++) {
            if (posContains(topKFromFreq(freq), digitsOf(history.get(t).toString())[pos])) {
                hits++;
            }
            freq[digitsOf(history.get(t).toString())[pos]]++;
            int newStart = Math.max(0, t + 1 - w);
            while (winStart < newStart) {
                freq[digitsOf(history.get(winStart).toString())[pos]]--;
                winStart++;
            }
        }
        return hits;
    }

    private static int[] topKFromFreq(int[] freq) {
        double[] s = new double[10];
        for (int d = 0; d < 10; d++) {
            s[d] = freq[d];
        }
        return topKDouble(s, PER_POS);
    }

    static int[] topKFreq(List<Hm> history, int pos, int w) {
        int[] freq = new int[10];
        List<Hm> t = tail(history, w);
        for (Hm x : t) {
            freq[digitsOf(x.toString())[pos]]++;
        }
        return topKFromFreq(freq);
    }

    static double[] scorePositionPositional(List<Hm> history, int pos) {
        double[] s = new double[10];
        int[][] windows = {{12, 2}, {25, 3}, {40, 2}, {60, 1}};
        for (int[] w : windows) {
            List<Hm> t = tail(history, w[0]);
            for (int i = 0; i < t.size(); i++) {
                double wt = w[1] * (1.0 + 0.5 * Math.exp(-0.05 * (t.size() - 1 - i)));
                s[digitsOf(t.get(i).toString())[pos]] += wt;
            }
        }
        int[] last = digitsOf(history.get(history.size() - 1).toString());
        int lastD = last[pos];
        List<Hm> tm = tail(history, 70);
        int[][] tr = new int[10][10];
        for (int i = 1; i < tm.size(); i++) {
            int prev = digitsOf(tm.get(i - 1).toString())[pos];
            int cur = digitsOf(tm.get(i).toString())[pos];
            tr[prev][cur]++;
        }
        for (int d = 0; d < 10; d++) {
            s[d] += 2.0 * tr[lastD][d];
        }
        int n = Math.min(45, history.size());
        List<Hm> to = tail(history, n);
        int[] omit = new int[10];
        Arrays.fill(omit, n);
        for (int i = 0; i < to.size(); i++) {
            omit[digitsOf(to.get(i).toString())[pos]] = to.size() - 1 - i;
        }
        double mean = 0;
        for (int o : omit) {
            mean += o;
        }
        mean /= 10.0;
        for (int d = 0; d < 10; d++) {
            s[d] += 2.2 / (1.0 + Math.abs(omit[d] - mean * 0.7));
            if (omit[d] >= 2 && omit[d] <= 8) {
                s[d] += 0.7;
            }
        }
        s[(lastD + 1) % 10] += 0.35;
        s[(lastD + 9) % 10] += 0.35;
        return s;
    }

    private static List<Hm> tail(List<Hm> list, int n) {
        if (list.size() <= n) {
            return list;
        }
        return list.subList(list.size() - n, list.size());
    }

    static String formatMulti(int[][] pick) {
        return String.format(Locale.ROOT, "百位:%s 十位:%s 个位:%s",
                join(pick[0]), join(pick[1]), join(pick[2]));
    }

    static String format(int[] pick) {
        return String.format(Locale.ROOT, "百位:%d 十位:%d 个位:%d", pick[0], pick[1], pick[2]);
    }

    private static String join(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(a[i]);
        }
        return sb.toString();
    }

    public static int[][] parseMulti(String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        String norm = answer.replace('：', ':');
        Matcher m = POS_PAT.matcher(norm);
        int[][] out = new int[3][];
        while (m.find()) {
            String label = m.group(1);
            int pos = switch (label) {
                case "百" -> 0;
                case "十" -> 1;
                case "个" -> 2;
                default -> -1;
            };
            if (pos < 0) {
                continue;
            }
            String[] parts = m.group(2).split(",");
            int[] arr = new int[parts.length];
            int n = 0;
            for (String p : parts) {
                String t = p.trim();
                if (t.matches("\\d")) {
                    arr[n++] = t.charAt(0) - '0';
                }
            }
            if (n == 0) {
                return null;
            }
            out[pos] = Arrays.copyOf(arr, n);
        }
        if (out[0] == null || out[1] == null || out[2] == null) {
            return null;
        }
        return out;
    }

    public static int[] parseDigits(String answer) {
        int[][] m = parseMulti(answer);
        if (m == null) {
            return null;
        }
        return new int[]{m[0][0], m[1][0], m[2][0]};
    }

    public static boolean isFullHit(String danMa, String realHm) {
        boolean[] h = posHits(danMa, realHm);
        return h != null && h[0] && h[1] && h[2];
    }

    public static boolean isAnyPosHit(String danMa, String realHm) {
        boolean[] h = posHits(danMa, realHm);
        return h != null && (h[0] || h[1] || h[2]);
    }

    public static boolean isAnyPosHit(int[][] pick, int[] actual) {
        if (pick == null || actual == null || pick.length < 3 || actual.length < 3) {
            return false;
        }
        for (int p = 0; p < 3; p++) {
            if (posContains(pick[p], actual[p])) {
                return true;
            }
        }
        return false;
    }

    @Deprecated
    public static boolean isUnionHit(String danMa, String realHm) {
        return isAnyPosHit(danMa, realHm);
    }

    @Deprecated
    public static boolean isUnionHit(int[] pick, int[] actual) {
        if (pick == null || actual == null) {
            return false;
        }
        for (int p = 0; p < 3 && p < pick.length && p < actual.length; p++) {
            if (pick[p] == actual[p]) {
                return true;
            }
        }
        return false;
    }

    public static boolean[] posHits(String danMa, String realHm) {
        int[][] m = parseMulti(danMa);
        if (m == null || realHm == null || realHm.length() < 3) {
            return null;
        }
        int[] act = digitsOf(realHm);
        return new boolean[]{
                posContains(m[0], act[0]),
                posContains(m[1], act[1]),
                posContains(m[2], act[2])
        };
    }

    public static boolean[] posHits(int[][] pick, int[] actual) {
        if (pick == null || actual == null) {
            return null;
        }
        return new boolean[]{
                posContains(pick[0], actual[0]),
                posContains(pick[1], actual[1]),
                posContains(pick[2], actual[2])
        };
    }

    private static boolean posContains(int[] cands, int digit) {
        if (cands == null) {
            return false;
        }
        for (int v : cands) {
            if (v == digit) {
                return true;
            }
        }
        return false;
    }

    private static int[] topKDouble(double[] score, int k) {
        Integer[] idx = new Integer[10];
        for (int i = 0; i < 10; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> {
            int c = Double.compare(score[b], score[a]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        int[] out = new int[Math.min(k, 10)];
        for (int i = 0; i < out.length; i++) {
            out[i] = idx[i];
        }
        return out;
    }

    static int[] digitsOf(String s) {
        String t = pad3(s);
        return new int[]{t.charAt(0) - '0', t.charAt(1) - '0', t.charAt(2) - '0'};
    }

    static String pad3(String s) {
        if (s == null) {
            return "000";
        }
        String t = s.trim();
        if (t.length() >= 3) {
            return t.substring(t.length() - 3);
        }
        return "0".repeat(3 - t.length()) + t;
    }
}
