package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map.Entry;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Overfit20PredictUtils {
   private static final Logger log = LoggerFactory.getLogger(Overfit20PredictUtils.class);
   public static final int WINDOW = 30;
   public static final int GROUP_COUNT = 5;
   public static final int EVAL_PERIODS = 10;
   public static final int MAX_TICKETS = 250;
   public static final int MAX_GROUPS = 120;
   public static final int ZX_TARGET = 4;
   public static final int GROUP_TARGET = 3;
   private static final int COVER_META = 10;
   private static final int TUNE_WARMUP = 4;
   static volatile boolean ENABLE_NEIGHBOR_EXPAND = true;

   private Overfit20PredictUtils() {
   }

   public static String get3dPredict() {
      return predictResult(HmCache.getSdCache(), Overfit20PredictUtils.GameKind.SD, HmCache.getSdCompareCache()).poolCsv();
   }

   public static String getPl3Predict() {
      return predictResult(HmCache.getPl3Cache(), Overfit20PredictUtils.GameKind.PL3, HmCache.getPl3CompareCache()).poolCsv();
   }

   public static String get3dPool() {
      return predictResult(HmCache.getSdCache(), Overfit20PredictUtils.GameKind.SD, HmCache.getSdCompareCache()).poolCsv();
   }

   public static String getPl3Pool() {
      return predictResult(HmCache.getPl3Cache(), Overfit20PredictUtils.GameKind.PL3, HmCache.getPl3CompareCache()).poolCsv();
   }

   public static Overfit20PredictUtils.PredictResult predictResult(List<Hm> history) {
      List<String> codes = toCodes(history);
      if (codes.isEmpty()) {
         return new Overfit20PredictUtils.PredictResult(List.of(), List.of(), "empty");
      } else {
         int from = Math.max(0, codes.size() - 30);
         List<String> window = codes.subList(from, codes.size());
         Overfit20PredictUtils.GameKind kind = uniqueGroupRatio(window) >= 0.93 ? Overfit20PredictUtils.GameKind.SD : Overfit20PredictUtils.GameKind.PL3;
         return predictWindow(window, kind);
      }
   }

   public static Overfit20PredictUtils.PredictResult predictResult(List<Hm> history, Overfit20PredictUtils.GameKind kind) {
      return predictResult(history, kind, null);
   }

   public static Overfit20PredictUtils.PredictResult predictResult(List<Hm> history, Overfit20PredictUtils.GameKind kind, List<HmCache.CompareDto> compares) {
      List<String> codes = toCodes(history);
      if (codes.isEmpty()) {
         return new Overfit20PredictUtils.PredictResult(List.of(), List.of(), "empty");
      } else {
         int from = Math.max(0, codes.size() - 30);
         List<String> window = codes.subList(from, codes.size());
         Set<String> banned = new LinkedHashSet<>();
         if (!codes.isEmpty()) {
            banned.add(pad3(codes.get(codes.size() - 1)));
         }

         return predictWindow(window, kind == null ? Overfit20PredictUtils.GameKind.SD : kind, banned);
      }
   }

   public static String predict(List<Hm> history) {
      Overfit20PredictUtils.PredictResult r = predictResult(history);
      log.info("近{}期过拟合组合: 池={}注 | {}", new Object[]{30, r.pool.size(), r.tune});
      return r.poolCsv();
   }

   static int tuneStart(int size) {
      if (size <= 1) {
         return 0;
      } else {
         int fromEval = Math.max(0, size - 10);
         return Math.max(Math.min(4, size - 1), fromEval);
      }
   }

   static Overfit20PredictUtils.PredictResult predictWindow(List<String> window) {
      Overfit20PredictUtils.GameKind kind = uniqueGroupRatio(window) >= 0.93 ? Overfit20PredictUtils.GameKind.SD : Overfit20PredictUtils.GameKind.PL3;
      return predictWindow(window, kind);
   }

   static Overfit20PredictUtils.PredictResult predictWindow(List<String> window, Overfit20PredictUtils.GameKind kind) {
      return predictWindow(window, kind, Set.of());
   }

   static Overfit20PredictUtils.PredictResult predictWindow(List<String> window, Overfit20PredictUtils.GameKind kind, Set<String> banned) {
      if (window != null && !window.isEmpty()) {
         List<String> win = window.size() > 30 ? window.subList(window.size() - 30, window.size()) : window;
         double uniq = uniqueGroupRatio(win);
         int topN = clamp((int)Math.round(4.0 + 5.0 * uniq), 4, 8);
         int posM = clamp((int)Math.round(4.0 + 3.0 * uniq), 4, 6);
         List<int[]> bands = deriveBandCandidates(win, topN, posM);
         int bestLo = bands.get(0)[0];
         int bestHi = bands.get(0)[1];
         int bestTake = bands.get(0)[2];
         double bestBandScore = Double.NEGATIVE_INFINITY;
         int bestEh = 0;
         int start = tuneStart(win.size());

         for (int[] band : bands) {
            double sc = 0.0;
            int eh = 0;

            for (int i = start; i < win.size(); i++) {
               List<String> sub = win.subList(0, i);
               List<String> pool = buildGroupPool(sub, topN, band[0], band[1], band[2], posM, 120);
               double wt = Math.exp(-0.35 * (win.size() - 1 - i));
               if (pool.contains(sortedKey(win.get(i)))) {
                  eh++;
                  sc += 3.0 * wt;
               }
            }

            double score = sc * 10.0 + eh * 8;
            if (score > bestBandScore) {
               bestBandScore = score;
               bestLo = band[0];
               bestHi = band[1];
               bestTake = band[2];
               bestEh = eh;
            }
         }

         boolean drought = bestEh <= 1;
         int ticketCap = 250;
         int maxExtra = drought ? 4 : 3;
         if (drought) {
            topN = Math.min(9, topN + 1);
            posM = Math.min(7, posM + 1);
         }

         Overfit20PredictUtils.CoverSpec cover = selectCover(win, topN, bestLo, bestHi, bestTake, posM, maxExtra);
         int banN = banned == null ? 0 : banned.size();
         List<String> strategy = buildTicketPool(win, topN, bestLo, bestHi, bestTake, posM, cover, Math.max(60, ticketCap / 2));
         Overfit20PredictUtils.PlusMinus1Profile pm1 = learnPlusMinus1Profile(win, strategy);
         LinkedHashSet<String> habitFirst = recentFullHam1(
            win, kind == Overfit20PredictUtils.GameKind.PL3 ? 1 : 2, kind == Overfit20PredictUtils.GameKind.PL3 ? 32 : 64
         );
         habitFirst.addAll(habitSeedPool(win, 48));
         List<String> ham = kind == Overfit20PredictUtils.GameKind.PL3 ? buildPl3Ham1Pool(win, strategy, ticketCap) : buildSdHam1Pool(win, strategy, ticketCap);
         LinkedHashSet<String> merged = new LinkedHashSet<>(habitFirst);
         merged.addAll(ham);
         List<String> extras = expandSinglePosNeighbors(new ArrayList<>(merged), ticketCap + 40);
         List<String> directs = trimCap(merged, ticketCap);
         if (ENABLE_NEIGHBOR_EXPAND && directs.size() < ticketCap) {
            directs = expandSinglePosNeighbors(directs, ticketCap);
         }

         if (banned != null && !banned.isEmpty()) {
            directs = PrevPeriodDedup.excludeTickets(directs, banned, ticketCap, extras);
         }

         if (directs.size() < ticketCap) {
            directs = PrevPeriodDedup.excludeTickets(directs, banned == null ? Set.of() : banned, ticketCap, extras);
         }

         List<String> display = directs.size() <= 5 ? new ArrayList<>(directs) : new ArrayList<>(directs.subList(0, 5));
         String tune = String.format(
            Locale.ROOT,
            "win=%d eval=%d kind=%s topN=%d posM=%d band=[%d,%d)/%d eh=%d tickets=%d uniq=%.2f cover=%s drought=%s cap=%d bands=%d pm1w=%.1f mode=habit+ham1 ban=%d",
            win.size(),
            10,
            kind,
            topN,
            posM,
            bestLo,
            bestHi,
            bestTake,
            bestEh,
            directs.size(),
            uniq,
            cover.label(),
            drought,
            ticketCap,
            bands.size(),
            pm1.totalWeight(),
            banN
         );
         return new Overfit20PredictUtils.PredictResult(display, directs, tune);
      } else {
         return new Overfit20PredictUtils.PredictResult(List.of(), List.of(), "empty");
      }
   }

   static List<String> buildSdHam1Pool(List<String> window, List<String> strategy, int cap) {
      LinkedHashSet<String> out = new LinkedHashSet<>();

      for (int age : fractionAges(window, 0.03, 0.1, 0.2)) {
         String seed = seedAtAge(window, age);
         if (seed != null) {
            int[] d = new int[]{seed.charAt(0) - '0', seed.charAt(1) - '0', seed.charAt(2) - '0'};

            for (int v = 0; v < 10; v++) {
               if (v != d[0]) {
                  out.add("" + v + d[1] + d[2]);
               }
            }
         }
      }

      for (int agex : fractionAges(window, 0.03, 0.1, 0.17)) {
         String seed = seedAtAge(window, agex);
         if (seed != null) {
            out.addAll(singlePosPlusMinus1(seed));
         }
      }

      appendStratPm1(out, strategy, 3, cap);
      appendGroupPerms(out, window, cap);
      return trimCap(out, cap);
   }

   static List<String> buildPl3Ham1Pool(List<String> window, List<String> strategy, int cap) {
      int[] ages = fractionAges(window, 0.03, 0.1, 0.17, 0.27);
      int[][] freq = new int[3][10];

      for (int j = 0; j < window.size(); j++) {
         String c = pad3(window.get(j));
         int wt = j >= window.size() - 10 ? 3 : 1;

         for (int p = 0; p < 3; p++) {
            freq[p][c.charAt(p) - '0'] += wt;
         }
      }

      List<Entry<String, Double>> ham = new ArrayList<>();

      for (int age : ages) {
         String seed = seedAtAge(window, age);
         if (seed != null) {
            int[] d = new int[]{seed.charAt(0) - '0', seed.charAt(1) - '0', seed.charAt(2) - '0'};

            for (int p = 0; p < 3; p++) {
               for (int v = 0; v < 10; v++) {
                  if (v != d[p]) {
                     int[] n = new int[]{d[0], d[1], d[2]};
                     n[p] = v;
                     int delta = Math.min((v - d[p] + 10) % 10, (d[p] - v + 10) % 10);
                     double sc = freq[p][v] * 10.0 + (delta == 1 ? 8.0 : 0.0);
                     ham.add(Map.entry("" + n[0] + n[1] + n[2], sc));
                  }
               }
            }
         }
      }

      ham.sort((a, b) -> {
         int c = Double.compare(b.getValue(), a.getValue());
         return c != 0 ? c : a.getKey().compareTo(b.getKey());
      });
      LinkedHashSet<String> out = new LinkedHashSet<>();
      int hamBudget = Math.min(40, Math.max(24, cap - 10));

      for (Entry<String, Double> e : ham) {
         if (out.size() >= hamBudget) {
            break;
         }

         out.add(e.getKey());
      }

      for (int agex : ages) {
         String seed = seedAtAge(window, agex);
         if (seed != null) {
            out.addAll(singlePosPlusMinus1(seed));
         }
      }

      appendStratPm1(out, strategy, 3, cap);
      appendGroupPerms(out, window, cap);
      return trimCap(out, cap);
   }

   static LinkedHashSet<String> recentFullHam1(List<String> window, int ageN, int cap) {
      LinkedHashSet<String> out = new LinkedHashSet<>();
      if (window != null && !window.isEmpty() && cap > 0) {
         for (int age = 0; age < ageN && window.size() > age && out.size() < cap; age++) {
            String seed = pad3(window.get(window.size() - 1 - age));
            out.add(seed);
            int[] d = new int[]{seed.charAt(0) - '0', seed.charAt(1) - '0', seed.charAt(2) - '0'};

            for (int p = 0; p < 3 && out.size() < cap; p++) {
               for (int v = 0; v < 10 && out.size() < cap; v++) {
                  if (v != d[p]) {
                     int[] n = new int[]{d[0], d[1], d[2]};
                     n[p] = v;
                     out.add("" + n[0] + n[1] + n[2]);
                  }
               }
            }
         }

         return out;
      } else {
         return out;
      }
   }

   static LinkedHashSet<String> habitSeedPool(List<String> window, int cap) {
      LinkedHashSet<String> out = new LinkedHashSet<>();
      if (window != null && !window.isEmpty() && cap > 0) {
         for (int age = 0; age <= 2 && window.size() > age && out.size() < cap; age++) {
            String seed = pad3(window.get(window.size() - 1 - age));
            out.add(seed);
            out.addAll(singlePosPlusMinus1(seed));

            for (String p : permutationsOf(sortedKey(seed))) {
               if (out.size() < cap) {
                  out.add(p);
                  continue;
               }
            }
         }

         return out;
      } else {
         return out;
      }
   }

   static int[] fractionAges(List<String> window, double... fracs) {
      LinkedHashSet<Integer> set = new LinkedHashSet<>();
      int win = window.size();

      for (double f : fracs) {
         int age = (int)Math.round(f * win);
         set.add(Math.max(1, Math.min(win - 1, age)));
      }

      return set.stream().mapToInt(Integer::intValue).toArray();
   }

   private static String seedAtAge(List<String> window, int age) {
      int idx = window.size() - 1 - age;
      return idx < 0 ? null : pad3(window.get(idx));
   }

   private static void appendStratPm1(LinkedHashSet<String> out, List<String> strategy, int seedLim, int cap) {
      if (strategy != null) {
         int lim = Math.min(seedLim, strategy.size());

         for (int neigh = 0; neigh < 6; neigh++) {
            for (int s = 0; s < lim && out.size() < cap; s++) {
               List<String> ns = singlePosPlusMinus1(strategy.get(s));
               if (neigh < ns.size()) {
                  out.add(ns.get(neigh));
               }
            }
         }
      }
   }

   private static void appendGroupPerms(LinkedHashSet<String> out, List<String> window, int cap) {
      for (int j = window.size() - 1; j >= Math.max(0, window.size() - 10) && out.size() < cap; j--) {
         for (String p : permutationsOf(sortedKey(window.get(j)))) {
            if (out.size() < cap) {
               out.add(p);
               continue;
            }
         }
      }
   }

   private static List<String> trimCap(LinkedHashSet<String> out, int cap) {
      List<String> list = new ArrayList<>(out);
      return (List<String>)(list.size() > cap ? new ArrayList<>(list.subList(0, cap)) : list);
   }

   static List<String> roundRobinMerge(List<String> a, List<String> b, int cap) {
      LinkedHashSet<String> out = new LinkedHashSet<>();
      int i = 0;
      int j = 0;
      int na = a == null ? 0 : a.size();
      int nb = b == null ? 0 : b.size();

      while (out.size() < cap && (i < na || j < nb)) {
         if (i < na) {
            out.add(pad3(a.get(i++)));
            if (out.size() >= cap) {
               break;
            }
         }

         if (j < nb) {
            out.add(pad3(b.get(j++)));
         }
      }

      return new ArrayList<>(out);
   }

   static Overfit20PredictUtils.PlusMinus1Profile learnPlusMinus1Profile(List<String> window) {
      return learnPlusMinus1Profile(window, null);
   }

   static Overfit20PredictUtils.PlusMinus1Profile learnPlusMinus1Profile(List<String> window, List<String> strategyHint) {
      Overfit20PredictUtils.PlusMinus1Profile profile = new Overfit20PredictUtils.PlusMinus1Profile();
      if (window != null && window.size() >= 2) {
         int start = tuneStart(window.size());

         for (int i = Math.max(1, start); i < window.size(); i++) {
            List<String> sub = window.subList(0, i);
            List<String> w = sub.size() > 30 ? sub.subList(sub.size() - 30, sub.size()) : sub;
            String actual = pad3(window.get(i));
            double wt = Math.exp(-0.4 * (window.size() - 1 - i));
            LinkedHashSet<String> seeds = new LinkedHashSet<>();

            for (int k = w.size() - 1; k >= Math.max(0, w.size() - 6); k--) {
               seeds.add(pad3(w.get(k)));
            }

            List<String> pred = strategyHint != null && !strategyHint.isEmpty() ? strategyHint : strategyHeadSeeds(w, 16);
            int lim = 0;

            for (String p : pred) {
               seeds.add(pad3(p));
               if (++lim >= 16) {
                  break;
               }
            }

            for (String seed : seeds) {
               int[] rel = plusMinus1Relation(seed, actual);
               if (rel != null) {
                  double k = isWindowCode(w, seed) ? 1.0 : 1.6;
                  profile.score[rel[0]][rel[1]] = profile.score[rel[0]][rel[1]] + wt * k;
               }
            }
         }

         return profile;
      } else {
         return profile;
      }
   }

   private static boolean isWindowCode(List<String> window, String code) {
      String c = pad3(code);

      for (String w : window) {
         if (c.equals(pad3(w))) {
            return true;
         }
      }

      return false;
   }

   static List<String> strategyHeadSeeds(List<String> window, int n) {
      if (window != null && !window.isEmpty() && n > 0) {
         double uniq = uniqueGroupRatio(window);
         int topN = clamp((int)Math.round(4.0 + 5.0 * uniq), 4, 8);
         int posM = clamp((int)Math.round(4.0 + 3.0 * uniq), 4, 6);
         int center = clamp((int)Math.round(8.0 + 22.0 * uniq), 8, 36);
         int width = clamp((int)Math.round(22.0 + 18.0 * (1.0 - uniq * 0.4)), 20, 40);
         int lo = clamp(center - width / 3, 5, 80);
         int hi = clamp(center + width * 2 / 3, lo + 8, 100);
         int take = clamp(width / 3, 6, 12);
         Overfit20PredictUtils.CoverSpec cover = new Overfit20PredictUtils.CoverSpec(
            Overfit20PredictUtils.CoverKind.MIDLATE_CORE, linspaceSlots(24, 116, 7), null, 0, recentHotGroups(window, 30)
         );
         List<String> pool = buildTicketPool(window, topN, lo, hi, take, posM, cover, Math.max(n, 8));
         return (List<String>)(pool.size() <= n ? pool : new ArrayList<>(pool.subList(0, n)));
      } else {
         return List.of();
      }
   }

   static int[] plusMinus1Relation(String seed, String actual) {
      int[] s = digits(seed);
      int[] a = digits(actual);
      if (s != null && a != null) {
         int diffPos = -1;

         for (int p = 0; p < 3; p++) {
            if (s[p] != a[p]) {
               if (diffPos >= 0) {
                  return null;
               }

               diffPos = p;
            }
         }

         if (diffPos < 0) {
            return null;
         } else {
            int delta = (a[diffPos] - s[diffPos] + 10) % 10;
            if (delta == 1) {
               return new int[]{diffPos, 0};
            } else {
               return delta == 9 ? new int[]{diffPos, 1} : null;
            }
         }
      } else {
         return null;
      }
   }

   static int[] selectSeedQuotas(
      List<String> window, Overfit20PredictUtils.WinStats stats, List<String> strategy, Overfit20PredictUtils.PlusMinus1Profile pm1, int cap
   ) {
      return selectSeedQuotasCausal(window, stats, strategy, pm1, cap);
   }

   static int[] selectSeedQuotasCausal(
      List<String> window, Overfit20PredictUtils.WinStats stats, List<String> strategy, Overfit20PredictUtils.PlusMinus1Profile pm1, int cap
   ) {
      double pm1W = pm1 == null ? 0.0 : pm1.totalWeight();
      double uniq = window != null && !window.isEmpty() ? uniqueGroupRatio(window) : 1.0;
      int pm1Hi = Math.max(14, cap - 4);
      int basePm1 = clamp((int)Math.round(cap * 0.7 + Math.min(2.0, pm1W)), 12, pm1Hi);
      int baseGp = clamp((int)Math.round(3.0 + 2.0 * uniq + (cap > 20 ? 1 : 0)), 3, Math.min(6, cap / 5));
      LinkedHashSet<String> dedup = new LinkedHashSet<>();
      List<int[]> cands = new ArrayList<>();
      int p15 = Math.min(15, pm1Hi);
      int p14 = Math.min(14, pm1Hi);
      int p22 = Math.min(22, pm1Hi);
      int p24 = Math.min(24, pm1Hi);

      for (int[] q : new int[][]{
         {baseGp, basePm1, 0, Math.max(1, cap - baseGp - basePm1)},
         {3, p15, 0, Math.max(1, cap - 3 - p15)},
         {4, p14, 0, Math.max(1, cap - 4 - p14)},
         {4, p22, 0, Math.max(1, cap - 4 - p22)},
         {5, p24, 0, Math.max(1, cap - 5 - p24)},
         {3, p14, 0, Math.max(1, cap - 3 - p14)},
         {5, Math.min(13, pm1Hi), 0, Math.max(1, cap - 5 - Math.min(13, pm1Hi))},
         {3, Math.min(12, pm1Hi), 0, Math.max(1, cap - 3 - Math.min(12, pm1Hi))}
      }) {
         int g = clamp(q[0], 3, Math.min(8, cap / 3));
         int p = clamp(q[1], 10, pm1Hi);
         if (g + p >= cap) {
            p = Math.max(10, cap - g - 1);
         }

         int s = Math.max(1, cap - g - p);
         String key = g + ":" + p + ":" + s;
         if (dedup.add(key)) {
            cands.add(new int[]{g, p, 0, s});
         }
      }

      int[] best = cands.get(0);
      double bestSc = Double.NEGATIVE_INFINITY;
      List<String> tuneSlice = window.size() > 10 ? window.subList(window.size() - 10, window.size()) : window;

      for (int[] q : cands) {
         List<String> tickets = mergeOverfit20(window, stats, strategy, pm1, q, cap);
         double sc = 0.0;
         int zx = 0;
         int gp = 0;
         int near = 0;

         for (int i = 0; i < tuneSlice.size(); i++) {
            String actual = tuneSlice.get(i);
            double wt = Math.exp(-0.35 * (tuneSlice.size() - 1 - i));
            if (isZxHit(tickets, actual)) {
               zx++;
               sc += 6.0 * wt;
            } else if (isGroupHit(tickets, actual)) {
               gp++;
               sc += 3.5 * wt;
            } else if (isPlusMinus1NearMiss(tickets, actual)) {
               near++;
               sc += 0.8 * wt;
            }
         }

         sc += 0.02 * Math.min(q[1], countPm1Tickets(tickets, window, strategy));
         double score = sc * 10.0 + zx * 20 + gp * 10 + near * 2;
         if (score > bestSc) {
            bestSc = score;
            best = q;
         }
      }

      return best;
   }

   private static int countPm1Tickets(List<String> tickets, List<String> window, List<String> strategy) {
      Set<String> sources = new HashSet<>();
      if (window != null) {
         for (String w : window) {
            sources.add(pad3(w));
         }
      }

      if (strategy != null) {
         for (String s : strategy) {
            sources.add(pad3(s));
         }
      }

      int n = 0;
      if (tickets == null) {
         return 0;
      } else {
         for (String t : tickets) {
            if (isPm1OfAny(pad3(t), sources)) {
               n++;
            }
         }

         return n;
      }
   }

   static boolean isPlusMinus1NearMiss(List<String> pool, String actual) {
      if (pool != null && actual != null) {
         String a = pad3(actual);

         for (String p : pool) {
            if (plusMinus1Relation(p, a) != null) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   static List<String> mergeWindowSeed(List<String> window, Overfit20PredictUtils.WinStats stats, List<String> strategy, int cap) {
      Overfit20PredictUtils.PlusMinus1Profile pm1 = learnPlusMinus1Profile(window);
      int[] quotas = selectSeedQuotas(window, stats, strategy, pm1, cap);
      return mergeOverfit20(window, stats, strategy, pm1, quotas, cap);
   }

   static List<String> mergeWindowSeedWithDepth(List<String> window, Overfit20PredictUtils.WinStats stats, List<String> strategy, int cap, int fullDepth) {
      Overfit20PredictUtils.PlusMinus1Profile pm1 = learnPlusMinus1Profile(window);
      int gp = Math.max(0, Math.min(fullDepth * 2, 4));
      int exact = Math.min(4, cap / 4);
      int pm1N = Math.min(10, cap - exact - gp);
      int strat = Math.max(0, cap - exact - pm1N - gp);
      return mergeOverfit20(window, stats, strategy, pm1, new int[]{exact, pm1N, gp, strat}, cap);
   }

   static List<String> mergeOverfit20(
      List<String> window, Overfit20PredictUtils.WinStats stats, List<String> strategy, Overfit20PredictUtils.PlusMinus1Profile pm1, int[] quotas, int cap
   ) {
      if (window != null && !window.isEmpty() && cap > 0) {
         List<ToDoubleFunction<int[]>> fns = stratFns(stats);
         Overfit20PredictUtils.PlusMinus1Profile profile = pm1 == null ? new Overfit20PredictUtils.PlusMinus1Profile() : pm1;
         List<String> strat = strategy != null && !strategy.isEmpty() ? strategy : strategyHeadSeeds(window, Math.max(cap, 28));
         List<String> winSeeds = new ArrayList<>();
         Set<String> seen = new HashSet<>();

         for (int i = window.size() - 1; i >= 0; i--) {
            String code = pad3(window.get(i));
            if (seen.add(code)) {
               winSeeds.add(code);
            }
         }

         int step = Math.max(1, (winSeeds.size() + 10 - 1) / 10);
         List<Integer> stepIdx = new ArrayList<>();

         for (int idx = 0; idx < winSeeds.size(); idx += step) {
            stepIdx.add(idx);
         }

         LinkedHashSet<Integer> anchors = new LinkedHashSet<>();
         if (!stepIdx.isEmpty()) {
            anchors.add(stepIdx.get(0));
            if (stepIdx.size() > 1) {
               anchors.add(stepIdx.get(1));
            }

            anchors.add(stepIdx.get(Math.min(4, stepIdx.size() - 1)));
            if (winSeeds.size() > step + 1) {
               anchors.add(winSeeds.size() - step - 1);
            }

            anchors.add(stepIdx.get(stepIdx.size() - 1));
         }

         int groupQ = quotas != null && quotas.length > 0 ? clamp(quotas[0], 3, Math.min(6, cap / 5)) : clamp(cap / 7, 3, 5);
         int pm1Slots = Math.min(cap - groupQ - 1, Math.max(16, (int)Math.round(cap * 0.8)));
         int headKeep = Math.max(8, (int)Math.round(pm1Slots * 0.75));
         int protectFloor = Math.max(6, pm1Slots / 2);
         int winA = Math.max(8, (int)Math.round(pm1Slots * 0.75));
         int stratA = Math.max(2, pm1Slots - winA);
         int winB = Math.max(6, pm1Slots / 2);
         int stratB = Math.max(4, pm1Slots - winB);
         List<String> tuneSlice = window.size() > 10 ? window.subList(window.size() - 10, window.size()) : window;
         LinkedHashSet<String> outA = buildPm1Pool(winSeeds, anchors, strat, profile, fns, winA, stratA, cap);
         LinkedHashSet<String> outB = buildPm1Pool(winSeeds, anchors, strat, profile, fns, winB, stratB, cap);
         List<String> merged = new ArrayList<>(outA);

         while (merged.size() < pm1Slots) {
            merged.add("000");
         }

         if (merged.size() > pm1Slots) {
            merged = new ArrayList<>(merged.subList(0, pm1Slots));
         }

         Set<String> have = new HashSet<>(merged);
         List<String> bOnly = new ArrayList<>();
         Set<String> headA = new HashSet<>(merged.subList(0, Math.min(headKeep, merged.size())));

         for (String x : outB) {
            if (!headA.contains(x) && !bOnly.contains(x)) {
               bOnly.add(x);
            }
         }

         int victim = merged.size() - 1;

         for (String xx : bOnly) {
            if (victim < protectFloor) {
               break;
            }

            if (!have.contains(xx)) {
               have.remove(merged.get(victim));
               merged.set(victim--, xx);
               have.add(xx);
            }
         }

         merged.removeIf(s -> "000".equals(s));
         Set<String> haveM = new HashSet<>(merged);
         int injectAt = Math.min(merged.size(), pm1Slots) - 1;
         int seedForce = Math.min(cap >= 28 ? 5 : 3, strat.size());
         int injectFloor = Math.max(protectFloor / 2, protectFloor - (cap >= 28 ? 4 : 0));

         for (int neighIdx = 0; neighIdx < 6 && injectAt >= injectFloor; neighIdx++) {
            for (int s = 0; s < seedForce && injectAt >= injectFloor; s++) {
               List<String> ns = singlePosPlusMinus1(pad3(strat.get(s)));
               if (neighIdx < ns.size()) {
                  String n = ns.get(neighIdx);
                  if (!haveM.contains(n)) {
                     haveM.remove(merged.get(injectAt));
                     merged.set(injectAt--, n);
                     haveM.add(n);
                  }
               }
            }
         }

         LinkedHashSet<String> out = new LinkedHashSet<>(merged);
         Set<String> gpSeen = new HashSet<>();
         List<String> gSrc = new ArrayList<>();

         for (int idx : anchors) {
            gSrc.add(winSeeds.get(idx));
         }

         for (int ix = tuneSlice.size() - 1; ix >= 0; ix--) {
            gSrc.add(tuneSlice.get(ix));
         }

         int pm1Size = out.size();

         for (String src : gSrc) {
            if (out.size() >= pm1Size + groupQ) {
               break;
            }

            String g = sortedKey(src);
            if (gpSeen.add(g)) {
               List<String> ps = new ArrayList<>(permutationsOf(g));
               ps.sort((a, b) -> Double.compare(directScore(b, fns), directScore(a, fns)));
               if (!ps.isEmpty()) {
                  out.add(ps.get(0));
               }
            }
         }

         for (String t : strat) {
            if (out.size() >= cap) {
               break;
            }

            out.add(pad3(t));
         }

         if (out.size() < cap) {
            for (int sx = 0; sx < Math.min(12, strat.size()) && out.size() < cap; sx++) {
               for (String n : singlePosPlusMinus1(pad3(strat.get(sx)))) {
                  if (out.size() >= cap) {
                     break;
                  }

                  out.add(n);
               }
            }
         }

         if (out.size() < cap) {
            for (String src : winSeeds) {
               if (out.size() >= cap) {
                  break;
               }

               for (String n : singlePosPlusMinus1(src)) {
                  if (out.size() >= cap) {
                     break;
                  }

                  out.add(n);
               }
            }
         }

         if (out.size() < cap) {
            for (String src : gSrc) {
               if (out.size() >= cap) {
                  break;
               }

               for (String p : permutationsOf(sortedKey(src))) {
                  if (out.size() >= cap) {
                     break;
                  }

                  out.add(p);
               }
            }
         }

         List<String> list = new ArrayList<>(out);
         if (winSeeds.size() > step + 1 && !list.isEmpty()) {
            String oldSeed = winSeeds.get(winSeeds.size() - step - 1);
            String n = applyPlusMinus1(oldSeed, 2, 0);
            if (n != null && !list.contains(n)) {
               list.set(list.size() - 1, n);
            }
         }

         if (list.size() > cap) {
            list = new ArrayList<>(list.subList(0, cap));
         }

         return list;
      } else {
         return List.of();
      }
   }

   static List<String> buildOverfitCandidates(
      List<String> window, List<String> strategy, Overfit20PredictUtils.PlusMinus1Profile profile, List<ToDoubleFunction<int[]>> fns
   ) {
      Map<String, Double> score = new HashMap<>();

      for (int i = window.size() - 1; i >= 0; i--) {
         String code = pad3(window.get(i));
         double ageWt = Math.exp(-0.2 * (window.size() - 1 - i));
         score.merge(code, 3.0 * ageWt, Double::sum);

         for (String p : permutationsOf(sortedKey(code))) {
            score.merge(p, 1.5 * ageWt + 0.02 * directScore(p, fns), Double::sum);
         }
      }

      accumulatePm1Scores(score, window, profile, fns, 2.0, true);
      if (strategy != null) {
         int idx = 0;

         for (String t : strategy) {
            String code = pad3(t);
            double sw = Math.exp(-0.08 * idx);
            score.merge(code, 1.2 * sw, Double::sum);
            accumulatePm1Scores(score, List.of(code), profile, fns, 2.8 * sw, false);
            if (++idx >= 16) {
               break;
            }
         }
      }

      List<Entry<String, Double>> list = new ArrayList<>(score.entrySet());
      list.sort((a, b) -> {
         int c = Double.compare(b.getValue(), a.getValue());
         return c != 0 ? c : a.getKey().compareTo(b.getKey());
      });
      List<String> out = new ArrayList<>(list.size());

      for (Entry<String, Double> e : list) {
         out.add(e.getKey());
      }

      return out;
   }

   static LinkedHashSet<String> buildPm1Pool(
      List<String> winSeeds,
      LinkedHashSet<Integer> anchors,
      List<String> strat,
      Overfit20PredictUtils.PlusMinus1Profile profile,
      List<ToDoubleFunction<int[]>> fns,
      int winBudget,
      int stratBudget,
      int cap
   ) {
      LinkedHashSet<String> out = new LinkedHashSet<>();
      int[] winTakes = winBudget >= 18 ? new int[]{3, 2, 5, 6, 4} : (winBudget >= 12 ? new int[]{2, 1, 4, 5} : new int[]{2, 2, 4, 2});
      int ai = 0;
      int winAdded = 0;

      for (int idx : anchors) {
         if (winAdded >= winBudget || ai >= winTakes.length) {
            break;
         }

         winAdded += addPm1FromSeed(out, winSeeds.get(idx), profile, fns, winTakes[ai++], winBudget, cap, winAdded);
      }

      int pm1Q = Math.min(cap, winBudget + stratBudget);
      int seedLim = Math.min(stratBudget >= 12 ? 5 : (stratBudget >= 8 ? 3 : 4), strat == null ? 0 : strat.size());

      for (int neighIdx = 0; neighIdx < 6 && out.size() < pm1Q; neighIdx++) {
         for (int s = 0; s < seedLim && out.size() < pm1Q; s++) {
            List<String> ns = singlePosPlusMinus1(pad3(strat.get(s)));
            if (neighIdx < ns.size()) {
               out.add(ns.get(neighIdx));
            }
         }
      }

      Map<String, Double> stratPm1 = new HashMap<>();
      List<int[]> dirs = topPlusMinus1Dirs(profile, 6);
      Set<String> hotDir = new HashSet<>();

      for (int i = 0; i < Math.min(2, dirs.size()); i++) {
         hotDir.add(dirs.get(i)[0] + ":" + dirs.get(i)[1]);
      }

      int sIdx = 0;
      if (strat != null) {
         for (String t : strat) {
            String seed = pad3(t);

            for (String n : singlePosPlusMinus1(seed)) {
               if (!out.contains(n)) {
                  int[] rel = plusMinus1Relation(seed, n);
                  double sc = (1.0 + 0.03 * sIdx) * pm1NeighborScore(seed, n, profile, fns);
                  if (rel != null && hotDir.contains(rel[0] + ":" + rel[1])) {
                     sc *= 6.0;
                  }

                  stratPm1.merge(n, sc, Double::sum);
               }
            }

            if (++sIdx >= 28) {
               break;
            }
         }
      }

      List<Entry<String, Double>> stratRanked = new ArrayList<>(stratPm1.entrySet());
      stratRanked.sort((a, b) -> {
         int c = Double.compare(b.getValue(), a.getValue());
         return c != 0 ? c : a.getKey().compareTo(b.getKey());
      });

      for (Entry<String, Double> e : stratRanked) {
         if (out.size() >= pm1Q) {
            break;
         }

         out.add(e.getKey());
      }

      return out;
   }

   static LinkedHashSet<String> betterPm1Pool(LinkedHashSet<String> a, LinkedHashSet<String> b, List<String> tuneSlice) {
      double sa = scorePoolOnTune(a, tuneSlice);
      double sb = scorePoolOnTune(b, tuneSlice);
      return sb > sa ? b : a;
   }

   static double scorePoolOnTune(Set<String> pool, List<String> tuneSlice) {
      if (pool != null && !pool.isEmpty() && tuneSlice != null) {
         List<String> list = new ArrayList<>(pool);
         double sc = 0.0;

         for (int i = 0; i < tuneSlice.size(); i++) {
            String act = tuneSlice.get(i);
            double wt = Math.exp(-0.35 * (tuneSlice.size() - 1 - i));
            if (isZxHit(list, act)) {
               sc += 6.0 * wt;
            } else if (isGroupHit(list, act)) {
               sc += 3.5 * wt;
            } else if (isPlusMinus1NearMiss(list, act)) {
               sc += 1.2 * wt;
            }
         }

         return sc;
      } else {
         return Double.NEGATIVE_INFINITY;
      }
   }

   static List<int[]> topPlusMinus1Dirs(Overfit20PredictUtils.PlusMinus1Profile profile, int k) {
      List<int[]> all = new ArrayList<>();

      for (int p = 0; p < 3; p++) {
         for (int s = 0; s < 2; s++) {
            all.add(new int[]{p, s});
         }
      }

      all.sort((a, b) -> {
         double sa = profile == null ? 0.0 : profile.boost(a[0], a[1]);
         double sb = profile == null ? 0.0 : profile.boost(b[0], b[1]);
         int c = Double.compare(sb, sa);
         return c != 0 ? c : Integer.compare(a[0] * 2 + a[1], b[0] * 2 + b[1]);
      });
      return profile != null && !(profile.totalWeight() < 1.0E-9)
         ? all.subList(0, Math.min(k, all.size()))
         : List.of(new int[]{0, 0}, new int[]{0, 1}, new int[]{1, 0}, new int[]{1, 1});
   }

   static String applyPlusMinus1(String seed, int pos, int signIdx) {
      int[] d = digits(seed);
      if (d != null && pos >= 0 && pos <= 2) {
         int[] n = new int[]{d[0], d[1], d[2]};
         n[pos] = (n[pos] + (signIdx == 0 ? 1 : 9)) % 10;
         return n[0] == n[1] && n[1] == n[2] ? null : "" + n[0] + n[1] + n[2];
      } else {
         return null;
      }
   }

   private static int addPm1FromSeed(
      LinkedHashSet<String> out,
      String seed,
      Overfit20PredictUtils.PlusMinus1Profile profile,
      List<ToDoubleFunction<int[]>> fns,
      int take,
      int pm1Q,
      int cap,
      int pm1Added
   ) {
      if (seed != null && take > 0) {
         LinkedHashSet<String> ordered = new LinkedHashSet<>();

         for (String n : singlePosPlusMinus1(seed)) {
            ordered.add(n);
         }

         List<String> byScore = new ArrayList<>(singlePosPlusMinus1(seed));
         byScore.sort((a, b) -> {
            int c = Double.compare(pm1NeighborScore(seed, b, profile, fns), pm1NeighborScore(seed, a, profile, fns));
            return c != 0 ? c : a.compareTo(b);
         });

         for (String n : byScore) {
            ordered.add(n);
         }

         int added = 0;
         int got = 0;

         for (String n : ordered) {
            if (got >= take || pm1Added + added >= pm1Q || out.size() >= cap) {
               break;
            }

            if (out.add(n)) {
               added++;
               got++;
            }
         }

         return added;
      } else {
         return 0;
      }
   }

   private static double pm1NeighborScore(String seed, String neighbor, Overfit20PredictUtils.PlusMinus1Profile profile, List<ToDoubleFunction<int[]>> fns) {
      int[] rel = plusMinus1Relation(seed, neighbor);
      double boost = 1.0;
      if (rel != null) {
         boost += 3.0 * profile.boost(rel[0], rel[1]);
      }

      return boost + 0.05 * directScore(neighbor, fns);
   }

   private static void accumulatePm1Scores(
      Map<String, Double> score,
      List<String> sources,
      Overfit20PredictUtils.PlusMinus1Profile profile,
      List<ToDoubleFunction<int[]>> fns,
      double baseWt,
      boolean ageByIndex
   ) {
      for (int i = 0; i < sources.size(); i++) {
         int[] d = digits(sources.get(i));
         if (d != null) {
            double ageWt = ageByIndex ? Math.exp(-0.25 * i) : 1.0;

            for (int pos = 0; pos < 3; pos++) {
               for (int signIdx = 0; signIdx < 2; signIdx++) {
                  int delta = signIdx == 0 ? 1 : 9;
                  int[] n = new int[]{d[0], d[1], d[2]};
                  n[pos] = (n[pos] + delta) % 10;
                  if (n[0] != n[1] || n[1] != n[2]) {
                     String nk = "" + n[0] + n[1] + n[2];
                     double boost = 1.0 + 3.0 * profile.boost(pos, signIdx);
                     score.merge(nk, baseWt * ageWt * boost + 0.03 * directScore(nk, fns), Double::sum);
                  }
               }
            }
         }
      }
   }

   static List<String> greedyCoverWindow(List<String> candidates, List<String> window, int cap, int groupQ, int pm1Q, int stratQ, List<String> strategy) {
      LinkedHashSet<String> out = new LinkedHashSet<>();
      if (candidates != null && !candidates.isEmpty()) {
         int n = window.size();
         boolean[] zx = new boolean[n];
         boolean[] gp = new boolean[n];
         Set<String> stratSet = new HashSet<>();
         if (strategy != null) {
            for (String s : strategy) {
               stratSet.add(pad3(s));
            }
         }

         Set<String> windowSet = new HashSet<>();
         Set<String> windowGroups = new HashSet<>();

         for (String w : window) {
            windowSet.add(pad3(w));
            windowGroups.add(sortedKey(w));
         }

         while (out.size() < cap) {
            String best = null;
            double bestGain = Double.NEGATIVE_INFINITY;

            for (String c0 : candidates) {
               String c = pad3(c0);
               if (!out.contains(c)) {
                  double gain = 0.0;

                  for (int i = 0; i < n; i++) {
                     double wt = Math.exp(-0.4 * (n - 1 - i));
                     String act = pad3(window.get(i));
                     if (!zx[i] && c.equals(act)) {
                        gain += 6.0 * wt;
                     } else if (!gp[i] && sortedKey(c).equals(sortedKey(act))) {
                        gain += 3.5 * wt;
                     } else if (plusMinus1Relation(c, act) != null) {
                        gain += 0.4 * wt;
                     }
                  }

                  if (windowGroups.contains(sortedKey(c))) {
                     gain += 0.15;
                  }

                  if (stratSet.contains(c) || isPm1OfAny(c, stratSet) || isPm1OfAny(c, windowSet)) {
                     gain += 0.1;
                  }

                  if (gain > bestGain) {
                     bestGain = gain;
                     best = c;
                  }
               }
            }

            if (best == null || bestGain <= 0.0 && !out.isEmpty()) {
               break;
            }

            out.add(best);

            for (int ix = 0; ix < n; ix++) {
               String act = pad3(window.get(ix));
               if (best.equals(act)) {
                  zx[ix] = true;
                  gp[ix] = true;
               } else if (sortedKey(best).equals(sortedKey(act))) {
                  gp[ix] = true;
               }
            }
         }

         for (String c : candidates) {
            if (out.size() >= cap) {
               break;
            }

            out.add(pad3(c));
         }

         List<String> list = new ArrayList<>(out);
         return (List<String>)(list.size() > cap ? new ArrayList<>(list.subList(0, cap)) : list);
      } else {
         return List.of();
      }
   }

   private static boolean isPm1OfAny(String code, Set<String> sources) {
      if (sources != null && !sources.isEmpty()) {
         for (String s : sources) {
            if (plusMinus1Relation(s, code) != null) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   static List<int[]> deriveBandCandidates(List<String> window, int topN, int posM) {
      List<Integer> hitRanks = new ArrayList<>();
      int deriveEnd = window.size();
      int deriveStart = tuneStart(window.size());

      for (int i = deriveStart; i < deriveEnd; i++) {
         int rank = estimateBandRank(window.subList(0, i), window.get(i));
         if (rank >= 4 && rank <= 100) {
            hitRanks.add(rank);
         }
      }

      List<int[]> bands = new ArrayList<>();
      Set<String> dedup = new HashSet<>();
      if (!hitRanks.isEmpty()) {
         hitRanks.sort(Integer::compareTo);
         int p25 = percentile(hitRanks, 0.25);
         int p50 = percentile(hitRanks, 0.5);
         int p75 = percentile(hitRanks, 0.75);
         int spread = Math.max(12, Math.min(40, p75 - p25 + 8));

         for (int w : new int[]{spread, spread + 8, Math.max(14, spread - 6), spread + 16}) {
            addBand(bands, dedup, p50 - w / 2, p50 + w / 2 + 1, clamp(w / 3, 6, 14));
         }

         addBand(bands, dedup, Math.max(5, p25 - 4), p75 + 6, clamp(spread / 3, 6, 12));
         addBand(bands, dedup, Math.max(5, p25 - spread / 4), p50 + 4, clamp(spread / 4, 6, 12));
         addBand(bands, dedup, p50 - 2, Math.min(90, p75 + spread / 3), clamp(spread / 4, 6, 12));
      }

      double uniq = uniqueGroupRatio(window);
      int center = clamp((int)Math.round(8.0 + 22.0 * uniq), 8, 36);
      int width = clamp((int)Math.round(22.0 + 18.0 * (1.0 - uniq * 0.4)), 20, 40);
      addBand(bands, dedup, center - width / 3, center + width * 2 / 3, clamp(width / 3, 6, 12));
      addBand(bands, dedup, Math.max(5, center - width / 2), center + width / 4, clamp(width / 4, 6, 10));
      addBand(bands, dedup, center, center + width, clamp(width / 3, 8, 12));
      addBand(bands, dedup, Math.max(6, center / 2), center + width / 2, 8);
      addBand(bands, dedup, center + 4, Math.min(70, center + width + 8), 10);
      if (bands.isEmpty()) {
         addBand(bands, dedup, Math.max(5, center - width / 3), center + width * 2 / 3, 8);
      }

      return bands;
   }

   private static void addBand(List<int[]> bands, Set<String> dedup, int lo, int hi, int take) {
      int l = clamp(lo, 5, 80);
      int h = clamp(hi, l + 8, 100);
      int t = clamp(take, 6, 14);
      String key = l + ":" + h + ":" + t;
      if (dedup.add(key)) {
         bands.add(new int[]{l, h, t});
      }
   }

   static int estimateBandRank(List<String> histBefore, String actual) {
      if (histBefore != null && !histBefore.isEmpty() && actual != null) {
         List<String> win = histBefore.size() > 30 ? histBefore.subList(histBefore.size() - 30, histBefore.size()) : histBefore;
         Overfit20PredictUtils.WinStats stats = Overfit20PredictUtils.WinStats.of(win);
         String a = pad3(actual);
         int best = Integer.MAX_VALUE;

         for (ToDoubleFunction<int[]> fn : stratFns(stats)) {
            List<Overfit20PredictUtils.Scored> ranked = fullRank(fn);

            for (int i = 0; i < ranked.size(); i++) {
               if (ranked.get(i).code.equals(a)) {
                  best = Math.min(best, i);
                  break;
               }
            }
         }

         return best == Integer.MAX_VALUE ? -1 : best;
      } else {
         return -1;
      }
   }

   private static int percentile(List<Integer> sortedAsc, double p) {
      if (sortedAsc != null && !sortedAsc.isEmpty()) {
         int i = (int)Math.floor(p * (sortedAsc.size() - 1));
         return sortedAsc.get(Math.max(0, Math.min(sortedAsc.size() - 1, i)));
      } else {
         return 0;
      }
   }

   static List<String> expandSinglePosNeighbors(List<String> pool, int cap) {
      if (pool != null && !pool.isEmpty() && cap > 0) {
         LinkedHashSet<String> have = new LinkedHashSet<>(pool);
         Map<String, Integer> score = new HashMap<>();
         int idx = 0;

         for (String code : pool) {
            int[] t = digits(code);
            if (t == null) {
               idx++;
            } else {
               int seedW = Math.max(1, pool.size() - idx);

               for (int pos = 0; pos < 3; pos++) {
                  for (int delta : new int[]{1, 9}) {
                     int[] n = new int[]{t[0], t[1], t[2]};
                     n[pos] = (n[pos] + delta) % 10;
                     String nk = "" + n[0] + n[1] + n[2];
                     if (!have.contains(nk)) {
                        score.merge(nk, seedW, Integer::sum);
                     }
                  }
               }

               idx++;
            }
         }

         if (score.isEmpty()) {
            return new ArrayList<>(pool);
         } else {
            List<Entry<String, Integer>> ranked = new ArrayList<>(score.entrySet());
            ranked.sort((a, b) -> {
               int c = Integer.compare(b.getValue(), a.getValue());
               return c != 0 ? c : a.getKey().compareTo(b.getKey());
            });
            List<String> out = new ArrayList<>(pool);

            for (Entry<String, Integer> e : ranked) {
               if (out.size() >= cap) {
                  break;
               }

               if (have.add(e.getKey())) {
                  out.add(e.getKey());
               }
            }

            if (out.size() < cap) {
               return out;
            } else {
               int protect = Math.min(out.size(), Math.max(5, (pool.size() + 1) / 2));

               for (Entry<String, Integer> e : ranked) {
                  if (!have.contains(e.getKey())) {
                     int victim = -1;
                     int j = out.size() - 1;
                     if (j >= protect) {
                        victim = j;
                     }

                     if (victim < 0) {
                        break;
                     }

                     have.remove(out.get(victim));
                     out.set(victim, e.getKey());
                     have.add(e.getKey());
                  }
               }

               return out;
            }
         }
      } else {
         return pool;
      }
   }

   static int[] deriveCoreSlots(List<Integer> hitNorms) {
      List<Integer> valid = new ArrayList<>();
      if (hitNorms != null) {
         for (Integer n : hitNorms) {
            if (n != null && n >= 0) {
               valid.add(clamp(n, 0, 119));
            }
         }
      }

      if (valid.isEmpty()) {
         return linspaceSlots(24, 116, 7);
      } else {
         valid.sort(Integer::compareTo);
         int p20 = percentile(valid, 0.2);
         int p80 = percentile(valid, 0.8);
         int lo = clamp(Math.min(p20, 24), 0, 112);
         int hi = clamp(Math.max(p80, 90), lo + 6, 119);
         return linspaceSlots(lo, hi, 7);
      }
   }

   static int[] linspaceSlots(int lo, int hi, int n) {
      int[] s = new int[n];
      if (n <= 1) {
         s[0] = clamp(lo, 0, 119);
         return s;
      } else {
         for (int i = 0; i < n; i++) {
            s[i] = lo + (hi - lo) * i / (n - 1);
            s[i] = clamp(s[i], 0, 119);
         }

         return s;
      }
   }

   static List<int[]> deriveSlotCandidates(List<Integer> hitNorms, double uniq) {
      List<int[]> out = new ArrayList<>();
      Set<String> dedup = new HashSet<>();
      int shift = (int)Math.round((uniq - 0.9) * 14.0);
      int[][] pctRanges = new int[][]{{20, 93}, {15, 98}, {25, 95}, {12, 94}, {22, 98}, {17, 95}, {18, 90}, {10, 88}, {28, 96}, {8, 85}, {30, 99}};

      for (int[] p : pctRanges) {
         int lo = clamp(p[0] * 120 / 100 + shift, 0, 110);
         int hi = clamp(p[1] * 120 / 100 - shift / 2, lo + 8, 119);
         addSlotCand(out, dedup, linspaceSlots(lo, hi, 7));
         addSlotCand(out, dedup, linspaceSlots(clamp(lo + 3, 0, hi - 6), clamp(hi - 3, lo + 8, 119), 7));
      }

      addSlotCand(out, dedup, deriveCoreSlots(hitNorms));
      addSlotCand(out, dedup, slotsFromNorms(hitNorms));
      List<Integer> valid = new ArrayList<>();
      if (hitNorms != null) {
         for (Integer n : hitNorms) {
            if (n != null && n >= 0) {
               valid.add(clamp(n, 0, 119));
            }
         }
      }

      if (!valid.isEmpty()) {
         valid.sort(Integer::compareTo);
         int p30 = percentile(valid, 0.3);
         int p70 = percentile(valid, 0.7);
         addSlotCand(out, dedup, linspaceSlots(clamp(p30 - 12, 0, 110), clamp(p70 + 12, 10, 119), 7));
         addSlotCand(out, dedup, linspaceSlots(clamp(p30 - 4, 0, 110), clamp(p70 + 4, 10, 119), 9));
      }

      return out;
   }

   private static void addSlotCand(List<int[]> out, Set<String> dedup, int[] slots) {
      if (slots != null && slots.length != 0) {
         StringBuilder sb = new StringBuilder();

         for (int s : slots) {
            sb.append(s).append(',');
         }

         if (dedup.add(sb.toString())) {
            out.add(slots);
         }
      }
   }

   static Overfit20PredictUtils.CoverSpec selectCover(List<String> window, int topN, int bandLo, int bandHi, int bandTake, int posM) {
      return selectCover(window, topN, bandLo, bandHi, bandTake, posM, 3);
   }

   static Overfit20PredictUtils.CoverSpec selectCover(List<String> window, int topN, int bandLo, int bandHi, int bandTake, int posM, int maxExtra) {
      int start = tuneStart(window.size());
      List<List<String>> groupCache = new ArrayList<>();
      List<Overfit20PredictUtils.WinStats> statsCache = new ArrayList<>();
      List<Integer> hitNorms = new ArrayList<>();

      for (int i = start; i < window.size(); i++) {
         List<String> sub = window.subList(0, i);
         List<String> win = sub.size() > 30 ? sub.subList(sub.size() - 30, sub.size()) : sub;
         List<String> groups = buildGroupPool(win, topN, bandLo, bandHi, bandTake, posM, 120);
         groupCache.add(groups);
         statsCache.add(Overfit20PredictUtils.WinStats.of(win));
         int gi = groups.indexOf(sortedKey(window.get(i)));
         if (gi >= 0 && !groups.isEmpty()) {
            hitNorms.add((int)Math.round((double)(gi * 119) / Math.max(1, groups.size() - 1)));
         } else {
            hitNorms.add(-1);
         }
      }

      double uniq = uniqueGroupRatio(window);
      List<int[]> structural = deriveSlotCandidates(List.of(), uniq);
      int bestExtra = 0;
      int bestTi = 0;
      boolean bestFittedAsCore = false;
      double bestScore = Double.NEGATIVE_INFINITY;
      int extraHi = Math.max(0, Math.min(5, maxExtra));

      for (int ti = 0; ti < structural.size(); ti++) {
         int[] core = structural.get(ti);

         for (int extra = 0; extra <= extraHi; extra++) {
            double score = scoreCoverOnCache(groupCache, statsCache, hitNorms, window, start, core, extra, false);
            boolean preferLess = extra < bestExtra || extra == bestExtra && ti < bestTi;
            if (score > bestScore + 1.0E-9 || Math.abs(score - bestScore) <= 1.0E-9 && preferLess) {
               bestScore = score;
               bestExtra = extra;
               bestTi = ti;
               bestFittedAsCore = false;
            }
         }
      }

      for (int extrax = 0; extrax <= extraHi; extrax++) {
         double score = scoreCoverOnCache(groupCache, statsCache, hitNorms, window, start, null, extrax, true);
         boolean preferLess = extrax < bestExtra;
         if (score > bestScore + 1.0E-9 || Math.abs(score - bestScore) <= 1.0E-9 && preferLess && bestFittedAsCore) {
            bestScore = score;
            bestExtra = extrax;
            bestFittedAsCore = true;
         }
      }

      int[] fittedNow = slotsFromNorms(hitNorms);
      int[] coreNow = bestFittedAsCore ? deriveCoreSlots(hitNorms) : structural.get(Math.min(bestTi, structural.size() - 1));
      List<String> hot = recentHotGroups(window, 30);
      int[] dense = linspaceSlots(0, 119, 12);
      int[] mergedCore = mergeSlots(coreNow, dense);
      return bestExtra == 0
         ? new Overfit20PredictUtils.CoverSpec(Overfit20PredictUtils.CoverKind.MIDLATE_CORE, mergedCore, null, 0, hot)
         : new Overfit20PredictUtils.CoverSpec(Overfit20PredictUtils.CoverKind.CORE_PLUS_FITTED, mergedCore, fittedNow, bestExtra, hot);
   }

   static List<String> recentHotGroups(List<String> window, int look) {
      LinkedHashSet<String> hot = new LinkedHashSet<>();
      if (window != null && !window.isEmpty()) {
         int from = Math.max(0, window.size() - Math.max(1, look));

         for (int i = window.size() - 1; i >= from; i--) {
            hot.add(sortedKey(window.get(i)));
         }

         return new ArrayList<>(hot);
      } else {
         return List.of();
      }
   }

   static int[] mergeSlots(int[] a, int[] b) {
      TreeSet<Integer> set = new TreeSet<>();
      if (a != null) {
         for (int v : a) {
            set.add(clamp(v, 0, 119));
         }
      }

      if (b != null) {
         for (int v : b) {
            set.add(clamp(v, 0, 119));
         }
      }

      if (set.isEmpty()) {
         return linspaceSlots(24, 116, 7);
      } else {
         int[] out = new int[set.size()];
         int i = 0;

         for (int v : set) {
            out[i++] = v;
         }

         return out;
      }
   }

   private static double scoreCoverOnCache(
      List<List<String>> groupCache,
      List<Overfit20PredictUtils.WinStats> statsCache,
      List<Integer> hitNorms,
      List<String> window,
      int start,
      int[] fixedCore,
      int extra,
      boolean fittedAsCore
   ) {
      double sc = 0.0;
      int zx = 0;
      int gp = 0;

      for (int k = 0; k < groupCache.size(); k++) {
         List<Integer> past = hitNorms.subList(0, k);
         int[] core = fittedAsCore ? deriveCoreSlots(past) : fixedCore;
         int[] fitted = slotsFromNorms(past);
         List<String> hot = recentHotGroups(window.subList(0, start + k), 30);
         Overfit20PredictUtils.CoverSpec use = extra <= 0
            ? new Overfit20PredictUtils.CoverSpec(Overfit20PredictUtils.CoverKind.MIDLATE_CORE, core, null, 0, hot)
            : new Overfit20PredictUtils.CoverSpec(Overfit20PredictUtils.CoverKind.CORE_PLUS_FITTED, core, fitted, extra, hot);
         List<String> tickets = ticketsFromGroups(groupCache.get(k), statsCache.get(k), use, 250);
         String actual = window.get(start + k);
         double wt = Math.exp(-0.2 * (groupCache.size() - 1 - k));
         if (isZxHit(tickets, actual)) {
            zx++;
            sc += 5.0 * wt;
         } else if (isGroupHit(tickets, actual)) {
            gp++;
            sc += 3.0 * wt;
         }
      }

      return sc * 10.0 + zx * 12 + gp * 6;
   }

   static int[] slotsFromNorms(List<Integer> hitNorms) {
      TreeSet<Integer> norms = new TreeSet<>();

      for (Integer n : hitNorms) {
         if (n != null && n >= 0) {
            norms.add(clamp(n, 0, 119));
            norms.add(clamp(n - 6, 0, 119));
            norms.add(clamp(n + 6, 0, 119));
         }
      }

      if (norms.isEmpty()) {
         return deriveCoreSlots(List.of());
      } else {
         List<Integer> list = new ArrayList<>(norms);

         while (list.size() < 7) {
            int idx = list.size() * 119 / 6;
            if (!list.contains(idx)) {
               list.add(idx);
            } else {
               list.add(clamp(idx + list.size(), 0, 119));
            }
         }

         list.sort(Integer::compareTo);
         int[] slots = new int[7];

         for (int i = 0; i < 7; i++) {
            int idx = i == 6 ? list.size() - 1 : (int)Math.floor(i * (list.size() - 1) / 6.0);
            slots[i] = list.get(idx);
         }

         return slots;
      }
   }

   private static List<String> slotsSelect(List<String> groups, int[] slots) {
      LinkedHashSet<String> selected = new LinkedHashSet<>();

      for (int slot : slots) {
         int idx = Math.min(groups.size() - 1, Math.max(0, slot * groups.size() / 120));
         selected.add(groups.get(idx));
      }

      return new ArrayList<>(selected);
   }

   static List<String> ticketsFromGroups(List<String> groups, Overfit20PredictUtils.WinStats stats, Overfit20PredictUtils.CoverSpec cover) {
      return ticketsFromGroups(groups, stats, cover, 250);
   }

   static List<String> ticketsFromGroups(List<String> groups, Overfit20PredictUtils.WinStats stats, Overfit20PredictUtils.CoverSpec cover, int maxTickets) {
      if (groups != null && !groups.isEmpty()) {
         int cap = maxTickets > 0 ? maxTickets : 250;
         int[] coreSlots = cover.coreSlots != null ? cover.coreSlots : deriveCoreSlots(List.of());
         LinkedHashSet<String> coreSet = new LinkedHashSet<>();
         if (cover.priorityGroups != null) {
            for (String g : cover.priorityGroups) {
               if (groups.contains(g)) {
                  coreSet.add(g);
               }

               if (coreSet.size() >= 10) {
                  break;
               }
            }
         }

         for (String g : slotsSelect(groups, coreSlots)) {
            coreSet.add(g);
            if (coreSet.size() >= 16) {
               break;
            }
         }

         List<String> core = new ArrayList<>(coreSet);
         if (core.isEmpty()) {
            core = takeFirst(groups, Math.min(7, groups.size()));
         }

         int reserve = 0;
         List<String> extras = List.of();
         if (cover.kind == Overfit20PredictUtils.CoverKind.CORE_PLUS_FITTED && cover.extraGroups > 0 && cover.fittedSlots != null) {
            LinkedHashSet<String> extraSet = new LinkedHashSet<>();

            for (String gx : slotsSelect(groups, cover.fittedSlots)) {
               if (!core.contains(gx)) {
                  extraSet.add(gx);
               }

               if (extraSet.size() >= cover.extraGroups) {
                  break;
               }
            }

            extras = new ArrayList<>(extraSet);
            reserve = Math.min(extras.size() * 2, Math.max(6, cover.extraGroups * 2));
         }

         List<String> tickets = roundRobinExpand(core, stats, cap - reserve);
         if (!extras.isEmpty() && tickets.size() < cap) {
            for (String t : roundRobinExpand(extras, stats, cap - tickets.size())) {
               if (!tickets.contains(t)) {
                  tickets.add(t);
               }

               if (tickets.size() >= cap) {
                  break;
               }
            }
         }

         if (tickets.size() < cap) {
            for (String t : expandGroups(groups, stats)) {
               if (!tickets.contains(t)) {
                  tickets.add(t);
                  if (tickets.size() >= cap) {
                     break;
                  }
               }
            }
         }

         return (List<String>)(tickets.size() > cap ? new ArrayList<>(tickets.subList(0, cap)) : tickets);
      } else {
         return List.of();
      }
   }

   static List<String> buildTicketPool(List<String> hist, int topN, int bandLo, int bandHi, int bandTake, int posM, Overfit20PredictUtils.CoverSpec cover) {
      return buildTicketPool(hist, topN, bandLo, bandHi, bandTake, posM, cover, 250);
   }

   static List<String> buildTicketPool(
      List<String> hist, int topN, int bandLo, int bandHi, int bandTake, int posM, Overfit20PredictUtils.CoverSpec cover, int maxTickets
   ) {
      List<String> win = hist.size() > 30 ? hist.subList(hist.size() - 30, hist.size()) : hist;
      Overfit20PredictUtils.WinStats stats = Overfit20PredictUtils.WinStats.of(win);
      List<String> groups = buildGroupPool(win, topN, bandLo, bandHi, bandTake, posM, 120);
      return ticketsFromGroups(groups, stats, cover, maxTickets);
   }

   static List<String> buildTicketPool(List<String> hist, int topN, int bandLo, int bandHi, int bandTake, int posM) {
      List<String> win = hist.size() > 30 ? hist.subList(hist.size() - 30, hist.size()) : hist;
      Overfit20PredictUtils.CoverSpec cover = selectCover(win, topN, bandLo, bandHi, bandTake, posM);
      return buildTicketPool(hist, topN, bandLo, bandHi, bandTake, posM, cover);
   }

   static List<String> buildTicketPool(List<String> hist, int topN, int bandLo, int bandHi, int bandTake, int posM, int mode) {
      return buildTicketPool(hist, topN, bandLo, bandHi, bandTake, posM);
   }

   private static List<String> roundRobinExpand(List<String> groups, Overfit20PredictUtils.WinStats stats, int cap) {
      List<ToDoubleFunction<int[]>> fns = stratFns(stats);
      List<List<String>> perms = new ArrayList<>(groups.size());

      for (String g : groups) {
         List<String> ps = new ArrayList<>(permutationsOf(g));
         ps.sort((a, b) -> Double.compare(directScore(b, fns), directScore(a, fns)));
         perms.add(ps);
      }

      LinkedHashSet<String> out = new LinkedHashSet<>();
      int maxLen = 0;

      for (List<String> p : perms) {
         maxLen = Math.max(maxLen, p.size());
      }

      for (int round = 0; round < maxLen && out.size() < cap; round++) {
         for (List<String> p : perms) {
            if (round < p.size()) {
               out.add(p.get(round));
               if (out.size() < cap) {
                  continue;
               }
               break;
            }
         }
      }

      return new ArrayList<>(out);
   }

   private static double directScore(String code, List<ToDoubleFunction<int[]>> fns) {
      int[] abc = digits(code);
      double sc = 0.0;

      for (ToDoubleFunction<int[]> fn : fns) {
         sc += fn.applyAsDouble(abc);
      }

      return sc / Math.max(1, fns.size());
   }

   private static List<String> linspace(List<String> groups, int n) {
      if (groups.isEmpty()) {
         return List.of();
      } else if (groups.size() <= n) {
         return new ArrayList<>(groups);
      } else {
         LinkedHashSet<String> out = new LinkedHashSet<>();

         for (int i = 0; i < n; i++) {
            int idx = i == n - 1 ? groups.size() - 1 : (int)Math.floor((double)(i * (groups.size() - 1)) / (n - 1));
            out.add(groups.get(idx));
         }

         return new ArrayList<>(out);
      }
   }

   private static List<String> parityStride(List<String> groups, int n, boolean odd) {
      List<String> out = new ArrayList<>();

      for (int i = odd ? 1 : 0; i < groups.size() && out.size() < n; i += 2) {
         out.add(groups.get(i));
      }

      for (int i = odd ? 0 : 1; i < groups.size() && out.size() < n; i += 2) {
         out.add(groups.get(i));
      }

      return out;
   }

   private static int chooseParity(List<String> window, int topN, int lo, int hi, int take, int posM) {
      int start = Math.max(10, window.size() - 8);
      int evenHits = 0;
      int oddHits = 0;

      for (int i = start; i < window.size(); i++) {
         List<String> sub = window.subList(0, i);
         List<String> groups = buildGroupPool(sub, topN, lo, hi, take, posM, 120);
         String g = sortedKey(window.get(i));
         int gi = groups.indexOf(g);
         if (gi >= 0) {
            if ((gi & 1) == 0) {
               evenHits++;
            } else {
               oddHits++;
            }
         }
      }

      return oddHits > evenHits ? 1 : 0;
   }

   private static List<String> mixCover(List<String> groups) {
      LinkedHashSet<String> out = new LinkedHashSet<>();
      out.addAll(takeFirst(groups, 5));
      out.addAll(headTail(groups, 0, 8));
      out.addAll(linspace(groups, 16));
      return new ArrayList<>(out);
   }

   private static void addHeadMidTail(LinkedHashSet<String> core, List<String> base, int head, int mid, int tail) {
      int es = base.size();
      if (es != 0) {
         core.addAll(base.subList(0, Math.min(head, es)));
         core.addAll(base.subList(Math.max(0, es - tail), es));
         if (es > head + tail) {
            int midStart = Math.max(0, es / 3);
            int midEnd = Math.min(es, midStart + mid);
            core.addAll(base.subList(midStart, midEnd));
         }
      }
   }

   private static List<String> takeFirst(List<String> groups, int n) {
      return new ArrayList<>(groups.subList(0, Math.min(n, groups.size())));
   }

   private static List<String> stride(List<String> groups, int n) {
      if (groups.isEmpty()) {
         return List.of();
      } else if (groups.size() <= n) {
         return new ArrayList<>(groups);
      } else {
         List<String> out = new ArrayList<>();
         double step = (double)groups.size() / n;

         for (int i = 0; i < n; i++) {
            int idx = Math.min(groups.size() - 1, (int)Math.round(i * step));
            String g = groups.get(idx);
            if (!out.contains(g)) {
               out.add(g);
            }
         }

         for (String g : groups) {
            if (out.size() >= n) {
               break;
            }

            if (!out.contains(g)) {
               out.add(g);
            }
         }

         return out;
      }
   }

   private static List<String> headTail(List<String> groups, int head, int tail) {
      LinkedHashSet<String> out = new LinkedHashSet<>();

      for (int i = 0; i < Math.min(head, groups.size()); i++) {
         out.add(groups.get(i));
      }

      for (int i = 0; i < Math.min(tail, groups.size()); i++) {
         out.add(groups.get(groups.size() - 1 - i));
      }

      return new ArrayList<>(out);
   }

   static List<Overfit20PredictUtils.DirectScored> rankAllDirects(Overfit20PredictUtils.WinStats stats) {
      List<ToDoubleFunction<int[]>> fns = stratFns(stats);
      List<Overfit20PredictUtils.DirectScored> all = new ArrayList<>(1000);

      for (int a = 0; a <= 9; a++) {
         for (int b = 0; b <= 9; b++) {
            for (int c = 0; c <= 9; c++) {
               if (a != b || b != c) {
                  int[] abc = new int[]{a, b, c};
                  double sc = 0.0;

                  for (ToDoubleFunction<int[]> fn : fns) {
                     sc += fn.applyAsDouble(abc);
                  }

                  sc /= fns.size();
                  String code = "" + a + b + c;
                  all.add(new Overfit20PredictUtils.DirectScored(code, sortedKey(code), sc));
               }
            }
         }
      }

      all.sort(Comparator.<Overfit20PredictUtils.DirectScored>comparingDouble(d -> d.score).reversed());
      return all;
   }

   static List<String> buildGroupPool(List<String> hist, int topN, int bandLo, int bandHi, int bandTake, int posM, int maxGroups) {
      List<String> win = hist.size() > 30 ? hist.subList(hist.size() - 30, hist.size()) : hist;
      Overfit20PredictUtils.WinStats stats = Overfit20PredictUtils.WinStats.of(win);
      LinkedHashSet<String> seen = new LinkedHashSet<>();
      Map<String, String> bestOrder = new HashMap<>();

      for (int i = win.size() - 1; i >= 0; i--) {
         String code = pad3(win.get(i));
         String g = sortedKey(code);
         if (seen.add(g)) {
            bestOrder.put(g, code);
         }

         int[] d = digits(code);
         if (d != null) {
            for (int pos = 0; pos < 3; pos++) {
               for (int delta : new int[]{1, 9}) {
                  int[] n = new int[]{d[0], d[1], d[2]};
                  n[pos] = (n[pos] + delta) % 10;
                  if (n[0] != n[1] || n[1] != n[2]) {
                     String nc = "" + n[0] + n[1] + n[2];
                     String ng = sortedKey(nc);
                     if (seen.add(ng)) {
                        bestOrder.put(ng, nc);
                     }
                  }
               }
            }
         }

         if (seen.size() >= maxGroups / 2) {
            break;
         }
      }

      for (ToDoubleFunction<int[]> fn : stratFns(stats)) {
         List<Overfit20PredictUtils.Scored> ranked = fullRank(fn);

         for (int i = 0; i < Math.min(topN, ranked.size()); i++) {
            Overfit20PredictUtils.Scored s = ranked.get(i);
            if (seen.add(s.group)) {
               bestOrder.put(s.group, s.code);
            }
         }

         if (bandTake > 0 && bandLo < ranked.size()) {
            int hi = Math.min(bandHi, ranked.size());
            List<Overfit20PredictUtils.Scored> band = ranked.subList(bandLo, hi);
            if (!band.isEmpty()) {
               int step = Math.max(1, band.size() / bandTake);
               int added = 0;

               for (int ix = 0; ix < band.size() && added < bandTake; ix += step) {
                  Overfit20PredictUtils.Scored s = band.get(ix);
                  if (seen.add(s.group)) {
                     bestOrder.put(s.group, s.code);
                     added++;
                  }
               }
            }
         }
      }

      int[] last = digits(win.get(win.size() - 1));
      List<Entry<String, Integer>> deltas = new ArrayList<>(stats.deltas.entrySet());
      deltas.sort((a, bx) -> Integer.compare((Integer)bx.getValue(), a.getValue()));

      for (int ixx = 0; ixx < Math.min(10, deltas.size()); ixx++) {
         String key = deltas.get(ixx).getKey();
         String[] p = key.split(",");
         int a = (last[0] + Integer.parseInt(p[0])) % 10;
         int b = (last[1] + Integer.parseInt(p[1])) % 10;
         int c = (last[2] + Integer.parseInt(p[2])) % 10;
         if (a != b || b != c) {
            String gx = sortedKey("" + a + b + c);
            if (seen.add(gx)) {
               bestOrder.put(gx, "" + a + b + c);
            }
         }
      }

      int[][] tops = new int[3][posM];

      for (int pos = 0; pos < 3; pos++) {
         List<int[]> dig = new ArrayList<>();

         for (int dx = 0; dx < 10; dx++) {
            dig.add(new int[]{dx, stats.posFreq[pos][dx]});
         }

         dig.sort((x, y) -> Integer.compare(y[1], x[1]));

         for (int ixxx = 0; ixxx < posM; ixxx++) {
            tops[pos][ixxx] = dig.get(ixxx)[0];
         }
      }

      for (int a : tops[0]) {
         for (int b : tops[1]) {
            for (int c : tops[2]) {
               if (a != b || b != c) {
                  String gx = sortedKey("" + a + b + c);
                  if (seen.add(gx)) {
                     bestOrder.put(gx, "" + a + b + c);
                  }
               }
            }
         }
      }

      List<String> pool = new ArrayList<>();

      for (String gx : seen) {
         pool.add(gx);
         if (pool.size() >= maxGroups) {
            break;
         }
      }

      return pool;
   }

   static List<String> buildDisplayFive(List<String> window, Overfit20PredictUtils.WinStats stats) {
      LinkedHashSet<String> out = new LinkedHashSet<>();

      label39:
      for (ToDoubleFunction<int[]> fn : stratFns(stats)) {
         Iterator lo = fullRank(fn).iterator();

         while (true) {
            if (lo.hasNext()) {
               Overfit20PredictUtils.Scored s = (Overfit20PredictUtils.Scored)lo.next();
               if (!out.add(s.code)) {
                  continue;
               }
            }

            if (out.size() >= 5) {
               break label39;
            }
            break;
         }
      }

      if (out.size() < 5) {
         double uniq = uniqueGroupRatio(window);
         int lo = clamp((int)Math.round(8.0 + 20.0 * uniq), 5, 40);
         int hi = clamp(lo + (int)Math.round(20.0 + 25.0 * (1.0 - uniq)), lo + 8, 80);
         int take = clamp((hi - lo) / 3, 6, 14);

         for (String g : buildGroupPool(window, 6, lo, hi, take, 5, 120)) {
            out.add(bestOrderOf(g, stats));
            if (out.size() >= 5) {
               break;
            }
         }
      }

      return new ArrayList<>(out);
   }

   static List<String> expandGroups(List<String> groups, Overfit20PredictUtils.WinStats stats) {
      LinkedHashSet<String> out = new LinkedHashSet<>();

      for (String g : groups) {
         for (String p : permutationsOf(g)) {
            out.add(p);
         }
      }

      return new ArrayList<>(out);
   }

   static List<ToDoubleFunction<int[]>> stratFns(Overfit20PredictUtils.WinStats ft) {
      double avgOmit = 0.0;

      for (int i = 0; i < 3; i++) {
         for (int d = 0; d < 10; d++) {
            avgOmit += ft.omit[i][d];
         }
      }

      avgOmit /= 30.0;
      int shapeTot = Math.max(1, ft.pairCnt + ft.zu6Cnt);
      double pairR = (double)ft.pairCnt / shapeTot;
      List<ToDoubleFunction<int[]>> list = new ArrayList<>(5);
      list.add(abc -> 1.5 * posSum(ft, abc) + 0.5 * omitSweet(ft, abc) + 0.4 * transSum(ft, abc) + 0.3 * neiScore(ft, abc) + 0.3 * sumSpan(ft, abc));
      double omitBoost = 1.2 + avgOmit / 20.0;
      list.add(abc -> 0.4 * posSum(ft, abc) + omitBoost * omitSweet(ft, abc) + 0.8 * sumSpan(ft, abc) + 0.4 * oddSize(ft, abc));
      list.add(abc -> 0.4 * posSum(ft, abc) + 1.4 * transSum(ft, abc) + 1.2 * neiScore(ft, abc) + 1.0 * deltaScore(ft, abc) + 0.3 * omitSweet(ft, abc));
      list.add(abc -> 0.5 * posSum(ft, abc) + 0.5 * omitSweet(ft, abc) + 1.3 * sumSpan(ft, abc) + 1.0 * oddSize(ft, abc) + (0.8 + pairR) * shapeScore(ft, abc));
      list.add(abc -> 1.2 * -posSum(ft, abc) + 1.0 * omitSweet(ft, abc) + 0.6 * sumSpan(ft, abc) + 0.5 * neiScore(ft, abc) + 0.4 * deltaScore(ft, abc));
      return list;
   }

   private static double posSum(Overfit20PredictUtils.WinStats ft, int[] abc) {
      return ft.posFreq[0][abc[0]] + ft.posFreq[1][abc[1]] + ft.posFreq[2][abc[2]];
   }

   private static double omitSweet(Overfit20PredictUtils.WinStats ft, int[] abc) {
      double s = 0.0;

      for (int i = 0; i < 3; i++) {
         double mean = 0.0;

         for (int d = 0; d < 10; d++) {
            mean += ft.omit[i][d];
         }

         mean /= 10.0;
         s += 1.0 / (1.0 + Math.abs(ft.omit[i][abc[i]] - mean));
      }

      return s;
   }

   private static double transSum(Overfit20PredictUtils.WinStats ft, int[] abc) {
      double s = 0.0;

      for (int i = 0; i < 3; i++) {
         s += ft.trans[i][ft.last[i]][abc[i]];
      }

      return s;
   }

   private static double neiScore(Overfit20PredictUtils.WinStats ft, int[] abc) {
      double s = 0.0;

      for (int i = 0; i < 3; i++) {
         int dist = Math.min((abc[i] - ft.last[i] + 10) % 10, (ft.last[i] - abc[i] + 10) % 10);
         s += dist == 0 ? 2.0 : (dist == 1 ? 1 : 0);
      }

      return s;
   }

   private static double sumSpan(Overfit20PredictUtils.WinStats ft, int[] abc) {
      int sum = abc[0] + abc[1] + abc[2];
      int span = Math.max(abc[0], Math.max(abc[1], abc[2])) - Math.min(abc[0], Math.min(abc[1], abc[2]));
      return -Math.abs(sum - ft.sumMean) / (ft.sumStd + 0.5) - Math.abs(span - ft.spanMean);
   }

   private static double oddSize(Overfit20PredictUtils.WinStats ft, int[] abc) {
      int odd = (abc[0] & 1) + (abc[1] & 1) + (abc[2] & 1);
      int big = (abc[0] >= 5 ? 1 : 0) + (abc[1] >= 5 ? 1 : 0) + (abc[2] >= 5 ? 1 : 0);
      return ft.oddHist[odd] + ft.bigHist[big];
   }

   private static double shapeScore(Overfit20PredictUtils.WinStats ft, int[] abc) {
      int u = uniqCount(abc);
      if (u == 2) {
         return ft.pairCnt;
      } else {
         return u == 3 ? ft.zu6Cnt : 0.0;
      }
   }

   private static double deltaScore(Overfit20PredictUtils.WinStats ft, int[] abc) {
      int da = (abc[0] - ft.last[0] + 10) % 10;
      int db = (abc[1] - ft.last[1] + 10) % 10;
      int dc = (abc[2] - ft.last[2] + 10) % 10;
      return ft.deltas.getOrDefault(da + "," + db + "," + dc, 0).intValue();
   }

   static List<Overfit20PredictUtils.Scored> fullRank(ToDoubleFunction<int[]> fn) {
      List<Overfit20PredictUtils.Scored> all = new ArrayList<>(220);
      Map<String, Overfit20PredictUtils.Scored> best = new HashMap<>();

      for (int a = 0; a <= 9; a++) {
         for (int b = 0; b <= 9; b++) {
            for (int c = 0; c <= 9; c++) {
               if (a != b || b != c) {
                  int[] abc = new int[]{a, b, c};
                  double sc = fn.applyAsDouble(abc);
                  String code = "" + a + b + c;
                  String g = sortedKey(code);
                  Overfit20PredictUtils.Scored old = best.get(g);
                  if (old == null || sc > old.score) {
                     best.put(g, new Overfit20PredictUtils.Scored(g, code, sc));
                  }
               }
            }
         }
      }

      all.addAll(best.values());
      all.sort(Comparator.<Overfit20PredictUtils.Scored>comparingDouble(s -> s.score).reversed());
      return all;
   }

   private static int uniqCount(int[] d) {
      if (d[0] == d[1] && d[1] == d[2]) {
         return 1;
      } else {
         return d[0] != d[1] && d[1] != d[2] && d[0] != d[2] ? 3 : 2;
      }
   }

   static double uniqueGroupRatio(List<String> win) {
      Set<String> set = new HashSet<>();

      for (String c : win) {
         set.add(sortedKey(c));
      }

      return (double)set.size() / win.size();
   }

   private static String bestOrderOf(String group, Overfit20PredictUtils.WinStats stats) {
      String best = group;
      double bestSc = Double.NEGATIVE_INFINITY;
      ToDoubleFunction<int[]> fn = stratFns(stats).get(0);

      for (String p : permutationsOf(group)) {
         int[] abc = digits(p);
         double sc = fn.applyAsDouble(abc);
         if (sc > bestSc) {
            bestSc = sc;
            best = p;
         }
      }

      return best;
   }

   static List<String> singlePosPlusMinus1(String code) {
      int[] d = digits(code);
      if (d == null) {
         return List.of();
      } else {
         List<String> out = new ArrayList<>(6);

         for (int pos = 0; pos < 3; pos++) {
            for (int delta : new int[]{1, 9}) {
               int[] n = new int[]{d[0], d[1], d[2]};
               n[pos] = (n[pos] + delta) % 10;
               if (n[0] != n[1] || n[1] != n[2]) {
                  out.add("" + n[0] + n[1] + n[2]);
               }
            }
         }

         return out;
      }
   }

   static List<String> permutationsOf(String groupKey) {
      char[] ch = groupKey.toCharArray();
      Set<String> out = new LinkedHashSet<>();
      permute(ch, 0, out);
      return new ArrayList<>(out);
   }

   private static void permute(char[] ch, int idx, Set<String> out) {
      if (idx == ch.length) {
         out.add(new String(ch));
      } else {
         Set<Character> used = new HashSet<>();

         for (int i = idx; i < ch.length; i++) {
            if (used.add(ch[i])) {
               swap(ch, idx, i);
               permute(ch, idx + 1, out);
               swap(ch, idx, i);
            }
         }
      }
   }

   private static void swap(char[] ch, int i, int j) {
      char t = ch[i];
      ch[i] = ch[j];
      ch[j] = t;
   }

   static List<String> toCodes(List<Hm> history) {
      List<String> out = new ArrayList<>();
      if (history == null) {
         return out;
      } else {
         for (Hm hm : history) {
            if (hm != null) {
               out.add(pad3(hm.toString()));
            }
         }

         return out;
      }
   }

   static boolean isZxHit(List<String> pred, String actual) {
      if (pred != null && actual != null) {
         String a = pad3(actual);

         for (String p : pred) {
            if (a.equals(pad3(p))) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   static boolean isGroupHit(List<String> pred, String actual) {
      if (pred != null && actual != null) {
         String key = sortedKey(pad3(actual));

         for (String p : pred) {
            if (key.equals(sortedKey(pad3(p)))) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   static boolean isZxHit(String predCsv, String actual) {
      return isZxHit(splitCsv(predCsv), actual);
   }

   static boolean isGroupHit(String predCsv, String actual) {
      return isGroupHit(splitCsv(predCsv), actual);
   }

   static List<String> splitCsv(String csv) {
      if (csv != null && !csv.isBlank()) {
         List<String> out = new ArrayList<>();

         for (String p : csv.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) {
               out.add(pad3(t));
            }
         }

         return out;
      } else {
         return List.of();
      }
   }

   static String sortedKey(String code) {
      char[] c = pad3(code).toCharArray();
      Arrays.sort(c);
      return new String(c);
   }

   static String pad3(String s) {
      if (s == null) {
         return "000";
      } else {
         String t = s.trim();

         while (t.length() < 3) {
            t = "0" + t;
         }

         return t.length() > 3 ? t.substring(t.length() - 3) : t;
      }
   }

   static int[] digits(String code) {
      String p = pad3(code);
      return new int[]{p.charAt(0) - '0', p.charAt(1) - '0', p.charAt(2) - '0'};
   }

   static int clamp(int v, int lo, int hi) {
      return Math.max(lo, Math.min(hi, v));
   }

   public static String summarizeHits(int zx, int group, int n) {
      boolean pass = zx >= 4 && group >= 3;
      return String.format(Locale.ROOT, "近%d期逐期评估：直选=%d/%d 组选=%d/%d → %s（目标直选≥%d 组选≥%d，池≤%d注）", n, zx, n, group, n, pass ? "达标" : "未达标", 4, 3, 250);
   }

   static enum CoverKind {
      MIDLATE_CORE,
      CORE_PLUS_FITTED;
   }

   static final class CoverSpec {
      final Overfit20PredictUtils.CoverKind kind;
      final int[] coreSlots;
      final int[] fittedSlots;
      final int extraGroups;
      final List<String> priorityGroups;

      CoverSpec(Overfit20PredictUtils.CoverKind kind, int[] coreSlots, int[] fittedSlots, int extraGroups) {
         this(kind, coreSlots, fittedSlots, extraGroups, List.of());
      }

      CoverSpec(Overfit20PredictUtils.CoverKind kind, int[] coreSlots, int[] fittedSlots, int extraGroups, List<String> priorityGroups) {
         this.kind = kind;
         this.coreSlots = coreSlots;
         this.fittedSlots = fittedSlots;
         this.extraGroups = extraGroups;
         this.priorityGroups = priorityGroups == null ? List.of() : List.copyOf(priorityGroups);
      }

      String label() {
         String base = this.kind == Overfit20PredictUtils.CoverKind.MIDLATE_CORE ? "midlate-dyn" : "core+fit(x" + this.extraGroups + ")";
         return this.priorityGroups.isEmpty() ? base : base + "+hot" + this.priorityGroups.size();
      }
   }

   static final class DirectScored {
      final String code;
      final String group;
      final double score;

      DirectScored(String code, String group, double score) {
         this.code = code;
         this.group = group;
         this.score = score;
      }
   }

   public static enum GameKind {
      SD,
      PL3;
   }

   static final class PlusMinus1Profile {
      final double[][] score = new double[3][2];

      double boost(int pos, int signIdx) {
         return pos >= 0 && pos <= 2 && signIdx >= 0 && signIdx <= 1 ? this.score[pos][signIdx] : 0.0;
      }

      double totalWeight() {
         double s = 0.0;

         for (int p = 0; p < 3; p++) {
            s += this.score[p][0] + this.score[p][1];
         }

         return s;
      }
   }

   public static final class PredictResult {
      public final List<String> displayFive;
      public final List<String> pool;
      public final String tune;

      public PredictResult(List<String> displayFive, List<String> pool, String tune) {
         this.displayFive = List.copyOf(displayFive);
         this.pool = List.copyOf(pool);
         this.tune = tune == null ? "" : tune;
      }

      public String displayCsv() {
         return String.join(",", this.displayFive);
      }

      public String poolCsv() {
         return String.join(",", this.pool);
      }
   }

   static final class Scored {
      final String group;
      final String code;
      final double score;

      Scored(String group, String code, double score) {
         this.group = group;
         this.code = code;
         this.score = score;
      }
   }

   static final class WinStats {
      final int n;
      final int[][] posFreq = new int[3][10];
      final int[][] omit = new int[3][10];
      final int[][][] trans = new int[3][10][10];
      final Map<String, Integer> deltas = new HashMap<>();
      final int[] last = new int[3];
      final double sumMean;
      final double spanMean;
      final double sumStd;
      final int[] oddHist = new int[4];
      final int[] bigHist = new int[4];
      final int pairCnt;
      final int zu6Cnt;

      private WinStats(int n, double sumMean, double spanMean, double sumStd, int pairCnt, int zu6Cnt) {
         this.n = n;
         this.sumMean = sumMean;
         this.spanMean = spanMean;
         this.sumStd = sumStd;
         this.pairCnt = pairCnt;
         this.zu6Cnt = zu6Cnt;
      }

      static Overfit20PredictUtils.WinStats of(List<String> win) {
         int n = win.size();
         double sumAcc = 0.0;
         double spanAcc = 0.0;
         int pair = 0;
         int zu6 = 0;
         Overfit20PredictUtils.WinStats s = new Overfit20PredictUtils.WinStats(n, 0.0, 0.0, 0.0, 0, 0);

         for (int i = 0; i < 3; i++) {
            Arrays.fill(s.omit[i], n);
         }

         for (int t = 0; t < n; t++) {
            int[] d = Overfit20PredictUtils.digits(win.get(t));

            for (int i = 0; i < 3; i++) {
               s.posFreq[i][d[i]]++;
               s.omit[i][d[i]] = n - 1 - t;
            }

            int sum = d[0] + d[1] + d[2];
            int span = Math.max(d[0], Math.max(d[1], d[2])) - Math.min(d[0], Math.min(d[1], d[2]));
            sumAcc += sum;
            spanAcc += span;
            s.oddHist[(d[0] & 1) + (d[1] & 1) + (d[2] & 1)]++;
            s.bigHist[(d[0] >= 5 ? 1 : 0) + (d[1] >= 5 ? 1 : 0) + (d[2] >= 5 ? 1 : 0)]++;
            int u = Overfit20PredictUtils.uniqCount(d);
            if (u == 2) {
               pair++;
            } else if (u == 3) {
               zu6++;
            }

            if (t > 0) {
               int[] p = Overfit20PredictUtils.digits(win.get(t - 1));

               for (int i = 0; i < 3; i++) {
                  s.trans[i][p[i]][d[i]]++;
               }

               String dk = (d[0] - p[0] + 10) % 10 + "," + (d[1] - p[1] + 10) % 10 + "," + (d[2] - p[2] + 10) % 10;
               s.deltas.merge(dk, 1, Integer::sum);
            }
         }

         double mean = sumAcc / n;
         double var = 0.0;

         for (String code : win) {
            int[] d = Overfit20PredictUtils.digits(code);
            double diff = d[0] + d[1] + d[2] - mean;
            var += diff * diff;
         }

         Overfit20PredictUtils.WinStats out = new Overfit20PredictUtils.WinStats(n, mean, spanAcc / n, Math.sqrt(var / n), pair, zu6);

         for (int i = 0; i < 3; i++) {
            System.arraycopy(s.posFreq[i], 0, out.posFreq[i], 0, 10);
            System.arraycopy(s.omit[i], 0, out.omit[i], 0, 10);
            out.last[i] = Overfit20PredictUtils.digits(win.get(n - 1))[i];

            for (int a = 0; a < 10; a++) {
               System.arraycopy(s.trans[i][a], 0, out.trans[i][a], 0, 10);
            }
         }

         System.arraycopy(s.oddHist, 0, out.oddHist, 0, 4);
         System.arraycopy(s.bigHist, 0, out.bigHist, 0, 4);
         out.deltas.putAll(s.deltas);
         return out;
      }
   }
}
