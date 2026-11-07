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
 * 打分。三码 20 注按最近 30 期走期：直选至少 4 期，组选尽量多。
 * 每期按离上一期最近的号码截取，避免 10 期窗口里 20 注基本不变。3D 和排列三各自现算。
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

    /** 旧平移扫描还在用。线上三码不再读这些窗口，只按最近 30 期现算。 */
    private static String sdCoverNote = "";
    private static String plCoverNote = "";

    /** 三码平移窗口。衰减小于 1 时，窗口里越近的期越决定百十个差。 */
    static int sdWindow = 16;
    static int plWindow = 13;
    static double sdDecay = 1.0;
    static double plDecay = 0.60;
    static boolean sdRecentTie = false;
    static boolean plRecentTie = true;

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
        String note = pl3 ? plCoverNote : sdCoverNote;
        String game = pl3 ? "排列三" : "福彩3D";
        if (note == null || note.isEmpty()) {
            return game + ":三码20注近30 " + highRule(pl3).text();
        }
        return game + ":三码20注近30 " + note;
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
        return shiftSanma(digits, end, pl3);
    }

    /**
     * 用 train 上选出的近 30 期规则，回放到 prefix 这一期。
     * 用来把「留下」的那条规则写回最近几期，而不是每一期再另选一条。
     */
    public static int[] sanmaLocked(int[][] train, int[][] prefix, boolean pl3) {
        if (prefix == null || prefix.length < 40) {
            return new int[0];
        }
        if (train == null || train.length < 40) {
            return sanma(prefix, pl3);
        }
        return nearSanma(prefix, prefix.length, pl3);
    }

    private static int[] shiftSanma(int[][] digits, int end, boolean pl3) {
        return nearSanma(digits, end, pl3);
    }

    /** 改法之前的整组平移，只留给近30期对照。 */
    private static int[] legacyShiftSanma(int[][] digits, boolean pl3) {
        int end = digits.length;
        int[] base = base20(digits, end, pl3);
        int window = pl3 ? plWindow : sdWindow;
        int[] off = fitShift(digits, end, pl3, window, 1, true);
        return applyShift(base, off[0], off[1], off[2], true, false);
    }

    /**
     * 高胜率规则出本期 20 注。上期整组跟偏差会掉直选，不再搬到本期票上。
     */
    private static int[] nearSanma(int[][] digits, int end, boolean pl3) {
        if (end < 40) {
            return base20(digits, end, pl3);
        }
        TenRule rule = highRule(pl3);
        int[] now = tenTickets(digits, end, pl3, rule);
        int from = Math.max(40, end - 30);
        scoreWindow(digits, from, end, pl3, rule);
        String note = rule.text();
        if (pl3) {
            plCoverNote = note;
        } else {
            sdCoverNote = note;
        }
        return now;
    }

    private static int[] lastNeigh(int id) {
        int[] out = new int[16];
        boolean[] used = new boolean[1000];
        int f = addPlain(out, used, 0, id);
        f = addPerms(out, used, f, id);
        int h = id / 100;
        int t = (id / 10) % 10;
        int u = id % 10;
        for (int d : new int[]{-1, 1}) {
            f = addPlain(out, used, f, ((h + d + 10) % 10) * 100 + t * 10 + u);
            f = addPlain(out, used, f, h * 100 + ((t + d + 10) % 10) * 10 + u);
            f = addPlain(out, used, f, h * 100 + t * 10 + ((u + d + 10) % 10));
        }
        return Arrays.copyOf(out, f);
    }

    private static int[] takeFill(int[] head, int[] tail, int keepHead) {
        int[] out = new int[20];
        boolean[] used = new boolean[1000];
        int f = 0;
        int n = Math.min(keepHead, head == null ? 0 : head.length);
        for (int i = 0; i < n && f < 20; i++) {
            f = addPlain(out, used, f, head[i]);
        }
        if (tail != null) {
            for (int i = 0; i < tail.length && f < 20; i++) {
                f = addPlain(out, used, f, tail[i]);
            }
        }
        if (head != null) {
            for (int i = n; i < head.length && f < 20; i++) {
                f = addPlain(out, used, f, head[i]);
            }
        }
        return Arrays.copyOf(out, f);
    }

    /** 近 30 期留下的规则。直选至少 4，组选取更高的一条，并躲开每期几乎同一批号。 */
    private static TenRule highRule(boolean pl3) {
        if (pl3) {
            // 十位原格子只留 2 个，近 12 期有 9 期十位落在格外，近 8 期直选是 0。
            // 热号仍留 12 注，冷号按遗漏再留一个顺序，个位减 1。近 30 期 3直提到 4直，近 8 期补上 3 直，前 30 期仍有 1 直。
            TenRule rule = new TenRule(9, 30, 6, 4, 2, 2, -1);
            rule.cut = 12;
            rule.keep = 12;
            return rule;
        }
        TenRule rule = new TenRule(9, 30, 7, 3, 2, 1, -1);
        rule.cut = 2;
        return rule;
    }

    private static final int TEN_LOOK = 10;

    private static final class TenRule {
        final int kind;
        final int window;
        final int a;
        final int b;
        final int c;
        final int pos;
        final int delta;
        /** 小于 1 时，窗口里越近的期权重越大，热号不会整窗黏住。 */
        double decay = 1;
        /** 从 20 注末尾换上期邻域的注数。0 表示不换。 */
        int fresh;
        /** 0 嵌套截取，1 格子分最高，2 离上期最近，4 高分并留同组第二序，5 近号加跳号，6 跳号优先，8 热号贴近、冷号按分，12 冷号再留第二序。 */
        int cut;
        /** cut=5 时近号保留的注数，其余留给离上期至少差两位的号。 */
        int keep;
        int zx;
        int grp;

        TenRule(int kind, int window, int a, int b, int c) {
            this(kind, window, a, b, c, -1, 0);
        }

        TenRule(int kind, int window, int a, int b, int c, int pos, int delta) {
            this.kind = kind;
            this.window = window;
            this.a = a;
            this.b = b;
            this.c = c;
            this.pos = pos;
            this.delta = delta;
        }

        String text() {
            String body = switch (kind) {
                case 0 -> "近号原号" + window;
                case 1 -> "近号全序" + window;
                case 2 -> "热位" + window + " " + a + "x" + b + "x" + c;
                case 3 -> "冷位" + window + " " + a + "x" + b + "x" + c;
                case 4 -> "跟号" + window + " " + a + "x" + b + "x" + c;
                case 5 -> "近热6码" + window;
                case 9 -> "热冷各半" + window + " " + a + "x" + b + "x" + c;
                default -> "定位底不平移";
            };
            if (decay < 0.999) {
                body += String.format(Locale.ROOT, " 衰减%.2f", decay);
            }
            if (cut == 1) {
                body += " 高分";
            } else if (cut == 2) {
                body += " 近上期";
            } else if (cut == 4) {
                body += " 二序";
            } else if (cut == 5) {
                body += " 近" + keep + "跳";
            } else if (cut == 6) {
                body += " 跳号优先";
            } else if (cut == 8) {
                body += " 热近冷分" + (keep > 0 ? keep : 10);
            } else if (cut == 12) {
                body += " 热近冷二序" + (keep > 0 ? keep : 10);
            }
            if (pos >= 0) {
                body += (pos == 0 ? " 百" : pos == 1 ? " 十" : " 个") + (delta > 0 ? "+1" : "-1");
            }
            if (fresh > 0) {
                body += " 尾" + fresh + "上期邻域";
            }
            return "近30走期 " + zx + "直" + grp + "组 " + body;
        }
    }

    private static TenRule pickTenRule(int[][] digits, int end, boolean pl3) {
        int from = Math.max(40, end - TEN_LOOK);
        TenRule best = highRule(pl3);
        scoreWindow(digits, from, end, pl3, best);
        for (TenRule rule : tenCandidates()) {
            int[][] cache = new int[end][];
            int z = 0;
            int g = 0;
            for (int t = from; t < end; t++) {
                cache[t] = tenBase(digits, t, pl3, rule);
                int id = code(digits[t]);
                if (has(cache[t], id)) {
                    z++;
                }
                if (groupHit(cache[t], id)) {
                    g++;
                }
            }
            rule.zx = z;
            rule.grp = g;
            if (z > best.zx) {
                best = rule;
            }
            for (int pos = 0; pos < 3; pos++) {
                for (int d : new int[]{-1, 1}) {
                    int nz = 0;
                    int ng = 0;
                    int[] off = {0, 0, 0};
                    off[pos] = d;
                    for (int t = from; t < end; t++) {
                        int[] bets = applyShift(cache[t], off[0], off[1], off[2], true, false);
                        int id = code(digits[t]);
                        if (has(bets, id)) {
                            nz++;
                        }
                        if (groupHit(bets, id)) {
                            ng++;
                        }
                    }
                    if (nz > best.zx) {
                        best = new TenRule(rule.kind, rule.window, rule.a, rule.b, rule.c, pos, d);
                        best.zx = nz;
                        best.grp = ng;
                    }
                }
            }
        }
        return best;
    }

    private static void scoreWindow(int[][] digits, int from, int end, boolean pl3, TenRule rule) {
        int z = 0;
        int g = 0;
        for (int t = from; t < end; t++) {
            int[] bets = tenTickets(digits, t, pl3, rule);
            int id = code(digits[t]);
            if (has(bets, id)) {
                z++;
            }
            if (groupHit(bets, id)) {
                g++;
            }
        }
        rule.zx = z;
        rule.grp = g;
    }

    private static List<TenRule> tenCandidates() {
        List<TenRule> out = new ArrayList<>();
        for (int k = 1; k <= 20; k++) {
            out.add(new TenRule(0, k, 0, 0, 0));
            out.add(new TenRule(1, k, 0, 0, 0));
        }
        int[] windows = {5, 8, 10, 12, 15};
        for (int w : windows) {
            out.add(new TenRule(5, w, 0, 0, 0));
            for (int a = 1; a <= 8; a++) {
                for (int b = 1; b <= 8; b++) {
                    for (int c = 1; c <= 8; c++) {
                        int prod = a * b * c;
                        if (prod < 10 || prod > 24) {
                            continue;
                        }
                        out.add(new TenRule(2, w, a, b, c));
                        out.add(new TenRule(3, w, a, b, c));
                        out.add(new TenRule(4, w, a, b, c));
                    }
                }
            }
            int[][] wide = {{10, 2, 1}, {10, 1, 2}, {2, 10, 1}, {1, 10, 2}, {2, 1, 10}, {1, 2, 10}};
            for (int[] sh : wide) {
                out.add(new TenRule(2, w, sh[0], sh[1], sh[2]));
                out.add(new TenRule(3, w, sh[0], sh[1], sh[2]));
                out.add(new TenRule(4, w, sh[0], sh[1], sh[2]));
            }
        }
        out.add(new TenRule(6, 0, 0, 0, 0));
        return out;
    }

    private static int[] tenTickets(int[][] digits, int end, boolean pl3, TenRule rule) {
        return compose(digits, end, tenBase(digits, end, pl3, rule), rule);
    }

    private static int[] tenBase(int[][] digits, int end, boolean pl3, TenRule rule) {
        return switch (rule.kind) {
            case 0 -> recentCodes(digits, end, rule.window, false);
            case 1 -> recentCodes(digits, end, rule.window, true);
            case 2, 3, 4 -> tenBox(digits, end, rule);
            case 5 -> sixPool20(digits, end, rule.window, rule.decay);
            case 9 -> mixHotCold(digits, end, rule);
            default -> base20(digits, end, pl3);
        };
    }

    private static int[] tenBox(int[][] digits, int end, TenRule rule) {
        int[] take = {Math.min(10, rule.a), Math.min(10, rule.b), Math.min(10, rule.c)};
        double[][] pos = boxPos(digits, end, rule);
        if (rule.cut == 4) {
            return scoreAndSecond(pos, take);
        }
        if (rule.cut <= 0) {
            return boxCross(pos, take);
        }
        int last = end > 0 ? code(digits[end - 1]) : 0;
        if (rule.cut == 5) {
            return nearAndJump(pos, take, last, rule.keep > 0 ? rule.keep : 10);
        }
        if (rule.cut == 6) {
            return farFirst(pos, take, last);
        }
        return boxPick(pos, take, last, rule.cut);
    }

    /** 一部分留离上期最近的号，其余留给至少差两位、定位分更高的号。 */
    private static int[] nearAndJump(double[][] pos, int[] take, int last, int keepNear) {
        int[] near = boxPick(pos, take, last, 2);
        int[] ranked = boxPick(pos, take, last, 1);
        int[] jump = new int[ranked.length];
        int[] close = new int[ranked.length];
        int jf = 0;
        int cf = 0;
        for (int id : ranked) {
            if (posHamming(id, last) >= 2) {
                jump[jf++] = id;
            } else {
                close[cf++] = id;
            }
        }
        int[] tail = new int[jf + cf];
        System.arraycopy(jump, 0, tail, 0, jf);
        System.arraycopy(close, 0, tail, jf, cf);
        return takeFill(near, tail, Math.max(0, Math.min(20, keepNear)));
    }

    private static int posHamming(int a, int b) {
        int h = 0;
        if (a / 100 != b / 100) {
            h++;
        }
        if ((a / 10) % 10 != (b / 10) % 10) {
            h++;
        }
        if (a % 10 != b % 10) {
            h++;
        }
        return h;
    }

    /**
     * 热位和遗漏冷位各留一部分。cut=8/12 时热号仍贴上期，冷号按遗漏分取，不再跟着上期走。
     * keep 是热号注数，其余给冷号。
     */
    private static int[] mixHotCold(int[][] digits, int end, TenRule rule) {
        TenRule hot = new TenRule(2, rule.window, rule.a, rule.b, rule.c);
        hot.decay = rule.decay;
        TenRule cold = new TenRule(3, rule.window, rule.a, rule.b, rule.c);
        int keepHot = rule.keep > 0 ? rule.keep : 10;
        if (rule.cut == 8 || rule.cut == 12) {
            hot.cut = 2;
            cold.cut = 1;
        } else {
            hot.cut = rule.cut;
            cold.cut = rule.cut;
        }
        int[] take = {Math.min(10, rule.a), Math.min(10, rule.b), Math.min(10, rule.c)};
        int[] coldBets = rule.cut == 12
                ? scoreAndSecond(boxPos(digits, end, cold), take)
                : tenBox(digits, end, cold);
        return takeFill(tenBox(digits, end, hot), coldBets, keepHot);
    }

    /** 格子里先留离上期至少差两位的号，按定位分从高到低，剩下再补近号。 */
    private static int[] farFirst(double[][] pos, int[] take, int last) {
        int[] a = topK(pos[0], Math.max(1, take[0]));
        int[] b = topK(pos[1], Math.max(1, take[1]));
        int[] c = topK(pos[2], Math.max(1, take[2]));
        int nAll = a.length * b.length * c.length;
        int[] all = new int[nAll];
        double[] score = new double[nAll];
        int n = 0;
        for (int x : a) {
            for (int y : b) {
                for (int z : c) {
                    int id = x * 100 + y * 10 + z;
                    all[n] = id;
                    double far = posHamming(id, last) >= 2 ? 1000 : 0;
                    score[n] = far + pos[0][x] + pos[1][y] + pos[2][z];
                    n++;
                }
            }
        }
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) {
            ord[i] = i;
        }
        Arrays.sort(ord, (i, j) -> {
            int cmp = Double.compare(score[j], score[i]);
            return cmp != 0 ? cmp : Integer.compare(all[i], all[j]);
        });
        int[] out = new int[Math.min(20, n)];
        boolean[] used = new boolean[1000];
        int f = 0;
        for (int i = 0; i < n && f < 20; i++) {
            f = addPlain(out, used, f, all[ord[i]]);
        }
        return Arrays.copyOf(out, f);
    }

    /** 格子按定位分从高到低取，每个组选最多留两个顺序，用来补组选。 */
    private static int[] scoreAndSecond(double[][] pos, int[] take) {
        int[] a = topK(pos[0], Math.max(1, take[0]));
        int[] b = topK(pos[1], Math.max(1, take[1]));
        int[] c = topK(pos[2], Math.max(1, take[2]));
        int nAll = a.length * b.length * c.length;
        int[] all = new int[nAll];
        double[] score = new double[nAll];
        int n = 0;
        for (int x : a) {
            for (int y : b) {
                for (int z : c) {
                    int id = x * 100 + y * 10 + z;
                    all[n] = id;
                    score[n] = pos[0][x] + pos[1][y] + pos[2][z];
                    n++;
                }
            }
        }
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) {
            ord[i] = i;
        }
        Arrays.sort(ord, (i, j) -> {
            int cmp = Double.compare(score[j], score[i]);
            return cmp != 0 ? cmp : Integer.compare(all[i], all[j]);
        });
        int[] out = new int[20];
        boolean[] used = new boolean[1000];
        boolean[] grouped = new boolean[1000];
        int f = 0;
        for (int i = 0; i < n && f < 20; i++) {
            int id = all[ord[i]];
            int g = groupKey(id);
            if (grouped[g]) {
                continue;
            }
            f = addPlain(out, used, f, id);
            grouped[g] = true;
            int second = secondOrder(id, pos);
            if (second >= 0 && f < 20) {
                f = addPlain(out, used, f, second);
            }
        }
        return Arrays.copyOf(out, f);
    }

    private static int secondOrder(int id, double[][] pos) {
        int best = -1;
        double bestS = -1e18;
        for (int perm : allPerms(id)) {
            if (perm == id) {
                continue;
            }
            double s = seqScore(perm, pos);
            if (s > bestS) {
                bestS = s;
                best = perm;
            }
        }
        return best;
    }

    /** 最近开出的原号，不够 20 再按新到旧补全序。不做加减。 */
    private static int[] recentCodes(int[][] digits, int end, int take, boolean permsFirst) {
        int[] out = new int[20];
        boolean[] used = new boolean[1000];
        int f = 0;
        int from = Math.max(0, end - Math.max(1, take));
        if (permsFirst) {
            for (int t = end - 1; t >= from && f < 20; t--) {
                f = addPerms(out, used, f, code(digits[t]));
            }
        } else {
            for (int t = end - 1; t >= from && f < 20; t--) {
                f = addPlain(out, used, f, code(digits[t]));
            }
            for (int t = end - 1; t >= 0 && f < 20; t--) {
                f = addPerms(out, used, f, code(digits[t]));
            }
        }
        return Arrays.copyOf(out, f);
    }

    private static int addPerms(int[] out, boolean[] used, int f, int id) {
        for (int perm : allPerms(id)) {
            f = addPlain(out, used, f, perm);
            if (f >= 20) {
                break;
            }
        }
        return f;
    }

    private static int[] rankBox20(int[][] digits, int end, int w, int[] take, boolean cold, double decay) {
        double[][] pos = new double[3][10];
        if (cold) {
            for (int p = 0; p < 3; p++) {
                int[] om = omission(digits, end, p);
                for (int x = 0; x < 10; x++) {
                    pos[p][x] = om[x];
                }
            }
        } else {
            int from = Math.max(0, end - w);
            int len = end - from;
            for (int i = 0; i < len; i++) {
                double wgt = decay >= 0.999 ? 1.0 : Math.pow(decay, len - 1 - i);
                int t = from + i;
                for (int p = 0; p < 3; p++) {
                    pos[p][digits[t][p]] += wgt;
                }
            }
        }
        return boxCross(pos, take);
    }

    /** 上一期该位开了什么，这段里它后面最常跟什么。 */
    private static int[] transBox20(int[][] digits, int end, int w, int[] take, double decay) {
        if (end < 2) {
            return new int[0];
        }
        double[][][] trans = new double[3][10][10];
        int from = Math.max(1, end - w);
        int len = end - from;
        for (int i = 0; i < len; i++) {
            double wgt = decay >= 0.999 ? 1.0 : Math.pow(decay, len - 1 - i);
            int t = from + i;
            for (int p = 0; p < 3; p++) {
                trans[p][digits[t - 1][p]][digits[t][p]] += wgt;
            }
        }
        double[][] pos = new double[3][10];
        for (int p = 0; p < 3; p++) {
            int prev = digits[end - 1][p];
            for (int x = 0; x < 10; x++) {
                pos[p][x] = trans[p][prev][x];
            }
        }
        return boxCross(pos, take);
    }

    private static int[] boxCross(double[][] pos, int[] take) {
        int[] a = topK(pos[0], take[0]);
        int[] b = topK(pos[1], take[1]);
        int[] c = topK(pos[2], take[2]);
        int[] out = new int[20];
        boolean[] used = new boolean[1000];
        int f = 0;
        for (int x : a) {
            for (int y : b) {
                for (int z : c) {
                    f = addPlain(out, used, f, x * 100 + y * 10 + z);
                    if (f >= 20) {
                        return Arrays.copyOf(out, f);
                    }
                }
            }
        }
        return Arrays.copyOf(out, f);
    }

    /** 近窗出现最多的 6 个数字，组成 20 个组六，每组只留定位分最高的一个顺序。 */
    private static int[] sixPool20(int[][] digits, int end, int w, double decay) {
        double[] heat = new double[10];
        double[][] pos = new double[3][10];
        int from = Math.max(0, end - w);
        int len = end - from;
        for (int i = 0; i < len; i++) {
            double wgt = decay >= 0.999 ? 1.0 : Math.pow(decay, len - 1 - i);
            int t = from + i;
            for (int p = 0; p < 3; p++) {
                heat[digits[t][p]] += wgt;
                pos[p][digits[t][p]] += wgt;
            }
        }
        int ban = end > 0 ? code(digits[end - 1]) : -1;
        int[] six = topK(heat, 6);
        return zu6Of(six, pos, ban);
    }

    private static int[] rawTickets(int[][] digits, int end, boolean pl3, NearPick pick) {
        int[] base = baseByMode(digits, end, pick.mode);
        int[] off = fitShift(digits, end, pl3, pick.window, 1, true, pick.decay, pick.recentTie);
        return applyShift(base, off[0], off[1], off[2], true, false);
    }

    private static final class DailyFix {
        final boolean extraOrder;
        final int dh;
        final int dt;
        final int du;
        final String text;

        DailyFix(boolean extraOrder, int dh, int dt, int du, String text) {
            this.extraOrder = extraOrder;
            this.dh = dh;
            this.dt = dt;
            this.du = du;
            this.text = text;
        }

        static DailyFix none() {
            return new DailyFix(false, 0, 0, 0, "上期命中，不纠偏");
        }
    }

    /** 把上一期预测相对开奖的偏差，加到本期已经生成的 20 注上。 */
    private static int[] applyLastBias(int[] now, DailyFix fix) {
        if (fix == null || now == null || now.length == 0) {
            return now;
        }
        if (!fix.extraOrder && fix.dh == 0 && fix.dt == 0 && fix.du == 0) {
            return now;
        }
        return shapeTickets(now, new int[]{fix.dh, fix.dt, fix.du}, fix.extraOrder, -1);
    }

    /** 上一期预测对开奖的偏差：同号不同序，或某个位置差 1。 */
    private static DailyFix classifyMiss(int[] bets, int draw) {
        if (has(bets, draw)) {
            return DailyFix.none();
        }
        if (groupHit(bets, draw)) {
            return new DailyFix(true, 0, 0, 0, "上期同号不同序，补第二序");
        }
        int bestPerm = -1;
        int bestSrc = -1;
        int bestAbs = 99;
        int bestNz = 9;
        int[] bestW = {0, 0, 0};
        for (int bet : bets) {
            for (int perm : allPerms(bet)) {
                int[] w = wrapDiff(perm, draw);
                int abs = Math.abs(w[0]) + Math.abs(w[1]) + Math.abs(w[2]);
                int nz = 0;
                for (int p = 0; p < 3; p++) {
                    if (w[p] != 0) {
                        nz++;
                    }
                }
                if (abs < bestAbs || (abs == bestAbs && nz < bestNz)) {
                    bestAbs = abs;
                    bestNz = nz;
                    bestW = w;
                    bestPerm = perm;
                    bestSrc = bet;
                }
            }
        }
        boolean extra = bestPerm != bestSrc;
        int dh = clamp1(bestW[0]);
        int dt = clamp1(bestW[1]);
        int du = clamp1(bestW[2]);
        if (bestNz == 1 && bestAbs == 1) {
            String pos = dh != 0 ? "百" : dt != 0 ? "十" : "个";
            int d = dh + dt + du;
            String kind = extra ? "上期组选差1，补序并" : "上期直选差1，";
            return new DailyFix(extra, dh, dt, du, kind + pos + (d > 0 ? "+1" : "-1"));
        }
        StringBuilder sb = new StringBuilder("上期较远，按近号每位最多移1");
        sb.append(axisText("百", dh)).append(axisText("十", dt)).append(axisText("个", du));
        if (extra) {
            sb.append("，并补第二序");
        }
        return new DailyFix(extra, dh, dt, du, sb.toString());
    }

    private static String axisText(String name, int d) {
        if (d == 0) {
            return "";
        }
        return " " + name + (d > 0 ? "+1" : "-1");
    }

    private static int clamp1(int d) {
        if (d > 0) {
            return 1;
        }
        if (d < 0) {
            return -1;
        }
        return 0;
    }

    private static int[] wrapDiff(int bet, int id) {
        int[] raw = posDiff(bet, id);
        return new int[]{wrapSigned(raw[0]), wrapSigned(raw[1]), wrapSigned(raw[2])};
    }

    private static int wrapSigned(int d) {
        d = Math.floorMod(d, 10);
        if (d > 5) {
            d -= 10;
        }
        return d;
    }

    private static int[] allPerms(int id) {
        int a = id / 100;
        int b = (id / 10) % 10;
        int c = id % 10;
        int[] d = {a, b, c};
        int[] out = new int[6];
        int n = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (j == i) {
                    continue;
                }
                int k = 3 - i - j;
                int perm = d[i] * 100 + d[j] * 10 + d[k];
                boolean seen = false;
                for (int u = 0; u < n; u++) {
                    if (out[u] == perm) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    out[n++] = perm;
                }
            }
        }
        return Arrays.copyOf(out, n);
    }

    private static final class NearPick {
        final DigitMode mode;
        final int window;
        final double decay;
        final boolean recentTie;
        final boolean extraOrder;
        final int nudgePos;
        int[] liveOff;
        int[] liveBase;

        NearPick(DigitMode mode, int window, double decay, boolean recentTie, boolean extraOrder, int nudgePos) {
            this.mode = mode;
            this.window = window;
            this.decay = decay;
            this.recentTie = recentTie;
            this.extraOrder = extraOrder;
            this.nudgePos = nudgePos;
        }

        int cost() {
            return (extraOrder ? 4 : 0) + (nudgePos >= 0 ? 2 : 0);
        }

        String text() {
            String nudge = nudgePos < 0 ? "不补邻号" : (nudgePos == 0 ? "百" : nudgePos == 1 ? "十" : "个") + "位±1";
            String order = extraOrder ? "前4组再留1序" : "一组一序";
            return String.format(Locale.ROOT, "%s 窗%d 衰减%.2f%s %s %s",
                    mode, window, decay, recentTie ? " 近端平局" : "", order, nudge);
        }
    }

    private static NearPick defaultPick(boolean pl3) {
        if (pl3) {
            return new NearPick(DigitMode.HOT30, 13, 0.60, true, false, -1);
        }
        return new NearPick(DigitMode.BELL12, 16, 1.0, false, false, -1);
    }

    private static NearPick pickNear(int[][] digits, int end, boolean pl3) {
        NearPick base = defaultPick(pl3);
        int look = 30;
        int low = Math.max(40, end - look - base.window);
        int from = Math.max(low + base.window, end - look);
        if (from >= end) {
            return base;
        }
        int[][] bets = new int[end + 1][];
        int[] draw = new int[end];
        int[] gk = new int[end];
        for (int t = low; t < end; t++) {
            draw[t] = code(digits[t]);
            gk[t] = groupKey(draw[t]);
            bets[t] = baseByMode(digits, t, base.mode);
        }
        bets[end] = baseByMode(digits, end, base.mode);
        int[][] offs = new int[end][];
        for (int s = from; s < end; s++) {
            offs[s] = offOnCache(bets, draw, gk, s, base.window, base.decay, base.recentTie);
        }
        int[] liveOff = offOnCache(bets, draw, gk, end, base.window, base.decay, base.recentTie);
        NearPick best = base;
        int bestZ = -1;
        int bestG = -1;
        int bestLastZ = -1;
        int bestLastG = -1;
        int bestCost = Integer.MAX_VALUE;
        for (boolean extra : new boolean[]{false, true}) {
            for (int nudge = -1; nudge <= 2; nudge++) {
                NearPick pick = new NearPick(base.mode, base.window, base.decay, base.recentTie, extra, nudge);
                int z = 0;
                int g = 0;
                int lastZ = -1;
                int lastG = -1;
                int i = 0;
                for (int s = from; s < end; s++, i++) {
                    int[] tickets = shapeTickets(bets[s], offs[s], extra, nudge);
                    int w = i + 1;
                    if (has(tickets, draw[s])) {
                        z += w;
                        lastZ = i;
                    }
                    if (groupHit(tickets, draw[s])) {
                        g += w;
                        lastG = i;
                    }
                }
                if (betterCover(z, g, lastZ, lastG, pick.cost(),
                        bestZ, bestG, bestLastZ, bestLastG, bestCost)) {
                    bestZ = z;
                    bestG = g;
                    bestLastZ = lastZ;
                    bestLastG = lastG;
                    bestCost = pick.cost();
                    best = pick;
                    best.liveOff = liveOff;
                    best.liveBase = bets[end];
                }
            }
        }
        return best;
    }

    private static int[] realize(int[][] digits, int end, NearPick pick) {
        if (pick.liveBase != null && pick.liveOff != null) {
            return shapeTickets(pick.liveBase, pick.liveOff, pick.extraOrder, pick.nudgePos);
        }
        int low = Math.max(40, end - 30 - pick.window);
        int[][] bets = new int[end + 1][];
        int[] draw = new int[end];
        int[] gk = new int[end];
        for (int t = low; t < end; t++) {
            draw[t] = code(digits[t]);
            gk[t] = groupKey(draw[t]);
            bets[t] = baseByMode(digits, t, pick.mode);
        }
        int[] off = offOnCache(bets, draw, gk, end, pick.window, pick.decay, pick.recentTie);
        return shapeTickets(baseByMode(digits, end, pick.mode), off, pick.extraOrder, pick.nudgePos);
    }

    private static int[] offOnCache(int[][] bets, int[] draw, int[] gk, int end, int window,
                                    double decay, boolean recentTie) {
        int from = Math.max(0, end - window);
        int bestZ = -1;
        int bestG = -1;
        int bestL1 = 99;
        int bestLastZ = -1;
        int bestLastG = -1;
        int bh = 0;
        int bt = 0;
        int bu = 0;
        for (int dh = -1; dh <= 1; dh++) {
            for (int dt = -1; dt <= 1; dt++) {
                for (int du = -1; du <= 1; du++) {
                    int z = 0;
                    int g = 0;
                    int lastZ = -1;
                    int lastG = -1;
                    for (int s = from; s < end; s++) {
                        int[] row = bets[s];
                        if (row == null) {
                            continue;
                        }
                        boolean hz = false;
                        boolean hg = false;
                        for (int bet : row) {
                            int sid = shiftId(bet, dh, dt, du, true);
                            if (sid == draw[s]) {
                                hz = true;
                            }
                            if (sid >= 0 && groupKey(sid) == gk[s]) {
                                hg = true;
                            }
                        }
                        int w = periodWeight(end - 1 - s, decay);
                        if (hz) {
                            z += w;
                            lastZ = s;
                        }
                        if (hg) {
                            g += w;
                            lastG = s;
                        }
                    }
                    int l1 = Math.abs(dh) + Math.abs(dt) + Math.abs(du);
                    if (betterShift(z, g, lastZ, lastG, l1, bestZ, bestG, bestLastZ, bestLastG, bestL1, recentTie)) {
                        bestZ = z;
                        bestG = g;
                        bestL1 = l1;
                        bestLastZ = lastZ;
                        bestLastG = lastG;
                        bh = dh;
                        bt = dt;
                        bu = du;
                    }
                }
            }
        }
        return new int[]{bh, bt, bu};
    }

    private static int[] shapeTickets(int[] base, int[] off, boolean extra, int nudgePos) {
        if (base == null || off == null) {
            return new int[0];
        }
        if (!extra && nudgePos < 0) {
            return applyShift(base, off[0], off[1], off[2], true, false);
        }
        int[] out = new int[20];
        boolean[] used = new boolean[1000];
        int f = 0;
        int reserve = (extra ? 4 : 0) + (nudgePos >= 0 ? 2 : 0);
        int keep = 20 - reserve;
        for (int i = 0; i < base.length && f < keep; i++) {
            f = addPlain(out, used, f, shiftId(base[i], off[0], off[1], off[2], true));
        }
        if (extra) {
            int n = Math.min(4, base.length);
            for (int i = 0; i < n && f < 20; i++) {
                int alt = altOrder(base[i]);
                if (alt >= 0) {
                    f = addPlain(out, used, f, shiftId(alt, off[0], off[1], off[2], true));
                }
            }
        }
        if (nudgePos >= 0 && base.length > 0 && f < 20) {
            int id = shiftId(base[0], off[0], off[1], off[2], true);
            f = addPlain(out, used, f, shiftOne(id, nudgePos, 1, true));
            if (f < 20) {
                f = addPlain(out, used, f, shiftOne(id, nudgePos, -1, true));
            }
        }
        for (int i = 0; i < base.length && f < 20; i++) {
            f = addPlain(out, used, f, shiftId(base[i], off[0], off[1], off[2], true));
        }
        return Arrays.copyOf(out, f);
    }

    private static int[] baseByMode(int[][] digits, int end, DigitMode mode) {
        double[][] pos = new double[3][10];
        for (int p = 0; p < 3; p++) {
            pos[p] = modeScore(mode, digits, end, p);
        }
        int ban = end > 0 ? code(digits[end - 1]) : -1;
        return groups20(pos, ban, 3);
    }

    private static int shiftOne(int id, int pos, int delta, boolean wrap) {
        if (id < 0) {
            return -1;
        }
        int dh = pos == 0 ? delta : 0;
        int dt = pos == 1 ? delta : 0;
        int du = pos == 2 ? delta : 0;
        return shiftId(id, dh, dt, du, wrap);
    }

    private static int altOrder(int id) {
        int a = id / 100;
        int b = (id / 10) % 10;
        int c = id % 10;
        int[] cand = {
                a * 100 + c * 10 + b,
                b * 100 + a * 10 + c,
                b * 100 + c * 10 + a,
                c * 100 + a * 10 + b,
                c * 100 + b * 10 + a
        };
        for (int x : cand) {
            if (x != id) {
                return x;
            }
        }
        return -1;
    }

    private static int addPlain(int[] out, boolean[] used, int f, int id) {
        if (f >= out.length || id < 0 || used[id]) {
            return f;
        }
        used[id] = true;
        out[f] = id;
        return f + 1;
    }

    private static boolean betterCover(int z, int g, int lastZ, int lastG, int extra,
                                       int bestZ, int bestG, int bestLastZ, int bestLastG, int bestExtra) {
        if (z != bestZ) {
            return z > bestZ;
        }
        if (g != bestG) {
            return g > bestG;
        }
        if (lastZ != bestLastZ) {
            return lastZ > bestLastZ;
        }
        if (lastG != bestLastG) {
            return lastG > bestLastG;
        }
        return extra < bestExtra;
    }

    private static double[][] basePos(int[][] digits, int end, boolean pl3) {
        double[][] pos = new double[3][10];
        DigitMode mode = pl3 ? DigitMode.HOT30 : DigitMode.BELL12;
        for (int p = 0; p < 3; p++) {
            pos[p] = modeScore(mode, digits, end, p);
        }
        return pos;
    }

    /** 热组多留 depth 个顺序，其余组留 1 个，总数不超过 20。multi 是享受多顺序的组数。 */
    private static int[] mixedSix(double[][] pos, int ban, int multi, int depth) {
        double[] heat = new double[10];
        for (int p = 0; p < 3; p++) {
            for (int x = 0; x < 10; x++) {
                heat[x] += pos[p][x];
            }
        }
        int[] six = topK(heat, 6);
        int[][] ids = new int[20][6];
        double[][] sc = new double[20][6];
        int[] pc = new int[20];
        double[] best = new double[20];
        int g = 0;
        for (int i = 0; i < six.length; i++) {
            for (int j = i + 1; j < six.length; j++) {
                for (int k = j + 1; k < six.length; k++) {
                    int[] d = {six[i], six[j], six[k]};
                    int n = 0;
                    for (int a = 0; a < 3; a++) {
                        for (int b = 0; b < 3; b++) {
                            if (b == a) {
                                continue;
                            }
                            int c = 3 - a - b;
                            int id = d[a] * 100 + d[b] * 10 + d[c];
                            boolean seen = false;
                            for (int u = 0; u < n; u++) {
                                if (ids[g][u] == id) {
                                    seen = true;
                                    break;
                                }
                            }
                            if (seen || id == ban) {
                                continue;
                            }
                            ids[g][n] = id;
                            sc[g][n] = seqScore(id, pos);
                            n++;
                        }
                    }
                    for (int a = 1; a < n; a++) {
                        int id = ids[g][a];
                        double s = sc[g][a];
                        int b = a;
                        while (b > 0 && sc[g][b - 1] < s) {
                            ids[g][b] = ids[g][b - 1];
                            sc[g][b] = sc[g][b - 1];
                            b--;
                        }
                        ids[g][b] = id;
                        sc[g][b] = s;
                    }
                    pc[g] = n;
                    best[g] = n > 0 ? sc[g][0] : -1e18;
                    g++;
                }
            }
        }
        Integer[] order = new Integer[g];
        for (int i = 0; i < g; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int cmp = Double.compare(best[b], best[a]);
            return cmp != 0 ? cmp : Integer.compare(ids[a][0], ids[b][0]);
        });
        int[] out = new int[20];
        boolean[] used = new boolean[1000];
        int f = 0;
        for (int rank = 0; rank < g && f < 20; rank++) {
            int gi = order[rank];
            int take = rank < multi ? depth : 1;
            if (take > pc[gi]) {
                take = pc[gi];
            }
            for (int u = 0; u < take && f < 20; u++) {
                int id = ids[gi][u];
                if (used[id]) {
                    continue;
                }
                used[id] = true;
                out[f++] = id;
            }
        }
        return Arrays.copyOf(out, f);
    }

    private static void mixScan(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int cacheFrom = Math.max(50, n - 230);
        int r = 1;
        int dim = 3;
        int mixes = 6;
        int[] multi = {0, 3, 5, 8, 4, 6};
        int[] depth = {2, 2, 2, 2, 3, 3};
        boolean[][][][][] zx = new boolean[mixes][n][dim][dim][dim];
        boolean[][][][][] grp = new boolean[mixes][n][dim][dim][dim];
        for (int t = cacheFrom; t < n; t++) {
            double[][] pos = basePos(digits, t, pl3);
            int ban = code(digits[t - 1]);
            int id = code(digits[t]);
            int gk = groupKey(id);
            for (int k = 0; k < mixes; k++) {
                int[] bets = mixedSix(pos, ban, multi[k], depth[k]);
                for (int bet : bets) {
                    for (int dh = -r; dh <= r; dh++) {
                        for (int dt = -r; dt <= r; dt++) {
                            for (int du = -r; du <= r; du++) {
                                int sid = shiftId(bet, dh, dt, du, true);
                                if (sid == id) {
                                    zx[k][t][dh + r][dt + r][du + r] = true;
                                }
                                if (groupKey(sid) == gk) {
                                    grp[k][t][dh + r][dt + r][du + r] = true;
                                }
                            }
                        }
                    }
                }
            }
        }
        int window = pl3 ? plWindow : sdWindow;
        double decay = pl3 ? plDecay : sdDecay;
        boolean recentTie = pl3 ? plRecentTie : sdRecentTie;
        sb.append("===== ").append(name).append(" =====\n");
        for (int k = 0; k < mixes; k++) {
            int[] hit = countMix(zx[k], grp[k], n, window, decay, recentTie);
            sb.append(String.format(Locale.ROOT, "热组%d留%d序  近100 %d直%d组 | 前100 %d直%d组%n",
                    multi[k], depth[k], hit[0], hit[1], hit[2], hit[3]));
        }
        int[] adapt = new int[4];
        for (int t = n - 200; t < n; t++) {
            int bucket = t >= n - 100 ? 0 : 2;
            int bestZ = -1;
            int bestG = -1;
            int bestK = 0;
            for (int k = 0; k < mixes; k++) {
                int[] off = mixOffset(zx[k], grp[k], t, window, decay, recentTie);
                int z = 0;
                int g = 0;
                int from = Math.max(0, t - window);
                for (int s = from; s < t; s++) {
                    int w = periodWeight(t - 1 - s, decay);
                    if (zx[k][s][off[0] + r][off[1] + r][off[2] + r]) {
                        z += w;
                    }
                    if (grp[k][s][off[0] + r][off[1] + r][off[2] + r]) {
                        g += w;
                    }
                }
                if (z > bestZ || (z == bestZ && g > bestG)) {
                    bestZ = z;
                    bestG = g;
                    bestK = k;
                }
            }
            int[] off = mixOffset(zx[bestK], grp[bestK], t, window, decay, recentTie);
            if (zx[bestK][t][off[0] + r][off[1] + r][off[2] + r]) {
                adapt[bucket]++;
            }
            if (grp[bestK][t][off[0] + r][off[1] + r][off[2] + r]) {
                adapt[bucket + 1]++;
            }
        }
        sb.append(String.format(Locale.ROOT, "按近期直选切换  近100 %d直%d组 | 前100 %d直%d组%n",
                adapt[0], adapt[1], adapt[2], adapt[3]));
    }

    private static int[] countMix(boolean[][][][] zx, boolean[][][][] grp, int n, int window,
                                  double decay, boolean recentTie) {
        int[] hit = new int[4];
        int[] froms = {n - 100, n - 200};
        int[] tos = {n, n - 100};
        for (int b = 0; b < froms.length; b++) {
            for (int t = froms[b]; t < tos[b]; t++) {
                int[] off = mixOffset(zx, grp, t, window, decay, recentTie);
                if (zx[t][off[0] + 1][off[1] + 1][off[2] + 1]) {
                    hit[b * 2]++;
                }
                if (grp[t][off[0] + 1][off[1] + 1][off[2] + 1]) {
                    hit[b * 2 + 1]++;
                }
            }
        }
        return hit;
    }

    private static int[] mixOffset(boolean[][][][] zx, boolean[][][][] grp, int end, int window,
                                   double decay, boolean recentTie) {
        int from = Math.max(0, end - window);
        int bestZ = -1;
        int bestG = -1;
        int bestL1 = 99;
        int bestLastZ = -1;
        int bestLastG = -1;
        int bh = 0;
        int bt = 0;
        int bu = 0;
        for (int dh = -1; dh <= 1; dh++) {
            for (int dt = -1; dt <= 1; dt++) {
                for (int du = -1; du <= 1; du++) {
                    int z = 0;
                    int g = 0;
                    int lastZ = -1;
                    int lastG = -1;
                    for (int s = from; s < end; s++) {
                        int w = periodWeight(end - 1 - s, decay);
                        if (zx[s][dh + 1][dt + 1][du + 1]) {
                            z += w;
                            lastZ = s;
                        }
                        if (grp[s][dh + 1][dt + 1][du + 1]) {
                            g += w;
                            lastG = s;
                        }
                    }
                    int l1 = Math.abs(dh) + Math.abs(dt) + Math.abs(du);
                    if (betterShift(z, g, lastZ, lastG, l1, bestZ, bestG, bestLastZ, bestLastG, bestL1, recentTie)) {
                        bestZ = z;
                        bestG = g;
                        bestL1 = l1;
                        bestLastZ = lastZ;
                        bestLastG = lastG;
                        bh = dh;
                        bt = dt;
                        bu = du;
                    }
                }
            }
        }
        return new int[]{bh, bt, bu};
    }

    /** 底稿的冷热窗口也按近期直选挑，不把 3D 遗漏12、排三热30 写死。 */
    private static void modeScan(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int cacheFrom = Math.max(50, n - 230);
        DigitMode[] modes = {
                DigitMode.HOT8, DigitMode.HOT12, DigitMode.HOT20, DigitMode.HOT30,
                DigitMode.BELL5, DigitMode.BELL8, DigitMode.BELL12, DigitMode.COLD
        };
        int dim = 3;
        boolean[][][][][] zx = new boolean[modes.length][n][dim][dim][dim];
        boolean[][][][][] grp = new boolean[modes.length][n][dim][dim][dim];
        for (int t = cacheFrom; t < n; t++) {
            int ban = code(digits[t - 1]);
            int id = code(digits[t]);
            int gk = groupKey(id);
            for (int k = 0; k < modes.length; k++) {
                double[][] pos = new double[3][10];
                for (int p = 0; p < 3; p++) {
                    pos[p] = modeScore(modes[k], digits, t, p);
                }
                int[] bets = groups20(pos, ban, 3);
                for (int bet : bets) {
                    for (int dh = -1; dh <= 1; dh++) {
                        for (int dt = -1; dt <= 1; dt++) {
                            for (int du = -1; du <= 1; du++) {
                                int sid = shiftId(bet, dh, dt, du, true);
                                if (sid == id) {
                                    zx[k][t][dh + 1][dt + 1][du + 1] = true;
                                }
                                if (groupKey(sid) == gk) {
                                    grp[k][t][dh + 1][dt + 1][du + 1] = true;
                                }
                            }
                        }
                    }
                }
            }
        }
        int window = pl3 ? plWindow : sdWindow;
        double decay = pl3 ? plDecay : sdDecay;
        boolean recentTie = pl3 ? plRecentTie : sdRecentTie;
        sb.append("===== ").append(name).append(" 底稿 =====\n");
        for (int k = 0; k < modes.length; k++) {
            int[] hit = countMix(zx[k], grp[k], n, window, decay, recentTie);
            sb.append(String.format(Locale.ROOT, "%-8s 近100 %d直%d组 | 前100 %d直%d组%n",
                    modes[k], hit[0], hit[1], hit[2], hit[3]));
        }
        int[] adapt = new int[4];
        for (int t = n - 200; t < n; t++) {
            int bucket = t >= n - 100 ? 0 : 2;
            int bestZ = -1;
            int bestG = -1;
            int bestK = 0;
            for (int k = 0; k < modes.length; k++) {
                int[] off = mixOffset(zx[k], grp[k], t, window, decay, recentTie);
                int z = 0;
                int g = 0;
                int from = Math.max(0, t - window);
                for (int s = from; s < t; s++) {
                    int w = periodWeight(t - 1 - s, decay);
                    if (zx[k][s][off[0] + 1][off[1] + 1][off[2] + 1]) {
                        z += w;
                    }
                    if (grp[k][s][off[0] + 1][off[1] + 1][off[2] + 1]) {
                        g += w;
                    }
                }
                if (z > bestZ || (z == bestZ && g > bestG)) {
                    bestZ = z;
                    bestG = g;
                    bestK = k;
                }
            }
            int[] off = mixOffset(zx[bestK], grp[bestK], t, window, decay, recentTie);
            if (zx[bestK][t][off[0] + 1][off[1] + 1][off[2] + 1]) {
                adapt[bucket]++;
            }
            if (grp[bestK][t][off[0] + 1][off[1] + 1][off[2] + 1]) {
                adapt[bucket + 1]++;
            }
        }
        sb.append(String.format(Locale.ROOT, "按近期直选换底  近100 %d直%d组 | 前100 %d直%d组%n",
                adapt[0], adapt[1], adapt[2], adapt[3]));
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

    /** 用窗口内已经开出的号，挑使直选最多的百十个平移。越近的期权重越大。平局先看组选，排三再看最近一次命中。 */
    private static int[] fitShift(int[][] digits, int end, boolean pl3, int window, int radius, boolean wrap) {
        double decay = pl3 ? plDecay : sdDecay;
        boolean recentTie = pl3 ? plRecentTie : sdRecentTie;
        return fitShift(digits, end, pl3, window, radius, wrap, decay, recentTie);
    }

    private static int[] fitShift(int[][] digits, int end, boolean pl3, int window, int radius, boolean wrap,
                                  double decay, boolean recentTie) {
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
        int bestLastZ = -1;
        int bestLastG = -1;
        int bh = 0;
        int bt = 0;
        int bu = 0;
        for (int dh = -radius; dh <= radius; dh++) {
            for (int dt = -radius; dt <= radius; dt++) {
                for (int du = -radius; du <= radius; du++) {
                    int z = 0;
                    int g = 0;
                    int lastZ = -1;
                    int lastG = -1;
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
                        int w = periodWeight(m - 1 - i, decay);
                        if (hz) {
                            z += w;
                            lastZ = i;
                        }
                        if (hg) {
                            g += w;
                            lastG = i;
                        }
                    }
                    int l1 = Math.abs(dh) + Math.abs(dt) + Math.abs(du);
                    if (betterShift(z, g, lastZ, lastG, l1, bestZ, bestG, bestLastZ, bestLastG, bestL1, recentTie)) {
                        bestZ = z;
                        bestG = g;
                        bestL1 = l1;
                        bestLastZ = lastZ;
                        bestLastG = lastG;
                        bh = dh;
                        bt = dt;
                        bu = du;
                    }
                }
            }
        }
        return new int[]{bh, bt, bu};
    }

    /** 距今 0 期权重 1000。衰减越小于 1，越只看窗口末端的走势。 */
    private static int periodWeight(int age, double decay) {
        if (decay >= 0.999) {
            return 1000;
        }
        if (age <= 0) {
            return 1000;
        }
        return (int) Math.round(1000.0 * Math.pow(decay, age));
    }

    private static boolean betterShift(int z, int g, int lastZ, int lastG, int l1,
                                       int bestZ, int bestG, int bestLastZ, int bestLastG, int bestL1,
                                       boolean recentTie) {
        if (z != bestZ) {
            return z > bestZ;
        }
        if (g != bestG) {
            return g > bestG;
        }
        if (recentTie) {
            if (lastZ != bestLastZ) {
                return lastZ > bestLastZ;
            }
            if (lastG != bestLastG) {
                return lastG > bestLastG;
            }
        }
        return l1 < bestL1;
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

    /** 与线上 fitShift 同一套权重：直选优先，组选其次，再看窗口末端最近一次命中。 */
    private static int[] bestOffsetWeighted(boolean[][][][] zx, boolean[][][][] grp, int end, int window,
                                            int radius, double decay, boolean recentTie) {
        int from = Math.max(0, end - window);
        int bestZ = -1;
        int bestG = -1;
        int bestL1 = 99;
        int bestLastZ = -1;
        int bestLastG = -1;
        int bh = 0;
        int bt = 0;
        int bu = 0;
        int r = SHIFT_R;
        for (int dh = -radius; dh <= radius; dh++) {
            for (int dt = -radius; dt <= radius; dt++) {
                for (int du = -radius; du <= radius; du++) {
                    int z = 0;
                    int g = 0;
                    int lastZ = -1;
                    int lastG = -1;
                    for (int s = from; s < end; s++) {
                        int w = periodWeight(end - 1 - s, decay);
                        if (zx[s][dh + r][dt + r][du + r]) {
                            z += w;
                            lastZ = s;
                        }
                        if (grp[s][dh + r][dt + r][du + r]) {
                            g += w;
                            lastG = s;
                        }
                    }
                    int l1 = Math.abs(dh) + Math.abs(dt) + Math.abs(du);
                    if (betterShift(z, g, lastZ, lastG, l1, bestZ, bestG, bestLastZ, bestLastG, bestL1, recentTie)) {
                        bestZ = z;
                        bestG = g;
                        bestL1 = l1;
                        bestLastZ = lastZ;
                        bestLastG = lastG;
                        bh = dh;
                        bt = dt;
                        bu = du;
                    }
                }
            }
        }
        return new int[]{bh, bt, bu};
    }

    private static int[] packTrend(boolean[] hz, boolean[] hg, int n) {
        int[] out = new int[10];
        int[] from = {n - 10, n - 10, n - 20, n - 20, n - 30, n - 30, n - 50, n - 50, n - 100, n - 100};
        int[] to = {n, n, n, n, n, n, n, n, n - 50, n - 50};
        for (int i = 0; i < from.length; i++) {
            int hit = 0;
            boolean direct = i % 2 == 0;
            for (int t = from[i]; t < to[i]; t++) {
                if (direct ? hz[t] : hg[t]) {
                    hit++;
                }
            }
            out[i] = hit;
        }
        return out;
    }

    private static boolean betterTrend(int[] a, int[] b) {
        int[] order = {4, 5, 2, 3, 0, 1, 6, 7};
        for (int i : order) {
            if (a[i] != b[i]) {
                return a[i] > b[i];
            }
        }
        return false;
    }

    private static String trendText(int[] hit) {
        return String.format(Locale.ROOT, "近10 %d直%d组 | 近20 %d直%d组 | 近30 %d直%d组 | 近50 %d直%d组 | 前50 %d直%d组",
                hit[0], hit[1], hit[2], hit[3], hit[4], hit[5], hit[6], hit[7], hit[8], hit[9]);
    }

    private static int[] trendHitsOf(boolean[][][][] zx, boolean[][][][] grp, int n,
                                     int[] dh, int[] dt, int[] du) {
        boolean[] hz = new boolean[n];
        boolean[] hg = new boolean[n];
        int r = SHIFT_R;
        for (int t = n - 100; t < n; t++) {
            hz[t] = zx[t][dh[t] + r][dt[t] + r][du[t] + r];
            hg[t] = grp[t][dh[t] + r][dt[t] + r][du[t] + r];
        }
        return packTrend(hz, hg, n);
    }

    private static int[] adaptiveHitsOf(boolean[][][][] zx, boolean[][][][] grp, int n, int[] windows,
                                        int trend, int[][] dh, int[][] dt, int[][] du) {
        boolean[] hz = new boolean[n];
        boolean[] hg = new boolean[n];
        int r = SHIFT_R;
        for (int t = n - 100; t < n; t++) {
            int w = pickWindow(zx, grp, t, windows, trend, dh, dt, du);
            hz[t] = zx[t][dh[w][t] + r][dt[w][t] + r][du[w][t] + r];
            hg[t] = grp[t][dh[w][t] + r][dt[w][t] + r][du[w][t] + r];
        }
        return packTrend(hz, hg, n);
    }

    private static int pickWindow(boolean[][][][] zx, boolean[][][][] grp, int end, int[] windows, int trend,
                                  int[][] dh, int[][] dt, int[][] du) {
        int bestW = windows[0];
        int bestZ = -1;
        int bestG = -1;
        int bestZ10 = -1;
        int bestG10 = -1;
        int r = SHIFT_R;
        int from = Math.max(0, end - trend);
        int recentFrom = Math.max(from, end - 10);
        for (int w : windows) {
            int z = 0;
            int g = 0;
            int z10 = 0;
            int g10 = 0;
            for (int s = from; s < end; s++) {
                boolean hz = zx[s][dh[w][s] + r][dt[w][s] + r][du[w][s] + r];
                boolean hg = grp[s][dh[w][s] + r][dt[w][s] + r][du[w][s] + r];
                if (hz) {
                    z++;
                }
                if (hg) {
                    g++;
                }
                if (s >= recentFrom) {
                    if (hz) {
                        z10++;
                    }
                    if (hg) {
                        g10++;
                    }
                }
            }
            boolean better = z > bestZ || (z == bestZ && g > bestG)
                    || (z == bestZ && g == bestG && z10 > bestZ10)
                    || (z == bestZ && g == bestG && z10 == bestZ10 && g10 > bestG10)
                    || (z == bestZ && g == bestG && z10 == bestZ10 && g10 == bestG10 && w < bestW);
            if (better) {
                bestZ = z;
                bestG = g;
                bestZ10 = z10;
                bestG10 = g10;
                bestW = w;
            }
        }
        return bestW;
    }

    /** 窗口 6 到 60。按近30直选、近30组选、近20直选、近20组选留下，直选优先于组选。 */
    private static void trendScan(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int cacheFrom = Math.max(50, n - 360);
        int r = SHIFT_R;
        int dim = r * 2 + 1;
        boolean[][][][] zx = new boolean[n][dim][dim][dim];
        boolean[][][][] grp = new boolean[n][dim][dim][dim];
        for (int t = cacheFrom; t < n; t++) {
            int[] bets = base20(digits, t, pl3);
            markShift(bets, bets.length, code(digits[t]), groupKey(code(digits[t])), zx, grp, t, true);
        }
        int span = 160;
        int w0 = 6;
        int wN = 60;
        double[] decays = {0.60, 0.75, 0.85, 0.92, 1.0};
        int[][][][] dh = new int[decays.length][2][wN + 1][n];
        int[][][][] dt = new int[decays.length][2][wN + 1][n];
        int[][][][] du = new int[decays.length][2][wN + 1][n];
        for (int di = 0; di < decays.length; di++) {
            boolean[] ties = decays[di] >= 0.999 ? new boolean[]{false, true} : new boolean[]{true};
            for (boolean recentTie : ties) {
                int ti = recentTie ? 1 : 0;
                for (int window = w0; window <= wN; window++) {
                    for (int t = n - span; t < n; t++) {
                        int[] off = bestOffsetWeighted(zx, grp, t, window, 1, decays[di], recentTie);
                        dh[di][ti][window][t] = off[0];
                        dt[di][ti][window][t] = off[1];
                        du[di][ti][window][t] = off[2];
                    }
                }
            }
        }
        int curW = pl3 ? 12 : 35;
        int[] current = trendHitsOf(zx, grp, n, dh[4][0][curW], dt[4][0][curW], du[4][0][curW]);
        sb.append("===== ").append(name).append(" =====\n");
        sb.append(String.format(Locale.ROOT, "当前 W%d 衰减1 %s%n", curW, trendText(current)));
        int[] best = current;
        String bestLabel = "当前 W" + curW;
        for (int di = 0; di < decays.length; di++) {
            boolean[] ties = decays[di] >= 0.999 ? new boolean[]{false, true} : new boolean[]{true};
            for (boolean recentTie : ties) {
                int ti = recentTie ? 1 : 0;
                sb.append(String.format(Locale.ROOT, "---- 衰减%.2f %s ----%n", decays[di], recentTie ? "近端平局" : "小平移平局"));
                for (int window = w0; window <= wN; window++) {
                    int[] hit = trendHitsOf(zx, grp, n, dh[di][ti][window], dt[di][ti][window], du[di][ti][window]);
                    String label = String.format(Locale.ROOT, "W%02d 衰减%.2f%s", window, decays[di], recentTie ? "近" : "平");
                    sb.append(String.format(Locale.ROOT, "%-16s %s%n", label, trendText(hit)));
                    if (betterTrend(hit, best)) {
                        best = hit;
                        bestLabel = label;
                    }
                }
            }
        }
        int[] adaptWindows = {6, 8, 10, 12, 14, 16, 20, 24, 30, 35, 40, 50};
        sb.append("---- 按最近走势挑窗口 ----\n");
        for (int trend : new int[]{12, 16, 20, 24, 30}) {
            for (int di = 0; di < decays.length; di++) {
                if (decays[di] != 0.75 && decays[di] != 0.85 && decays[di] != 1.0) {
                    continue;
                }
                int[] hit = adaptiveHitsOf(zx, grp, n, adaptWindows, trend, dh[di][1], dt[di][1], du[di][1]);
                String label = String.format(Locale.ROOT, "自适应L%d 衰减%.2f", trend, decays[di]);
                sb.append(String.format(Locale.ROOT, "%-16s %s%n", label, trendText(hit)));
                if (betterTrend(hit, best)) {
                    best = hit;
                    bestLabel = label;
                }
            }
        }
        sb.append("留下 ").append(bestLabel).append("  ").append(trendText(best)).append('\n');
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
        if (args != null && args.length > 0 && "lift10".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("近10期走期。先出本期票，再试上期偏差和上期开奖邻域。直选更高才留下。\n");
            liftScan(sb, "福彩3D", sd, false);
            liftScan(sb, "排列三", pl, true);
            boostScan(sb, "福彩3D", sd, false);
            boostScan(sb, "排列三", pl, true);
            edgeScan(sb, "福彩3D", sd, false);
            edgeScan(sb, "排列三", pl, true);
            cutScan(sb, "福彩3D", sd, false);
            cutScan(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_lift10.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "dir".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("直选单独计，组选不含直选。看最近30、再往前30、最近100。\n");
            directProbe(sb, "福彩3D", sd, false);
            directProbe(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_direct.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "zoom".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            zoomGame(sb, "福彩3D", sd, false);
            zoomGame(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_zoom.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "late".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("最近几期三码直选没中。近30期直选至少4，前30期直选至少2，重合低于75%，相邻两期不能有16注相同。\n");
            sb.append("在这些条件里，优先补上最近8期的直选。组选次数含直选。\n");
            lateScan(sb, "福彩3D", sd, false);
            lateScan(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_late.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "today".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("最新开奖组选直选都未中。热号继续贴上期，冷号改为按遗漏分取，再和现行比最近30期。\n");
            sb.append("直选至少4。相邻两期有16注及以上相同就不留。组选次数含直选。\n");
            todayScan(sb, "福彩3D", sd, false);
            todayScan(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_today.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "jump".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("最新开奖跳开上期。20注里留一部分离上期至少差两位的号，再和现行比最近30期。\n");
            jumpScan(sb, "福彩3D", sd, false);
            jumpScan(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_jump.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "w30".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("三码按30期窗口走期。直选至少4，组选尽量多。相邻两期有15注及以上相同就视为太黏，不留。\n");
            sb.append("组选次数含直选。重合是相邻两期相同号码的平均占比。\n");
            window30Scan(sb, "福彩3D", sd, false);
            window30Scan(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_w30.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "ten".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("三码只回测最近10期，走期直选。不做按位加减。目标10期至少3直。\n");
            tenReport(sb, "福彩3D", sd, false);
            tenReport(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_ten.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "last".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("只看上一期预测和开奖号做纠偏，给出下一期20注。\n");
            lastFix(sb, "福彩3D", sd, false, 993);
            lastFix(sb, "排列三", pl, true, 446);
            Path out = Path.of("reports/sanma_last.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "gap30".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("最近30期，预测号离开奖号的差。只看这30期。\n");
            gap30(sb, "福彩3D", sd, false);
            gap30(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_gap30.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "near".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("三码只回看最近30期来决定要不要补顺序、补哪一位。3D和排三分开。\n");
            sb.append("原平移一直留在候选里。补了顺序或单位置±1却没有更高的直选、组选，就维持原票。\n");
            nearReport(sb, "福彩3D", sd, false);
            nearReport(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_near30.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
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
        if (args != null && args.length > 0 && "dash".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("组六平移之上，热组多留顺序。留几组、留几个顺序按最近窗口直选现算，平移也按同一窗口重算。\n");
            sb.append("热组0留2序就是现在的每组一个顺序。前100是再往前100期。\n");
            int[][] sdLocal = parseXlsx(Path.of("D:/彩票/3D.xlsx"));
            int[][] plLocal = parseXlsx(Path.of("D:/彩票/排列三.xlsx"));
            sb.append("3D期数=").append(sdLocal.length).append(" 排三期数=").append(plLocal.length).append('\n');
            mixScan(sb, "福彩3D", sdLocal, false);
            modeScan(sb, "福彩3D", sdLocal, false);
            mixScan(sb, "排列三", plLocal, true);
            modeScan(sb, "排列三", plLocal, true);
            Path out = Path.of("reports/sanma_dasheng.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(sb);
            return;
        }
        if (args != null && args.length > 0 && "trend".equals(args[0])) {
            StringBuilder sb = new StringBuilder();
            sb.append("三码窗口按最近走势重扫。窗口内越近的期权重越大。\n");
            sb.append("排序：近30直选、近30组选、近20直选、近20组选、近10直选、近10组选，再看近50。前50只作对照。\n");
            trendScan(sb, "福彩3D", sd, false);
            trendScan(sb, "排列三", pl, true);
            Path out = Path.of("reports/sanma_trend.txt");
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

    /** 最近 30 期，平移结果离开奖号有多近。 */
    private static void gap30(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int from = Math.max(41, n - 30);
        int[] kind = new int[4];
        int[] pos = new int[3];
        int[] signPos = new int[3];
        int[] signNeg = new int[3];
        int wrapGap = 0;
        int lineGap = 0;
        int zx = 0;
        int grp = 0;
        int orderZx = 0;
        int orderGrp = 0;
        int nudgeZx = 0;
        int nudgeGrp = 0;
        sb.append("===== ").append(name).append(" =====\n");
        for (int t = from; t < n; t++) {
            int[][] hist = Arrays.copyOf(digits, t);
            int[] bets = legacyShiftSanma(hist, pl3);
            int id = code(digits[t]);
            int[] base = base20(hist, hist.length, pl3);
            int[] off = fitShift(hist, hist.length, pl3, pl3 ? plWindow : sdWindow, 1, true);
            int[] withOrder = shapeTickets(base, off, true, -1);
            int[] withNudge = shapeTickets(base, off, false, pl3 ? 2 : 0);
            if (has(withOrder, id)) {
                orderZx++;
            }
            if (groupHit(withOrder, id)) {
                orderGrp++;
            }
            if (has(withNudge, id)) {
                nudgeZx++;
            }
            if (groupHit(withNudge, id)) {
                nudgeGrp++;
            }
            if (has(bets, id)) {
                zx++;
                kind[0]++;
                continue;
            }
            if (groupHit(bets, id)) {
                grp++;
                kind[1]++;
                sb.append(String.format(Locale.ROOT, "顺序 %03d%n", id));
                continue;
            }
            int close = closestBet(bets, id);
            int[] diff = posDiff(close, id);
            int nz = 0;
            int at = -1;
            for (int p = 0; p < 3; p++) {
                if (diff[p] != 0) {
                    nz++;
                    at = p;
                }
            }
            int ad = at < 0 ? 0 : Math.abs(diff[at]);
            int circ = at < 0 ? 0 : Math.min(ad, 10 - ad);
            if (nz == 1 && circ == 1) {
                kind[2]++;
                pos[at]++;
                if (diff[at] > 0) {
                    signPos[at]++;
                } else {
                    signNeg[at]++;
                }
                if (ad == 9) {
                    wrapGap++;
                } else {
                    lineGap++;
                }
                sb.append(String.format(Locale.ROOT, "差1 %03d 近%03d %s%+d%n",
                        id, close, at == 0 ? "百" : at == 1 ? "十" : "个", diff[at]));
            } else {
                kind[3]++;
                sb.append(String.format(Locale.ROOT, "较远 %03d 近%03d 位差%d%n", id, close, nz));
            }
        }
        sb.append(String.format(Locale.ROOT,
                "直选%d 只差顺序%d 单位置差1 %d 较远%d | 百%d 十%d 个%d | + %d/%d/%d - %d/%d/%d | 环差%d 直差%d%n",
                kind[0], kind[1], kind[2], kind[3], pos[0], pos[1], pos[2],
                signPos[0], signPos[1], signPos[2], signNeg[0], signNeg[1], signNeg[2], wrapGap, lineGap));
        sb.append(String.format(Locale.ROOT, "平移命中 %d直 %d组%n", zx, grp + zx));
        sb.append(String.format(Locale.ROOT, "同平移再留前4组第二序 %d直 %d组 | 同平移再补%s位±1 %d直 %d组%n",
                orderZx, orderGrp, pl3 ? "个" : "百", nudgeZx, nudgeGrp));
    }

    private static int closestBet(int[] bets, int id) {
        int best = bets.length == 0 ? -1 : bets[0];
        int bestNz = 9;
        int bestAbs = 99;
        for (int bet : bets) {
            int[] diff = posDiff(bet, id);
            int nz = 0;
            int abs = 0;
            for (int p = 0; p < 3; p++) {
                if (diff[p] != 0) {
                    nz++;
                    abs += Math.min(Math.abs(diff[p]), 10 - Math.abs(diff[p]));
                }
            }
            if (nz < bestNz || (nz == bestNz && abs < bestAbs)) {
                bestNz = nz;
                bestAbs = abs;
                best = bet;
            }
        }
        return best;
    }

    /** 开奖减预测。差 9 或 -9 留着，用来区分 0/9 是不是环上的差 1。 */
    private static int[] posDiff(int bet, int id) {
        return new int[]{
                id / 100 - bet / 100,
                (id / 10) % 10 - (bet / 10) % 10,
                id % 10 - bet % 10
        };
    }

    /** 几种上期偏差用法，只看近10期走期直选。 */
    private static void liftScan(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int from = Math.max(41, n - TEN_LOOK);
        TenRule rule = highRule(pl3);
        String[] labels = {
                "只出本期",
                "现行：远号也整组跟偏差",
                "只处理同号不同序和单位置差1",
                "只单位置差1",
                "只补第二序",
                "半留本期半跟偏差",
                "远号只动差最大的那一位",
                "近号按原序不算调序"
        };
        sb.append("===== ").append(name).append(" =====\n");
        int bestZ = -1;
        int bestG = -1;
        int bestKind = 0;
        for (int kind = 0; kind < labels.length; kind++) {
            int z = 0;
            int g = 0;
            StringBuilder hits = new StringBuilder();
            for (int t = from; t < n; t++) {
                int[] bets = liftTickets(digits, t, pl3, rule, kind);
                int id = code(digits[t]);
                if (has(bets, id)) {
                    z++;
                    hits.append(String.format(Locale.ROOT, " 直%03d", id));
                } else if (groupHit(bets, id)) {
                    g++;
                    hits.append(String.format(Locale.ROOT, " 组%03d", id));
                } else {
                    hits.append(String.format(Locale.ROOT, " 失%03d", id));
                }
            }
            sb.append(String.format(Locale.ROOT, "%d直%d组 %s%s%n", z, z + g > 0 ? g + z : g, labels[kind], hits));
            if (z > bestZ || (z == bestZ && g > bestG)) {
                bestZ = z;
                bestG = g;
                bestKind = kind;
            }
        }
        sb.append("留下 ").append(labels[bestKind]).append(String.format(Locale.ROOT, " %d直%n", bestZ));
    }

    /** 不搬上期整组偏差，改用上期开奖邻域换尾巴，或两套规则拼接。 */
    private static void boostScan(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int from = Math.max(41, n - TEN_LOOK);
        TenRule rule = highRule(pl3);
        TenRule other = pl3 ? new TenRule(4, 5, 4, 2, 3) : new TenRule(2, 15, 2, 2, 5, 1, 1);
        String[] labels = {
                "只出本期",
                "尾4换上期邻域",
                "尾6换上期邻域",
                "尾8换上期邻域",
                "头4上期邻域",
                "尾4换上期原号另序",
                "跟号12+热位8",
                "跟号10+热位10",
                "热位12+跟号8",
                "尾4换近号"
        };
        sb.append("===== ").append(name).append(" 拔高 =====\n");
        int bestZ = -1;
        int bestKind = 0;
        for (int kind = 0; kind < labels.length; kind++) {
            int z = 0;
            int g = 0;
            StringBuilder hits = new StringBuilder();
            for (int t = from; t < n; t++) {
                int[] bets = boostKind(digits, t, pl3, rule, other, kind);
                int id = code(digits[t]);
                if (has(bets, id)) {
                    z++;
                    hits.append(String.format(Locale.ROOT, " 直%03d", id));
                } else if (groupHit(bets, id)) {
                    g++;
                    hits.append(String.format(Locale.ROOT, " 组%03d", id));
                } else {
                    hits.append(String.format(Locale.ROOT, " 失%03d", id));
                }
            }
            sb.append(String.format(Locale.ROOT, "%d直%d组 %s%s%n", z, z + g, labels[kind], hits));
            if (z > bestZ) {
                bestZ = z;
                bestKind = kind;
            }
        }
        sb.append("留下 ").append(labels[bestKind]).append(String.format(Locale.ROOT, " %d直%n", bestZ));
    }

    private static int[] boostKind(int[][] digits, int end, boolean pl3, TenRule rule, TenRule other, int kind) {
        int[] now = tenTickets(digits, end, pl3, rule);
        int[] alt = tenTickets(digits, end, pl3, other);
        int last = end >= 1 ? code(digits[end - 1]) : -1;
        int[] neigh = last < 0 ? new int[0] : lastNeigh(last);
        int[] perms = last < 0 ? new int[0] : recentOne(last);
        int[] recent = recentCodes(digits, end, 8, false);
        return switch (kind) {
            case 1 -> takeFill(now, neigh, 16);
            case 2 -> takeFill(now, neigh, 14);
            case 3 -> takeFill(now, neigh, 12);
            case 4 -> takeFill(neigh, now, Math.min(4, neigh.length));
            case 5 -> takeFill(now, perms, 16);
            case 6 -> takeFill(pl3 ? alt : now, pl3 ? now : alt, 12);
            case 7 -> takeFill(pl3 ? alt : now, pl3 ? now : alt, 10);
            case 8 -> takeFill(pl3 ? now : alt, pl3 ? alt : now, 12);
            case 9 -> takeFill(now, recent, 16);
            default -> now;
        };
    }

    private static int[] recentOne(int id) {
        int[] out = new int[6];
        boolean[] used = new boolean[1000];
        int f = addPerms(out, used, 0, id);
        return Arrays.copyOf(out, f);
    }

    /** 4x2x3 有 24 格只留 20，换截取方式看能否多中。 */
    private static void cutScan(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int from = Math.max(40, n - TEN_LOOK);
        TenRule rule = highRule(pl3);
        String[] labels = {"现行嵌套截 20", "格子分最高 20", "离上期最近 20", "同上期同位数最多 20"};
        sb.append("===== ").append(name).append(" 截取 =====\n");
        int bestZ = -1;
        int bestKind = 0;
        for (int kind = 0; kind < labels.length; kind++) {
            int z = 0;
            int g = 0;
            StringBuilder hits = new StringBuilder();
            for (int t = from; t < n; t++) {
                int[] bets = cutTickets(digits, t, pl3, rule, kind);
                int id = code(digits[t]);
                if (has(bets, id)) {
                    z++;
                    hits.append(String.format(Locale.ROOT, " 直%03d", id));
                } else if (groupHit(bets, id)) {
                    g++;
                    hits.append(String.format(Locale.ROOT, " 组%03d", id));
                } else {
                    hits.append(String.format(Locale.ROOT, " 失%03d", id));
                }
            }
            sb.append(String.format(Locale.ROOT, "%d直%d组 %s%s%n", z, z + g, labels[kind], hits));
            if (z > bestZ) {
                bestZ = z;
                bestKind = kind;
            }
        }
        sb.append("留下 ").append(labels[bestKind]).append(String.format(Locale.ROOT, " %d直%n", bestZ));
    }

    private static int[] cutTickets(int[][] digits, int end, boolean pl3, TenRule rule, int kind) {
        if (kind == 0) {
            return tenTickets(digits, end, pl3, rule);
        }
        double[][] pos = boxPos(digits, end, rule);
        int last = end >= 1 ? code(digits[end - 1]) : 0;
        int[] raw = boxPick(pos, new int[]{rule.a, rule.b, rule.c}, last, kind);
        if (rule.pos < 0) {
            return raw;
        }
        int[] off = {0, 0, 0};
        off[rule.pos] = rule.delta;
        return applyShift(raw, off[0], off[1], off[2], true, false);
    }

    private static double[][] boxPos(int[][] digits, int end, TenRule rule) {
        if (rule.kind == 4) {
            double[][][] trans = new double[3][10][10];
            int from = Math.max(1, end - rule.window);
            int len = end - from;
            for (int i = 0; i < len; i++) {
                double wgt = rule.decay >= 0.999 ? 1.0 : Math.pow(rule.decay, len - 1 - i);
                int t = from + i;
                for (int p = 0; p < 3; p++) {
                    trans[p][digits[t - 1][p]][digits[t][p]] += wgt;
                }
            }
            double[][] pos = new double[3][10];
            if (end > 0) {
                for (int p = 0; p < 3; p++) {
                    int prev = digits[end - 1][p];
                    for (int x = 0; x < 10; x++) {
                        pos[p][x] = trans[p][prev][x];
                    }
                }
            }
            return pos;
        }
        double[][] pos = new double[3][10];
        if (rule.kind == 3) {
            for (int p = 0; p < 3; p++) {
                int[] om = omission(digits, end, p);
                for (int x = 0; x < 10; x++) {
                    pos[p][x] = om[x];
                }
            }
        } else {
            int from = Math.max(0, end - rule.window);
            int len = end - from;
            for (int i = 0; i < len; i++) {
                double wgt = rule.decay >= 0.999 ? 1.0 : Math.pow(rule.decay, len - 1 - i);
                int t = from + i;
                for (int p = 0; p < 3; p++) {
                    pos[p][digits[t][p]] += wgt;
                }
            }
        }
        return pos;
    }

    private static int[] boxPick(double[][] pos, int[] take, int last, int kind) {
        int[] a = topK(pos[0], take[0]);
        int[] b = topK(pos[1], take[1]);
        int[] c = topK(pos[2], take[2]);
        int nAll = take[0] * take[1] * take[2];
        int[] all = new int[nAll];
        double[] score = new double[nAll];
        int n = 0;
        for (int x : a) {
            for (int y : b) {
                for (int z : c) {
                    int id = x * 100 + y * 10 + z;
                    all[n] = id;
                    if (kind == 1) {
                        score[n] = pos[0][x] + pos[1][y] + pos[2][z];
                    } else if (kind == 2) {
                        int[] d = posDiff(id, last);
                        score[n] = -(Math.abs(d[0]) + Math.abs(d[1]) + Math.abs(d[2]));
                    } else {
                        int same = (x == last / 100 ? 1 : 0)
                                + (y == (last / 10) % 10 ? 1 : 0)
                                + (z == last % 10 ? 1 : 0);
                        score[n] = same;
                    }
                    n++;
                }
            }
        }
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) {
            ord[i] = i;
        }
        Arrays.sort(ord, (i, j) -> {
            int cmp = Double.compare(score[j], score[i]);
            return cmp != 0 ? cmp : Integer.compare(all[i], all[j]);
        });
        int[] out = new int[Math.min(20, n)];
        boolean[] used = new boolean[1000];
        int f = 0;
        for (int i = 0; i < n && f < 20; i++) {
            f = addPlain(out, used, f, all[ord[i]]);
        }
        return Arrays.copyOf(out, f);
    }

    /** 在近10期上细扫跟号/热位窗口和格子，直选高于现行才报。 */
    private static void edgeScan(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int from = Math.max(40, n - TEN_LOOK);
        TenRule locked = highRule(pl3);
        scoreWindow(digits, from, n, pl3, locked);
        TenRule best = locked;
        int[] kinds = {2, 3, 4};
        int[] windows = {3, 4, 5, 6, 7, 8, 9, 10, 12, 15, 18, 20};
        for (int kind : kinds) {
            for (int w : windows) {
                for (int a = 1; a <= 8; a++) {
                    for (int b = 1; b <= 8; b++) {
                        for (int c = 1; c <= 8; c++) {
                            int prod = a * b * c;
                            if (prod < 8 || prod > 36) {
                                continue;
                            }
                            TenRule rule = new TenRule(kind, w, a, b, c);
                            scoreWindow(digits, from, n, pl3, rule);
                            if (rule.zx > best.zx || (rule.zx == best.zx && rule.grp > best.grp && rule.zx >= locked.zx)) {
                                if (rule.zx > locked.zx || (rule.zx == locked.zx && rule.grp > locked.grp)) {
                                    best = rule;
                                }
                            }
                        }
                    }
                }
            }
        }
        sb.append("===== ").append(name).append(" 格子细扫 =====\n");
        sb.append("现行 ").append(locked.text()).append('\n');
        if (best.zx > locked.zx) {
            sb.append("更高 ").append(best.text()).append('\n');
        } else {
            sb.append("没有格子比现行更多直选\n");
        }
    }

    private static int[] liftTickets(int[][] digits, int end, boolean pl3, TenRule rule, int kind) {
        int[] now = tenTickets(digits, end, pl3, rule);
        if (kind == 0 || end < 41) {
            return now;
        }
        int[] lastPred = tenTickets(digits, end - 1, pl3, rule);
        int draw = code(digits[end - 1]);
        DailyFix fix;
        if (kind == 7) {
            fix = classifyMissRaw(lastPred, draw);
        } else {
            fix = classifyMiss(lastPred, draw);
        }
        if (kind == 2) {
            if (!fix.extraOrder && !(Math.abs(fix.dh) + Math.abs(fix.dt) + Math.abs(fix.du) == 1)) {
                return now;
            }
            if (Math.abs(fix.dh) + Math.abs(fix.dt) + Math.abs(fix.du) > 1) {
                return applyLastBias(now, new DailyFix(fix.extraOrder, 0, 0, 0, fix.text));
            }
        }
        if (kind == 3) {
            int n1 = Math.abs(fix.dh) + Math.abs(fix.dt) + Math.abs(fix.du);
            if (n1 != 1) {
                return now;
            }
            return applyLastBias(now, new DailyFix(false, fix.dh, fix.dt, fix.du, fix.text));
        }
        if (kind == 4) {
            if (!fix.extraOrder) {
                return now;
            }
            return applyLastBias(now, new DailyFix(true, 0, 0, 0, fix.text));
        }
        if (kind == 5) {
            return applyShift(now, fix.dh, fix.dt, fix.du, true, true);
        }
        if (kind == 6) {
            int axes = (fix.dh != 0 ? 1 : 0) + (fix.dt != 0 ? 1 : 0) + (fix.du != 0 ? 1 : 0);
            if (axes <= 1) {
                return applyLastBias(now, fix);
            }
            int pos = 0;
            int mag = Math.abs(fix.dh);
            if (Math.abs(fix.dt) > mag) {
                pos = 1;
                mag = Math.abs(fix.dt);
            }
            if (Math.abs(fix.du) > mag) {
                pos = 2;
            }
            int[] off = {0, 0, 0};
            off[pos] = pos == 0 ? fix.dh : pos == 1 ? fix.dt : fix.du;
            return applyLastBias(now, new DailyFix(false, off[0], off[1], off[2], fix.text));
        }
        return applyLastBias(now, fix);
    }

    /** 偏差按原顺序最近的一注算，不先调序。 */
    private static DailyFix classifyMissRaw(int[] bets, int draw) {
        if (has(bets, draw)) {
            return DailyFix.none();
        }
        if (groupHit(bets, draw)) {
            return new DailyFix(true, 0, 0, 0, "上期同号不同序，补第二序");
        }
        int close = closestBet(bets, draw);
        int[] w = wrapDiff(close, draw);
        int nz = 0;
        for (int p = 0; p < 3; p++) {
            if (w[p] != 0) {
                nz++;
            }
        }
        int dh = clamp1(w[0]);
        int dt = clamp1(w[1]);
        int du = clamp1(w[2]);
        if (nz == 1 && Math.abs(w[0]) + Math.abs(w[1]) + Math.abs(w[2]) == 1) {
            String pos = dh != 0 ? "百" : dt != 0 ? "十" : "个";
            int d = dh + dt + du;
            return new DailyFix(false, dh, dt, du, "上期直选差1，" + pos + (d > 0 ? "+1" : "-1"));
        }
        return new DailyFix(false, dh, dt, du, "上期较远，按原序近号每位最多移1"
                + axisText("百", dh) + axisText("十", dt) + axisText("个", du));
    }

    private static void zoomGame(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int end = n - 1;
        int draw = code(digits[end]);
        sb.append("===== ").append(name).append(" =====\n");
        digitRank(sb, digits, end, draw);
        int from = Math.max(40, n - 30);
        int priorFrom = Math.max(40, from - 30);
        List<TenRule> rules = new ArrayList<>();
        rules.add(highRule(pl3));
        if (pl3) {
            rules.add(coldNear(2, 6, 2, 1, 6));
            rules.add(coldNear(2, 6, 2, 0.92, 6));
            rules.add(coldNear(2, 5, 2, 1, 6));
            rules.add(coldNear(2, 5, 2, 0.92, 6));
            rules.add(coldNear(2, 4, 3, 1, 6));
            TenRule wide = new TenRule(9, 30, 2, 2, 8, 2, -1);
            wide.decay = 0.92;
            wide.cut = 8;
            wide.keep = 10;
            rules.add(wide);
        } else {
            for (int[] sh : new int[][]{{10, 3, 2}, {8, 4, 2}, {10, 2, 2}, {9, 3, 2}, {8, 3, 3}, {10, 4, 1}}) {
                for (int cut : new int[]{2, 8}) {
                    TenRule rule = new TenRule(9, 30, sh[0], sh[1], sh[2]);
                    rule.cut = cut;
                    rule.keep = cut == 8 ? 10 : 0;
                    rules.add(rule);
                    TenRule shifted = new TenRule(9, 30, sh[0], sh[1], sh[2], 1, -1);
                    shifted.cut = cut;
                    shifted.keep = cut == 8 ? 10 : 0;
                    rules.add(shifted);
                }
            }
        }
        for (TenRule rule : rules) {
            Hit30 hit = scoreLook(digits, from, n, pl3, rule);
            int[] pg = countLook(digits, priorFrom, from, pl3, rule);
            sb.append(String.format(Locale.ROOT, "重合%.0f%% 黏连%d 前30 %d直%d组 最新%s %s%n",
                    hit.overlap() * 100, hit.sticky, pg[0], pg[1], lastWord(hit.last), rule.text()));
            sb.append("  逐期").append(marks(digits, from, n, pl3, rule)).append('\n');
            sb.append("  下一期 ").append(csv(tenTickets(digits, n, pl3, rule))).append('\n');
        }
        sleeveProbe(sb, digits, pl3);
    }

    /** 现行 20 注保留前若干注，尾部换成冷号或跳号，看直选会不会掉。 */
    private static void sleeveProbe(StringBuilder sb, int[][] digits, boolean pl3) {
        int n = digits.length;
        int from = Math.max(40, n - 30);
        int[][] shapes = pl3
                ? new int[][]{{6, 2, 4}, {2, 6, 2}, {4, 3, 3}}
                : new int[][]{{7, 3, 2}, {8, 3, 3}, {5, 4, 4}};
        sb.append("---- 尾部换冷号或跳号 ----\n");
        for (int[] sh : shapes) {
            for (int mode = 0; mode < 2; mode++) {
                for (int keep : new int[]{12, 14, 16, 18}) {
                    int z = 0;
                    int g = 0;
                    int share = 0;
                    int pairs = 0;
                    int sticky = 0;
                    int last = 0;
                    int[] prev = null;
                    for (int t = from; t < n; t++) {
                        int[] bets = sleeve(digits, t, pl3, sh, mode, keep);
                        int id = code(digits[t]);
                        if (has(bets, id)) {
                            z++;
                            if (t == n - 1) {
                                last = 2;
                            }
                        } else if (t == n - 1 && groupHit(bets, id)) {
                            last = 1;
                        }
                        if (groupHit(bets, id)) {
                            g++;
                        }
                        if (prev != null) {
                            int s = intersect(prev, bets);
                            share += s;
                            pairs++;
                            if (s >= 16) {
                                sticky++;
                            }
                        }
                        prev = bets;
                    }
                    if (z < 4) {
                        continue;
                    }
                    double overlap = pairs == 0 ? 1 : share / (20.0 * pairs);
                    sb.append(String.format(Locale.ROOT,
                            "留%d %s %dx%dx%d 重合%.0f%% 黏连%d %d直%d组 最新%s%n",
                            keep, mode == 0 ? "冷分" : "热跳", sh[0], sh[1], sh[2],
                            overlap * 100, sticky, z, g, lastWord(last)));
                }
            }
        }
    }

    private static int[] sleeve(int[][] digits, int end, boolean pl3, int[] sh, int mode, int keepHead) {
        int[] head = tenTickets(digits, end, pl3, highRule(pl3));
        TenRule extra = new TenRule(mode == 0 ? 3 : 2, 30, sh[0], sh[1], sh[2]);
        extra.cut = mode == 0 ? 1 : 6;
        int last = end > 0 ? code(digits[end - 1]) : 0;
        int[] tail = mode == 0
                ? tenBox(digits, end, extra)
                : farFirst(boxPos(digits, end, extra), new int[]{sh[0], sh[1], sh[2]}, last);
        return takeFill(head, tail, keepHead);
    }

    private static TenRule coldNear(int a, int b, int c, double decay, int fresh) {
        TenRule rule = new TenRule(3, 30, a, b, c, 2, -1);
        rule.decay = decay;
        rule.cut = 2;
        rule.fresh = fresh;
        return rule;
    }

    /** 开奖前，这一注三位在热号和遗漏里分别排第几。1 是最热或最冷。 */
    private static void digitRank(StringBuilder sb, int[][] digits, int end, int draw) {
        int[] digs = {draw / 100, (draw / 10) % 10, draw % 10};
        String[] posName = {"百", "十", "个"};
        TenRule hot = new TenRule(2, 30, 1, 1, 1);
        TenRule cold = new TenRule(3, 30, 1, 1, 1);
        double[][] hotPos = boxPos(digits, end, hot);
        double[][] coldPos = boxPos(digits, end, cold);
        for (int p = 0; p < 3; p++) {
            sb.append(String.format(Locale.ROOT, "%s%d 热第%d 冷第%d  ",
                    posName[p], digs[p], rankOf(hotPos[p], digs[p]), rankOf(coldPos[p], digs[p])));
        }
        sb.append('\n');
    }

    private static int rankOf(double[] score, int digit) {
        int rank = 1;
        for (int i = 0; i < score.length; i++) {
            if (score[i] > score[digit]) {
                rank++;
            }
        }
        return rank;
    }

    /** 热号贴上期、冷号按遗漏。只留下直选不低于现行、组选更高，或直选更高的规则。 */
    private static void todayScan(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int from = Math.max(40, n - 30);
        int priorFrom = Math.max(40, from - 30);
        sb.append("===== ").append(name).append(" =====\n");
        sb.append("最近开奖");
        for (int t = Math.max(0, n - 6); t < n; t++) {
            sb.append(String.format(Locale.ROOT, " %03d", code(digits[t])));
        }
        sb.append('\n');
        Hit30 current = scoreLook(digits, from, n, pl3, highRule(pl3));
        int lastId = code(digits[n - 1]);
        sb.append(String.format(Locale.ROOT, "现行 重合%.0f%% 黏连%d/%d %s 最新%s%03d%n",
                current.overlap() * 100, current.sticky, current.pairs, current.rule.text(),
                lastWord(current.last), lastId));
        sb.append("现行逐期").append(marks(digits, from, n, pl3, current.rule)).append('\n');
        sb.append("上期票 ").append(csv(tenTickets(digits, n - 1, pl3, highRule(pl3)))).append('\n');
        explainMiss(sb, digits, n - 1, pl3, highRule(pl3), lastId);
        List<TenRule> bases = todayBases();
        int[] times = new int[n - from];
        for (int i = 0; i < times.length; i++) {
            times[i] = from + i;
        }
        List<Hit30> better = new ArrayList<>();
        List<Hit30> caught = new ArrayList<>();
        Hit30 bestAny = null;
        for (TenRule baseRule : bases) {
            int[][] cache = new int[times.length][];
            for (int i = 0; i < times.length; i++) {
                cache[i] = tenBase(digits, times[i], pl3, baseRule);
            }
            int[] freshSet = (baseRule.cut == 8 || baseRule.cut == 12) ? new int[]{0} : new int[]{0, 6};
            for (int fresh : freshSet) {
                for (int pos = -1; pos <= 2; pos++) {
                    int[] deltas = pos < 0 ? new int[]{0} : new int[]{-1, 1};
                    for (int delta : deltas) {
                        TenRule rule = copyRule(baseRule, pos, delta, fresh);
                        Hit30 hit = scoreCache(digits, times, cache, rule);
                        if (bestAny == null || hitBetter(hit, bestAny)) {
                            bestAny = hit;
                        }
                        if (hit.zx < 4 || hit.overlap() >= 0.75 || hit.sticky > 0) {
                            continue;
                        }
                        if (hit.zx > current.zx || (hit.zx == current.zx && hit.grp > current.grp)) {
                            keepBest(better, hit, 10);
                        } else if (hit.last > 0 && current.last == 0 && hit.zx >= current.zx
                                && hit.grp >= current.grp - 1) {
                            keepBest(caught, hit, 6);
                        }
                    }
                }
            }
        }
        sb.append("基规则 ").append(bases.size()).append('\n');
        if (bestAny != null) {
            sb.append(String.format(Locale.ROOT, "扫描最高 重合%.0f%% 黏连%d 最新%s %s%n",
                    bestAny.overlap() * 100, bestAny.sticky, lastWord(bestAny.last), bestAny.rule.text()));
        }
        if (better.isEmpty()) {
            sb.append("没有规则在30期里超过现行的直选或组选。\n");
        } else {
            sb.append("---- 高于现行 ----\n");
            for (Hit30 hit : better) {
                if (priorFrom < from) {
                    int[] pg = countLook(digits, priorFrom, from, pl3, hit.rule);
                    hit.priorZ = pg[0];
                    hit.priorG = pg[1];
                }
                sb.append(String.format(Locale.ROOT, "重合%.0f%% 黏连%d 前30 %d直%d组 最新%s %s%n",
                        hit.overlap() * 100, hit.sticky, hit.priorZ, hit.priorG, lastWord(hit.last), hit.rule.text()));
            }
        }
        if (!caught.isEmpty()) {
            sb.append("---- 30期不差、最新一期命中 ----\n");
            for (Hit30 hit : caught) {
                sb.append(String.format(Locale.ROOT, "重合%.0f%% 最新%s %s%n",
                        hit.overlap() * 100, lastWord(hit.last), hit.rule.text()));
            }
        }
        sb.append("现行下一期 ").append(csv(tenTickets(digits, n, pl3, highRule(pl3)))).append('\n');
    }

    /**
     * 最近 8 期直选连着没中时，换一条仍能守住近 30 期的规则。
     * 前 30 期直选不到 2 的不留，避免只贴住眼前这几期。
     */
    private static void lateScan(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int from = Math.max(40, n - 30);
        int priorFrom = Math.max(40, from - 30);
        sb.append("===== ").append(name).append(" =====\n");
        Hit30 current = scoreLook(digits, from, n, pl3, highRule(pl3));
        int[] priorNow = countLook(digits, priorFrom, from, pl3, current.rule);
        sb.append(String.format(Locale.ROOT, "现行 近30 %d直%d组 近8直%d 前30 %d直%d组 重合%.0f%% %s%n",
                current.zx, current.grp, current.tail, priorNow[0], priorNow[1],
                current.overlap() * 100, current.rule.text()));
        sb.append("近12").append(marks(digits, Math.max(from, n - 12), n, pl3, current.rule)).append('\n');
        List<TenRule> bases = todayBases();
        int[] times = new int[n - from];
        for (int i = 0; i < times.length; i++) {
            times[i] = from + i;
        }
        List<Hit30> kept = new ArrayList<>();
        for (TenRule baseRule : bases) {
            int[][] cache = new int[times.length][];
            for (int i = 0; i < times.length; i++) {
                cache[i] = tenBase(digits, times[i], pl3, baseRule);
            }
            int[] freshSet = (baseRule.cut == 8 || baseRule.cut == 12) ? new int[]{0} : new int[]{0, 6};
            for (int fresh : freshSet) {
                for (int pos = -1; pos <= 2; pos++) {
                    int[] deltas = pos < 0 ? new int[]{0} : new int[]{-1, 1};
                    for (int delta : deltas) {
                        TenRule rule = copyRule(baseRule, pos, delta, fresh);
                        Hit30 hit = scoreCache(digits, times, cache, rule);
                        if (hit.zx < 4 || hit.overlap() >= 0.75 || hit.sticky > 0) {
                            continue;
                        }
                        if (hit.tail <= current.tail && hit.zx <= current.zx && hit.grp <= current.grp) {
                            continue;
                        }
                        kept.add(hit);
                    }
                }
            }
        }
        kept.sort(Flow1000Ranker::lateOrder);
        sb.append("候选 ").append(kept.size()).append('\n');
        Hit30 lock = null;
        int shown = 0;
        int checked = 0;
        for (Hit30 hit : kept) {
            if (checked >= 40 && lock != null) {
                break;
            }
            int[] prior = countLook(digits, priorFrom, from, pl3, hit.rule);
            hit.priorZ = prior[0];
            hit.priorG = prior[1];
            checked++;
            if (shown < 8) {
                sb.append(String.format(Locale.ROOT, "近8直%d 近30 %d直%d组 前30 %d直%d组 重合%.0f%% %s%n",
                        hit.tail, hit.zx, hit.grp, hit.priorZ, hit.priorG, hit.overlap() * 100, hit.rule.text()));
                shown++;
            }
            boolean tailUp = hit.tail > current.tail && hit.zx >= current.zx;
            boolean windowUp = hit.zx > current.zx || (hit.zx == current.zx && hit.grp > current.grp);
            if (lock == null && hit.priorZ >= 1 && (tailUp || windowUp)) {
                lock = hit;
            }
        }
        if (lock == null) {
            sb.append("没有规则能在补上近8期的同时守住前30期。现行保持不变。\n");
            return;
        }
        sb.append("留下 ").append(lock.rule.text()).append('\n');
        sb.append("留下近12").append(marks(digits, Math.max(from, n - 12), n, pl3, lock.rule)).append('\n');
        sb.append(String.format(Locale.ROOT,
                "LOCK kind=%d w=%d a=%d b=%d c=%d pos=%d d=%d decay=%.2f fresh=%d cut=%d keep=%d tail=%d zx=%d grp=%d priorZ=%d%n",
                lock.rule.kind, lock.rule.window, lock.rule.a, lock.rule.b, lock.rule.c,
                lock.rule.pos, lock.rule.delta, lock.rule.decay, lock.rule.fresh, lock.rule.cut, lock.rule.keep,
                lock.tail, lock.zx, lock.grp, lock.priorZ));
    }


    private static int lateOrder(Hit30 a, Hit30 b) {
        if (a.tail != b.tail) {
            return Integer.compare(b.tail, a.tail);
        }
        if (a.zx != b.zx) {
            return Integer.compare(b.zx, a.zx);
        }
        if (a.grp != b.grp) {
            return Integer.compare(b.grp, a.grp);
        }
        return Double.compare(a.overlap(), b.overlap());
    }

    /** 最新开奖离上一期多远，以及它在不在热位、冷位格子里。 */
    private static void explainMiss(StringBuilder sb, int[][] digits, int end, boolean pl3, TenRule rule, int draw) {
        int prev = end > 0 ? code(digits[end - 1]) : draw;
        sb.append(String.format(Locale.ROOT, "最新%03d 离上期%03d 差%d位", draw, prev, posHamming(draw, prev)));
        int[] take = {Math.min(10, Math.max(1, rule.a)), Math.min(10, Math.max(1, rule.b)), Math.min(10, Math.max(1, rule.c))};
        if (rule.kind == 2 || rule.kind == 3 || rule.kind == 9) {
            int hotKind = rule.kind == 3 ? 3 : 2;
            TenRule hot = new TenRule(hotKind, rule.window, rule.a, rule.b, rule.c);
            hot.decay = rule.decay;
            sb.append(rule.kind == 3 ? " 冷格" : " 热格").append(inCross(boxPos(digits, end, hot), take, draw) ? "有" : "无");
            if (rule.kind == 9) {
                TenRule cold = new TenRule(3, rule.window, rule.a, rule.b, rule.c);
                sb.append(" 冷格").append(inCross(boxPos(digits, end, cold), take, draw) ? "有" : "无");
            }
        }
        sb.append('\n');
    }

    private static boolean inCross(double[][] pos, int[] take, int id) {
        int[] a = topK(pos[0], take[0]);
        int[] b = topK(pos[1], take[1]);
        int[] c = topK(pos[2], take[2]);
        int x = id / 100;
        int y = (id / 10) % 10;
        int z = id % 10;
        return contains(a, x) && contains(b, y) && contains(c, z);
    }

    private static boolean contains(int[] xs, int v) {
        for (int x : xs) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    private static List<TenRule> todayBases() {
        List<TenRule> out = new ArrayList<>();
        double[] decays = {1, 0.92};
        int[] keeps = {8, 10, 12};
        for (int a = 1; a <= 8; a++) {
            for (int b = 1; b <= 8; b++) {
                for (int c = 1; c <= 8; c++) {
                    int prod = a * b * c;
                    if (prod < 16 || prod > 48) {
                        continue;
                    }
                    for (double decay : decays) {
                        for (int cut : new int[]{1, 2}) {
                            TenRule mix = new TenRule(9, 30, a, b, c);
                            mix.decay = decay;
                            mix.cut = cut;
                            out.add(mix);
                        }
                        for (int cut : new int[]{8, 12}) {
                            for (int keep : keeps) {
                                TenRule mix = new TenRule(9, 30, a, b, c);
                                mix.decay = decay;
                                mix.cut = cut;
                                mix.keep = keep;
                                out.add(mix);
                            }
                        }
                        for (int kind : new int[]{2, 3}) {
                            TenRule near = new TenRule(kind, 30, a, b, c);
                            near.decay = decay;
                            near.cut = 2;
                            out.add(near);
                            if (decay > 0.99) {
                                TenRule far = new TenRule(kind, 30, a, b, c);
                                far.cut = 6;
                                out.add(far);
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /** 近号加跳号。只留下比现行更高的直选，或直选不掉、组选更多的规则。 */
    private static void jumpScan(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int from = Math.max(40, n - 30);
        int priorFrom = Math.max(40, from - 30);
        sb.append("===== ").append(name).append(" =====\n");
        Hit30 current = scoreLook(digits, from, n, pl3, highRule(pl3));
        int lastId = code(digits[n - 1]);
        sb.append(String.format(Locale.ROOT, "现行 重合%.0f%% %s 最新%s%03d%n",
                current.overlap() * 100, current.rule.text(), lastWord(current.last), lastId));
        List<TenRule> bases = jumpBases();
        int[] times = new int[n - from];
        for (int i = 0; i < times.length; i++) {
            times[i] = from + i;
        }
        List<Hit30> better = new ArrayList<>();
        List<Hit30> caught = new ArrayList<>();
        for (TenRule baseRule : bases) {
            int[][] cache = new int[times.length][];
            for (int i = 0; i < times.length; i++) {
                cache[i] = tenBase(digits, times[i], pl3, baseRule);
            }
            for (int fresh : new int[]{0, 6}) {
                for (int pos = -1; pos <= 2; pos++) {
                    int[] deltas = pos < 0 ? new int[]{0} : new int[]{-1, 1};
                    for (int delta : deltas) {
                        TenRule rule = copyRule(baseRule, pos, delta, fresh);
                        Hit30 hit = scoreCache(digits, times, cache, rule);
                        if (hit.zx < 4 || hit.overlap() >= 0.75) {
                            continue;
                        }
                        if (hit.zx > current.zx || (hit.zx == current.zx && hit.grp > current.grp)) {
                            keepBest(better, hit, 8);
                        } else if (hit.last > 0 && current.last == 0 && hit.grp >= current.grp - 1) {
                            keepBest(caught, hit, 6);
                        }
                    }
                }
            }
        }
        sb.append("基规则 ").append(bases.size()).append('\n');
        if (better.isEmpty()) {
            sb.append("没有规则在30期里超过现行的直选或组选。\n");
        } else {
            sb.append("---- 高于现行 ----\n");
            for (Hit30 hit : better) {
                if (priorFrom < from) {
                    int[] pg = countLook(digits, priorFrom, from, pl3, hit.rule);
                    hit.priorZ = pg[0];
                    hit.priorG = pg[1];
                }
                sb.append(String.format(Locale.ROOT, "重合%.0f%% 前30 %d直%d组 最新%s %s%n",
                        hit.overlap() * 100, hit.priorZ, hit.priorG, lastWord(hit.last), hit.rule.text()));
            }
        }
        if (!caught.isEmpty()) {
            sb.append("---- 组选差不多、最新一期命中 ----\n");
            for (Hit30 hit : caught) {
                sb.append(String.format(Locale.ROOT, "重合%.0f%% 最新%s %s%n",
                        hit.overlap() * 100, lastWord(hit.last), hit.rule.text()));
            }
        }
    }

    private static String lastWord(int last) {
        return last == 2 ? "直选" : last == 1 ? "组选" : "未中";
    }

    private static List<TenRule> jumpBases() {
        List<TenRule> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        double[] decays = {1, 0.92};
        int[] keeps = {8, 10, 14};
        int[] kinds = {2, 3, 4, 9};
        List<int[]> shapes = new ArrayList<>();
        for (int a = 1; a <= 7; a++) {
            for (int b = 1; b <= 7; b++) {
                for (int c = 1; c <= 7; c++) {
                    int prod = a * b * c;
                    if (prod >= 16 && prod <= 48) {
                        shapes.add(new int[]{a, b, c});
                    }
                }
            }
        }
        int[][] extra = {{6, 2, 4}, {5, 2, 4}, {7, 3, 2}, {10, 2, 2}, {2, 2, 10}, {5, 5, 5}, {8, 2, 3}, {3, 6, 2}};
        for (int[] sh : extra) {
            shapes.add(sh);
        }
        for (int[] sh : shapes) {
            for (int kind : kinds) {
                for (double decay : decays) {
                    if (kind == 3 && decay < 0.999) {
                        continue;
                    }
                    for (int keep : keeps) {
                        String key = kind + ":" + sh[0] + ":" + sh[1] + ":" + sh[2] + ":" + decay + ":" + keep;
                        if (!seen.add(key)) {
                            continue;
                        }
                        TenRule rule = new TenRule(kind, 30, sh[0], sh[1], sh[2]);
                        rule.decay = decay;
                        rule.cut = 5;
                        rule.keep = keep;
                        out.add(rule);
                    }
                }
            }
        }
        return out;
    }

    /** 30 期窗口。直选至少 4，组选尽量多，相邻两期不能基本是同一批号。 */
    private static void window30Scan(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int from = Math.max(40, n - 30);
        int priorFrom = Math.max(40, from - 30);
        sb.append("===== ").append(name).append(" =====\n");
        sb.append("期数 ").append(n).append(" 评估 ").append(from).append("..").append(n - 1);
        if (priorFrom < from) {
            sb.append(" 对照 ").append(priorFrom).append("..").append(from - 1);
        }
        sb.append('\n');
        Hit30 current = scoreLook(digits, from, n, pl3, highRule(pl3));
        sb.append(String.format(Locale.ROOT, "现行 重合%.0f%% 黏连%d/%d %s%n",
                current.overlap() * 100, current.sticky, current.pairs, current.rule.text()));
        sb.append("现行逐期").append(marks(digits, from, n, pl3, current.rule)).append('\n');
        sb.append("下一期 ").append(csv(sanma(digits, pl3))).append('\n');
        List<TenRule> bases = w30Bases();
        int[] times = new int[n - from];
        for (int i = 0; i < times.length; i++) {
            times[i] = from + i;
        }
        List<Hit30> passed = new ArrayList<>();
        List<Hit30> rich = new ArrayList<>();
        Hit30 bestAny = null;
        Hit30 bestHot = null;
        for (TenRule baseRule : bases) {
            int[][] cache = new int[times.length][];
            for (int i = 0; i < times.length; i++) {
                cache[i] = tenBase(digits, times[i], pl3, baseRule);
            }
            for (int fresh : new int[]{0, 6}) {
                for (int pos = -1; pos <= 2; pos++) {
                    int[] deltas = pos < 0 ? new int[]{0} : new int[]{-1, 1};
                    for (int delta : deltas) {
                        TenRule rule = copyRule(baseRule, pos, delta, fresh);
                        Hit30 hit = scoreCache(digits, times, cache, rule);
                        if (bestAny == null || hitBetter(hit, bestAny)) {
                            bestAny = hit;
                        }
                        if ((rule.kind == 2 || rule.kind == 4 || rule.kind == 9)
                                && (bestHot == null || hitBetter(hit, bestHot))) {
                            bestHot = hit;
                        }
                        if (hit.zx >= 4) {
                            passed.add(hit);
                        } else if (hit.zx >= 3 && hit.overlap() < 0.75) {
                            keepBest(rich, hit, 8);
                        }
                    }
                }
            }
        }
        sb.append("基规则 ").append(bases.size()).append(" 直选>=4 ").append(passed.size()).append('\n');
        if (bestHot != null) {
            sb.append(String.format(Locale.ROOT, "热位或跟号最高 重合%.0f%% %s%n",
                    bestHot.overlap() * 100, bestHot.rule.text()));
        }
        List<Hit30> band = overlapBand(passed, 0.75);
        String bandName = "重合<75%";
        if (band.isEmpty()) {
            band = overlapBand(passed, 0.85);
            bandName = "重合<85%";
        }
        if (band.isEmpty()) {
            band = passed;
            bandName = "不限重合";
        }
        band.sort(Flow1000Ranker::hitOrder);
        sb.append("---- ").append(bandName).append(" 按组选 ----\n");
        int show = Math.min(12, band.size());
        for (int i = 0; i < show; i++) {
            Hit30 hit = band.get(i);
            sb.append(String.format(Locale.ROOT, "重合%.0f%% 黏连%d/%d %s%n",
                    hit.overlap() * 100, hit.sticky, hit.pairs, hit.rule.text()));
        }
        if (band.isEmpty()) {
            sb.append("没有规则在30期里直选到4。最高 ");
            if (bestAny != null) {
                sb.append(String.format(Locale.ROOT, "重合%.0f%% %s%n", bestAny.overlap() * 100, bestAny.rule.text()));
            } else {
                sb.append("无\n");
            }
            sb.append("---- 直选3且重合<75% 按组选 ----\n");
            for (Hit30 hit : rich) {
                sb.append(String.format(Locale.ROOT, "重合%.0f%% 黏连%d/%d %s%n",
                        hit.overlap() * 100, hit.sticky, hit.pairs, hit.rule.text()));
            }
            return;
        }
        int check = Math.min(8, band.size());
        Hit30 raw = band.get(0);
        Hit30 stable = null;
        for (int i = 0; i < check; i++) {
            Hit30 hit = band.get(i);
            if (priorFrom < from) {
                int[] pg = countLook(digits, priorFrom, from, pl3, hit.rule);
                hit.priorZ = pg[0];
                hit.priorG = pg[1];
            }
            sb.append(String.format(Locale.ROOT, "前30 %d直%d组 %s%n", hit.priorZ, hit.priorG, hit.rule.text()));
            if (stable == null && (hit.priorZ >= 2 || hit.priorG >= 5)) {
                stable = hit;
            }
        }
        Hit30 lock = raw;
        if (stable != null && raw.grp - stable.grp <= 2) {
            lock = stable;
        }
        sb.append("留下 ").append(lock.rule.text());
        sb.append(String.format(Locale.ROOT, " 重合%.0f%%%n", lock.overlap() * 100));
        sb.append("留下逐期").append(marks(digits, from, n, pl3, lock.rule)).append('\n');
        sb.append(String.format(Locale.ROOT,
                "LOCK kind=%d w=%d a=%d b=%d c=%d pos=%d d=%d decay=%.2f fresh=%d cut=%d zx=%d grp=%d%n",
                lock.rule.kind, lock.rule.window, lock.rule.a, lock.rule.b, lock.rule.c,
                lock.rule.pos, lock.rule.delta, lock.rule.decay, lock.rule.fresh, lock.rule.cut,
                lock.zx, lock.grp));
        for (int w : new int[]{20, 40}) {
            TenRule alt = new TenRule(lock.rule.kind, w, lock.rule.a, lock.rule.b, lock.rule.c, lock.rule.pos, lock.rule.delta);
            alt.decay = lock.rule.decay;
            alt.fresh = lock.rule.fresh;
            alt.cut = lock.rule.cut;
            Hit30 other = scoreLook(digits, from, n, pl3, alt);
            sb.append(String.format(Locale.ROOT, "同一规则窗%d 重合%.0f%% %s%n",
                    w, other.overlap() * 100, other.rule.text()));
        }
    }

    private static final class Hit30 {
        TenRule rule;
        int zx;
        int grp;
        int share;
        int pairs;
        int sticky;
        int priorZ = -1;
        int priorG = -1;
        /** 2 直选，1 只组选，0 未中。只看窗口最后一期。 */
        int last;
        /** 窗口末尾 8 期的直选次数，用来看最近是不是连着没中。 */
        int tail;

        double overlap() {
            return pairs == 0 ? 1 : share / (20.0 * pairs);
        }
    }

    private static boolean hitBetter(Hit30 a, Hit30 b) {
        if (a.zx != b.zx) {
            return a.zx > b.zx;
        }
        if (a.grp != b.grp) {
            return a.grp > b.grp;
        }
        return a.overlap() < b.overlap();
    }

    private static int hitOrder(Hit30 a, Hit30 b) {
        if (a.grp != b.grp) {
            return Integer.compare(b.grp, a.grp);
        }
        if (a.zx != b.zx) {
            return Integer.compare(b.zx, a.zx);
        }
        int c = Double.compare(a.overlap(), b.overlap());
        if (c != 0) {
            return c;
        }
        if (a.rule.fresh != b.rule.fresh) {
            return Integer.compare(a.rule.fresh, b.rule.fresh);
        }
        c = Double.compare(b.rule.decay, a.rule.decay);
        if (c != 0) {
            return c;
        }
        return Integer.compare(a.rule.pos, b.rule.pos);
    }

    private static List<Hit30> overlapBand(List<Hit30> hits, double maxOverlap) {
        List<Hit30> out = new ArrayList<>();
        for (Hit30 hit : hits) {
            if (hit.overlap() < maxOverlap) {
                out.add(hit);
            }
        }
        return out;
    }

    private static List<TenRule> w30Bases() {
        List<TenRule> out = new ArrayList<>();
        out.add(new TenRule(0, 30, 0, 0, 0));
        out.add(new TenRule(1, 30, 0, 0, 0));
        double[] decays = {1, 0.92};
        for (double decay : decays) {
            TenRule six = new TenRule(5, 30, 0, 0, 0);
            six.decay = decay;
            out.add(six);
        }
        int[][] wide = {{10, 2, 1}, {10, 1, 2}, {2, 10, 1}, {1, 10, 2}, {2, 1, 10}, {1, 2, 10},
                {10, 2, 2}, {2, 10, 2}, {2, 2, 10}, {5, 5, 5}};
        for (int a = 1; a <= 8; a++) {
            for (int b = 1; b <= 8; b++) {
                for (int c = 1; c <= 8; c++) {
                    int prod = a * b * c;
                    if (prod < 16 || prod > 48) {
                        continue;
                    }
                    addBox(out, a, b, c, decays);
                }
            }
        }
        for (int[] sh : wide) {
            addBox(out, sh[0], sh[1], sh[2], decays);
        }
        return out;
    }

    private static void addBox(List<TenRule> out, int a, int b, int c, double[] decays) {
        int prod = a * b * c;
        int[] cuts = prod <= 20 ? new int[]{0} : prod <= 48 ? new int[]{0, 1, 2, 4} : new int[]{1, 2};
            for (int cut : cuts) {
                TenRule cold = new TenRule(3, 30, a, b, c);
                cold.cut = cut;
                out.add(cold);
                if (cut == 1 || cut == 2) {
                    for (double decay : decays) {
                        TenRule mix = new TenRule(9, 30, a, b, c);
                        mix.decay = decay;
                        mix.cut = cut;
                        out.add(mix);
                    }
                }
            for (double decay : decays) {
                for (int kind : new int[]{2, 4}) {
                    TenRule rule = new TenRule(kind, 30, a, b, c);
                    rule.decay = decay;
                    rule.cut = cut;
                    out.add(rule);
                }
            }
        }
    }

    private static void keepBest(List<Hit30> top, Hit30 hit, int limit) {
        top.add(hit);
        top.sort(Flow1000Ranker::hitOrder);
        while (top.size() > limit) {
            top.remove(top.size() - 1);
        }
    }

    private static TenRule copyRule(TenRule src, int pos, int delta, int fresh) {
        TenRule rule = new TenRule(src.kind, src.window, src.a, src.b, src.c, pos, delta);
        rule.decay = src.decay;
        rule.fresh = fresh;
        rule.cut = src.cut;
        rule.keep = src.keep;
        return rule;
    }

    private static Hit30 scoreCache(int[][] digits, int[] times, int[][] cache, TenRule rule) {
        Hit30 hit = new Hit30();
        hit.rule = rule;
        int[] prev = null;
        for (int i = 0; i < times.length; i++) {
            int[] bets = compose(digits, times[i], cache[i], rule);
            int id = code(digits[times[i]]);
            if (has(bets, id)) {
                hit.zx++;
                if (i >= times.length - 8) {
                    hit.tail++;
                }
                if (i == times.length - 1) {
                    hit.last = 2;
                }
            } else if (i == times.length - 1 && groupHit(bets, id)) {
                hit.last = 1;
            }
            if (groupHit(bets, id)) {
                hit.grp++;
            }
            if (prev != null) {
                int share = intersect(prev, bets);
                hit.share += share;
                hit.pairs++;
                if (share >= 16) {
                    hit.sticky++;
                }
            }
            prev = bets;
        }
        rule.zx = hit.zx;
        rule.grp = hit.grp;
        return hit;
    }

    private static int[] compose(int[][] digits, int end, int[] base, TenRule rule) {
        int[] bets = base == null ? new int[0] : base;
        if (rule.pos >= 0) {
            int[] off = {0, 0, 0};
            off[rule.pos] = rule.delta;
            bets = applyShift(bets, off[0], off[1], off[2], true, false);
        }
        int[] ranked = bets;
        int lastId = end > 0 ? code(digits[end - 1]) : -1;
        int[] neigh = lastId >= 0 ? lastNeigh(lastId) : new int[0];
        if (rule.fresh > 0 && lastId >= 0) {
            bets = takeFill(bets, neigh, Math.max(0, 20 - rule.fresh));
        }
        if (lastId >= 0) {
            bets = oneLastGroup(bets, neigh, ranked, lastId);
        }
        return bets;
    }

    /**
     * 与上一期开奖号数字相同、顺序不同的注，20 注里只留最先出现的一注。
     * 空位先补上期邻号，再补原先排在后面的号。
     */
    private static int[] oneLastGroup(int[] bets, int[] neigh, int[] ranked, int lastId) {
        int group = groupKey(lastId);
        int members = 0;
        int kept = -1;
        if (bets != null) {
            for (int id : bets) {
                if (groupKey(id) != group) {
                    continue;
                }
                members++;
                if (kept < 0) {
                    kept = id;
                }
            }
        }
        if (members <= 1) {
            return bets == null ? new int[0] : bets;
        }
        int[] out = new int[20];
        boolean[] used = new boolean[1000];
        int f = 0;
        f = fillExceptGroup(out, used, f, bets, group, kept);
        f = fillExceptGroup(out, used, f, neigh, group, kept);
        f = fillExceptGroup(out, used, f, ranked, group, kept);
        return Arrays.copyOf(out, f);
    }

    private static void directProbe(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        sb.append("===== ").append(name).append(" =====\n");
        hamLine(sb, digits, Math.max(1, n - 30), n, "近30");
        hamLine(sb, digits, Math.max(1, n - 60), Math.max(1, n - 30), "前30");
        hamLine(sb, digits, Math.max(1, n - 100), n, "近100");
        String[] names = {"现行", "邻号打底", "步长60", "步长30", "整注热200", "整注遗漏", "现行10加步长"};
        for (int scheme = 0; scheme < names.length; scheme++) {
            sb.append(names[scheme]);
            for (int[] span : new int[][]{{30, 0}, {30, 30}, {100, 0}}) {
                int end = n - span[1];
                int from = Math.max(40, end - span[0]);
                int[] sc = scoreScheme(digits, from, end, pl3, scheme);
                sb.append(String.format(Locale.ROOT, "  %s %d直%d组",
                        span[1] == 0 ? "近" + span[0] : "前" + span[0], sc[0], sc[1]));
            }
            sb.append('\n');
        }
        TenRule best = null;
        int bestZ = -1;
        int bestG = -1;
        double bestOv = 1;
        int seen = 0;
        int[] windows = {15, 30, 45};
        int[] kinds = {2, 3, 4};
        int[][] shapes = {{6, 2, 4}, {7, 3, 2}, {5, 3, 2}, {4, 4, 2}, {8, 2, 2}, {3, 3, 4}, {2, 4, 3}, {5, 2, 3}};
        for (int w : windows) {
            for (int kind : kinds) {
                for (int[] sh : shapes) {
                    for (int cut : new int[]{1, 2}) {
                        for (double decay : new double[]{1, 0.92}) {
                            TenRule base = new TenRule(kind, w, sh[0], sh[1], sh[2]);
                            base.decay = decay;
                            base.cut = cut;
                            for (int pos = -1; pos <= 2; pos++) {
                                int[] deltas = pos < 0 ? new int[]{0} : new int[]{-1, 1};
                                for (int delta : deltas) {
                                    TenRule rule = copyRule(base, pos, delta, 0);
                                    seen++;
                                    int[] now = scoreRule(digits, Math.max(40, n - 30), n, pl3, rule);
                                    if (now[3] > 0 || now[2] >= 750) {
                                        continue;
                                    }
                                    if (now[0] > bestZ || (now[0] == bestZ && now[1] > bestG)) {
                                        bestZ = now[0];
                                        bestG = now[1];
                                        bestOv = now[2] / 1000.0;
                                        best = rule;
                                        best.zx = now[0];
                                        best.grp = now[1];
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        sb.append("格子直选扫描 ").append(seen).append(" 条\n");
        if (best != null) {
            int[] prior = scoreRule(digits, Math.max(40, n - 60), Math.max(40, n - 30), pl3, best);
            int[] hundred = scoreRule(digits, Math.max(40, n - 100), n, pl3, best);
            sb.append(String.format(Locale.ROOT,
                    "直选最高 近30 %d直%d组 重合%.0f%% 前30 %d直%d组 近100 %d直%d组 %s%n",
                    bestZ, bestG, bestOv * 100, prior[0], prior[1], hundred[0], hundred[1], best.text()));
        }
    }

    private static void hamLine(StringBuilder sb, int[][] digits, int from, int end, String label) {
        int[] ham = new int[4];
        int periods = 0;
        for (int t = from; t < end; t++) {
            int h = posHamming(code(digits[t]), code(digits[t - 1]));
            ham[Math.min(3, h)]++;
            periods++;
        }
        sb.append(String.format(Locale.ROOT, "%s %d期 与上期差0/1/2/3位: %d %d %d %d%n",
                label, periods, ham[0], ham[1], ham[2], ham[3]));
    }

    /** 返回 直选、组选不含直选、重合千分比、黏连次数。 */
    private static int[] scoreScheme(int[][] digits, int from, int end, boolean pl3, int scheme) {
        int z = 0;
        int g = 0;
        int share = 0;
        int pairs = 0;
        int sticky = 0;
        int[] prev = null;
        for (int t = from; t < end; t++) {
            int[] bets = schemeTickets(digits, t, pl3, scheme);
            int id = code(digits[t]);
            if (has(bets, id)) {
                z++;
            } else if (groupHit(bets, id)) {
                g++;
            }
            if (prev != null) {
                int s = intersect(prev, bets);
                share += s;
                pairs++;
                if (s >= 16) {
                    sticky++;
                }
            }
            prev = bets;
        }
        int ov = pairs == 0 ? 1000 : (int) Math.round(share * 1000.0 / (20.0 * pairs));
        return new int[]{z, g, ov, sticky};
    }

    private static int[] scoreRule(int[][] digits, int from, int end, boolean pl3, TenRule rule) {
        int z = 0;
        int g = 0;
        int share = 0;
        int pairs = 0;
        int sticky = 0;
        int[] prev = null;
        for (int t = from; t < end; t++) {
            int[] bets = tenTickets(digits, t, pl3, rule);
            int id = code(digits[t]);
            if (has(bets, id)) {
                z++;
            } else if (groupHit(bets, id)) {
                g++;
            }
            if (prev != null) {
                int s = intersect(prev, bets);
                share += s;
                pairs++;
                if (s >= 16) {
                    sticky++;
                }
            }
            prev = bets;
        }
        int ov = pairs == 0 ? 1000 : (int) Math.round(share * 1000.0 / (20.0 * pairs));
        return new int[]{z, g, ov, sticky};
    }

    private static int[] schemeTickets(int[][] digits, int end, boolean pl3, int scheme) {
        int last = end > 0 ? code(digits[end - 1]) : 0;
        if (scheme == 0) {
            return tenTickets(digits, end, pl3, highRule(pl3));
        }
        int[] raw = switch (scheme) {
            case 1 -> takeFill(plusMinus(last), tenTickets(digits, end, pl3, highRule(pl3)), 6);
            case 2 -> stepBox(digits, end, 60, 3);
            case 3 -> stepBox(digits, end, 30, 3);
            case 4 -> codeRank(digits, end, true, 200);
            case 5 -> codeRank(digits, end, false, 0);
            default -> takeFill(tenTickets(digits, end, pl3, highRule(pl3)), stepBox(digits, end, 30, 3), 10);
        };
        return oneLastGroup(raw, plusMinus(last), raw, last);
    }

    private static int[] plusMinus(int id) {
        int[] out = new int[6];
        boolean[] used = new boolean[1000];
        int f = 0;
        int h = id / 100;
        int t = (id / 10) % 10;
        int u = id % 10;
        for (int d : new int[]{-1, 1}) {
            f = addPlain(out, used, f, ((h + d + 10) % 10) * 100 + t * 10 + u);
            f = addPlain(out, used, f, h * 100 + ((t + d + 10) % 10) * 10 + u);
            f = addPlain(out, used, f, h * 100 + t * 10 + ((u + d + 10) % 10));
        }
        return Arrays.copyOf(out, f);
    }

    /** 每位取近窗里最常见的增减步长，配成 20 注。 */
    private static int[] stepBox(int[][] digits, int end, int window, int take) {
        int[][] cnt = new int[3][10];
        int from = Math.max(1, end - window);
        for (int t = from; t < end; t++) {
            for (int p = 0; p < 3; p++) {
                cnt[p][Math.floorMod(digits[t][p] - digits[t - 1][p], 10)]++;
            }
        }
        int[][] top = new int[3][];
        for (int p = 0; p < 3; p++) {
            double[] s = new double[10];
            for (int i = 0; i < 10; i++) {
                s[i] = cnt[p][i];
            }
            top[p] = topK(s, take);
        }
        int nAll = take * take * take;
        int[] all = new int[nAll];
        double[] score = new double[nAll];
        int n = 0;
        int h = digits[end - 1][0];
        int t = digits[end - 1][1];
        int u = digits[end - 1][2];
        for (int a : top[0]) {
            for (int b : top[1]) {
                for (int c : top[2]) {
                    all[n] = Math.floorMod(h + a, 10) * 100 + Math.floorMod(t + b, 10) * 10 + Math.floorMod(u + c, 10);
                    score[n] = cnt[0][a] + cnt[1][b] + cnt[2][c];
                    n++;
                }
            }
        }
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) {
            ord[i] = i;
        }
        Arrays.sort(ord, (i, j) -> {
            int c = Double.compare(score[j], score[i]);
            return c != 0 ? c : Integer.compare(all[i], all[j]);
        });
        int[] out = new int[20];
        boolean[] used = new boolean[1000];
        int f = 0;
        for (int i = 0; i < n && f < 20; i++) {
            f = addPlain(out, used, f, all[ord[i]]);
        }
        return Arrays.copyOf(out, f);
    }

    /** 整注热号或整注遗漏，取前 20。 */
    private static int[] codeRank(int[][] digits, int end, boolean hot, int window) {
        double[] score = new double[1000];
        if (hot) {
            int from = Math.max(0, end - window);
            for (int t = from; t < end; t++) {
                score[code(digits[t])] += 1 + (t - from) * 0.002;
            }
        } else {
            int[] seen = new int[1000];
            Arrays.fill(seen, -1);
            for (int t = 0; t < end; t++) {
                seen[code(digits[t])] = t;
            }
            for (int id = 0; id < 1000; id++) {
                score[id] = seen[id] < 0 ? end : (end - 1 - seen[id]);
            }
        }
        Integer[] ord = new Integer[1000];
        for (int i = 0; i < 1000; i++) {
            ord[i] = i;
        }
        Arrays.sort(ord, (a, b) -> {
            int c = Double.compare(score[b], score[a]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        int[] out = new int[20];
        boolean[] used = new boolean[1000];
        int f = 0;
        for (int i = 0; i < 1000 && f < 20; i++) {
            f = addPlain(out, used, f, ord[i]);
        }
        return Arrays.copyOf(out, f);
    }

    private static int fillExceptGroup(int[] out, boolean[] used, int f, int[] src, int group, int kept) {
        if (src == null) {
            return f;
        }
        for (int id : src) {
            if (f >= 20) {
                return f;
            }
            if (groupKey(id) == group && id != kept) {
                continue;
            }
            f = addPlain(out, used, f, id);
        }
        return f;
    }

    private static Hit30 scoreLook(int[][] digits, int from, int end, boolean pl3, TenRule rule) {
        int[] times = new int[Math.max(0, end - from)];
        int[][] cache = new int[times.length][];
        for (int i = 0; i < times.length; i++) {
            times[i] = from + i;
            cache[i] = tenBase(digits, times[i], pl3, rule);
        }
        return scoreCache(digits, times, cache, rule);
    }

    private static int[] countLook(int[][] digits, int from, int end, boolean pl3, TenRule rule) {
        int z = 0;
        int g = 0;
        for (int t = from; t < end; t++) {
            int[] bets = tenTickets(digits, t, pl3, rule);
            int id = code(digits[t]);
            if (has(bets, id)) {
                z++;
            }
            if (groupHit(bets, id)) {
                g++;
            }
        }
        return new int[]{z, g};
    }

    private static String marks(int[][] digits, int from, int end, boolean pl3, TenRule rule) {
        StringBuilder sb = new StringBuilder();
        for (int t = from; t < end; t++) {
            int[] bets = tenTickets(digits, t, pl3, rule);
            int id = code(digits[t]);
            if (has(bets, id)) {
                sb.append(String.format(Locale.ROOT, " 直%03d", id));
            } else if (groupHit(bets, id)) {
                sb.append(String.format(Locale.ROOT, " 组%03d", id));
            } else {
                sb.append(String.format(Locale.ROOT, " 失%03d", id));
            }
        }
        return sb.toString();
    }

    private static int intersect(int[] a, int[] b) {
        int n = 0;
        if (a == null || b == null) {
            return 0;
        }
        for (int x : a) {
            for (int y : b) {
                if (x == y) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /** 最近 10 期走期回测，并给出下一期 20 注。 */
    private static void tenReport(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int from = Math.max(40, n - TEN_LOOK);
        sb.append("===== ").append(name).append(" =====\n");
        sb.append("近10开奖");
        for (int t = from; t < n; t++) {
            sb.append(String.format(Locale.ROOT, " %03d", code(digits[t])));
        }
        sb.append('\n');
        TenRule best = highRule(pl3);
        scoreWindow(digits, from, n, pl3, best);
        sb.append("留下 ").append(best.text()).append('\n');
        sb.append("逐期");
        for (int t = from; t < n; t++) {
            int[] bets = sanma(Arrays.copyOf(digits, t), pl3);
            int id = code(digits[t]);
            if (has(bets, id)) {
                sb.append(String.format(Locale.ROOT, " 直%03d", id));
            } else if (groupHit(bets, id)) {
                sb.append(String.format(Locale.ROOT, " 组%03d", id));
            } else {
                sb.append(String.format(Locale.ROOT, " 失%03d", id));
            }
        }
        sb.append('\n');
        if (best.zx < 3) {
            sb.append("  近10走期直选未到3，用直选最高的那条。\n");
        }
        int[] next = sanma(digits, pl3);
        sb.append(describe(pl3)).append('\n');
        sb.append("下一期 ").append(csv(next)).append('\n');
    }

    /** 上一期预测对上开奖号，再给出纠偏后的下一期。 */
    private static void lastFix(StringBuilder sb, String name, int[][] digits, boolean pl3, int lastDraw) {
        int n = digits.length;
        int have = n > 0 ? code(digits[n - 1]) : -1;
        int[][] hist = digits;
        if (have != lastDraw) {
            hist = Arrays.copyOf(digits, n + 1);
            hist[n] = new int[]{lastDraw / 100, (lastDraw / 10) % 10, lastDraw % 10};
        }
        int end = hist.length;
        NearPick pick = defaultPick(pl3);
        int[] lastBets = rawTickets(hist, end - 1, pl3, pick);
        DailyFix fix = classifyMiss(lastBets, code(hist[end - 1]));
        int[] next = sanma(hist, pl3);
        sb.append("===== ").append(name).append(" =====\n");
        sb.append(String.format(Locale.ROOT, "表内末号 %03d 用于纠偏的开奖 %03d%n", have, code(hist[end - 1])));
        sb.append("上期预测 ").append(csv(lastBets)).append('\n');
        sb.append(fix.text).append('\n');
        sb.append(describe(pl3)).append('\n');
        sb.append("下一期 ").append(csv(next)).append('\n');
    }

    /** 最近 30 期逐期现算，并给出下一期的 20 注。旧平移只作对照。 */
    private static void nearReport(StringBuilder sb, String name, int[][] digits, boolean pl3) {
        int n = digits.length;
        int from = Math.max(40, n - 30);
        int zx = 0;
        int grp = 0;
        int lzx = 0;
        int lgrp = 0;
        int periods = 0;
        for (int t = from; t < n; t++) {
            periods++;
            int[][] hist = Arrays.copyOf(digits, t);
            int id = code(digits[t]);
            int[] bets = sanma(hist, pl3);
            int[] old = legacyShiftSanma(hist, pl3);
            if (has(bets, id)) {
                zx++;
            }
            if (groupHit(bets, id)) {
                grp++;
            }
            if (has(old, id)) {
                lzx++;
            }
            if (groupHit(old, id)) {
                lgrp++;
            }
        }
        int[] next = sanma(digits, pl3);
        sb.append("===== ").append(name).append(" =====\n");
        sb.append(String.format(Locale.ROOT, "近%d期 现算 %d直%d组 | 旧平移 %d直%d组%n",
                periods, zx, grp, lzx, lgrp));
        sb.append(describe(pl3)).append('\n');
        sb.append("下一期 ").append(csv(next)).append('\n');
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
