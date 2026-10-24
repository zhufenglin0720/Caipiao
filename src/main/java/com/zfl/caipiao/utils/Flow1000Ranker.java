package com.zfl.caipiao.utils;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * 3D / 排列三 1000 注直选排序。
 * <p>
 * 乐彩网、500 彩票网、购彩大神共用的定位框架：同位冷热、遗漏回补、邻期转移、
 * 重号、邻号（不绕 0/9）、跨位重号。每位先按近窗命中选出冷热窗口，再对 000–999
 * 打分。三码 10 注不拿名次最前的 10 个，而在全量排序上比较几种取法，用近期
 * 直选命中最高的那种（爆流带 / 定位复式 / 邻号）。
 */
public final class Flow1000Ranker {

    public enum Strategy {
        HOT12, HOT30, HOT60, ADAPT3, ADAPT7, OMIT, COLD, TRANS, HABIT, BLEND, BLEND7, REVERT, MIX
    }

    public enum SanmaPick {
        T10, BOX4, BOX5, HAM, B30, META, COLD10
    }

    /**
     * 近 200 期、1680 期本地开奖上锁定。两位数字分布和汉明距离都贴着均匀理论，
     * 追热、转移、邻号把三码直选压在 1% 附近，不能当主模型。
     * <p>
     * 福彩3D：七码用遗漏回补，近 200 期全中 44%（均匀基线 34%）；胆码用自适应热号；
     * 大底取回补排序前 500，约 53%。三码取 1000 注里整注遗漏最长的 10 个（约 1.5%，10 注基线 1%）。
     * 排列三：七码和胆码用爆冷，七码约 42%、胆码约 71%（基线 66%），大底约 53%。
     * 三码在爆冷 1000 注排序上取近 40 期命中最密的 30 名爆流段，再撒 10 注（约 2%）。
     * 双胆把定位分最高的 6 个数字配成 3 组：3D 用重号邻号，排列三用近 60 期热号。
     */
    static Strategy sdStrategy = Strategy.OMIT;
    static SanmaPick sdPick = SanmaPick.COLD10;
    static boolean sdBurstPool = false;
    static Strategy plStrategy = Strategy.COLD;
    static SanmaPick plPick = SanmaPick.B30;
    static boolean plBurstPool = false;

    private static final SanmaPick[] META_ORDER = {
            SanmaPick.BOX5, SanmaPick.BOX4, SanmaPick.T10, SanmaPick.HAM, SanmaPick.B30
    };

    private Flow1000Ranker() {
    }

    public static int[][] matrix(List<String> codes) {
        int n = codes == null ? 0 : codes.size();
        int[][] m = new int[n][3];
        for (int i = 0; i < n; i++) {
            String s = codes.get(i);
            if (s == null) {
                continue;
            }
            s = s.trim();
            if (s.length() < 3) {
                s = ("000" + s).substring(s.length());
            }
            if (s.length() > 3) {
                s = s.substring(s.length() - 3);
            }
            for (int p = 0; p < 3; p++) {
                char ch = s.charAt(p);
                m[i][p] = ch >= '0' && ch <= '9' ? ch - '0' : 0;
            }
        }
        return m;
    }

    public static String describe(boolean pl3) {
        return pl3
                ? "500排三:三码20注近30热6码，再按近12期直选差环移±1"
                : "乐彩3D:三码20注遗漏12的6码，再按近35期直选差环移±1";
    }

    public static String csv(int[] ids) {
        if (ids == null || ids.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%03d", ids[i]));
        }
        return sb.toString();
    }

    public static int[] sanma(int[][] digits, boolean pl3) {
        if (digits == null || digits.length < 40) {
            return new int[0];
        }
        int end = digits.length;
        int[] base = base20(digits, end, pl3);
        int window = pl3 ? 12 : 35;
        int[] off = fitShift(digits, end, pl3, window, 1, true);
        return applyShift(base, off[0], off[1], off[2], true, false);
    }

    public static int[] pool(int[][] digits, boolean pl3, int limit) {
        if (digits == null || digits.length < 40) {
            return new int[0];
        }
        int cap = Math.max(1, Math.min(1000, limit));
        ExpertView ex = expert(digits, pl3);
        if (cap >= 1000) {
            return Arrays.copyOf(ex.order, Math.min(1000, ex.order.length));
        }
        return takeFrom(ex.order, 0, cap, code(digits[digits.length - 1]), cap);
    }

    public static int[][] digitTop(int[][] digits, boolean pl3, int k) {
        int[][] out = new int[3][k];
        if (digits == null || digits.length < 40) {
            return out;
        }
        if (pl3 && k <= 3) {
            int end = digits.length;
            for (int p = 0; p < 3; p++) {
                int[] omit = omission(digits, end, p);
                double[] cold = new double[10];
                for (int x = 0; x < 10; x++) {
                    cold[x] = omit[x];
                }
                int[] top = topK(cold, k);
                System.arraycopy(top, 0, out[p], 0, k);
            }
            return out;
        }
        ExpertView ex = expert(digits, pl3);
        for (int p = 0; p < 3; p++) {
            int[] top = topK(ex.pos[p], k);
            System.arraycopy(top, 0, out[p], 0, k);
        }
        return out;
    }

    public static int[][] shuangDan(int[][] digits, boolean pl3) {
        if (digits == null || digits.length < 40) {
            return new int[3][2];
        }
        int end = digits.length;
        double[][] digit = pl3 ? hot(digits, end, 60) : habit(digits, end);
        return shuang(digit, digits, end);
    }

    /**
     * 短周期未命中修正：3D 定位漏在刚出的热号上，改用近 20 期热号。
     * 排三七码漏在冷号盖不住的中段遗漏，改用遗漏靠近 12 期的回补；胆码仍取最冷 3 个。
     * 奇偶回补、杀号、跨度在近 20–80 期里方向偏反，不再拿来压排序。
     */
    private static final class ExpertView {
        final double[][] pos;
        final int[] order;
        /** 定位分前三：金胆、次胆、三胆。 */
        final int[] dan;

        ExpertView(double[][] pos, int[] order, int[] dan) {
            this.pos = pos;
            this.order = order;
            this.dan = dan;
        }
    }

    private static ExpertView expert(int[][] d, boolean pl3) {
        int end = d.length;
        int[] last = d[end - 1];
        double[][] pos = new double[3][10];
        for (int p = 0; p < 3; p++) {
            double[] src = pl3 ? bellAt(d, end, p, 12) : hotZ(d, end, p, 20);
            System.arraycopy(src, 0, pos[p], 0, 10);
        }
        double[] heat = new double[10];
        for (int p = 0; p < 3; p++) {
            for (int x = 0; x < 10; x++) {
                heat[x] += pos[p][x];
            }
        }
        int[] dan = topK(heat, 3);
        double[] score = new double[1000];
        int lastId = code(last);
        for (int a = 0; a < 10; a++) {
            for (int b = 0; b < 10; b++) {
                for (int c = 0; c < 10; c++) {
                    int id = a * 100 + b * 10 + c;
                    double v = pos[0][a] + pos[1][b] + pos[2][c];
                    if (a == b && b == c) {
                        v -= 4;
                    } else if (a != b && b != c && a != c) {
                        v += 0.12;
                    }
                    if (id == lastId) {
                        v -= 6;
                    }
                    score[id] = v;
                }
            }
        }
        return new ExpertView(pos, rankOrder(score), dan);
    }

    private static int[] expertTen(int[] order, int ban) {
        int[] out = new int[10];
        boolean[] used = new boolean[1000];
        boolean[] usedG = new boolean[1000];
        int[] sumCnt = new int[28];
        int[] headCnt = new int[10];
        int f = 0;
        for (int pass = 0; pass < 2 && f < 10; pass++) {
            for (int id : order) {
                if (f >= 10) {
                    break;
                }
                if (id == ban || used[id] || usedG[groupKey(id)]) {
                    continue;
                }
                int sum = id / 100 + (id / 10) % 10 + id % 10;
                int head = id / 100;
                if (pass == 0 && (sumCnt[sum] >= 3 || headCnt[head] >= 4)) {
                    continue;
                }
                used[id] = true;
                usedG[groupKey(id)] = true;
                sumCnt[sum]++;
                headCnt[head]++;
                out[f++] = id;
            }
        }
        return Arrays.copyOf(out, f);
    }

    /** 近 6 期三位合计出现过多的数字。3D 只杀极端过热的 1 个，排三最多杀 2 个。 */
    private static boolean[] killHot(int[][] d, int end, int minCount, int maxKill) {
        int from = Math.max(0, end - 6);
        int[] freq = new int[10];
        for (int i = from; i < end; i++) {
            boolean[] seen = drawSet(d[i]);
            for (int x = 0; x < 10; x++) {
                if (seen[x]) {
                    freq[x]++;
                }
            }
        }
        boolean[] kill = new boolean[10];
        for (int n = 0; n < maxKill; n++) {
            int best = -1;
            int bestC = minCount - 1;
            for (int x = 0; x < 10; x++) {
                if (!kill[x] && freq[x] > bestC) {
                    bestC = freq[x];
                    best = x;
                }
            }
            if (best >= 0) {
                kill[best] = true;
            }
        }
        return kill;
    }

    private static boolean isPrime(int x) {
        return x == 1 || x == 2 || x == 3 || x == 5 || x == 7;
    }

    private static int primeCount(int[][] d, int end, int pos, int w) {
        int from = Math.max(0, end - w);
        int c = 0;
        for (int i = from; i < end; i++) {
            if (isPrime(d[i][pos])) {
                c++;
            }
        }
        return c;
    }

    private static int sideCount(int[][] d, int end, int pos, int w, boolean odd) {
        int from = Math.max(0, end - w);
        int c = 0;
        for (int i = from; i < end; i++) {
            int x = d[i][pos];
            if (odd ? (x % 2 == 1) : (x >= 5)) {
                c++;
            }
        }
        return c;
    }

    private static int[] roadOmit(int[][] d, int end, int pos) {
        int[] om = {end, end, end};
        for (int r = 0; r < 3; r++) {
            for (int i = end - 1; i >= 0; i--) {
                if (d[i][pos] % 3 == r) {
                    om[r] = end - 1 - i;
                    break;
                }
            }
        }
        return om;
    }

    private static int repeatCount(int[][] d, int end, int pos, int w) {
        int from = Math.max(1, end - w);
        int c = 0;
        for (int i = from; i < end; i++) {
            if (d[i][pos] == d[i - 1][pos]) {
                c++;
            }
        }
        return c;
    }

    private static int sumCenter(int lastSum, int prevSum) {
        int delta = lastSum - prevSum;
        if (lastSum <= 7) {
            return Math.min(16, lastSum + 5);
        }
        if (lastSum >= 20) {
            return Math.max(11, lastSum - 5);
        }
        if (delta <= -8) {
            return Math.max(6, lastSum);
        }
        if (delta >= 8) {
            return Math.min(21, lastSum);
        }
        return (int) Math.round(0.45 * lastSum + 0.55 * 13.5);
    }

    private static int spanOf(int[] x) {
        return Math.max(x[0], Math.max(x[1], x[2])) - Math.min(x[0], Math.min(x[1], x[2]));
    }

    private static int zu6Run(int[][] d, int end) {
        int run = 0;
        for (int i = end - 1; i >= 0 && run < 8; i--) {
            int a = d[i][0];
            int b = d[i][1];
            int c = d[i][2];
            if (a != b && b != c && a != c) {
                run++;
            } else {
                break;
            }
        }
        return run;
    }

    private static boolean containsDan(int a, int b, int c, int[] dan) {
        for (int x : dan) {
            if (a == x || b == x || c == x) {
                return true;
            }
        }
        return false;
    }

    private static int[] metaNow(int[][] digits, Strategy st) {
        int n = digits.length;
        int from = Math.max(80, n - 40);
        int rankFrom = Math.max(50, from - 40);
        int[] rankAt = new int[n];
        Arrays.fill(rankAt, -1);
        int[] wins = new int[META_ORDER.length];
        for (int t = rankFrom; t < n; t++) {
            Bundle b = buildBundle(digits, t, st, rankAt);
            int id = code(digits[t]);
            rankAt[t] = indexOf(b.order, id);
            if (t < from) {
                continue;
            }
            for (int m = 0; m < META_ORDER.length; m++) {
                if (has(picks(b, META_ORDER[m]), id)) {
                    wins[m]++;
                }
            }
        }
        int best = 0;
        for (int m = 1; m < wins.length; m++) {
            if (wins[m] > wins[best]) {
                best = m;
            }
        }
        Bundle today = buildBundle(digits, n, st, rankAt);
        return picks(today, META_ORDER[best]);
    }

    private static int[] pastRanks(int[][] digits, Strategy st, int lookback) {
        int n = digits.length;
        int[] rankAt = new int[n];
        Arrays.fill(rankAt, -1);
        int from = Math.max(50, n - lookback);
        for (int t = from; t < n; t++) {
            Bundle b = buildBundle(digits, t, st, null);
            rankAt[t] = indexOf(b.order, code(digits[t]));
        }
        return rankAt;
    }

    private static int[] picks(Bundle b, SanmaPick pick) {
        return switch (pick) {
            case T10 -> b.t10;
            case BOX4 -> b.box4;
            case BOX5 -> b.box5;
            case HAM -> b.hamPicks;
            case B30 -> b.b30 == null ? b.t10 : b.b30;
            case META -> b.box5;
            case COLD10 -> new int[0];
        };
    }

    private static final class Bundle {
        int[] order;
        double[] score;
        double[][] digit;
        int[] t10;
        int[] box4;
        int[] box5;
        int[] hamPicks;
        int[] b30;
    }

    private static Bundle buildBundle(int[][] digits, int end, Strategy st, int[] rankAt) {
        double[][] digit = scoreDigits(st, digits, end);
        double[] score = scoreTickets(digits, end, digit, st);
        int[] order = rankOrder(score);
        int ban = code(digits[end - 1]);
        Bundle b = new Bundle();
        b.order = order;
        b.score = score;
        b.digit = digit;
        b.t10 = distinctFront(order, 10, ban);
        b.box4 = box(digit, score, 4, 10, ban);
        b.box5 = box(digit, score, 5, 10, ban);
        b.hamPicks = hamPick(score, digits[end - 1], 10, ban);
        if (rankAt != null) {
            int lo = bestStart(rankAt, Math.max(0, end - 40), end, 30, 5);
            b.b30 = spread(order, lo, Math.min(1000, lo + 30), 10, ban);
        }
        return b;
    }

    static double[][] scoreDigits(Strategy st, int[][] d, int end) {
        return switch (st) {
            case HOT12 -> hot(d, end, 12);
            case HOT30 -> hot(d, end, 30);
            case HOT60 -> hot(d, end, 60);
            case ADAPT3 -> adaptHot(d, end, 3);
            case ADAPT7 -> adaptHot(d, end, 7);
            case OMIT -> bellAll(d, end);
            case COLD -> coldAll(d, end);
            case TRANS -> transAll(d, end);
            case HABIT -> habit(d, end);
            case BLEND -> blend(d, end, 3);
            case BLEND7 -> blend(d, end, 7);
            case REVERT -> revert(d, end);
            case MIX -> mix(d, end);
        };
    }

    /** 遗漏回补 + 近 30 期冷热。七码吃遗漏，胆码吃热号。 */
    private static double[][] mix(int[][] d, int end) {
        double[][] out = new double[3][10];
        boolean[] set = drawSet(d[end - 1]);
        for (int p = 0; p < 3; p++) {
            double[] hz = hotZ(d, end, p, 30);
            double[] bl = bell(d, end, p);
            double[] tz = transZ(d, end, p);
            int last = d[end - 1][p];
            for (int x = 0; x < 10; x++) {
                out[p][x] = bl[x] + 0.85 * hz[x] + 0.35 * tz[x]
                        + (x == last ? 0.4 : 0)
                        + (isNeigh(last, x) ? 0.35 : 0)
                        + (set[x] && x != last ? 0.25 : 0);
            }
        }
        return out;
    }

    private static double[][] hot(int[][] d, int end, int w) {
        double[][] out = new double[3][10];
        for (int p = 0; p < 3; p++) {
            out[p] = hotZ(d, end, p, w);
        }
        return out;
    }

    private static double[][] adaptHot(int[][] d, int end, int topN) {
        double[][] out = new double[3][10];
        for (int p = 0; p < 3; p++) {
            int w = selectWindow(d, end, p, topN);
            out[p] = hotZ(d, end, p, w);
        }
        return out;
    }

    private static double[][] bellAll(int[][] d, int end) {
        double[][] out = new double[3][10];
        for (int p = 0; p < 3; p++) {
            out[p] = bell(d, end, p);
        }
        return out;
    }

    private static double[][] coldAll(int[][] d, int end) {
        double[][] out = new double[3][10];
        for (int p = 0; p < 3; p++) {
            int[] om = omission(d, end, p);
            for (int x = 0; x < 10; x++) {
                out[p][x] = Math.min(2.0, om[x] / 18.0);
            }
        }
        return out;
    }

    private static double[][] transAll(int[][] d, int end) {
        double[][] out = new double[3][10];
        for (int p = 0; p < 3; p++) {
            out[p] = transZ(d, end, p);
        }
        return out;
    }

    private static double[][] habit(int[][] d, int end) {
        double[][] out = new double[3][10];
        boolean[] set = drawSet(d[end - 1]);
        for (int p = 0; p < 3; p++) {
            double[] hz = hotZ(d, end, p, 12);
            int last = d[end - 1][p];
            for (int x = 0; x < 10; x++) {
                out[p][x] = 0.7 * hz[x]
                        + (x == last ? 1.3 : 0)
                        + (isNeigh(last, x) ? 1.0 : 0)
                        + (set[x] && x != last ? 0.6 : 0);
            }
        }
        return out;
    }

    private static double[][] blend(int[][] d, int end, int topN) {
        double[][] out = new double[3][10];
        boolean[] set = drawSet(d[end - 1]);
        int[] prev = end >= 2 ? d[end - 2] : d[end - 1];
        for (int p = 0; p < 3; p++) {
            int w = selectWindow(d, end, p, topN);
            double[] hz = hotZ(d, end, p, w);
            double[] tz = transZ(d, end, p);
            double[] bl = bell(d, end, p);
            int last = d[end - 1][p];
            for (int x = 0; x < 10; x++) {
                out[p][x] = hz[x]
                        + 0.55 * tz[x]
                        + 0.45 * bl[x]
                        + (x == last ? 0.85 : 0)
                        + (isNeigh(last, x) ? 0.55 : 0)
                        + (set[x] && x != last ? 0.35 : 0)
                        + (x == prev[p] ? 0.25 : 0);
            }
        }
        return out;
    }

    private static double[][] revert(int[][] d, int end) {
        double[][] out = new double[3][10];
        for (int p = 0; p < 3; p++) {
            double[] hz = hotZ(d, end, p, 30);
            double[] bl = bell(d, end, p);
            for (int x = 0; x < 10; x++) {
                out[p][x] = -hz[x] + 0.8 * bl[x];
            }
        }
        return out;
    }

    private static int selectWindow(int[][] d, int end, int pos, int topN) {
        int[] ws = {8, 12, 16, 22, 30, 45, 60, 90};
        int from = Math.max(100, end - 36);
        if (from >= end) {
            return 30;
        }
        int bestW = 30;
        int bestH = -1;
        for (int w : ws) {
            if (w >= end) {
                continue;
            }
            int h = 0;
            for (int t = from; t < end; t++) {
                double[] z = hotZ(d, t, pos, w);
                int actual = d[t][pos];
                for (int x : topK(z, topN)) {
                    if (x == actual) {
                        h++;
                        break;
                    }
                }
            }
            if (h > bestH || (h == bestH && w < bestW)) {
                bestH = h;
                bestW = w;
            }
        }
        return bestW;
    }

    private static double[] hotZ(int[][] d, int end, int pos, int w) {
        int from = Math.max(0, end - w);
        int len = Math.max(1, end - from);
        int[] f = new int[10];
        for (int i = from; i < end; i++) {
            f[d[i][pos]]++;
        }
        double exp = len / 10.0;
        double sd = Math.sqrt(len * 0.1 * 0.9);
        if (sd < 1e-6) {
            sd = 1;
        }
        double[] z = new double[10];
        for (int x = 0; x < 10; x++) {
            z[x] = (f[x] - exp) / sd;
        }
        return z;
    }

    private static double[] transZ(int[][] d, int end, int pos) {
        double[] z = new double[10];
        if (end < 3) {
            return z;
        }
        int last = d[end - 1][pos];
        int from = Math.max(1, end - 60);
        int[] cnt = new int[10];
        int tot = 0;
        for (int i = from; i < end; i++) {
            if (d[i - 1][pos] == last) {
                cnt[d[i][pos]]++;
                tot++;
            }
        }
        if (tot < 6) {
            return z;
        }
        double exp = tot / 10.0;
        double sd = Math.sqrt(tot * 0.1 * 0.9);
        if (sd < 1e-6) {
            sd = 1;
        }
        for (int x = 0; x < 10; x++) {
            z[x] = (cnt[x] - exp) / sd;
        }
        return z;
    }

    /** 遗漏靠近平均遗漏（约 8–10 期）时回补，刚出和极冷都压低。 */
    private static double[] bell(int[][] d, int end, int pos) {
        int[] om = omission(d, end, pos);
        double[] s = new double[10];
        for (int x = 0; x < 10; x++) {
            double u = (om[x] - 8.0) / 5.0;
            s[x] = Math.exp(-0.5 * u * u);
        }
        return s;
    }

    private static int[] omission(int[][] d, int end, int pos) {
        int[] om = new int[10];
        Arrays.fill(om, end);
        boolean[] seen = new boolean[10];
        int found = 0;
        for (int i = end - 1; i >= 0 && found < 10; i--) {
            int x = d[i][pos];
            if (!seen[x]) {
                seen[x] = true;
                om[x] = end - 1 - i;
                found++;
            }
        }
        return om;
    }

    private static double[] scoreTickets(int[][] digits, int end, double[][] digit, Strategy st) {
        int lastId = code(digits[end - 1]);
        boolean[] set = drawSet(digits[end - 1]);
        boolean habit = st == Strategy.HABIT || st == Strategy.BLEND || st == Strategy.BLEND7;
        double[] s = new double[1000];
        for (int a = 0; a < 10; a++) {
            for (int b = 0; b < 10; b++) {
                for (int c = 0; c < 10; c++) {
                    int id = a * 100 + b * 10 + c;
                    double v = digit[0][a] + digit[1][b] + digit[2][c];
                    if (a == b && b == c) {
                        v -= 4.0;
                    } else if (a != b && b != c && a != c) {
                        v += 0.12;
                    }
                    if (habit) {
                        if (hamming(id, lastId) == 1) {
                            v += 1.05;
                        }
                        int share = share(id, set);
                        if (share == 1) {
                            v += 0.35;
                        } else if (share == 2) {
                            v += 0.12;
                        }
                    }
                    if (id == lastId) {
                        v -= 6.0;
                    }
                    s[id] = v;
                }
            }
        }
        return s;
    }

    /**
     * 双胆不用近窗共现：共现追热在回测里只有 15% 左右，低于 3 组不相交双胆的均匀水平。
     * 直接把定位分最高的 6 个数字配成 3 组。
     */
    private static int[][] shuang(double[][] digit, int[][] d, int end) {
        double[] heat = new double[10];
        for (int p = 0; p < 3; p++) {
            for (int x = 0; x < 10; x++) {
                heat[x] += digit[p][x];
            }
        }
        Integer[] order = new Integer[10];
        for (int i = 0; i < 10; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int c = Double.compare(heat[b], heat[a]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        int[][] out = new int[3][2];
        for (int g = 0; g < 3; g++) {
            int a = order[g * 2];
            int b = order[g * 2 + 1];
            out[g][0] = Math.min(a, b);
            out[g][1] = Math.max(a, b);
        }
        return out;
    }

    private static int[] box(double[][] digit, double[] score, int k, int n, int ban) {
        int[][] top = new int[3][];
        for (int p = 0; p < 3; p++) {
            top[p] = topK(digit[p], k);
        }
        int m = k * k * k;
        Integer[] ix = new Integer[m];
        int c = 0;
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) {
                for (int u = 0; u < k; u++) {
                    ix[c++] = top[0][i] * 100 + top[1][j] * 10 + top[2][u];
                }
            }
        }
        Arrays.sort(ix, (a, b) -> {
            int cmp = Double.compare(score[b], score[a]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        int[] order = new int[m];
        for (int i = 0; i < m; i++) {
            order[i] = ix[i];
        }
        return distinctFront(order, n, ban);
    }

    private static int[] hamPick(double[] score, int[] last, int n, int ban) {
        Integer[] ix = new Integer[27];
        int c = 0;
        for (int p = 0; p < 3; p++) {
            for (int x = 0; x < 10; x++) {
                if (x == last[p]) {
                    continue;
                }
                int a = last[0];
                int b = last[1];
                int cc = last[2];
                if (p == 0) {
                    a = x;
                } else if (p == 1) {
                    b = x;
                } else {
                    cc = x;
                }
                ix[c++] = a * 100 + b * 10 + cc;
            }
        }
        Arrays.sort(ix, (a, b) -> {
            int cmp = Double.compare(score[b], score[a]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        int[] order = new int[c];
        for (int i = 0; i < c; i++) {
            order[i] = ix[i];
        }
        return distinctFront(order, n, ban);
    }

    /** 七码立方里均匀撒 10 注，让直选落在定位七码内部。 */
    private static int[] latin7(double[][] digit, int ban) {
        int[][] top = new int[3][];
        for (int p = 0; p < 3; p++) {
            top[p] = topK(digit[p], 7);
        }
        int[] out = new int[10];
        boolean[] used = new boolean[1000];
        boolean[] usedG = new boolean[1000];
        int f = 0;
        for (int i = 0; i < 80 && f < 10; i++) {
            int id = top[0][i % 7] * 100 + top[1][(i * 3) % 7] * 10 + top[2][(i * 5) % 7];
            if (id == ban || used[id] || usedG[groupKey(id)]) {
                continue;
            }
            used[id] = true;
            usedG[groupKey(id)] = true;
            out[f++] = id;
        }
        return Arrays.copyOf(out, f);
    }

    /** 整注遗漏最长的 10 个直选。 */
    private static int[] coldTickets(int[][] digits, int end) {
        int[] seen = new int[1000];
        Arrays.fill(seen, -1);
        for (int i = 0; i < end; i++) {
            seen[code(digits[i])] = i;
        }
        Integer[] idx = new Integer[1000];
        for (int i = 0; i < 1000; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> {
            int oa = seen[a] < 0 ? end + 1 : end - seen[a];
            int ob = seen[b] < 0 ? end + 1 : end - seen[b];
            int c = Integer.compare(ob, oa);
            return c != 0 ? c : Integer.compare(a, b);
        });
        int[] out = new int[10];
        for (int i = 0; i < 10; i++) {
            out[i] = idx[i];
        }
        return out;
    }

    private static int[] distinctFront(int[] order, int n, int ban) {
        int[] out = new int[n];
        boolean[] usedG = new boolean[1000];
        boolean[] used = new boolean[1000];
        int f = 0;
        for (int id : order) {
            if (f >= n) {
                break;
            }
            if (id == ban || used[id]) {
                continue;
            }
            int g = groupKey(id);
            if (usedG[g]) {
                continue;
            }
            usedG[g] = true;
            used[id] = true;
            out[f++] = id;
        }
        if (f < n) {
            for (int id : order) {
                if (f >= n) {
                    break;
                }
                if (id == ban || used[id]) {
                    continue;
                }
                used[id] = true;
                out[f++] = id;
            }
        }
        return Arrays.copyOf(out, f);
    }

    private static int[] spread(int[] order, int lo, int hi, int n, int ban) {
        int width = Math.max(1, hi - lo);
        int[] out = new int[n];
        boolean[] usedG = new boolean[1000];
        boolean[] used = new boolean[1000];
        int f = 0;
        for (int b = 0; b < n; b++) {
            int s = lo + (int) ((long) b * width / n);
            int e = lo + (int) ((long) (b + 1) * width / n);
            if (e <= s) {
                e = Math.min(hi, s + 1);
            }
            for (int i = s; i < e && i < order.length; i++) {
                int id = order[i];
                if (id == ban || used[id] || usedG[groupKey(id)]) {
                    continue;
                }
                usedG[groupKey(id)] = true;
                used[id] = true;
                out[f++] = id;
                break;
            }
        }
        for (int i = lo; i < hi && f < n && i < order.length; i++) {
            int id = order[i];
            if (id == ban || used[id] || usedG[groupKey(id)]) {
                continue;
            }
            usedG[groupKey(id)] = true;
            used[id] = true;
            out[f++] = id;
        }
        for (int i = 0; i < order.length && f < n; i++) {
            int id = order[i];
            if (id == ban || used[id]) {
                continue;
            }
            used[id] = true;
            out[f++] = id;
        }
        return Arrays.copyOf(out, f);
    }

    private static int[] takeFrom(int[] order, int lo, int hi, int ban, int cap) {
        int[] out = new int[cap];
        int f = 0;
        int hi2 = Math.min(order.length, hi);
        for (int i = Math.max(0, lo); i < hi2 && f < cap; i++) {
            if (order[i] == ban) {
                continue;
            }
            out[f++] = order[i];
        }
        if (f < cap) {
            for (int i = 0; i < order.length && f < cap; i++) {
                if (i >= lo && i < hi2) {
                    continue;
                }
                if (order[i] == ban) {
                    continue;
                }
                out[f++] = order[i];
            }
        }
        return Arrays.copyOf(out, f);
    }

    private static int bestStart(int[] rankAt, int from, int to, int width, int step) {
        int bestLo = 0;
        int best = -1;
        int stride = Math.max(1, step);
        for (int lo = 0; lo + width <= 1000; lo += stride) {
            int h = 0;
            for (int i = from; i < to; i++) {
                int r = rankAt[i];
                if (r >= lo && r < lo + width) {
                    h++;
                }
            }
            if (h > best) {
                best = h;
                bestLo = lo;
            }
        }
        return bestLo;
    }

    private static int[] rankOrder(double[] score) {
        Integer[] idx = new Integer[score.length];
        for (int i = 0; i < score.length; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> {
            int c = Double.compare(score[b], score[a]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        int[] order = new int[score.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = idx[i];
        }
        return order;
    }

    private static int[] topK(double[] s, int k) {
        Integer[] d = new Integer[10];
        for (int i = 0; i < 10; i++) {
            d[i] = i;
        }
        Arrays.sort(d, (a, b) -> {
            int c = Double.compare(s[b], s[a]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        int[] out = new int[k];
        for (int i = 0; i < k; i++) {
            out[i] = d[i];
        }
        return out;
    }

    private static int indexOf(int[] order, int id) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] == id) {
                return i;
            }
        }
        return 999;
    }

    private static boolean has(int[] a, int id) {
        if (a == null) {
            return false;
        }
        for (int x : a) {
            if (x == id) {
                return true;
            }
        }
        return false;
    }

    private static boolean groupHit(int[] picks, int id) {
        int g = groupKey(id);
        if (picks == null) {
            return false;
        }
        for (int p : picks) {
            if (groupKey(p) == g) {
                return true;
            }
        }
        return false;
    }

    private static int groupKey(int id) {
        int a = id / 100;
        int b = (id / 10) % 10;
        int c = id % 10;
        if (a > b) {
            int t = a;
            a = b;
            b = t;
        }
        if (b > c) {
            int t = b;
            b = c;
            c = t;
        }
        if (a > b) {
            int t = a;
            a = b;
            b = t;
        }
        return a * 100 + b * 10 + c;
    }

    private static int code(int[] x) {
        return x[0] * 100 + x[1] * 10 + x[2];
    }

    private static boolean[] drawSet(int[] x) {
        boolean[] s = new boolean[10];
        s[x[0]] = true;
        s[x[1]] = true;
        s[x[2]] = true;
        return s;
    }

    private static boolean isNeigh(int last, int d) {
        return d == last - 1 || d == last + 1;
    }

    private static int hamming(int id, int last) {
        int h = 0;
        if (id / 100 != last / 100) {
            h++;
        }
        if ((id / 10) % 10 != (last / 10) % 10) {
            h++;
        }
        if (id % 10 != last % 10) {
            h++;
        }
        return h;
    }

    private static int share(int id, boolean[] lastSet) {
        boolean[] s = new boolean[10];
        s[id / 100] = true;
        s[(id / 10) % 10] = true;
        s[id % 10] = true;
        int c = 0;
        for (int d = 0; d < 10; d++) {
            if (s[d] && lastSet[d]) {
                c++;
            }
        }
        return c;
    }

    private static boolean posHit(double[] score, int actual, int k) {
        for (int x : topK(score, k)) {
            if (x == actual) {
                return true;
            }
        }
        return false;
    }

    private static final class Stat {
        int n;
        int h2;
        int t10;
        int box4;
        int box5;
        int ham;
        int b30;
        int meta;
        int t10b;
        int box4b;
        int box5b;
        int hamb;
        int b30b;
        int metab;
        int g10;
        int gBox;
        int p500;
        int b500;
        int p250;
        int dan;
        int dw;
        int shuang;
        int p500b;
        int b500b;
        int danb;
        int dwb;
        int p1;
        int p3;
        int p7;
        int cover;
        int coverB;
        int cold10;
        int cold10b;
        int shuangB;
    }

    private enum DigitMode {
        BELL5, BELL8, BELL12, COLD, HOT8, HOT12, HOT20, HOT30
    }

    private static double[] bellAt(int[][] d, int end, int pos, int center) {
        int[] om = omission(d, end, pos);
        double[] s = new double[10];
        for (int x = 0; x < 10; x++) {
            double u = (om[x] - center) / 5.0;
            s[x] = Math.exp(-0.5 * u * u);
        }
        return s;
    }

    private static double[] modeScore(DigitMode mode, int[][] d, int end, int pos) {
        return switch (mode) {
            case BELL5 -> bellAt(d, end, pos, 5);
            case BELL8 -> bellAt(d, end, pos, 8);
            case BELL12 -> bellAt(d, end, pos, 12);
            case COLD -> {
                int[] om = omission(d, end, pos);
                double[] s = new double[10];
                for (int x = 0; x < 10; x++) {
                    s[x] = Math.min(2.0, om[x] / 18.0);
                }
                yield s;
            }
            case HOT8 -> hotZ(d, end, pos, 8);
            case HOT12 -> hotZ(d, end, pos, 12);
            case HOT20 -> hotZ(d, end, pos, 20);
            case HOT30 -> hotZ(d, end, pos, 30);
        };
    }

    private static DigitMode pickMode(int[][] d, int end, int pos, int w, int k, boolean pl3) {
        DigitMode[] order = pl3
                ? new DigitMode[]{DigitMode.COLD, DigitMode.BELL12, DigitMode.BELL8, DigitMode.HOT30,
                DigitMode.HOT20, DigitMode.BELL5, DigitMode.HOT12, DigitMode.HOT8}
                : new DigitMode[]{DigitMode.BELL8, DigitMode.BELL12, DigitMode.BELL5, DigitMode.COLD,
                DigitMode.HOT30, DigitMode.HOT20, DigitMode.HOT12, DigitMode.HOT8};
        int from = Math.max(50, end - w);
        DigitMode best = order[0];
        int bestH = -1;
        for (DigitMode mode : order) {
            int h = 0;
            for (int t = from; t < end; t++) {
                if (posHit(modeScore(mode, d, t, pos), d[t][pos], k)) {
                    h++;
                }
            }
            if (h > bestH) {
                bestH = h;
                best = mode;
            }
        }
        return best;
    }

    /** 20 注按组覆盖。how：0 组六+组三前20组各1序，1 只组六前20，2 前10组各2序，3 最高6码的组六复式。 */
    private static void groupSearch(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        DigitMode[] modes = {DigitMode.HOT8, DigitMode.HOT12, DigitMode.HOT20, DigitMode.HOT30,
                DigitMode.BELL8, DigitMode.BELL12, DigitMode.COLD};
        String[] how = {"混合前20组", "只组六前20", "前10组各2序", "热6码组六复式", "6码里前20序"};
        sb.append("===== ").append(name).append(" =====\n");
        for (DigitMode mode : modes) {
            for (int howId = 0; howId < how.length; howId++) {
                sb.append(String.format(Locale.ROOT, "%-18s", mode + " " + how[howId]));
                appendGroupHits(sb, digits, n, pl3, mode, howId, -1);
                sb.append('\n');
            }
        }
        for (int w : new int[]{8, 12, 20, 30, 60}) {
            sb.append(String.format(Locale.ROOT, "%-18s", "出现" + w + " 组六复式"));
            appendGroupHits(sb, digits, n, pl3, null, 3, w);
            sb.append('\n');
        }
    }

    private static void appendGroupHits(StringBuilder sb, int[][] digits, int n, boolean pl3,
                                         DigitMode mode, int howId, int freqW) {
        int[] from = {n - 50, n - 100, n - 200};
        int[] len = {50, 50, 200};
        for (int w = 0; w < from.length; w++) {
            int zx = 0;
            int grp = 0;
            for (int t = from[w]; t < from[w] + len[w]; t++) {
                double[][] pos = freqW > 0 ? presencePos(digits, t, freqW) : new double[3][10];
                if (freqW <= 0) {
                    for (int p = 0; p < 3; p++) {
                        pos[p] = modeScore(mode, digits, t, p);
                    }
                }
                int[] ten = groups20(pos, code(digits[t - 1]), howId);
                int id = code(digits[t]);
                if (has(ten, id)) {
                    zx++;
                }
                if (groupHit(ten, id)) {
                    grp++;
                }
            }
            sb.append(String.format(Locale.ROOT, " %d直%d组", zx, grp));
        }
    }

    private static double[][] presencePos(int[][] d, int end, int w) {
        double[][] s = new double[3][10];
        int from = Math.max(0, end - w);
        for (int i = from; i < end; i++) {
            for (int p = 0; p < 3; p++) {
                s[p][d[i][p]]++;
            }
        }
        return s;
    }

    private static final int SHIFT_R = 3;

    /** 当前三码底稿：3D 遗漏12的6码组六，排三近30热号的6码组六。 */
    private static int[] base20(int[][] digits, int end, boolean pl3) {
        double[][] pos = new double[3][10];
        DigitMode mode = pl3 ? DigitMode.HOT30 : DigitMode.BELL12;
        for (int p = 0; p < 3; p++) {
            pos[p] = modeScore(mode, digits, end, p);
        }
        int ban = end > 0 ? code(digits[end - 1]) : -1;
        return groups20(pos, ban, 3);
    }

    private static int shiftId(int id, int dh, int dt, int du, boolean wrap) {
        int a = id / 100 + dh;
        int b = (id / 10) % 10 + dt;
        int c = id % 10 + du;
        if (wrap) {
            a = Math.floorMod(a, 10);
            b = Math.floorMod(b, 10);
            c = Math.floorMod(c, 10);
        } else if (a < 0 || a > 9 || b < 0 || b > 9 || c < 0 || c > 9) {
            return -1;
        }
        return a * 100 + b * 10 + c;
    }

    private static int[] applyShift(int[] base, int dh, int dt, int du, boolean wrap, boolean half) {
        if (dh == 0 && dt == 0 && du == 0) {
            return Arrays.copyOf(base, base.length);
        }
        int[] out = new int[20];
        boolean[] used = new boolean[1000];
        int f = 0;
        int keep = half ? Math.min(10, base.length) : 0;
        for (int i = 0; i < keep && f < 20; i++) {
            if (base[i] < 0 || used[base[i]]) {
                continue;
            }
            used[base[i]] = true;
            out[f++] = base[i];
        }
        int srcN = half ? keep : base.length;
        for (int i = 0; i < srcN && f < 20; i++) {
            int id = shiftId(base[i], dh, dt, du, wrap);
            if (id < 0 || used[id]) {
                continue;
            }
            used[id] = true;
            out[f++] = id;
        }
        for (int i = 0; i < base.length && f < 20; i++) {
            if (base[i] < 0 || used[base[i]]) {
                continue;
            }
            used[base[i]] = true;
            out[f++] = base[i];
        }
        return Arrays.copyOf(out, f);
    }

    /** 用窗口内已经开出的号，挑使直选最多的百十个平移。平局先看组选，再偏向不平移。 */
    private static int[] fitShift(int[][] digits, int end, boolean pl3, int window, int radius, boolean wrap) {
        int from = Math.max(40, end - window);
        int m = end - from;
        int[][] bets = new int[m][];
        int[] draw = new int[m];
        int[] gk = new int[m];
        for (int i = 0; i < m; i++) {
            int s = from + i;
            bets[i] = base20(digits, s, pl3);
            draw[i] = code(digits[s]);
            gk[i] = groupKey(draw[i]);
        }
        int bestZ = -1;
        int bestG = -1;
        int bestL1 = 99;
        int bh = 0;
        int bt = 0;
        int bu = 0;
        for (int dh = -radius; dh <= radius; dh++) {
            for (int dt = -radius; dt <= radius; dt++) {
                for (int du = -radius; du <= radius; du++) {
                    int z = 0;
                    int g = 0;
                    for (int i = 0; i < m; i++) {
                        boolean hz = false;
                        boolean hg = false;
                        for (int bet : bets[i]) {
                            int sid = shiftId(bet, dh, dt, du, wrap);
                            if (sid < 0) {
                                continue;
                            }
                            if (sid == draw[i]) {
                                hz = true;
                            }
                            if (groupKey(sid) == gk[i]) {
                                hg = true;
                            }
                        }
                        if (hz) {
                            z++;
                        }
                        if (hg) {
                            g++;
                        }
                    }
                    int l1 = Math.abs(dh) + Math.abs(dt) + Math.abs(du);
                    if (z > bestZ || (z == bestZ && g > bestG) || (z == bestZ && g == bestG && l1 < bestL1)) {
                        bestZ = z;
                        bestG = g;
                        bestL1 = l1;
                        bh = dh;
                        bt = dt;
                        bu = du;
                    }
                }
            }
        }
        return new int[]{bh, bt, bu};
    }

    private static void shiftSearch(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int cacheFrom = Math.max(50, n - 270);
        int r = SHIFT_R;
        int dim = r * 2 + 1;
        boolean[][][][] zxAllW = new boolean[n][dim][dim][dim];
        boolean[][][][] grpAllW = new boolean[n][dim][dim][dim];
        boolean[][][][] zxAllC = new boolean[n][dim][dim][dim];
        boolean[][][][] grpAllC = new boolean[n][dim][dim][dim];
        boolean[][][][] zxHeadW = new boolean[n][dim][dim][dim];
        boolean[][][][] grpHeadW = new boolean[n][dim][dim][dim];
        boolean[][][][] zxHeadC = new boolean[n][dim][dim][dim];
        boolean[][][][] grpHeadC = new boolean[n][dim][dim][dim];
        for (int t = cacheFrom; t < n; t++) {
            int[] bets = base20(digits, t, pl3);
            int id = code(digits[t]);
            int gk = groupKey(id);
            markShift(bets, bets.length, id, gk, zxAllW, grpAllW, t, true);
            markShift(bets, bets.length, id, gk, zxAllC, grpAllC, t, false);
            markShift(bets, Math.min(10, bets.length), id, gk, zxHeadW, grpHeadW, t, true);
            markShift(bets, Math.min(10, bets.length), id, gk, zxHeadC, grpHeadC, t, false);
        }
        sb.append("===== ").append(name).append(" =====\n");
        int[] froms = {n - 50, n - 100, n - 200};
        int[] lens = {50, 50, 200};
        sb.append(String.format(Locale.ROOT, "%-28s", "不平移"));
        for (int i = 0; i < froms.length; i++) {
            int z = 0;
            int g = 0;
            for (int t = froms[i]; t < froms[i] + lens[i]; t++) {
                if (zxAllW[t][r][r][r]) {
                    z++;
                }
                if (grpAllW[t][r][r][r]) {
                    g++;
                }
            }
            sb.append(String.format(Locale.ROOT, " %d直%d组", z, g));
        }
        sb.append('\n');
        for (int window : new int[]{8, 12, 16, 20, 24, 30, 40, 50}) {
            for (int radius : new int[]{1, 2, 3}) {
                for (int wrapBit = 0; wrapBit < 2; wrapBit++) {
                    for (int halfBit = 0; halfBit < 2; halfBit++) {
                        boolean wrap = wrapBit == 1;
                        boolean half = halfBit == 1;
                        boolean[][][][] zxAll = wrap ? zxAllW : zxAllC;
                        boolean[][][][] grpAll = wrap ? grpAllW : grpAllC;
                        boolean[][][][] zxHead = wrap ? zxHeadW : zxHeadC;
                        boolean[][][][] grpHead = wrap ? grpHeadW : grpHeadC;
                        String label = "W" + window + " R" + radius + (wrap ? "环" : "截") + (half ? "半" : "全");
                        sb.append(String.format(Locale.ROOT, "%-28s", label));
                        for (int i = 0; i < froms.length; i++) {
                            int z = 0;
                            int g = 0;
                            for (int t = froms[i]; t < froms[i] + lens[i]; t++) {
                                int[] off = bestOffset(zxAll, grpAll, t, window, radius);
                                boolean zero = off[0] == 0 && off[1] == 0 && off[2] == 0;
                                int ih = off[0] + r;
                                int it = off[1] + r;
                                int iu = off[2] + r;
                                boolean hz;
                                boolean hg;
                                if (half && !zero) {
                                    hz = zxHead[t][r][r][r] || zxHead[t][ih][it][iu];
                                    hg = grpHead[t][r][r][r] || grpHead[t][ih][it][iu];
                                } else {
                                    hz = zxAll[t][ih][it][iu];
                                    hg = grpAll[t][ih][it][iu];
                                }
                                if (hz) {
                                    z++;
                                }
                                if (hg) {
                                    g++;
                                }
                            }
                            sb.append(String.format(Locale.ROOT, " %d直%d组", z, g));
                        }
                        sb.append('\n');
                    }
                }
            }
        }
    }

    /** 当期三码里跟上期预测相同的号，有没有开出。 */
    private static void repeatScan(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int from = 41;
        int[][] bets = new int[n][];
        for (int t = from - 1; t < n; t++) {
            bets[t] = sanma(Arrays.copyOf(digits, t), pl3);
        }
        sb.append("===== ").append(name).append(" =====\n");
        int[] starts = {n - 50, n - 100, n - 200, from};
        int[] lens = {50, 50, 200, n - from};
        String[] labels = {"近50", "前50", "近200", "全部"};
        for (int w = 0; w < starts.length; w++) {
            int start = Math.max(from, starts[w]);
            int end = w == 1 ? n - 50 : Math.min(n, start + lens[w]);
            if (w == 3) {
                end = n;
            }
            int periods = 0;
            int withDup = 0;
            int dupSlots = 0;
            int zx = 0;
            int grp = 0;
            int freshZx = 0;
            StringBuilder hits = new StringBuilder();
            for (int t = start; t < end; t++) {
                periods++;
                int id = code(digits[t]);
                int gk = groupKey(id);
                boolean dupPeriod = false;
                boolean zxHit = false;
                boolean grpHit = false;
                boolean fresh = false;
                for (int bet : bets[t]) {
                    if (!has(bets[t - 1], bet)) {
                        if (bet == id) {
                            fresh = true;
                        }
                        continue;
                    }
                    dupPeriod = true;
                    dupSlots++;
                    if (bet == id) {
                        zxHit = true;
                    }
                    if (groupKey(bet) == gk) {
                        grpHit = true;
                    }
                }
                if (dupPeriod) {
                    withDup++;
                }
                if (zxHit) {
                    zx++;
                    hits.append(String.format(Locale.ROOT, " 直选 t=%d %03d", t, id));
                } else if (grpHit) {
                    grp++;
                    hits.append(String.format(Locale.ROOT, " 组选 t=%d %03d", t, id));
                }
                if (fresh) {
                    freshZx++;
                }
            }
            sb.append(String.format(Locale.ROOT,
                    "%s 期数%d 有重复%d期 重复号%d个 | 重复号直选%d 组选%d | 非重复号直选%d%n",
                    labels[w], periods, withDup, dupSlots, zx, grp, freshZx));
            if (hits.length() > 0 && hits.length() < 500) {
                sb.append("  命中").append(hits).append('\n');
            }
        }
    }

    /** 窗口从 10 试到 100，半径和环移与线上一致。 */
    private static void windowScan(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int cacheFrom = Math.max(50, n - 320);
        int r = SHIFT_R;
        int dim = r * 2 + 1;
        boolean[][][][] zx = new boolean[n][dim][dim][dim];
        boolean[][][][] grp = new boolean[n][dim][dim][dim];
        for (int t = cacheFrom; t < n; t++) {
            int[] bets = base20(digits, t, pl3);
            int id = code(digits[t]);
            markShift(bets, bets.length, id, groupKey(id), zx, grp, t, true);
        }
        int bestW = 10;
        int best50z = -1;
        int best50g = -1;
        int best200z = -1;
        int[] froms = {n - 50, n - 100, n - 200};
        int[] lens = {50, 50, 200};
        sb.append("===== ").append(name).append(" =====\n");
        for (int window = 10; window <= 100; window++) {
            int[] hit = new int[6];
            for (int i = 0; i < froms.length; i++) {
                for (int t = froms[i]; t < froms[i] + lens[i]; t++) {
                    int[] off = bestOffset(zx, grp, t, window, 1);
                    int ih = off[0] + r;
                    int it = off[1] + r;
                    int iu = off[2] + r;
                    if (zx[t][ih][it][iu]) {
                        hit[i * 2]++;
                    }
                    if (grp[t][ih][it][iu]) {
                        hit[i * 2 + 1]++;
                    }
                }
            }
            sb.append(String.format(Locale.ROOT, "W%3d 近50 %d直%d组 | 前50 %d直%d组 | 近200 %d直%d组%n",
                    window, hit[0], hit[1], hit[2], hit[3], hit[4], hit[5]));
            boolean better = hit[0] > best50z
                    || (hit[0] == best50z && hit[1] > best50g)
                    || (hit[0] == best50z && hit[1] == best50g && hit[4] > best200z);
            if (better) {
                bestW = window;
                best50z = hit[0];
                best50g = hit[1];
                best200z = hit[4];
            }
        }
        sb.append(String.format(Locale.ROOT, "留下 W=%d 近50 %d直%d组 近200直选 %d%n",
                bestW, best50z, best50g, best200z));
    }

    private static void markShift(int[] bets, int nBet, int id, int gk,
                                  boolean[][][][] zx, boolean[][][][] grp, int t, boolean wrap) {
        int r = SHIFT_R;
        for (int i = 0; i < nBet; i++) {
            for (int dh = -r; dh <= r; dh++) {
                for (int dt = -r; dt <= r; dt++) {
                    for (int du = -r; du <= r; du++) {
                        int sid = shiftId(bets[i], dh, dt, du, wrap);
                        if (sid < 0) {
                            continue;
                        }
                        if (sid == id) {
                            zx[t][dh + r][dt + r][du + r] = true;
                        }
                        if (groupKey(sid) == gk) {
                            grp[t][dh + r][dt + r][du + r] = true;
                        }
                    }
                }
            }
        }
    }

    private static int[] bestOffset(boolean[][][][] zx, boolean[][][][] grp, int end, int window, int radius) {
        int from = Math.max(0, end - window);
        int bestZ = -1;
        int bestG = -1;
        int bestL1 = 99;
        int bh = 0;
        int bt = 0;
        int bu = 0;
        int r = SHIFT_R;
        for (int dh = -radius; dh <= radius; dh++) {
            for (int dt = -radius; dt <= radius; dt++) {
                for (int du = -radius; du <= radius; du++) {
                    int z = 0;
                    int g = 0;
                    for (int s = from; s < end; s++) {
                        if (zx[s][dh + r][dt + r][du + r]) {
                            z++;
                        }
                        if (grp[s][dh + r][dt + r][du + r]) {
                            g++;
                        }
                    }
                    int l1 = Math.abs(dh) + Math.abs(dt) + Math.abs(du);
                    if (z > bestZ || (z == bestZ && g > bestG) || (z == bestZ && g == bestG && l1 < bestL1)) {
                        bestZ = z;
                        bestG = g;
                        bestL1 = l1;
                        bh = dh;
                        bt = dt;
                        bu = du;
                    }
                }
            }
        }
        return new int[]{bh, bt, bu};
    }

    private static int[] groups20(double[][] pos, int ban, int how) {
        if (how == 3 || how == 4) {
            double[] heat = new double[10];
            for (int p = 0; p < 3; p++) {
                for (int x = 0; x < 10; x++) {
                    heat[x] += pos[p][x];
                }
            }
            int[] six = topK(heat, 6);
            if (how == 3) {
                return zu6Of(six, pos, ban);
            }
            return topOrdersIn(six, pos, ban);
        }
        boolean zu3 = how != 1;
        int takeGroups = how == 2 ? 10 : 20;
        int perms = how == 2 ? 2 : 1;
        int[] id1 = new int[220];
        int[] id2 = new int[220];
        double[] score = new double[220];
        int n = 0;
        if (zu3) {
            for (int pair = 0; pair < 10; pair++) {
                for (int single = 0; single < 10; single++) {
                    if (pair == single) {
                        continue;
                    }
                    int[] seq = bestZu3(pair, single, pos);
                    id1[n] = seq[0];
                    id2[n] = seq[1];
                    score[n] = seqScore(seq[0], pos);
                    n++;
                }
            }
        }
        for (int a = 0; a < 8; a++) {
            for (int b = a + 1; b < 9; b++) {
                for (int c = b + 1; c < 10; c++) {
                    int[] seq = bestZu6(a, b, c, pos);
                    id1[n] = seq[0];
                    id2[n] = seq[1];
                    score[n] = seqScore(seq[0], pos);
                    n++;
                }
            }
        }
        Integer[] ix = new Integer[n];
        for (int i = 0; i < n; i++) {
            ix[i] = i;
        }
        Arrays.sort(ix, (x, y) -> {
            int cmp = Double.compare(score[y], score[x]);
            return cmp != 0 ? cmp : Integer.compare(id1[x], id1[y]);
        });
        int[] out = new int[20];
        boolean[] used = new boolean[1000];
        int f = 0;
        int g = 0;
        for (int i = 0; i < n && g < takeGroups && f < 20; i++) {
            int id = id1[ix[i]];
            int alt = id2[ix[i]];
            if (id == ban) {
                id = alt;
            }
            if (id == ban || used[id]) {
                continue;
            }
            used[id] = true;
            out[f++] = id;
            g++;
            if (perms > 1 && f < 20 && alt != ban && alt >= 0 && !used[alt]) {
                used[alt] = true;
                out[f++] = alt;
            }
        }
        return Arrays.copyOf(out, f);
    }

    private static int[] zu6Of(int[] six, double[][] pos, int ban) {
        int[] ids = new int[20];
        double[] score = new double[20];
        int n = 0;
        for (int i = 0; i < six.length; i++) {
            for (int j = i + 1; j < six.length; j++) {
                for (int k = j + 1; k < six.length; k++) {
                    int[] seq = bestZu6(six[i], six[j], six[k], pos);
                    int id = seq[0] == ban ? seq[1] : seq[0];
                    ids[n] = id;
                    score[n] = seqScore(id, pos);
                    n++;
                }
            }
        }
        Integer[] ix = new Integer[n];
        for (int i = 0; i < n; i++) {
            ix[i] = i;
        }
        Arrays.sort(ix, (a, b) -> {
            int cmp = Double.compare(score[b], score[a]);
            return cmp != 0 ? cmp : Integer.compare(ids[a], ids[b]);
        });
        int[] out = new int[Math.min(20, n)];
        for (int i = 0; i < out.length; i++) {
            out[i] = ids[ix[i]];
        }
        return out;
    }

    /** 6 个数字里的全部顺序，按定位分取前 20，用来补直选。 */
    private static int[] topOrdersIn(int[] six, double[][] pos, int ban) {
        int m = six.length;
        int[] ids = new int[m * m * m];
        double[] score = new double[ids.length];
        int n = 0;
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                for (int k = 0; k < m; k++) {
                    if (i == j && j == k) {
                        continue;
                    }
                    int id = six[i] * 100 + six[j] * 10 + six[k];
                    ids[n] = id;
                    score[n] = seqScore(id, pos);
                    n++;
                }
            }
        }
        Integer[] ix = new Integer[n];
        for (int i = 0; i < n; i++) {
            ix[i] = i;
        }
        Arrays.sort(ix, (a, b) -> {
            int cmp = Double.compare(score[b], score[a]);
            return cmp != 0 ? cmp : Integer.compare(ids[a], ids[b]);
        });
        int[] out = new int[20];
        boolean[] used = new boolean[1000];
        int f = 0;
        for (int i = 0; i < n && f < 20; i++) {
            int id = ids[ix[i]];
            if (id == ban || used[id]) {
                continue;
            }
            used[id] = true;
            out[f++] = id;
        }
        return Arrays.copyOf(out, f);
    }

    private static int[] bestZu6(int a, int b, int c, double[][] pos) {
        int[] d = {a, b, c};
        int best = -1;
        int second = -1;
        double bestS = -1e18;
        double secondS = -1e18;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (j == i) {
                    continue;
                }
                int k = 3 - i - j;
                int id = d[i] * 100 + d[j] * 10 + d[k];
                double s = seqScore(id, pos);
                if (s > bestS) {
                    second = best;
                    secondS = bestS;
                    best = id;
                    bestS = s;
                } else if (s > secondS) {
                    second = id;
                    secondS = s;
                }
            }
        }
        return new int[]{best, second};
    }

    private static int[] bestZu3(int pair, int single, double[][] pos) {
        int[] ids = {
                pair * 100 + pair * 10 + single,
                pair * 100 + single * 10 + pair,
                single * 100 + pair * 10 + pair
        };
        int best = ids[0];
        int second = ids[1];
        double bestS = seqScore(ids[0], pos);
        double secondS = -1e18;
        for (int i = 1; i < 3; i++) {
            double s = seqScore(ids[i], pos);
            if (s > bestS) {
                second = best;
                secondS = bestS;
                best = ids[i];
                bestS = s;
            } else if (s > secondS) {
                second = ids[i];
                secondS = s;
            }
        }
        return new int[]{best, second};
    }

    private static double seqScore(int id, double[][] pos) {
        if (id < 0) {
            return -1e18;
        }
        return pos[0][id / 100] + pos[1][(id / 10) % 10] + pos[2][id % 10];
    }

    /** 三码只换定位底和复式形状，七码大底仍走 expert。 */
    private static void boxModels(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        DigitMode[] modes = {DigitMode.BELL8, DigitMode.BELL12, DigitMode.HOT8, DigitMode.HOT12,
                DigitMode.HOT20, DigitMode.HOT30, DigitMode.COLD};
        int[][] shapes = {{2, 2, 2}, {1, 3, 3}, {1, 5, 2}, {2, 3, 2}};
        sb.append("===== ").append(name).append(" =====\n");
        for (DigitMode mode : modes) {
            for (int[] shape : shapes) {
                sb.append(mode).append(String.format(Locale.ROOT, " 百%d十%d个%d", shape[0], shape[1], shape[2]));
                for (int eval : new int[]{30, 50, 80, 200}) {
                    int hit = 0;
                    int grp = 0;
                    int from = n - eval;
                    for (int t = from; t < n; t++) {
                        double[][] pos = new double[3][10];
                        for (int p = 0; p < 3; p++) {
                            pos[p] = modeScore(mode, digits, t, p);
                        }
                        int id = code(digits[t]);
                        int[] ten = boxTen(pos, digitOrder(pos), code(digits[t - 1]), shape[0], shape[1], shape[2]);
                        if (has(ten, id)) {
                            hit++;
                        }
                        if (groupHit(ten, id)) {
                            grp++;
                        }
                    }
                    sb.append(String.format(Locale.ROOT, "/%d:%d直%d组", eval, hit, grp));
                }
                sb.append('\n');
            }
        }
        for (int w : new int[]{24, 40, 60}) {
            sb.append("走前").append(w).append(" 百2十2个2");
            for (int eval : new int[]{30, 50, 80, 200}) {
                int hit = 0;
                int grp = 0;
                int from = n - eval;
                for (int t = from; t < n; t++) {
                    double[][] pos = new double[3][10];
                    for (int p = 0; p < 3; p++) {
                        DigitMode mode = pickMode(digits, t, p, w, 2, pl3);
                        pos[p] = modeScore(mode, digits, t, p);
                    }
                    int id = code(digits[t]);
                    int[] ten = boxTen(pos, digitOrder(pos), code(digits[t - 1]), 2, 2, 2);
                    if (has(ten, id)) {
                        hit++;
                    }
                    if (groupHit(ten, id)) {
                        grp++;
                    }
                }
                sb.append(String.format(Locale.ROOT, "/%d:%d直%d组", eval, hit, grp));
            }
            sb.append('\n');
        }
    }

    private static int[] digitOrder(double[][] pos) {
        double[] score = new double[1000];
        for (int a = 0; a < 10; a++) {
            for (int b = 0; b < 10; b++) {
                for (int c = 0; c < 10; c++) {
                    score[a * 100 + b * 10 + c] = pos[0][a] + pos[1][b] + pos[2][c];
                }
            }
        }
        return rankOrder(score);
    }

    /** 三码 10 注对开奖：顺序、分位差、以及几种按差修正的取法。 */
    private static void sanmaGap(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        sb.append("===== ").append(name).append(" =====\n");
        for (int eval : new int[]{50, 200}) {
            int from = n - eval;
            int direct = 0;
            int groupOnly = 0;
            int[] bestHam = new int[4];
            int[] posExact = new int[3];
            int[] posD1 = new int[3];
            int[] posD2 = new int[3];
            int[] posD3 = new int[3];
            int twoPos = 0;
            int anyAllPos = 0;
            int permInTop40 = 0;
            int exactRankSum = 0;
            int exactRankN = 0;
            for (int t = from; t < n; t++) {
                int[][] hist = Arrays.copyOf(digits, t);
                ExpertView ex = expert(hist, pl3);
                int id = code(digits[t]);
                int ban = code(digits[t - 1]);
                int[] ten = expertTen(ex.order, ban);
                if (has(ten, id)) {
                    direct++;
                } else if (groupHit(ten, id)) {
                    groupOnly++;
                }
                int close = -1;
                int closeHam = 4;
                int closeAbs = 99;
                boolean[] seenPos = new boolean[3];
                for (int bet : ten) {
                    int ham = hamming(bet, id);
                    int abs = absDiff(bet, id);
                    if (ham < closeHam || (ham == closeHam && abs < closeAbs)) {
                        close = bet;
                        closeHam = ham;
                        closeAbs = abs;
                    }
                    if (bet / 100 == digits[t][0]) {
                        seenPos[0] = true;
                    }
                    if ((bet / 10) % 10 == digits[t][1]) {
                        seenPos[1] = true;
                    }
                    if (bet % 10 == digits[t][2]) {
                        seenPos[2] = true;
                    }
                }
                if (closeHam <= 3) {
                    bestHam[closeHam]++;
                }
                if (close >= 0) {
                    int[] bd = {close / 100, (close / 10) % 10, close % 10};
                    int same = 0;
                    for (int p = 0; p < 3; p++) {
                        int diff = Math.abs(bd[p] - digits[t][p]);
                        if (diff == 0) {
                            posExact[p]++;
                            same++;
                        } else if (diff == 1) {
                            posD1[p]++;
                        } else if (diff == 2) {
                            posD2[p]++;
                        } else {
                            posD3[p]++;
                        }
                    }
                    if (same >= 2) {
                        twoPos++;
                    }
                }
                if (seenPos[0] && seenPos[1] && seenPos[2]) {
                    anyAllPos++;
                }
                int exactRank = indexOf(ex.order, id);
                if (exactRank >= 0 && exactRank < 40) {
                    exactRankN++;
                    exactRankSum += exactRank;
                }
                int g = groupKey(id);
                boolean perm = false;
                for (int i = 0; i < 40 && i < ex.order.length; i++) {
                    if (groupKey(ex.order[i]) == g && ex.order[i] != id) {
                        perm = true;
                        break;
                    }
                }
                if (perm && !has(ten, id)) {
                    permInTop40++;
                }
            }
            sb.append(String.format(Locale.ROOT,
                    "近%d 直选%d 只对了顺序%d | 最近一注汉明0/1/2/3=%d/%d/%d/%d | 两位对%d | 三位都在10注里%d | 正序在前40名%d%n",
                    eval, direct, groupOnly, bestHam[0], bestHam[1], bestHam[2], bestHam[3], twoPos, anyAllPos, exactRankN));
            sb.append(String.format(Locale.ROOT,
                    "  最近一注 百位 对%d 差1=%d 差2=%d 差3+=%d | 十位 对%d 差1=%d 差2=%d 差3+=%d | 个位 对%d 差1=%d 差2=%d 差3+=%d%n",
                    posExact[0], posD1[0], posD2[0], posD3[0],
                    posExact[1], posD1[1], posD2[1], posD3[1],
                    posExact[2], posD1[2], posD2[2], posD3[2]));
            if (exactRankN > 0) {
                sb.append(String.format(Locale.ROOT, "  正序落在前40时的平均名次 %.1f%n", exactRankSum / (double) exactRankN));
            }
        }
        String[] pickers = {"原10注", "同组最多2个序", "同组最多3个序", "头名+差1位", "头两名各带差1", "前10名不拆组"};
        sb.append("取法");
        for (int kind = 0; kind < pickers.length; kind++) {
            sb.append(' ').append(pickers[kind]);
            for (int eval : new int[]{30, 50, 80, 200}) {
                int hit = 0;
                int grp = 0;
                int from = n - eval;
                for (int t = from; t < n; t++) {
                    ExpertView ex = expert(Arrays.copyOf(digits, t), pl3);
                    int id = code(digits[t]);
                    int[] ten = pickTen(ex.order, code(digits[t - 1]), kind);
                    if (has(ten, id)) {
                        hit++;
                    }
                    if (groupHit(ten, id)) {
                        grp++;
                    }
                }
                sb.append(String.format(Locale.ROOT, "/%d:%d直%d组", eval, hit, grp));
            }
            sb.append('\n');
        }
    }

    private static int absDiff(int bet, int id) {
        return Math.abs(bet / 100 - id / 100)
                + Math.abs((bet / 10) % 10 - (id / 10) % 10)
                + Math.abs(bet % 10 - id % 10);
    }

    private static int[] pickTen(int[] order, int ban, int kind) {
        if (kind == 0) {
            return expertTen(order, ban);
        }
        if (kind == 5) {
            int[] out = new int[10];
            int f = 0;
            for (int id : order) {
                if (id == ban) {
                    continue;
                }
                out[f++] = id;
                if (f == 10) {
                    break;
                }
            }
            return out;
        }
        if (kind == 1 || kind == 2) {
            int cap = kind == 1 ? 2 : 3;
            int[] out = new int[10];
            boolean[] used = new boolean[1000];
            int[] gCnt = new int[1000];
            int f = 0;
            for (int id : order) {
                if (f >= 10 || id == ban || used[id]) {
                    continue;
                }
                int g = groupKey(id);
                if (gCnt[g] >= cap) {
                    continue;
                }
                used[id] = true;
                gCnt[g]++;
                out[f++] = id;
            }
            return Arrays.copyOf(out, f);
        }
        int seeds = kind == 3 ? 1 : 2;
        int per = kind == 3 ? 9 : 4;
        int[] out = new int[10];
        boolean[] used = new boolean[1000];
        int f = 0;
        int takenSeed = 0;
        for (int id : order) {
            if (takenSeed >= seeds || f >= 10) {
                break;
            }
            if (id == ban || used[id]) {
                continue;
            }
            used[id] = true;
            out[f++] = id;
            takenSeed++;
            int[] neigh = neighByRank(order, id, ban, used, Math.min(per, 10 - f));
            for (int nb : neigh) {
                if (f >= 10) {
                    break;
                }
                out[f++] = nb;
            }
        }
        return Arrays.copyOf(out, f);
    }

    /** 百位较准、十位个位差得远时，用定位复式把 10 注铺在十位和个位上。 */
    private static void boxProbe(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int[][] shapes = {
                {1, 3, 3}, {1, 2, 5}, {1, 5, 2}, {2, 2, 2}, {2, 3, 3}, {1, 4, 4},
                {2, 4, 2}, {3, 3, 3}, {1, 1, 1}
        };
        sb.append("===== ").append(name).append(" 定位复式 =====\n");
        for (int eval : new int[]{50, 200}) {
            int from = n - eval;
            int[] posHit = new int[3];
            int[] pos2 = new int[3];
            int draws = 0;
            for (int t = from; t < n; t++) {
                ExpertView ex = expert(Arrays.copyOf(digits, t), pl3);
                draws++;
                for (int p = 0; p < 3; p++) {
                    int[] top = topK(ex.pos[p], 2);
                    if (top[0] == digits[t][p]) {
                        posHit[p]++;
                    }
                    if (top[0] == digits[t][p] || top[1] == digits[t][p]) {
                        pos2[p]++;
                    }
                }
            }
            sb.append(String.format(Locale.ROOT, "近%d 定位第1 百%d 十%d 个%d | 前2 百%d 十%d 个%d / %d%n",
                    eval, posHit[0], posHit[1], posHit[2], pos2[0], pos2[1], pos2[2], draws));
        }
        for (int[] shape : shapes) {
            sb.append(String.format(Locale.ROOT, "百%d十%d个%d", shape[0], shape[1], shape[2]));
            for (int eval : new int[]{30, 50, 80, 200}) {
                int hit = 0;
                int grp = 0;
                int from = n - eval;
                for (int t = from; t < n; t++) {
                    ExpertView ex = expert(Arrays.copyOf(digits, t), pl3);
                    int id = code(digits[t]);
                    int[] ten = boxTen(ex.pos, ex.order, code(digits[t - 1]), shape[0], shape[1], shape[2]);
                    if (has(ten, id)) {
                        hit++;
                    }
                    if (groupHit(ten, id)) {
                        grp++;
                    }
                }
                sb.append(String.format(Locale.ROOT, "/%d:%d直%d组", eval, hit, grp));
            }
            sb.append('\n');
        }
        sb.append("头名邻号十个");
        for (int eval : new int[]{30, 50, 80, 200}) {
            int hit = 0;
            int from = n - eval;
            for (int t = from; t < n; t++) {
                ExpertView ex = expert(Arrays.copyOf(digits, t), pl3);
                int id = code(digits[t]);
                int[] ten = neighborDigits(ex.pos, code(digits[t - 1]));
                if (has(ten, id)) {
                    hit++;
                }
            }
            sb.append(String.format(Locale.ROOT, "/%d:%d", eval, hit));
        }
        sb.append('\n');
    }

    private static int[] boxTen(double[][] pos, int[] order, int ban, int k0, int k1, int k2) {
        return boxTen(pos, order, ban, k0, k1, k2, 10);
    }

    private static int[] boxTen(double[][] pos, int[] order, int ban, int k0, int k1, int k2, int limit) {
        int cap = Math.max(1, limit);
        int[] a = topK(pos[0], k0);
        int[] b = topK(pos[1], k1);
        int[] c = topK(pos[2], k2);
        int m = k0 * k1 * k2;
        int[] combos = new int[m];
        double[] sc = new double[m];
        int n = 0;
        for (int i = 0; i < k0; i++) {
            for (int j = 0; j < k1; j++) {
                for (int u = 0; u < k2; u++) {
                    int id = a[i] * 100 + b[j] * 10 + c[u];
                    combos[n] = id;
                    sc[n] = pos[0][a[i]] + pos[1][b[j]] + pos[2][c[u]];
                    n++;
                }
            }
        }
        Integer[] ix = new Integer[m];
        for (int i = 0; i < m; i++) {
            ix[i] = i;
        }
        Arrays.sort(ix, (x, y) -> {
            int cmp = Double.compare(sc[y], sc[x]);
            return cmp != 0 ? cmp : Integer.compare(combos[x], combos[y]);
        });
        int[] out = new int[cap];
        boolean[] used = new boolean[1000];
        int f = 0;
        for (int i : ix) {
            int id = combos[i];
            if (id == ban || used[id]) {
                continue;
            }
            used[id] = true;
            out[f++] = id;
            if (f == cap) {
                return out;
            }
        }
        for (int id : order) {
            if (f == cap) {
                break;
            }
            if (id == ban || used[id]) {
                continue;
            }
            used[id] = true;
            out[f++] = id;
        }
        return Arrays.copyOf(out, f);
    }

    /** 每位取分最高的数字，十位和个位再加左右邻号，凑满 10 注。 */
    private static int[] neighborDigits(double[][] pos, int ban) {
        int[] best = new int[3];
        for (int p = 0; p < 3; p++) {
            best[p] = topK(pos[p], 1)[0];
        }
        int[] tens = digitSpan(best[1]);
        int[] ones = digitSpan(best[2]);
        double[][] fake = new double[3][10];
        for (int p = 0; p < 3; p++) {
            System.arraycopy(pos[p], 0, fake[p], 0, 10);
        }
        int[] a = {best[0]};
        return boxFrom(a, tens, ones, fake, ban);
    }

    private static int[] digitSpan(int d) {
        int[] raw = {d, d - 1, d + 1, d - 2, d + 2};
        int[] out = new int[5];
        boolean[] used = new boolean[10];
        int f = 0;
        for (int x : raw) {
            if (x < 0 || x > 9 || used[x]) {
                continue;
            }
            used[x] = true;
            out[f++] = x;
        }
        return Arrays.copyOf(out, f);
    }

    private static int[] boxFrom(int[] a, int[] b, int[] c, double[][] pos, int ban) {
        double[][] score = new double[3][10];
        for (int p = 0; p < 3; p++) {
            System.arraycopy(pos[p], 0, score[p], 0, 10);
        }
        int k0 = a.length;
        int k1 = b.length;
        int k2 = c.length;
        int m = k0 * k1 * k2;
        int[] combos = new int[m];
        double[] sc = new double[m];
        int n = 0;
        for (int i = 0; i < k0; i++) {
            for (int j = 0; j < k1; j++) {
                for (int u = 0; u < k2; u++) {
                    combos[n] = a[i] * 100 + b[j] * 10 + c[u];
                    sc[n] = score[0][a[i]] + score[1][b[j]] + score[2][c[u]];
                    n++;
                }
            }
        }
        Integer[] ix = new Integer[m];
        for (int i = 0; i < m; i++) {
            ix[i] = i;
        }
        Arrays.sort(ix, (x, y) -> {
            int cmp = Double.compare(sc[y], sc[x]);
            return cmp != 0 ? cmp : Integer.compare(combos[x], combos[y]);
        });
        int[] out = new int[10];
        boolean[] used = new boolean[1000];
        int f = 0;
        for (int i : ix) {
            int id = combos[i];
            if (id == ban || used[id]) {
                continue;
            }
            used[id] = true;
            out[f++] = id;
            if (f == 10) {
                break;
            }
        }
        return Arrays.copyOf(out, f);
    }

    /** 百位较准、十位个位差得远时，用定位复式把 10 注铺在十位和个位上。 */
    private static int[] neighByRank(int[] order, int seed, int ban, boolean[] used, int limit) {
        int[] out = new int[Math.max(0, limit)];
        int f = 0;
        if (limit <= 0) {
            return out;
        }
        for (int id : order) {
            if (id == ban || used[id] || hamming(id, seed) != 1) {
                continue;
            }
            used[id] = true;
            out[f++] = id;
            if (f == limit) {
                break;
            }
        }
        return Arrays.copyOf(out, f);
    }

    /** 针对短周期未命中的几套定位配方：热号、遗漏回补、极冷、冷号。 */
    private static void probeFixes(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        String[] names = {
                "回补8", "热20", "回补8+热20", "回补8+热20+极冷", "五码回补+1热+1冷",
                "回补12", "冷号", "冷号+回补12", "冷号+热12"
        };
        sb.append("===== ").append(name).append(" =====\n");
        for (int kind = 0; kind < names.length; kind++) {
            sb.append(names[kind]);
            for (int eval : new int[]{30, 40, 50, 60, 80, 200}) {
                int from = n - eval;
                int dw = 0;
                int dan = 0;
                int p500 = 0;
                int zx = 0;
                for (int t = from; t < n; t++) {
                    double[][] digit = new double[3][10];
                    int[][] seven = new int[3][7];
                    int[][] three = new int[3][3];
                    for (int p = 0; p < 3; p++) {
                        builtFix(digits, t, p, kind, digit[p], seven[p], three[p]);
                    }
                    boolean all7 = true;
                    boolean any3 = false;
                    for (int p = 0; p < 3; p++) {
                        int actual = digits[t][p];
                        boolean h7 = false;
                        for (int x : seven[p]) {
                            if (x == actual) {
                                h7 = true;
                                break;
                            }
                        }
                        if (!h7) {
                            all7 = false;
                        }
                        for (int x : three[p]) {
                            if (x == actual) {
                                any3 = true;
                                break;
                            }
                        }
                    }
                    if (all7) {
                        dw++;
                    }
                    if (any3) {
                        dan++;
                    }
                    int lastId = code(digits[t - 1]);
                    double[] score = new double[1000];
                    for (int a = 0; a < 10; a++) {
                        for (int b = 0; b < 10; b++) {
                            for (int c = 0; c < 10; c++) {
                                int id = a * 100 + b * 10 + c;
                                double v = digit[0][a] + digit[1][b] + digit[2][c];
                                if (a == b && b == c) {
                                    v -= 4;
                                } else if (a != b && b != c && a != c) {
                                    v += 0.12;
                                }
                                if (id == lastId) {
                                    v -= 6;
                                }
                                score[id] = v;
                            }
                        }
                    }
                    int[] order = rankOrder(score);
                    int id = code(digits[t]);
                    if (indexOf(order, id) < 500) {
                        p500++;
                    }
                    if (has(expertTen(order, lastId), id)) {
                        zx++;
                    }
                }
                sb.append(String.format(Locale.ROOT, " | %d期 七码%d 胆%d 大底%d 三码%d", eval, dw, dan, p500, zx));
            }
            sb.append('\n');
        }
    }

    private static void builtFix(int[][] d, int end, int pos, int kind, double[] score, int[] seven, int[] three) {
        double[] bell8 = bellAt(d, end, pos, 8);
        double[] bell12 = bellAt(d, end, pos, 12);
        double[] hot20 = hotZ(d, end, pos, 20);
        double[] hot12 = hotZ(d, end, pos, 12);
        int[] omit = omission(d, end, pos);
        for (int x = 0; x < 10; x++) {
            double cold = Math.min(2.0, omit[x] / 18.0);
            score[x] = switch (kind) {
                case 0 -> bell8[x];
                case 1 -> hot20[x];
                case 2 -> 3.0 * bell8[x] + hot20[x];
                case 3 -> 3.0 * bell8[x] + hot20[x] + (omit[x] >= 17 ? 2.4 : 0);
                case 4 -> 3.0 * bell8[x] + hot20[x];
                case 5 -> bell12[x];
                case 6 -> cold;
                case 7 -> 2.0 * cold + bell12[x];
                default -> 2.0 * cold + hot12[x];
            };
        }
        if (kind == 4) {
            int[] byBell = topK(bell8, 5);
            boolean[] used = new boolean[10];
            for (int i = 0; i < 5; i++) {
                seven[i] = byBell[i];
                used[byBell[i]] = true;
            }
            int hotPick = -1;
            double bestH = -1e9;
            int coldPick = -1;
            int bestOm = -1;
            for (int x = 0; x < 10; x++) {
                if (used[x]) {
                    continue;
                }
                if (hot20[x] > bestH) {
                    bestH = hot20[x];
                    hotPick = x;
                }
            }
            used[hotPick] = true;
            for (int x = 0; x < 10; x++) {
                if (used[x]) {
                    continue;
                }
                if (omit[x] > bestOm) {
                    bestOm = omit[x];
                    coldPick = x;
                }
            }
            seven[5] = hotPick;
            seven[6] = coldPick;
            int[] byMix = topK(score, 3);
            System.arraycopy(byMix, 0, three, 0, 3);
            return;
        }
        int[] s7 = topK(score, 7);
        int[] s3 = topK(score, 3);
        System.arraycopy(s7, 0, seven, 0, 7);
        System.arraycopy(s3, 0, three, 0, 3);
    }

    /** 短周期未命中：规则方向、遗漏落点、直选名次。 */
    private static void missDigest(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        sb.append("===== ").append(name).append(" 未命中 =====\n");
        for (int eval : new int[]{20, 30, 40, 50, 60, 80}) {
            int from = n - eval;
            int posN = 0;
            int posMiss = 0;
            int dw = 0;
            int dan = 0;
            int parityOn = 0;
            int parityHelp = 0;
            int parityHurt = 0;
            int sizeOn = 0;
            int sizeHelp = 0;
            int sizeHurt = 0;
            int primeOn = 0;
            int primeHelp = 0;
            int primeHurt = 0;
            int killHurt = 0;
            int killMiss = 0;
            int[] omitMiss = new int[4];
            int[] rankBand = new int[6];
            int poolOut = 0;
            int sumFar = 0;
            int spanOff = 0;
            int tailHurt = 0;
            int spanKill = 0;
            for (int t = from; t < n; t++) {
                int[][] hist = Arrays.copyOf(digits, t);
                ExpertView ex = expert(hist, pl3);
                int id = code(digits[t]);
                int rank = indexOf(ex.order, id);
                if (rank < 0) {
                    rank = 999;
                }
                int band = rank < 10 ? 0 : rank < 30 ? 1 : rank < 100 ? 2 : rank < 250 ? 3 : rank < 500 ? 4 : 5;
                rankBand[band]++;
                if (rank >= 500) {
                    poolOut++;
                    int[] draw = digits[t];
                    int sum = draw[0] + draw[1] + draw[2];
                    int lastSum = hist[t - 1][0] + hist[t - 1][1] + hist[t - 1][2];
                    int prevSum = t >= 2 ? hist[t - 2][0] + hist[t - 2][1] + hist[t - 2][2] : lastSum;
                    int dist = Math.abs(sum - sumCenter(lastSum, prevSum));
                    if (dist > 7) {
                        sumFar++;
                    }
                    int span = spanOf(draw);
                    int lastSpan = spanOf(hist[t - 1]);
                    int spanMain = lastSpan <= 1 ? 4 : (lastSpan >= 8 ? 5 : lastSpan);
                    if (span != spanMain && Math.abs(span - spanMain) != 1) {
                        spanOff++;
                    }
                    if (t >= 2 && spanOf(hist[t - 2]) == lastSpan && span == lastSpan) {
                        spanKill++;
                    }
                    if (t >= 2 && (prevSum % 10) == (lastSum % 10) && sum % 10 == lastSum % 10) {
                        tailHurt++;
                    }
                }
                boolean all7 = true;
                boolean any3 = false;
                boolean[] kill = pl3 ? killHot(hist, t, 4, 2) : killHot(hist, t, 5, 1);
                for (int p = 0; p < 3; p++) {
                    posN++;
                    int actual = digits[t][p];
                    int[] top7 = topK(ex.pos[p], 7);
                    int[] top3 = topK(ex.pos[p], 3);
                    boolean h7 = false;
                    for (int x : top7) {
                        if (x == actual) {
                            h7 = true;
                            break;
                        }
                    }
                    if (!h7) {
                        all7 = false;
                        posMiss++;
                        int om = omission(hist, t, p)[actual];
                        int ob = om <= 3 ? 0 : om <= 9 ? 1 : om <= 16 ? 2 : 3;
                        omitMiss[ob]++;
                        if (kill[actual]) {
                            killMiss++;
                        }
                    } else {
                        for (int x : top3) {
                            if (x == actual) {
                                any3 = true;
                                break;
                            }
                        }
                    }
                    if (!h7) {
                        for (int x : top3) {
                            if (x == actual) {
                                any3 = true;
                                break;
                            }
                        }
                    }
                    if (kill[actual]) {
                        killHurt++;
                    }
                    int oddCnt = sideCount(hist, t, p, 5, true);
                    int bigCnt = sideCount(hist, t, p, 5, false);
                    int primeCnt = primeCount(hist, t, p, 5);
                    if (oddCnt >= 4 || oddCnt <= 1) {
                        parityOn++;
                        boolean wantEven = oddCnt >= 4;
                        boolean hitSide = wantEven ? (actual % 2 == 0) : (actual % 2 == 1);
                        if (hitSide) {
                            parityHelp++;
                        } else {
                            parityHurt++;
                        }
                    }
                    if (bigCnt >= 4 || bigCnt <= 1) {
                        sizeOn++;
                        boolean wantSmall = bigCnt >= 4;
                        boolean hitSide = wantSmall ? actual < 5 : actual >= 5;
                        if (hitSide) {
                            sizeHelp++;
                        } else {
                            sizeHurt++;
                        }
                    }
                    if (primeCnt >= 4 || primeCnt <= 1) {
                        primeOn++;
                        boolean wantHe = primeCnt >= 4;
                        boolean hitSide = wantHe ? !isPrime(actual) : isPrime(actual);
                        if (hitSide) {
                            primeHelp++;
                        } else {
                            primeHurt++;
                        }
                    }
                }
                if (all7) {
                    dw++;
                }
                if (any3) {
                    dan++;
                }
            }
            sb.append(String.format(Locale.ROOT,
                    "近%d 七码%d 胆%d | 定位未中%d/%d 遗漏0-3=%d 4-9=%d 10-16=%d 17+=%d 杀号打中开奖%d(其中七码未中%d)%n",
                    eval, dw, dan, posMiss, posN, omitMiss[0], omitMiss[1], omitMiss[2], omitMiss[3], killHurt, killMiss));
            sb.append(String.format(Locale.ROOT,
                    "  奇偶触发%d 对%d 错%d | 大小触发%d 对%d 错%d | 质合触发%d 对%d 错%d%n",
                    parityOn, parityHelp, parityHurt, sizeOn, sizeHelp, sizeHurt, primeOn, primeHelp, primeHurt));
            sb.append(String.format(Locale.ROOT,
                    "  直选名次 1-10=%d 11-30=%d 31-100=%d 101-250=%d 251-500=%d 500+=%d | 大底未中%d 其中和值偏离%d 跨度偏离%d 杀跨%d 杀和尾%d%n",
                    rankBand[0], rankBand[1], rankBand[2], rankBand[3], rankBand[4], rankBand[5],
                    poolOut, sumFar, spanOff, spanKill, tailHurt));
        }
    }

    /** 每位用更短窗口里命中最高的定位模型，窗口长度逐个试。 */
    private static void shortSearch(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        sb.append("===== ").append(name).append(" 短窗模型 =====\n");
        int[] evals = {30, 50, 80, 200};
        sb.append("静态七码");
        for (DigitMode mode : DigitMode.values()) {
            sb.append(' ').append(mode);
            for (int eval : evals) {
                int hit = 0;
                int from = n - eval;
                for (int t = from; t < n; t++) {
                    boolean all = true;
                    for (int p = 0; p < 3; p++) {
                        if (!posHit(modeScore(mode, digits, t, p), digits[t][p], 7)) {
                            all = false;
                            break;
                        }
                    }
                    if (all) {
                        hit++;
                    }
                }
                sb.append(String.format(Locale.ROOT, "/%d:%d", eval, hit));
            }
            sb.append('\n');
        }
        for (int w : new int[]{16, 24, 30, 40, 50, 60, 80}) {
            int[] lens = {40, 50, 60, 80, 200};
            sb.append(String.format(Locale.ROOT, "走前W=%d", w));
            for (int eval : lens) {
                int dw = 0;
                int dan = 0;
                int p500 = 0;
                int p250 = 0;
                int zx = 0;
                int from = n - eval;
                for (int t = from; t < n; t++) {
                    double[][] digit = new double[3][10];
                    boolean all7 = true;
                    boolean any3 = false;
                    for (int p = 0; p < 3; p++) {
                        DigitMode mode = pickMode(digits, t, p, w, 7, pl3);
                        digit[p] = modeScore(mode, digits, t, p);
                        if (!posHit(digit[p], digits[t][p], 7)) {
                            all7 = false;
                        }
                        if (posHit(digit[p], digits[t][p], 3)) {
                            any3 = true;
                        }
                    }
                    if (all7) {
                        dw++;
                    }
                    if (any3) {
                        dan++;
                    }
                    int lastId = code(digits[t - 1]);
                    double[] score = new double[1000];
                    for (int a = 0; a < 10; a++) {
                        for (int b = 0; b < 10; b++) {
                            for (int c = 0; c < 10; c++) {
                                int id = a * 100 + b * 10 + c;
                                double v = digit[0][a] + digit[1][b] + digit[2][c];
                                if (a == b && b == c) {
                                    v -= 4;
                                } else if (a != b && b != c && a != c) {
                                    v += 0.12;
                                }
                                if (id == lastId) {
                                    v -= 6;
                                }
                                score[id] = v;
                            }
                        }
                    }
                    int[] order = rankOrder(score);
                    int id = code(digits[t]);
                    if (indexOf(order, id) < 500) {
                        p500++;
                    }
                    if (indexOf(order, id) < 250) {
                        p250++;
                    }
                    if (has(expertTen(order, lastId), id)) {
                        zx++;
                    }
                }
                sb.append(String.format(Locale.ROOT, " | %d期 七码%d 胆%d 大底%d 250=%d 三码%d",
                        eval, dw, dan, p500, p250, zx));
            }
            sb.append('\n');
        }
    }

    public static void main(String[] args) throws Exception {
        int[][] sd = loadGame(true);
        int[][] pl = loadGame(false);
        if (args != null && args.length > 0 && "rep".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("三码与上一期预测相同的号码，看这些重复号是否直选或组选命中。\n");
            repeatScan(sb, "福彩3D", sd, false);
            repeatScan(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_repeat.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "wscan".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("窗口10到100，每位±1环移，整组20注一起平移。按近50期直选、其次组选、再看近200期直选留窗口。\n");
            windowScan(sb, "福彩3D", sd, false);
            windowScan(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_window.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "adj".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("三码20注按窗口里的百十个差值做固定平移。差值每期重算，不写死。\n");
            sb.append("近50是最近50期，前50是再往前50期。目标近50直选5、组选10。\n");
            shiftSearch(sb, "福彩3D", sd, false);
            shiftSearch(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_shift.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "g20".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("20注按组选号。目标近50期直选5、组选10。随机20注直选约1次，20个组六约6次组选。\n");
            sb.append("前50是再往前的50期，用来看是不是只贴住最近50期。\n");
            groupSearch(sb, "福彩3D", sd, false);
            groupSearch(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_group20.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "box2".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("三码定位复式换底。直选基线：30期0.3 50期0.5 80期0.8 200期2。\n");
            boxModels(sb, "福彩3D", sd, false);
            boxModels(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_box.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "gap".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("三码10注与开奖号的差。直选基线1%，组选约看同号不同序。\n");
            sanmaGap(sb, "福彩3D", sd, false);
            sanmaGap(sb, "排列三", pl, true);
            boxProbe(sb, "福彩3D", sd, false);
            boxProbe(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_gap.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "fix".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("专项配方。七码基线34.3% 胆65.7% 大底50% 三码1%。\n");
            probeFixes(sb, "福彩3D", sd, false);
            probeFixes(sb, "排列三", pl, true);
            Path out = Path.of("reports/short_fix.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "miss".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("短周期未命中与窗口试算。七码全中基线34.3%，胆码≥1位65.7%，大底500=50%，三码10注=1%。\n\n");
            missDigest(sb, "福彩3D", sd, false);
            shortSearch(sb, "福彩3D", sd, false);
            missDigest(sb, "排列三", pl, true);
            shortSearch(sb, "排列三", pl, true);
            Path out = Path.of("reports/short_miss.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        StringBuilder sb = new StringBuilder();
        boolean fast = args != null && args.length > 0 && "expert".equals(args[0]);
        sb.append("1000注直选打分对照。基线：三码20注直选2%  大底500=50%  250注=25%  七码全中34.3%  胆码≥1位65.7%\n");
        sb.append("h2 是后一半，用来锁定；前一半只参与稳健分 min(前,后)。\n\n");
        if (!fast) {
            diagnose(sd, "福彩3D", sb);
            runGame("福彩3D", sd, sb);
            sb.append('\n');
            diagnose(pl, "排列三", sb);
            runGame("排列三", pl, sb);
        }
        sb.append("\n===== 生产锁定（对外接口）=====\n");
        sb.append("3D=").append(describe(false)).append("  排三=").append(describe(true)).append('\n');
        reportLocked(sb, "福彩3D", sd, false);
        reportLocked(sb, "排列三", pl, true);
        Path out = Path.of("reports/flow1000_tune.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println(sb);
    }

    private static void reportLocked(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        for (int eval : new int[]{50, 200}) {
            int zx = 0;
            int grp = 0;
            int p500 = 0;
            int g500 = 0;
            int p250 = 0;
            int dw = 0;
            int dan = 0;
            int sh = 0;
            int from = n - eval;
            for (int t = from; t < n; t++) {
                int[][] hist = Arrays.copyOf(digits, t);
                int id = code(digits[t]);
                int[] ten = sanma(hist, pl3);
                int[] pool500 = pool(hist, pl3, 500);
                int[] pool250 = pool(hist, pl3, 250);
                int[][] top7 = digitTop(hist, pl3, 7);
                int[][] top3 = digitTop(hist, pl3, 3);
                int[][] pairs = shuangDan(hist, pl3);
                if (has(ten, id)) {
                    zx++;
                }
                if (groupHit(ten, id)) {
                    grp++;
                }
                if (has(pool500, id)) {
                    p500++;
                }
                if (groupHit(pool500, id)) {
                    g500++;
                }
                if (has(pool250, id)) {
                    p250++;
                }
                boolean all7 = true;
                boolean any3 = false;
                for (int p = 0; p < 3; p++) {
                    boolean h7 = false;
                    for (int x : top7[p]) {
                        if (x == digits[t][p]) {
                            h7 = true;
                            break;
                        }
                    }
                    if (!h7) {
                        all7 = false;
                    }
                    for (int x : top3[p]) {
                        if (x == digits[t][p]) {
                            any3 = true;
                            break;
                        }
                    }
                }
                if (all7) {
                    dw++;
                }
                if (any3) {
                    dan++;
                }
                boolean[] present = drawSet(digits[t]);
                for (int[] pair : pairs) {
                    if (present[pair[0]] && present[pair[1]]) {
                        sh++;
                        break;
                    }
                }
            }
            sb.append(String.format(Locale.ROOT,
                    "%s 近%d期 | 三码直选 %d 组选 %d | 大底500直选 %d 组选 %d | 过拟合250直选 %d | 七码 %d | 胆码 %d | 双胆 %d%n",
                    name, eval, zx, grp, p500, g500, p250, dw, dan, sh));
        }
    }

    private static void diagnose(int[][] d, String name, StringBuilder sb) {
        int n = d.length;
        int slots = 0;
        int rep = 0;
        int ham0 = 0;
        int ham1 = 0;
        int ham2 = 0;
        int anyPos = 0;
        int[] freq = new int[10];
        double s1 = 0;
        double s2 = 0;
        double prod = 0;
        double prev = 0;
        boolean hasPrev = false;
        for (int i = 1; i < n; i++) {
            int ham = 0;
            for (int p = 0; p < 3; p++) {
                slots++;
                freq[d[i][p]]++;
                if (d[i][p] == d[i - 1][p]) {
                    rep++;
                    ham++;
                }
            }
            int dist = 3 - ham;
            if (dist == 0) {
                ham0++;
            } else if (dist == 1) {
                ham1++;
            } else if (dist == 2) {
                ham2++;
            }
            if (ham > 0) {
                anyPos++;
            }
            int sum = d[i][0] + d[i][1] + d[i][2];
            if (hasPrev) {
                prod += prev * sum;
            }
            s1 += sum;
            s2 += (double) sum * sum;
            prev = sum;
            hasPrev = true;
        }
        int pairs = n - 2;
        double mean = s1 / (n - 1);
        double var = s2 / (n - 1) - mean * mean;
        double cov = pairs > 0 ? (prod - pairs * mean * mean) / pairs : 0;
        double corr = var > 1e-6 ? cov / var : 0;
        double exp = (n - 1) * 3 / 10.0;
        double chi = 0;
        for (int x = 0; x < 10; x++) {
            double df = freq[x] - exp;
            chi += df * df / exp;
        }
        int denom = Math.max(1, n - 1);
        sb.append(String.format(Locale.ROOT,
                "%s 期数=%d 同位重号=%.3f(理论0.100) 至少同位重=%.3f(0.271) 汉明1=%.3f(0.027) 和值相关=%.3f 数字卡方=%.1f%n",
                name, n, rep / (double) slots, anyPos / (double) denom, ham1 / (double) denom, corr, chi));
    }

    private static void runGame(String name, int[][] digits, StringBuilder sb) {
        int n = digits.length;
        int eval = n >= 2500 ? 300 : 200;
        int warm = 40;
        if (n < eval + warm + 120) {
            eval = Math.max(60, n - warm - 120);
        }
        int halfCut = n - eval / 2;
        sb.append("---------- ").append(name).append(" 评估").append(eval).append("期 ----------\n");
        sb.append(String.format(Locale.ROOT,
                "%-8s %5s %5s %5s | %5s %5s %5s %5s %5s %5s | %5s %5s %5s %5s %5s | %s%n",
                "策略", "位1", "位3", "位7", "直10", "复4", "复5", "邻号", "爆30", "自选",
                "500", "爆500", "250", "七码", "胆", "后半直选 复5/爆30/自选  后半500/七码/胆"));
        Stat best = null;
        Strategy bestSt = null;
        String bestMethod = "";
        int bestRobust = -1;
        for (Strategy st : Strategy.values()) {
            Stat s = evalStrategy(digits, st, n - eval - warm, n, halfCut);
            sb.append(String.format(Locale.ROOT,
                    "%-8s %4.0f%% %4.0f%% %4.0f%% | %4.1f%% %4.1f%% %4.1f%% %4.1f%% %4.1f%% %4.1f%% | %4.0f%% %5.0f%% %4.0f%% %4.0f%% %4.0f%% | 复5 %d/%d 爆 %d/%d 自 %d/%d  500 %d 七 %d 胆 %d%n",
                    st.name(),
                    pct(s.p1, s.n * 3), pct(s.p3, s.n * 3), pct(s.p7, s.n * 3),
                    pct(s.t10, s.n), pct(s.box4, s.n), pct(s.box5, s.n), pct(s.ham, s.n), pct(s.b30, s.n), pct(s.meta, s.n),
                    pct(s.p500, s.n), pct(s.b500, s.n), pct(s.p250, s.n), pct(s.dw, s.n), pct(s.dan, s.n),
                    s.box5b, s.h2, s.b30b, s.h2, s.metab, s.h2, s.p500b, s.dwb, s.danb));
            sb.append(String.format(Locale.ROOT,
                    "         双胆 %.0f%% 后半 %.0f%% | 七码覆盖10 %.1f%% 后半 %d/%d | 冷号10 %.1f%% 后半 %d/%d%n",
                    pct(s.shuang, s.n), pct(s.shuangB, s.h2),
                    pct(s.cover, s.n), s.coverB, s.h2,
                    pct(s.cold10, s.n), s.cold10b, s.h2));
            int[][] methods = {
                    {s.t10 - s.t10b, s.t10b, 0},
                    {s.box4 - s.box4b, s.box4b, 1},
                    {s.box5 - s.box5b, s.box5b, 2},
                    {s.ham - s.hamb, s.hamb, 3},
                    {s.b30 - s.b30b, s.b30b, 4},
                    {s.meta - s.metab, s.metab, 5}
            };
            String[] names = {"T10", "BOX4", "BOX5", "HAM", "B30", "META"};
            for (int i = 0; i < methods.length; i++) {
                int robust = Math.min(methods[i][0], methods[i][1]);
                boolean poolOk = s.p500b * 100 >= s.h2 * 47;
                boolean dwOk = s.dwb * 100 >= s.h2 * 32;
                boolean danOk = s.danb * 100 >= s.h2 * 64;
                if (!poolOk || !dwOk || !danOk) {
                    continue;
                }
                if (robust > bestRobust || (robust == bestRobust && methods[i][1] > (best == null ? -1 : secondOf(best, bestMethod)))) {
                    bestRobust = robust;
                    best = s;
                    bestSt = st;
                    bestMethod = names[i];
                }
            }
            System.out.println(name + " " + st + " 完成 直10=" + s.t10 + "/" + s.n + " 复5=" + s.box5 + " 爆30=" + s.b30);
        }
        sb.append("锁定建议: ").append(bestSt).append(" / ").append(bestMethod)
                .append(" 稳健直选=").append(bestRobust)
                .append(" 后半500=").append(best == null ? 0 : best.p500b)
                .append(" 后半爆500=").append(best == null ? 0 : best.b500b)
                .append(" 组选直10=").append(best == null ? 0 : best.g10)
                .append(" 组选复5=").append(best == null ? 0 : best.gBox)
                .append('\n');
    }

    private static int secondOf(Stat s, String method) {
        return switch (method) {
            case "T10" -> s.t10b;
            case "BOX4" -> s.box4b;
            case "BOX5" -> s.box5b;
            case "HAM" -> s.hamb;
            case "B30" -> s.b30b;
            default -> s.metab;
        };
    }

    private static double pct(int hit, int n) {
        return n <= 0 ? 0 : (100.0 * hit / n);
    }

    private static Stat evalStrategy(int[][] digits, Strategy st, int start, int n, int halfCut) {
        Stat s = new Stat();
        int[] rankAt = new int[n];
        Arrays.fill(rankAt, -1);
        int[] methodHits = new int[5];
        for (int t = start; t < n; t++) {
            Bundle b = buildBundle(digits, t, st, rankAt);
            int id = code(digits[t]);
            int rank = indexOf(b.order, id);
            rankAt[t] = rank;
            boolean[] hitM = new boolean[5];
            hitM[0] = has(b.box5, id);
            hitM[1] = has(b.box4, id);
            hitM[2] = has(b.t10, id);
            hitM[3] = has(b.hamPicks, id);
            hitM[4] = has(b.b30, id);
            int metaBest = 0;
            for (int m = 1; m < 5; m++) {
                if (methodHits[m] > methodHits[metaBest]) {
                    metaBest = m;
                }
            }
            boolean metaHit = hitM[metaBest];
            if (t >= n - (n - halfCut) * 2 && t >= start + 40) {
                // counted below via halfCut; warmup is start..start+40 which is outside eval
            }
            boolean count = t >= halfCut - (halfCut - (n - (n - halfCut) * 2)) && t >= n - (n - (halfCut - (n - halfCut)));
            int evalStart = n - (n - halfCut) * 2;
            count = t >= evalStart;
            if (!count) {
                for (int m = 0; m < 5; m++) {
                    if (hitM[m]) {
                        methodHits[m]++;
                    }
                }
                continue;
            }
            boolean second = t >= halfCut;
            s.n++;
            if (second) {
                s.h2++;
            }
            if (hitM[2]) {
                s.t10++;
                if (second) {
                    s.t10b++;
                }
            }
            if (hitM[1]) {
                s.box4++;
                if (second) {
                    s.box4b++;
                }
            }
            if (hitM[0]) {
                s.box5++;
                if (second) {
                    s.box5b++;
                }
                if (groupHit(b.box5, id)) {
                    s.gBox++;
                }
            }
            if (hitM[3]) {
                s.ham++;
                if (second) {
                    s.hamb++;
                }
            }
            if (hitM[4]) {
                s.b30++;
                if (second) {
                    s.b30b++;
                }
            }
            if (metaHit) {
                s.meta++;
                if (second) {
                    s.metab++;
                }
            }
            int[] cover = latin7(b.digit, code(digits[t - 1]));
            if (has(cover, id)) {
                s.cover++;
                if (second) {
                    s.coverB++;
                }
            }
            if (has(coldTickets(digits, t), id)) {
                s.cold10++;
                if (second) {
                    s.cold10b++;
                }
            }
            if (groupHit(b.t10, id)) {
                s.g10++;
            }
            if (rank < 500) {
                s.p500++;
                if (second) {
                    s.p500b++;
                }
            }
            int lo500 = bestStart(rankAt, Math.max(0, t - 40), t, 500, 50);
            if (rank >= lo500 && rank < lo500 + 500) {
                s.b500++;
                if (second) {
                    s.b500b++;
                }
            }
            if (rank < 250) {
                s.p250++;
            }
            boolean d1 = false;
            boolean d3 = false;
            boolean d7 = true;
            boolean any3 = false;
            for (int p = 0; p < 3; p++) {
                boolean h1 = posHit(b.digit[p], digits[t][p], 1);
                boolean h3 = posHit(b.digit[p], digits[t][p], 3);
                boolean h7 = posHit(b.digit[p], digits[t][p], 7);
                if (h1) {
                    s.p1++;
                    d1 = true;
                }
                if (h3) {
                    s.p3++;
                    any3 = true;
                }
                if (h7) {
                    s.p7++;
                } else {
                    d7 = false;
                }
            }
            if (d7) {
                s.dw++;
                if (second) {
                    s.dwb++;
                }
            }
            if (any3) {
                s.dan++;
                if (second) {
                    s.danb++;
                }
            }
            int[][] pairs = shuang(b.digit, digits, t);
            boolean[] pr = drawSet(digits[t]);
            for (int[] pair : pairs) {
                if (pr[pair[0]] && pr[pair[1]]) {
                    s.shuang++;
                    if (second) {
                        s.shuangB++;
                    }
                    break;
                }
            }
            for (int m = 0; m < 5; m++) {
                if (hitM[m]) {
                    methodHits[m]++;
                }
            }
            if (d1) {
                // touch to keep the flag read for future tie-breaks
            }
        }
        return s;
    }

    private static int[][] loadGame(boolean is3d) {
        String url = is3d ? "http://data.17500.cn/3d_asc.txt" : "http://data.17500.cn/pl3_asc.txt";
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 400) {
                int[][] online = parseAsc(decode(resp.body()));
                if (online.length > 500) {
                    System.out.println("在线 " + url + " 期数=" + online.length);
                    return online;
                }
            }
        } catch (Exception e) {
            System.out.println("在线失败 " + url + ": " + e.getMessage());
        }
        String xlsx = is3d ? "D:/彩票/3D.xlsx" : "D:/彩票/排列三.xlsx";
        int[][] local = parseXlsx(Path.of(xlsx));
        System.out.println("本地 " + xlsx + " 期数=" + local.length);
        return local;
    }

    private static String decode(byte[] raw) {
        String utf = new String(raw, StandardCharsets.UTF_8);
        if (utf.indexOf('\uFFFD') >= 0) {
            return new String(raw, Charset.forName("GBK"));
        }
        return utf;
    }

    private static int[][] parseAsc(String text) {
        List<int[]> rows = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.contains("期号")) {
                continue;
            }
            String[] tokens = line.split("[\\s,\\t]+");
            int issue = -1;
            for (int i = 0; i < tokens.length; i++) {
                if (tokens[i].matches("\\d{5,8}")) {
                    issue = i;
                    break;
                }
            }
            if (issue < 0 || issue + 4 >= tokens.length) {
                continue;
            }
            String d1 = tokens[issue + 2];
            String d2 = tokens[issue + 3];
            String d3 = tokens[issue + 4];
            if (d1.length() != 1 || d2.length() != 1 || d3.length() != 1) {
                if (d1.matches("\\d{3}")) {
                    d2 = d1.substring(1, 2);
                    d3 = d1.substring(2, 3);
                    d1 = d1.substring(0, 1);
                } else {
                    continue;
                }
            }
            rows.add(new int[]{d1.charAt(0) - '0', d2.charAt(0) - '0', d3.charAt(0) - '0'});
        }
        int[][] out = new int[rows.size()][3];
        for (int i = 0; i < rows.size(); i++) {
            out[i] = rows.get(i);
        }
        return out;
    }

    private static int[][] parseXlsx(Path path) {
        List<String> cells = new ArrayList<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entry = zip.getEntry("xl/worksheets/sheet1.xml");
            try (InputStream in = zip.getInputStream(entry)) {
                String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("<t>([^<]*)</t>").matcher(xml);
                while (m.find()) {
                    cells.add(m.group(1));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取失败 " + path + ": " + e.getMessage(), e);
        }
        List<int[]> rows = new ArrayList<>();
        for (int i = 0; i + 3 < cells.size(); i += 4) {
            String a = cells.get(i + 1);
            String b = cells.get(i + 2);
            String c = cells.get(i + 3);
            if (a.length() == 1 && b.length() == 1 && c.length() == 1
                    && Character.isDigit(a.charAt(0)) && Character.isDigit(b.charAt(0)) && Character.isDigit(c.charAt(0))) {
                rows.add(new int[]{a.charAt(0) - '0', b.charAt(0) - '0', c.charAt(0) - '0'});
            }
        }
        int[][] out = new int[rows.size()][3];
        for (int i = 0; i < rows.size(); i++) {
            out[i] = rows.get(i);
        }
        return out;
    }
}
