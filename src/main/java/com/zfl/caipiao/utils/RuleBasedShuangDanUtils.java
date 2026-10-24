package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 双胆预测：每天推送 3 组双胆（每组 2 码），命中率优先。
 * <p>
 * 命中定义：开奖号码的数字集合同时包含该组两个胆码（不要求定位）。
 * 3 组互不重复在均匀开奖下 any-group 理论约 20%；近窗共现 + 热度吃偏频。
 * 输出示例：{@code 组1:1,5 组2:2,8 组3:0,3}
 */
public final class RuleBasedShuangDanUtils {
    private static final Logger log = LoggerFactory.getLogger(RuleBasedShuangDanUtils.class);

    public static final int GROUP_COUNT = 3;
    private static final int MIN_HISTORY = 40;
    private static final int HIT_VAL = 50;
    private static final int[] FREQ_WINDOWS = {8, 12, 20, 30};
    private static final Pattern GROUP_PAT = Pattern.compile("组(\\d+)[:：]([0-9])[,，\\-]([0-9])");

    private RuleBasedShuangDanUtils() {
    }

    public static String get3dShuangDan() {
        return predict(HmCache.getSdCache(), false);
    }

    public static String getPl3ShuangDan() {
        return predict(HmCache.getPl3Cache(), true);
    }

    public static String predict(List<Hm> history) {
        return predict(history, false);
    }

    public static String predict(List<Hm> history, boolean pl3) {
        if (history == null || history.size() < MIN_HISTORY) {
            return "";
        }
        List<String> codes = new ArrayList<>(history.size());
        for (Hm hm : history) {
            if (hm != null) {
                codes.add(hm.toString());
            }
        }
        int[][] pairs = Flow1000Ranker.shuangDan(Flow1000Ranker.matrix(codes), pl3);
        String out = format(pairs);
        log.info("双胆预测: {}", out);
        return out;
    }

    static int[][] pickTopPairs(List<Hm> history) {
        int n = history.size();
        int from = Math.max(MIN_HISTORY, n - HIT_VAL);
        int evalN = n - from;
        if (evalN <= 0) {
            evalN = 1;
            from = n - 1;
        }

        int[][] hits = new int[10][10];
        for (int t = from; t < n; t++) {
            boolean[] present = digitSet(history.get(t).toString());
            for (int a = 0; a < 10; a++) {
                if (!present[a]) {
                    continue;
                }
                for (int b = a + 1; b < 10; b++) {
                    if (present[b]) {
                        hits[a][b]++;
                    }
                }
            }
        }

        double[] heat = digitHeat(history);
        List<Cand> cands = new ArrayList<>(45);
        for (int a = 0; a < 10; a++) {
            for (int b = a + 1; b < 10; b++) {
                double rate = hits[a][b] / (double) evalN;
                double score = rate * 1000.0 + (heat[a] + heat[b]) * 0.15;
                cands.add(new Cand(a, b, hits[a][b], rate, score));
            }
        }
        cands.sort(Comparator.comparingDouble((Cand c) -> c.score).reversed()
                .thenComparingInt(c -> -c.hits)
                .thenComparingInt(c -> c.a)
                .thenComparingInt(c -> c.b));

        int[][] out = new int[GROUP_COUNT][2];
        boolean[] used = new boolean[10];
        int filled = 0;
        for (Cand c : cands) {
            if (filled >= GROUP_COUNT) {
                break;
            }
            if (used[c.a] || used[c.b]) {
                continue;
            }
            out[filled][0] = c.a;
            out[filled][1] = c.b;
            used[c.a] = true;
            used[c.b] = true;
            filled++;
        }
        if (filled < GROUP_COUNT) {
            for (Cand c : cands) {
                if (filled >= GROUP_COUNT) {
                    break;
                }
                if (alreadyPicked(out, filled, c.a, c.b)) {
                    continue;
                }
                if (used[c.a] && used[c.b]) {
                    continue;
                }
                out[filled][0] = c.a;
                out[filled][1] = c.b;
                used[c.a] = true;
                used[c.b] = true;
                filled++;
            }
        }
        if (filled < GROUP_COUNT) {
            for (Cand c : cands) {
                if (filled >= GROUP_COUNT) {
                    break;
                }
                if (alreadyPicked(out, filled, c.a, c.b)) {
                    continue;
                }
                out[filled][0] = c.a;
                out[filled][1] = c.b;
                filled++;
            }
        }
        return out;
    }

    private static boolean alreadyPicked(int[][] out, int n, int a, int b) {
        for (int i = 0; i < n; i++) {
            if (out[i][0] == a && out[i][1] == b) {
                return true;
            }
        }
        return false;
    }

    private static double[] digitHeat(List<Hm> history) {
        double[] s = new double[10];
        for (int w : FREQ_WINDOWS) {
            List<Hm> t = tail(history, w);
            double weight = 40.0 / w;
            for (int i = 0; i < t.size(); i++) {
                double decay = 1.0 + 0.4 * Math.exp(-0.08 * (t.size() - 1 - i));
                boolean[] present = digitSet(t.get(i).toString());
                for (int d = 0; d < 10; d++) {
                    if (present[d]) {
                        s[d] += weight * decay;
                    }
                }
            }
        }
        boolean[] last = digitSet(history.get(history.size() - 1).toString());
        for (int d = 0; d < 10; d++) {
            if (last[d]) {
                s[d] += 2.5;
            }
        }
        return s;
    }

    static String format(int[][] pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            int a = Math.min(pairs[i][0], pairs[i][1]);
            int b = Math.max(pairs[i][0], pairs[i][1]);
            sb.append(String.format(Locale.ROOT, "组%d:%d,%d", i + 1, a, b));
        }
        return sb.toString();
    }

    public static int[][] parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = GROUP_PAT.matcher(text.replace('：', ':'));
        List<int[]> list = new ArrayList<>(GROUP_COUNT);
        while (m.find()) {
            int a = m.group(2).charAt(0) - '0';
            int b = m.group(3).charAt(0) - '0';
            if (a == b) {
                continue;
            }
            list.add(new int[]{Math.min(a, b), Math.max(a, b)});
        }
        if (list.isEmpty()) {
            String[] parts = text.split("[,，\\s]+");
            for (String p : parts) {
                String t = p.trim();
                if (t.matches("\\d[-~]\\d")) {
                    int a = t.charAt(0) - '0';
                    int b = t.charAt(2) - '0';
                    if (a != b) {
                        list.add(new int[]{Math.min(a, b), Math.max(a, b)});
                    }
                }
            }
        }
        return list.isEmpty() ? null : list.toArray(new int[0][]);
    }

    public static boolean isPairHit(int a, int b, String realHm) {
        if (realHm == null || realHm.length() < 3) {
            return false;
        }
        boolean[] present = digitSet(realHm);
        return present[a] && present[b];
    }

    public static boolean[] groupHits(String shuangDan, String realHm) {
        int[][] pairs = parse(shuangDan);
        if (pairs == null || realHm == null || realHm.length() < 3) {
            return null;
        }
        boolean[] out = new boolean[pairs.length];
        for (int i = 0; i < pairs.length; i++) {
            out[i] = isPairHit(pairs[i][0], pairs[i][1], realHm);
        }
        return out;
    }

    public static boolean isAnyGroupHit(String shuangDan, String realHm) {
        boolean[] h = groupHits(shuangDan, realHm);
        if (h == null) {
            return false;
        }
        for (boolean b : h) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAllGroupsHit(String shuangDan, String realHm) {
        boolean[] h = groupHits(shuangDan, realHm);
        if (h == null || h.length == 0) {
            return false;
        }
        for (boolean b : h) {
            if (!b) {
                return false;
            }
        }
        return true;
    }

    static boolean[] digitSet(String hm) {
        boolean[] present = new boolean[10];
        String t = pad3(hm);
        for (int i = 0; i < 3; i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') {
                present[c - '0'] = true;
            }
        }
        return present;
    }

    private static List<Hm> tail(List<Hm> list, int n) {
        return list.size() <= n ? list : list.subList(list.size() - n, list.size());
    }

    static String pad3(String s) {
        if (s == null) {
            return "000";
        }
        String t = s.trim();
        return t.length() >= 3 ? t.substring(t.length() - 3) : "0".repeat(3 - t.length()) + t;
    }

    private static final class Cand {
        final int a;
        final int b;
        final int hits;
        final double rate;
        final double score;

        Cand(int a, int b, int hits, double rate, double score) {
            this.a = a;
            this.b = b;
            this.hits = hits;
            this.rate = rate;
            this.score = score;
        }
    }
}
