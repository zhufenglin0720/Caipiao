package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RuleBasedDingWeiUtils {
   private static final Logger log = LoggerFactory.getLogger(RuleBasedDingWeiUtils.class);
   private static final RuleBasedDingWeiUtils.PosProfile[] PROFILE_3D = new RuleBasedDingWeiUtils.PosProfile[]{
      new RuleBasedDingWeiUtils.PosProfile(8, 0.06, 2.3, 13.0, 14.5, 22.0, 10.5, 0.02, 0.16, 30, 0.08),
      new RuleBasedDingWeiUtils.PosProfile(12, 0.33, 10.2, 7.4, 9.5, 7.1, 12.1, 0.18, 0.19, 20, 0.29),
      new RuleBasedDingWeiUtils.PosProfile(30, 0.53, 25.8, 25.3, 0.5, 11.7, 13.3, 0.3, 0.16, 40, 0.4)
   };
   private static final RuleBasedDingWeiUtils.PosProfile[] PROFILE_PL3 = new RuleBasedDingWeiUtils.PosProfile[]{
      new RuleBasedDingWeiUtils.PosProfile(30, 0.08, 16.3, 15.0, 22.4, 8.4, 3.6, 3.9, 0.34, 20, 0.36),
      new RuleBasedDingWeiUtils.PosProfile(30, 0.0, 28.7, 8.4, 1.5, 3.2, 13.0, 1.85, 0.05, 30, 0.02),
      new RuleBasedDingWeiUtils.PosProfile(10, 0.29, 16.8, 39.4, 21.5, 20.3, 17.8, 2.1, 0.18, 80, 0.27)
   };
   private static final RuleBasedDingWeiUtils.PosTune[] TUNE_3D = new RuleBasedDingWeiUtils.PosTune[]{
      new RuleBasedDingWeiUtils.PosTune(
         1.0058094419606394, -2.8124440447935015, 5.665014342679835, -0.6491208170032974, 3.126374096260557, 4.913188795330199, 1, 6
      ),
      new RuleBasedDingWeiUtils.PosTune(
         1.6616593178390395, -1.4962178305765115, -1.8907636787077062, -3.0720824289993884, 5.271633929355165, 3.6251947418617285, 3, 7
      ),
      new RuleBasedDingWeiUtils.PosTune(
         1.3325886758595047, -2.0442417097519736, 4.150405914284169, 2.9120996211358925, 5.364055230470751, 0.25374481204095173, 3, 7
      )
   };
   private static final RuleBasedDingWeiUtils.PosTune[] TUNE_PL3 = new RuleBasedDingWeiUtils.PosTune[]{
      new RuleBasedDingWeiUtils.PosTune(
         6.493867899495938, 0.6633551849740806, 8.288945191017358, 8.431928054715472, 11.757558097231097, 2.0076140867076266, 2, 8
      ),
      new RuleBasedDingWeiUtils.PosTune(
         0.2406041516602177, 5.216064033123188, -1.7689538307388575, -1.1490410854096234, 25.813076944829213, 4.57268872587389, 2, 8
      ),
      new RuleBasedDingWeiUtils.PosTune(
         0.24039574516254236, 0.5108281051200335, 3.441344969593886, 0.6664547822417778, 9.934731738956305, 6.1106855458307505, 1, 6
      )
   };
   private static final double[][] LINEAR_3D = new double[][]{
      {-0.4992, 2.6249, 0.0193, -0.9455, -0.5185, 1.0, 0.6829, 1.6497},
      {-1.5, -0.2737, 1.0, -0.25, -2.0, 0.5, 0.3352, 1.0},
      {1.0, 1.0, 1.0, 0.905, 1.0, 2.8976, 3.0, 0.7065}
   };
   private static final double[][] LINEAR_PL3 = new double[][]{
      {3.5093, 2.1106, 1.5336, 1.0, 1.0, 1.8018, 0.1658, -2.0},
      {1.0, 0.5636, -0.6229, 1.0, -2.0, 1.0463, 1.75, 1.9342},
      {1.4234, -0.4867, 1.0, 3.7629, 1.0, 1.0, 1.0, 1.0}
   };
   private static final int TOP7 = 7;
   private static final int MIN_HISTORY = 30;
   private static final int MAX_SAMPLE = 200;
   private static final double PL3_SOFT_NEIGH = 1.2;
   private static final double PL3_SOFT_OMIT_MID = 0.8;
   static double HABIT_SCALE = 1.0;
   static int ENSURE_LAST_MAX_RANK = 10;
   static boolean CROSS_LAST = true;
   static int ENSURE_NEIGH_MAX_RANK = 0;

   private RuleBasedDingWeiUtils() {
   }

   public static String get3dDingWei() {
      return predict(HmCache.getSdCache(), HmCache.getSdCompareCache(), RuleBasedDingWeiUtils.GameKind.SD_3D);
   }

   public static String getPl3DingWei() {
      return predict(HmCache.getPl3Cache(), HmCache.getPl3CompareCache(), RuleBasedDingWeiUtils.GameKind.PL3);
   }

   public static String predict(List<Hm> history) {
      return predict(history, null, RuleBasedDingWeiUtils.GameKind.SD_3D);
   }

   public static String predict(List<Hm> history, List<HmCache.CompareDto> compares) {
      return predict(history, compares, RuleBasedDingWeiUtils.GameKind.SD_3D);
   }

   public static String predict(List<Hm> history, List<HmCache.CompareDto> compares, RuleBasedDingWeiUtils.GameKind kind) {
      if (history != null && history.size() >= 30) {
         RuleBasedDingWeiUtils.GameKind gameKind = kind == null ? RuleBasedDingWeiUtils.GameKind.SD_3D : kind;
         RuleBasedDingWeiUtils.PosProfile[] profiles = gameKind == RuleBasedDingWeiUtils.GameKind.PL3 ? PROFILE_PL3 : PROFILE_3D;
         RuleBasedDingWeiUtils.PosTune[] baseTunes = gameKind == RuleBasedDingWeiUtils.GameKind.PL3 ? TUNE_PL3 : TUNE_3D;
         double[][] linear = gameKind == RuleBasedDingWeiUtils.GameKind.PL3 ? LINEAR_PL3 : LINEAR_3D;
         HitRateMetaTuner.Snapshot meta = HitRateMetaTuner.analyze(compares, gameKind == RuleBasedDingWeiUtils.GameKind.PL3);
         RuleBasedDingWeiUtils.PosTune[] tunes = applyMetaBand(baseTunes, meta);
         int[][] digits = toDigitMatrix(tail(history, Math.max(200, needSample(profiles))));
         DrawHabit habit = DrawHabit.of(digits);
         int[][] prev = lastDingWeiPick(compares);
         int[][] top7 = new int[3][7];

         for (int pos = 0; pos < 3; pos++) {
            double[] score = scoreWithExperience(digits, pos, linear[pos], profiles[pos], tunes[pos]);

            for (int d = 0; d < 10; d++) {
               score[d] += habit.posBonus(pos, d) * HABIT_SCALE;
            }

            if (gameKind == RuleBasedDingWeiUtils.GameKind.PL3) {
               applyPl3SoftHitBoost(digits, pos, score, meta.softNeighMul, meta.softOmitMul);
            } else if (meta.droughtLevel >= 2 || meta.missDingWei >= 3) {
               applySoftNeighBoost(digits, pos, score, meta.softNeighMul);
            }

            top7[pos] = pickBandAwareTop7(score, tunes[pos]);
            top7[pos] = applyHabitEnsures(top7[pos], pos, score, habit);
            if (prev != null && PrevPeriodDedup.sameIntSet(top7[pos], prev[pos])) {
               top7[pos] = rotateIfSame(top7[pos], score);
               top7[pos] = applyHabitEnsures(top7[pos], pos, score, habit);
            }

            if (posMissRotate(meta, pos, gameKind)) {
               int[] swapped = swapInNextRank(top7[pos], score, habit.last[pos], meta.missDwPos[pos] >= 5 ? 2 : 1);
               if (!PrevPeriodDedup.sameIntSet(swapped, top7[pos])) {
                  log.info(
                     "七码连挂换号[{}] {} 连挂{}期 {} → {}",
                     new Object[]{gameKind, posName(pos), meta.missDwPos[pos], Arrays.toString(top7[pos]), Arrays.toString(swapped)}
                  );
                  top7[pos] = swapped;
               }
            }

            log.info(
               "七码定位[{}] {} Top7={} 命中带{}-{} (base{}-{})",
               new Object[]{
                  gameKind, posName(pos), Arrays.toString(top7[pos]), tunes[pos].bandLo, tunes[pos].bandHi, baseTunes[pos].bandLo, baseTunes[pos].bandHi
               }
            );
         }

         String result = format(top7);
         log.info("七码定位结果: {} | {}", result, meta.describe());
         return result;
      } else {
         log.warn("七码定位历史不足，size={}", history == null ? 0 : history.size());
         return null;
      }
   }

   private static RuleBasedDingWeiUtils.PosTune[] applyMetaBand(RuleBasedDingWeiUtils.PosTune[] base, HitRateMetaTuner.Snapshot meta) {
      RuleBasedDingWeiUtils.PosTune[] out = new RuleBasedDingWeiUtils.PosTune[base.length];

      for (int p = 0; p < base.length; p++) {
         RuleBasedDingWeiUtils.PosTune t = base[p];
         int lo = Math.max(1, Math.min(8, t.bandLo + meta.dwBandLoDelta[p]));
         int hi = Math.max(lo, Math.min(10, t.bandHi + meta.dwBandHiDelta[p]));
         if (hi - lo < 3) {
            hi = Math.min(10, lo + 3);
         }

         out[p] = new RuleBasedDingWeiUtils.PosTune(t.wLinear, t.wProfile, t.wRepeat, t.wCross, t.wAb, t.wNeigh, lo, hi);
      }

      return out;
   }

   private static void applyPl3SoftHitBoost(int[][] h, int pos, double[] score, double neighMul, double omitMul) {
      int[] om = omission(h, pos);
      int last = h[h.length - 1][pos];
      double neigh = 1.2 * (neighMul <= 0.0 ? 1.0 : neighMul);
      double omit = 0.8 * (omitMul <= 0.0 ? 1.0 : omitMul);

      for (int d = 0; d < 10; d++) {
         if (om[d] >= 3 && om[d] <= 10) {
            score[d] += omit;
         }

         if (d == neighbor(last, -1) || d == neighbor(last, 1)) {
            score[d] += neigh;
         }
      }
   }

   private static void applySoftNeighBoost(int[][] h, int pos, double[] score, double neighMul) {
      int last = h[h.length - 1][pos];
      double boost = 0.85 * (neighMul <= 0.0 ? 1.0 : neighMul);

      for (int d = 0; d < 10; d++) {
         if (d == neighbor(last, -1) || d == neighbor(last, 1)) {
            score[d] += boost;
         }
      }
   }

   private static int needSample(RuleBasedDingWeiUtils.PosProfile[] profiles) {
      int max = 200;

      for (RuleBasedDingWeiUtils.PosProfile p : profiles) {
         max = Math.max(max, Math.max(p.w, p.w2) + 20);
      }

      return Math.max(max, 120);
   }

   private static double[] scoreWithExperience(int[][] h, int pos, double[] linW, RuleBasedDingWeiUtils.PosProfile pf, RuleBasedDingWeiUtils.PosTune tune) {
      double[] lin = scoreLinear(h, pos, linW);
      double[] prof = scoreDigits(h, pos, pf);
      double[] s = new double[10];

      for (int d = 0; d < 10; d++) {
         s[d] = tune.wLinear * lin[d] + tune.wProfile * (prof[d] / 10.0);
      }

      int n = h.length;
      int b = h[n - 1][pos];
      int a = n >= 2 ? h[n - 2][pos] : b;
      s[b] += tune.wRepeat;
      boolean[] lastDraw = new boolean[10];

      for (int p = 0; p < 3; p++) {
         lastDraw[h[n - 1][p]] = true;
      }

      for (int d = 0; d < 10; d++) {
         if (lastDraw[d]) {
            s[d] += d == b ? tune.wCross * 0.35 : tune.wCross;
         }
      }

      if (n >= 2) {
         boolean[] prevDraw = new boolean[10];

         for (int p = 0; p < 3; p++) {
            prevDraw[h[n - 2][p]] = true;
         }

         for (int dx = 0; dx < 10; dx++) {
            if (prevDraw[dx] && !lastDraw[dx]) {
               s[dx] += tune.wCross * 0.4;
            }
         }
      }

      for (int base : new int[]{a, b}) {
         s[base] += tune.wAb;
         s[neighbor(base, -1)] += tune.wNeigh;
         s[neighbor(base, 1)] += tune.wNeigh;
      }

      return s;
   }

   private static int[] pickBandAwareTop7(double[] score, RuleBasedDingWeiUtils.PosTune tune) {
      Integer[] order = new Integer[10];

      for (int i = 0; i < 10; i++) {
         order[i] = i;
      }

      Arrays.sort(order, (x, y) -> {
         int c = Double.compare(score[y], score[x]);
         return c != 0 ? c : Integer.compare(x, y);
      });
      LinkedHashSet<Integer> set = new LinkedHashSet<>();

      for (int i = 0; i < 4 && set.size() < 7; i++) {
         set.add(order[i]);
      }

      int lo = tune.bandLo;
      int hi = tune.bandHi;

      for (int i = 0; i < 10 && set.size() < 7; i++) {
         int rank = i + 1;
         if (rank >= lo && rank <= hi) {
            set.add(order[i]);
         }
      }

      for (int ix : new int[]{4, 5, 8, 9}) {
         if (set.size() >= 7) {
            break;
         }

         set.add(order[ix]);
      }

      Integer[] var13 = order;
      int n = order.length;

      for (int var18 = 0; var18 < n; var18++) {
         int d = var13[var18];
         if (set.size() >= 7) {
            break;
         }

         set.add(d);
      }

      int[] out = new int[7];
      n = 0;

      for (int d : set) {
         out[n++] = d;
      }

      return out;
   }

   private static int[] applyHabitEnsures(int[] pick, int pos, double[] score, DrawHabit habit) {
      int[] out = ensureContainsIfStrong(pick, habit.last[pos], score, ENSURE_LAST_MAX_RANK);
      if (CROSS_LAST) {
         for (int o = 0; o < 3; o++) {
            if (o != pos) {
               out = ensureContainsIfStrong(out, habit.last[o], score, ENSURE_LAST_MAX_RANK);
            }
         }
      }

      if (ENSURE_NEIGH_MAX_RANK > 0 && !containsDigit(out, habit.last[pos])) {
         int d = habit.last[pos];
         out = ensureContainsIfStrong(out, (d + 1) % 10, score, ENSURE_NEIGH_MAX_RANK);
         out = ensureContainsIfStrong(out, (d + 9) % 10, score, ENSURE_NEIGH_MAX_RANK);
      }

      return out;
   }

   private static boolean containsDigit(int[] pick, int d) {
      if (pick == null) {
         return false;
      } else {
         for (int x : pick) {
            if (x == d) {
               return true;
            }
         }

         return false;
      }
   }

   private static int[] ensureContainsIfStrong(int[] pick, int must, double[] score) {
      return ensureContainsIfStrong(pick, must, score, ENSURE_LAST_MAX_RANK);
   }

   private static int[] ensureContainsIfStrong(int[] pick, int must, double[] score, int maxRank) {
      if (pick == null) {
         return pick;
      } else {
         for (int d : pick) {
            if (d == must) {
               return pick;
            }
         }

         int better = 0;

         for (int dx = 0; dx < 10; dx++) {
            if (score[dx] > score[must] || score[dx] == score[must] && dx < must) {
               better++;
            }
         }

         int rank = better + 1;
         return maxRank > 0 && rank <= maxRank ? ensureContains(pick, must, score) : pick;
      }
   }

   private static int[] ensureContains(int[] pick, int must, double[] score) {
      if (pick == null) {
         return pick;
      } else {
         for (int d : pick) {
            if (d == must) {
               return pick;
            }
         }

         int worst = 0;
         double worstSc = Double.POSITIVE_INFINITY;

         for (int i = 0; i < pick.length; i++) {
            if (score[pick[i]] < worstSc) {
               worstSc = score[pick[i]];
               worst = i;
            }
         }

         int[] out = Arrays.copyOf(pick, pick.length);
         out[worst] = must;
         return out;
      }
   }

   private static boolean posMissRotate(HitRateMetaTuner.Snapshot meta, int pos, RuleBasedDingWeiUtils.GameKind kind) {
      if (meta != null && meta.missDwPos != null && pos >= 0 && pos < meta.missDwPos.length) {
         int trigger = kind == RuleBasedDingWeiUtils.GameKind.PL3 ? 2 : 3;
         return meta.missDwPos[pos] >= trigger;
      } else {
         return false;
      }
   }

   private static int[] swapInNextRank(int[] pick, double[] score, int protect, int slots) {
      if (pick != null && pick.length != 0 && slots > 0) {
         boolean[] used = new boolean[10];

         for (int d : pick) {
            if (d >= 0 && d <= 9) {
               used[d] = true;
            }
         }

         List<Integer> incoming = new ArrayList<>();
         Integer[] order = new Integer[10];

         for (int i = 0; i < 10; i++) {
            order[i] = i;
         }

         Arrays.sort(order, (x, y) -> {
            int c = Double.compare(score[y], score[x]);
            return c != 0 ? c : Integer.compare(x, y);
         });
         Integer[] weak = order;
         int var19 = order.length;

         for (int replaced = 0; replaced < var19; replaced++) {
            int dx = weak[replaced];
            if (!used[dx]) {
               incoming.add(dx);
            }

            if (incoming.size() >= slots) {
               break;
            }
         }

         if (incoming.isEmpty()) {
            return pick;
         } else {
            weak = new Integer[pick.length];

            for (int i = 0; i < pick.length; i++) {
               weak[i] = i;
            }

            Arrays.sort(weak, (ix, j) -> {
               int c = Double.compare(score[pick[ix]], score[pick[j]]);
               return c != 0 ? c : Integer.compare(pick[j], pick[ix]);
            });
            int[] out = Arrays.copyOf(pick, pick.length);
            int replaced = 0;
            Integer[] var23 = weak;
            int var11 = weak.length;

            for (int var12 = 0; var12 < var11; var12++) {
               int i = var23[var12];
               if (replaced >= incoming.size()) {
                  break;
               }

               if (out[i] != protect) {
                  out[i] = incoming.get(replaced);
                  replaced++;
               }
            }

            return out;
         }
      } else {
         return pick;
      }
   }

   private static int[] rotateIfSame(int[] keep, double[] score) {
      boolean[] used = new boolean[10];

      for (int d : keep) {
         used[d] = true;
      }

      int best = -1;
      double bestSc = Double.NEGATIVE_INFINITY;

      for (int d = 0; d < 10; d++) {
         if (!used[d] && score[d] > bestSc) {
            bestSc = score[d];
            best = d;
         }
      }

      if (best < 0) {
         return keep;
      } else {
         int[] out = Arrays.copyOf(keep, keep.length);
         out[out.length - 1] = best;
         return out;
      }
   }

   private static int[][] lastDingWeiPick(List<HmCache.CompareDto> compares) {
      String raw = PrevPeriodDedup.lastField(compares, HmCache.CompareDto::getAiDingWeiHm);
      String[] parts = parseParts(raw);
      if (parts == null) {
         return null;
      } else {
         int[][] out = new int[3][];

         for (int p = 0; p < 3; p++) {
            String[] toks = parts[p].split(",");
            int[] arr = new int[toks.length];
            int n = 0;

            for (String t : toks) {
               String s = t.trim();
               if (s.matches("\\d")) {
                  arr[n++] = s.charAt(0) - '0';
               }
            }

            if (n == 0) {
               return null;
            }

            out[p] = Arrays.copyOf(arr, n);
         }

         return out;
      }
   }

   private static double[] scoreLinear(int[][] h, int pos, double[] w) {
      double[] s = new double[10];
      int[] f8 = freq(h, pos, 8);
      int[] f15 = freq(h, pos, 15);
      int[] f30 = freq(h, pos, 30);
      int[] om = omission(h, pos);
      int[] tr = transCount(h, pos);
      int[] f5 = freq(h, pos, 5);
      int last = h[h.length - 1][pos];

      for (int d = 0; d < 10; d++) {
         s[d] = w[0] * f8[d]
            + w[1] * f15[d]
            + w[2] * f30[d]
            + w[3] * Math.min(om[d], 20)
            + w[4] * tr[d]
            + w[5] * (d == last ? 1 : 0)
            + w[6] * (d != neighbor(last, -1) && d != neighbor(last, 1) ? 0 : 1)
            + w[7] * (f5[d] >= 2 ? -1 : 0);
         if (om[d] >= 4 && om[d] <= 12) {
            s[d] += Math.abs(w[3]) * 0.15;
         }
      }

      return s;
   }

   private static int[] transCount(int[][] h, int pos) {
      int[] t = new int[10];
      int last = h[h.length - 1][pos];
      int from = Math.max(1, h.length - 120);

      for (int i = from; i < h.length; i++) {
         if (h[i - 1][pos] == last) {
            t[h[i][pos]]++;
         }
      }

      return t;
   }

   private static double[] scoreDigits(int[][] h, int pos, RuleBasedDingWeiUtils.PosProfile pf) {
      double[] s = normalize100(freq(h, pos, pf.w));
      if (pf.mixW2 > 0.0) {
         s = mix(s, normalize100(freq(h, pos, pf.w2)), 1.0 - pf.mixW2, pf.mixW2);
      }

      if (pf.mixT > 0.0) {
         s = mix(s, normalize100(transScore(h, pos)), 1.0 - pf.mixT, pf.mixT);
      }

      int last = h[h.length - 1][pos];
      int[] f3 = freq(h, pos, 3);
      int[] f5 = freq(h, pos, 5);
      int[] om = omission(h, pos);
      int[] spans = top2Spans(h, pos, 25);

      for (int d = 0; d < 10; d++) {
         s[d] -= f3[d] * pf.anti;
         s[d] += Math.min(om[d], 18) * pf.omitF;
         int spa = Math.abs(d - last);
         if (spa == spans[0] || spa == spans[1]) {
            s[d] += pf.span;
         }

         if (d == last) {
            s[d] += pf.lastB;
         }

         if (d == neighbor(last, -1) || d == neighbor(last, 1)) {
            s[d] += pf.neighB;
         }

         if (om[d] >= 4 && om[d] <= 14) {
            s[d] += pf.mid;
         }

         if (f5[d] >= 3) {
            s[d] -= 10.0;
         }
      }

      return s;
   }

   private static double[] transScore(int[][] h, int pos) {
      double[] s = new double[10];
      int last = h[h.length - 1][pos];
      int from = Math.max(1, h.length - 100);

      for (int i = from; i < h.length; i++) {
         if (h[i - 1][pos] == last) {
            s[h[i][pos]]++;
         }
      }

      return s;
   }

   private static double[] mix(double[] a, double[] b, double wa, double wb) {
      double[] out = new double[10];

      for (int i = 0; i < 10; i++) {
         out[i] = a[i] * wa + b[i] * wb;
      }

      return out;
   }

   public static String predictFromCodes(List<String> codes) {
      return predictFromCodes(codes, RuleBasedDingWeiUtils.GameKind.PL3);
   }

   public static String predictFromCodes(List<String> codes, RuleBasedDingWeiUtils.GameKind kind) {
      List<Hm> list = new ArrayList<>(codes.size());

      for (int i = 0; i < codes.size(); i++) {
         String c = codes.get(i);

         while (c.length() < 3) {
            c = "0" + c;
         }

         list.add(
            Hm.builder().qh(String.valueOf(i + 1)).q1(String.valueOf(c.charAt(0))).q2(String.valueOf(c.charAt(1))).q3(String.valueOf(c.charAt(2))).build()
         );
      }

      return predict(list, null, kind);
   }

   public static String[] parseParts(String answer) {
      if (answer != null && !answer.isBlank()) {
         String s = answer.trim().replace('：', ':').replaceAll("\\s+", " ");
         if (s.contains("百位:") && s.contains("十位:") && s.contains("个位:")) {
            try {
               int iBai = s.indexOf("百位:");
               int iShi = s.indexOf("十位:");
               int iGe = s.indexOf("个位:");
               if (iBai >= 0 && iShi >= 0 && iGe >= 0) {
                  String bai = s.substring(iBai + 3, iShi).trim();
                  String shi = s.substring(iShi + 3, iGe).trim();
                  String ge = s.substring(iGe + 3).trim();
                  return validSeven(bai) && validSeven(shi) && validSeven(ge) ? new String[]{bai, shi, ge} : null;
               } else {
                  return null;
               }
            } catch (Exception var8) {
               log.warn("解析七码失败: {}", answer, var8);
               return null;
            }
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private static boolean validSeven(String part) {
      String[] arr = part.split(",");
      if (arr.length != 7) {
         return false;
      } else {
         for (String a : arr) {
            if (a == null || a.isBlank()) {
               return false;
            }

            try {
               int d = Integer.parseInt(a.trim());
               if (d < 0 || d > 9) {
                  return false;
               }
            } catch (NumberFormatException var7) {
               return false;
            }
         }

         return true;
      }
   }

   private static double[] normalize100(int[] freq) {
      double[] d = new double[10];

      for (int i = 0; i < 10; i++) {
         d[i] = freq[i];
      }

      return normalize100(d);
   }

   private static double[] normalize100(double[] freq) {
      double[] out = new double[10];
      double max = 0.0;

      for (double f : freq) {
         max = Math.max(max, f);
      }

      if (max <= 0.0) {
         return out;
      } else {
         for (int i = 0; i < 10; i++) {
            out[i] = freq[i] * 100.0 / max;
         }

         return out;
      }
   }

   private static int[] freq(int[][] digits, int pos, int window) {
      int[] f = new int[10];
      int n = digits.length;
      int from = Math.max(0, n - window);

      for (int i = from; i < n; i++) {
         f[digits[i][pos]]++;
      }

      return f;
   }

   private static int[] omission(int[][] digits, int pos) {
      int[] om = new int[10];
      Arrays.fill(om, digits.length);

      for (int d = 0; d < 10; d++) {
         for (int i = digits.length - 1; i >= 0; i--) {
            if (digits[i][pos] == d) {
               om[d] = digits.length - 1 - i;
               break;
            }
         }
      }

      return om;
   }

   private static int[] top2Spans(int[][] digits, int pos, int window) {
      int[] spanCnt = new int[10];
      int n = digits.length;
      int from = Math.max(1, n - window);

      for (int i = from; i < n; i++) {
         int sp = Math.abs(digits[i][pos] - digits[i - 1][pos]);
         if (sp < 10) {
            spanCnt[sp]++;
         }
      }

      Integer[] idx = new Integer[10];

      for (int ix = 0; ix < 10; ix++) {
         idx[ix] = ix;
      }

      Arrays.sort(idx, (a, b) -> Integer.compare(spanCnt[b], spanCnt[a]));
      return new int[]{idx[0], idx[1]};
   }

   private static int neighbor(int d, int delta) {
      return (d + delta + 10) % 10;
   }

   private static List<Hm> tail(List<Hm> history, int max) {
      return history.size() <= max ? history : history.subList(history.size() - max, history.size());
   }

   private static int[][] toDigitMatrix(List<Hm> history) {
      int n = history.size();
      int[][] m = new int[n][3];

      for (int i = 0; i < n; i++) {
         Hm hm = history.get(i);
         m[i][0] = parseDigit(hm.getQ1());
         m[i][1] = parseDigit(hm.getQ2());
         m[i][2] = parseDigit(hm.getQ3());
      }

      return m;
   }

   private static int parseDigit(String s) {
      return s != null && !s.isEmpty() ? s.charAt(s.length() - 1) - 48 : 0;
   }

   private static String format(int[][] top7) {
      StringBuilder sb = new StringBuilder();
      sb.append("百位:");
      appendDigits(sb, top7[0]);
      sb.append(' ');
      sb.append("十位:");
      appendDigits(sb, top7[1]);
      sb.append(' ');
      sb.append("个位:");
      appendDigits(sb, top7[2]);
      return sb.toString();
   }

   private static void appendDigits(StringBuilder sb, int[] arr) {
      for (int i = 0; i < arr.length; i++) {
         if (i > 0) {
            sb.append(',');
         }

         sb.append(arr[i]);
      }
   }

   private static String posName(int pos) {
      return switch (pos) {
         case 0 -> "百";
         case 1 -> "十";
         default -> "个";
      };
   }

   public static enum GameKind {
      SD_3D,
      PL3;
   }

   private static final class PosProfile {
      final int w;
      final double mixT;
      final double anti;
      final double span;
      final double mid;
      final double lastB;
      final double neighB;
      final double omitF;
      final double comboW;
      final int w2;
      final double mixW2;

      PosProfile(int w, double mixT, double anti, double span, double mid, double lastB, double neighB, double omitF, double comboW, int w2, double mixW2) {
         this.w = w;
         this.mixT = mixT;
         this.anti = anti;
         this.span = span;
         this.mid = mid;
         this.lastB = lastB;
         this.neighB = neighB;
         this.omitF = omitF;
         this.comboW = comboW;
         this.w2 = w2;
         this.mixW2 = mixW2;
      }
   }

   private static final class PosTune {
      final double wLinear;
      final double wProfile;
      final double wRepeat;
      final double wCross;
      final double wAb;
      final double wNeigh;
      final int bandLo;
      final int bandHi;

      PosTune(double wLinear, double wProfile, double wRepeat, double wCross, double wAb, double wNeigh, int bandLo, int bandHi) {
         this.wLinear = wLinear;
         this.wProfile = wProfile;
         this.wRepeat = wRepeat;
         this.wCross = wCross;
         this.wAb = wAb;
         this.wNeigh = wNeigh;
         this.bandLo = bandLo;
         this.bandHi = bandHi;
      }
   }
}
