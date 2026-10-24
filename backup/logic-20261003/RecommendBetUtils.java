package com.zfl.caipiao.utils;

import cn.hutool.core.util.StrUtil;
import com.zfl.caipiao.cache.HmCache;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class RecommendBetUtils {
   public static final int HIT_LOOKBACK = 100;
   public static final int MIN_PICK = 10;
   public static final int MAX_PICK = 10;
   private static final int MAX_RANK = 200;
   private static final int COND_LOOKBACK = 40;
   private static final double RECENCY_DECAY = 0.96;
   private static final double HIST_WEIGHT = 0.88;
   private static final int[] SEG_HI = new int[]{50, 100, 150, 200};
   private static final int[] DEFAULT_QUOTA = new int[]{1, 2, 3, 4};
   static int HABIT_RESERVE = 3;
   static int OF_MAX_SLOTS = 2;
   static boolean PIN_TOP1 = false;
   static int RECENT_GRP = 0;
   static boolean REALIGN_POOL_RANK = true;

   private RecommendBetUtils() {
   }

   public static String pickRecommendBets(String pred, List<HmCache.CompareDto> history) {
      return pickRecommendBets(pred, history, null, false);
   }

   public static String pickRecommendBets(String pred, List<HmCache.CompareDto> history, String overfitPool) {
      return pickRecommendBets(pred, history, overfitPool, false);
   }

   public static String pickRecommendBets(String pred, List<HmCache.CompareDto> history, String overfitPool, boolean pl3) {
      List<String> all = parseBets(pred);
      if (all.isEmpty()) {
         return "";
      } else {
         int n = Math.min(all.size(), 200);
         int[] quota = allocateQuota(history, n);
         double[] rankScores = scoreRanksForStratified(n, history);
         String lastReal = lastRealHm(history);
         Set<String> banned = new LinkedHashSet<>();
         if (lastReal != null && lastReal.length() == 3) {
            banned.add(lastReal);
         }

         Set<String> overfitSet = new LinkedHashSet<>();
         if (overfitPool != null && !overfitPool.isBlank()) {
            overfitSet.addAll(parseBets(overfitPool));
         }

         List<String> picked = new ArrayList<>(10);
         Set<String> usedDigitKeys = new LinkedHashSet<>();
         if (PIN_TOP1 && n >= 1) {
            String top = all.get(0);
            if (top != null && top.length() == 3 && !banned.contains(top)) {
               usedDigitKeys.add(digitKey(top));
               picked.add(top);
               int seg = segmentOf(1);
               if (seg >= 0 && quota[seg] > 0) {
                  quota[seg]--;
               }
            }
         }

         if (lastReal != null && lastReal.length() == 3) {
            List<String> habitCands = habitNearTickets(lastReal, all, history);
            int reserved = 0;

            for (String bet : habitCands) {
               if (reserved >= HABIT_RESERVE || picked.size() >= 10) {
                  break;
               }

               if (!banned.contains(bet)) {
                  String key = digitKey(bet);
                  if (usedDigitKeys.add(key)) {
                     picked.add(bet);
                     reserved++;
                     int rank = indexOfBet(String.join(",", all), bet);
                     int seg = segmentOf(rank);
                     if (seg >= 0 && quota[seg] > 0) {
                        quota[seg]--;
                     }
                  }
               }
            }
         }

         int ofSlots = 0;

         for (String bet : overfitSet) {
            if (ofSlots >= OF_MAX_SLOTS || picked.size() >= 10) {
               break;
            }

            int rank = -1;

            for (int r = 1; r <= n; r++) {
               if (all.get(r - 1).equals(bet)) {
                  rank = r;
                  break;
               }
            }

            if (rank >= 1 && !banned.contains(bet)) {
               String key = digitKey(bet);
               if (usedDigitKeys.add(key)) {
                  int seg = segmentOf(rank);
                  if (seg >= 0 && quota[seg] > 0) {
                     quota[seg]--;
                  } else {
                     int maxI = 3;

                     for (int s = 0; s < 4; s++) {
                        if (quota[s] > quota[maxI]) {
                           maxI = s;
                        }
                     }

                     if (quota[maxI] > 0) {
                        quota[maxI]--;
                     }
                  }

                  picked.add(bet);
                  ofSlots++;
               }
            }
         }

         for (int seg = 0; seg < 4; seg++) {
            int need = quota[seg];
            if (need > 0) {
               int lo = seg == 0 ? 1 : SEG_HI[seg - 1] + 1;
               int hi = Math.min(SEG_HI[seg], n);
               if (lo <= hi) {
                  List<Integer> ranks = new ArrayList<>();

                  for (int rx = lo; rx <= hi; rx++) {
                     ranks.add(rx);
                  }

                  ranks.sort(Comparator.<Integer>comparingDouble(rx -> {
                     String betx = all.get(rx - 1);
                     double sc = rankScores[rx];
                     if (overfitSet.contains(betx)) {
                        sc += 5.0;
                     }

                     sc += habitTicketBonus(betx, lastReal);
                     int spanx = hi - lo + 1;
                     int bin = spanx <= 1 ? 0 : (rx - lo) * need / spanx;
                     return sc + (need - bin) * 1.0E-4;
                  }).reversed().thenComparingInt(rx -> rx));
                  int span = hi - lo + 1;
                  int[] binUsed = new int[Math.max(1, need)];
                  int got = 0;

                  for (int rx : ranks) {
                     if (got >= need || picked.size() >= 10) {
                        break;
                     }

                     int bin = span <= 1 ? 0 : Math.min(need - 1, (rx - lo) * need / span);
                     if ((binUsed[bin] < 1 || got < Math.min(need, binUsed.length)) && binUsed[bin] < 1) {
                        String bet = all.get(rx - 1);
                        if (bet != null && bet.length() == 3 && !banned.contains(bet)) {
                           String key = digitKey(bet);
                           if (!usedDigitKeys.contains(key)) {
                              usedDigitKeys.add(key);
                              picked.add(bet);
                              binUsed[bin]++;
                              got++;
                           }
                        }
                     }
                  }

                  for (int rx : ranks) {
                     if (got >= need || picked.size() >= 10) {
                        break;
                     }

                     String bet = all.get(rx - 1);
                     if (bet != null && bet.length() == 3 && !banned.contains(bet)) {
                        String key = digitKey(bet);
                        if (!usedDigitKeys.contains(key)) {
                           usedDigitKeys.add(key);
                           picked.add(bet);
                           got++;
                        }
                     }
                  }
               }
            }
         }

         if (picked.size() < 10) {
            List<Integer> ranks = new ArrayList<>();

            for (int rx = 1; rx <= n; rx++) {
               ranks.add(rx);
            }

            ranks.sort(Comparator.<Integer>comparingDouble(rx -> rankScores[rx]).reversed().thenComparingInt(rx -> rx));

            for (int rx : ranks) {
               if (picked.size() >= 10) {
                  break;
               }

               String bet = all.get(rx - 1);
               if (bet != null && bet.length() == 3 && !banned.contains(bet)) {
                  String key = digitKey(bet);
                  if (usedDigitKeys.add(key)) {
                     picked.add(bet);
                  }
               }
            }
         }

         if (picked.size() < 10) {
            for (String bet : fillUniqueDigitSets(all, 10)) {
               if (picked.size() >= 10) {
                  break;
               }

               if (!banned.contains(bet)) {
                  String key = digitKey(bet);
                  if (usedDigitKeys.add(key)) {
                     picked.add(bet);
                  }
               }
            }
         }

         if (picked.size() < 10) {
            for (String bet : all) {
               if (picked.size() >= 10) {
                  break;
               }

               if (bet != null && bet.length() == 3) {
                  String key = digitKey(bet);
                  if (usedDigitKeys.add(key)) {
                     picked.add(bet);
                  }
               }
            }
         }

         if (REALIGN_POOL_RANK) {
            picked = realignToBestPoolRank(picked, all, banned);
         }

         return String.join(",", picked.subList(0, Math.min(10, picked.size())));
      }
   }

   static List<String> realignToBestPoolRank(List<String> picked, List<String> pool, Set<String> banned) {
      Map<String, String> best = new LinkedHashMap<>();
      if (pool != null) {
         for (String t : pool) {
            if (t != null && t.length() == 3 && (banned == null || !banned.contains(t))) {
               best.putIfAbsent(digitKey(t), t);
            }
         }
      }

      List<String> out = new ArrayList<>(picked.size());

      for (String p : picked) {
         if (p != null && p.length() == 3) {
            out.add(best.getOrDefault(digitKey(p), p));
         } else {
            out.add(p);
         }
      }

      return out;
   }

   static int[] allocateQuota(List<HmCache.CompareDto> history, int predSize) {
      int[] quota = Arrays.copyOf(DEFAULT_QUOTA, 4);
      double[] segW = new double[4];
      int samples = 0;
      if (history != null && !history.isEmpty()) {
         int end = history.size();
         int start = Math.max(0, end - 40);

         for (int i = start; i < end; i++) {
            HmCache.CompareDto dto = history.get(i);
            if (dto != null && !StrUtil.isBlank(dto.getRealHm()) && dto.getRealHm().length() == 3) {
               String list = listForRank(dto);
               if (!StrUtil.isBlank(list)) {
                  String actual = pad3(dto.getRealHm());
                  int rank = indexOfBet(list, actual);
                  if (rank >= 1) {
                     int seg = segmentOf(rank);
                     if (seg >= 0) {
                        int age = end - 1 - i;
                        segW[seg] += Math.pow(0.96, age);
                        samples++;
                     }
                  }
               }
            }
         }
      }

      if (samples < 3) {
         return clampQuotaToSize(quota, predSize);
      } else {
         double sum = 0.0;

         for (double w : segW) {
            sum += w;
         }

         if (sum <= 1.0E-12) {
            return clampQuotaToSize(quota, predSize);
         } else {
            int[] raw = new int[4];
            int assigned = 0;

            for (int s = 0; s < 4; s++) {
               raw[s] = 1;
               assigned++;
            }

            int remain = 10 - assigned;
            double[] frac = new double[4];

            for (int s = 0; s < 4; s++) {
               frac[s] = segW[s] / sum * remain;
               int add = (int)Math.floor(frac[s]);
               add = Math.min(3, add);
               raw[s] += add;
               assigned += add;
            }

            remain = 10 - (raw[0] + raw[1] + raw[2] + raw[3]);
            Integer[] order = new Integer[]{0, 1, 2, 3};
            Arrays.sort(order, (a, b) -> Double.compare(frac[b] - Math.floor(frac[b]), frac[a] - Math.floor(frac[a])));

            for (int oi = 0; remain > 0 && oi < 40; oi++) {
               int s = order[oi % 4];
               if (raw[s] < 4) {
                  raw[s]++;
                  remain--;
               }
            }

            while (remain < 0) {
               int best = -1;
               double bestW = Double.POSITIVE_INFINITY;

               for (int s = 0; s < 4; s++) {
                  if (raw[s] > 1 && segW[s] < bestW) {
                     bestW = segW[s];
                     best = s;
                  }
               }

               if (best < 0) {
                  break;
               }

               raw[best]--;
               remain++;
            }

            return clampQuotaToSize(raw, predSize);
         }
      }
   }

   private static int[] clampQuotaToSize(int[] quota, int predSize) {
      int[] q = Arrays.copyOf(quota, 4);
      if (predSize <= 0) {
         return q;
      } else {
         for (int s = 0; s < 4; s++) {
            int lo = s == 0 ? 1 : SEG_HI[s - 1] + 1;
            int hi = Math.min(SEG_HI[s], predSize);
            if (lo > hi && q[s] > 0) {
               int move = q[s];
               q[s] = 0;

               for (int t = 0; t < 4 && move > 0; t++) {
                  int tLo = t == 0 ? 1 : SEG_HI[t - 1] + 1;
                  int tHi = Math.min(SEG_HI[t], predSize);
                  if (tLo <= tHi && q[t] < 4) {
                     int add = Math.min(move, 4 - q[t]);
                     q[t] += add;
                     move -= add;
                  }
               }
            }
         }

         int sum = q[0] + q[1] + q[2] + q[3];
         if (sum != 10) {
            if (sum <= 0) {
               return Arrays.copyOf(DEFAULT_QUOTA, 4);
            }

            while (sum < 10) {
               int best = 0;

               for (int sx = 1; sx < 4; sx++) {
                  if (q[sx] < q[best]) {
                     best = sx;
                  }
               }

               if (q[best] >= 4) {
                  break;
               }

               q[best]++;
               sum++;
            }

            while (sum > 10) {
               int best = 0;

               for (int sxx = 1; sxx < 4; sxx++) {
                  if (q[sxx] > q[best]) {
                     best = sxx;
                  }
               }

               if (q[best] <= 1) {
                  break;
               }

               q[best]--;
               sum--;
            }
         }

         return q;
      }
   }

   static int segmentOf(int rank) {
      if (rank < 1) {
         return -1;
      } else {
         for (int s = 0; s < 4; s++) {
            if (rank <= SEG_HI[s]) {
               return s;
            }
         }

         return 3;
      }
   }

   static double[] scoreRanksForStratified(int predSize, List<HmCache.CompareDto> history) {
      int n = Math.min(200, Math.max(predSize, 1));
      double[] hist = new double[n + 1];
      List<Integer> recentHitRanks = new ArrayList<>();
      if (history != null && !history.isEmpty()) {
         int end = history.size();
         int start = Math.max(0, end - 40);

         for (int i = start; i < end; i++) {
            HmCache.CompareDto dto = history.get(i);
            if (dto != null && !StrUtil.isBlank(listForRank(dto)) && !StrUtil.isBlank(dto.getRealHm()) && dto.getRealHm().length() == 3) {
               int rank = indexOfBet(listForRank(dto), pad3(dto.getRealHm()));
               if (rank >= 1 && rank <= n) {
                  int age = end - 1 - i;
                  hist[rank] += Math.pow(0.96, age);
                  recentHitRanks.add(rank);
               }
            }
         }
      }

      int from = Math.max(0, recentHitRanks.size() - 6);

      for (int ix = from; ix < recentHitRanks.size(); ix++) {
         int rh = recentHitRanks.get(ix);
         double w = Math.pow(0.85, recentHitRanks.size() - 1 - ix);

         for (int d = -3; d <= 3; d++) {
            int r = rh + d;
            if (r >= 1 && r <= n) {
               hist[r] += w * (4 - Math.abs(d)) * 0.35;
            }
         }
      }

      double[] smooth = new double[n + 1];
      double smoothSum = 0.0;

      for (int r = 1; r <= n; r++) {
         double v = hist[r];
         if (r > 1) {
            v += 0.35 * hist[r - 1];
         }

         if (r < n) {
            v += 0.35 * hist[r + 1];
         }

         smooth[r] = v;
         smoothSum += v;
      }

      double[] scores = new double[n + 1];
      boolean hasHist = smoothSum > 1.0E-9;

      for (int r = 1; r <= n; r++) {
         double prior = (r >= 100 ? 1.2 : 0.8) / (1.0 + Math.log(1 + r));
         if (!hasHist) {
            scores[r] = prior;
         } else {
            scores[r] = 0.88 * (smooth[r] / smoothSum) + 0.12 * prior;
         }
      }

      return scores;
   }

   public static String dedupeByGroupKeepFirst(String pred) {
      List<String> all = parseBets(pred);
      if (all.isEmpty()) {
         return "";
      } else {
         List<String> out = new ArrayList<>();
         Set<String> seenGroup = new LinkedHashSet<>();

         for (String bet : all) {
            if (bet != null && bet.length() == 3) {
               String key = digitKey(bet);
               if (seenGroup.add(key)) {
                  out.add(bet);
               }
            }
         }

         return String.join(",", out);
      }
   }

   public static int countBets(String pred) {
      return parseBets(pred).size();
   }

   public static String reorderByHitRanks(String pred, List<HmCache.CompareDto> history) {
      if (StrUtil.isBlank(pred)) {
         return pred;
      } else {
         List<String> all = parseBets(pred);
         if (all.isEmpty()) {
            return pred;
         } else {
            List<String> front = parseBets(pickRecommendBets(pred, history));
            if (front.isEmpty()) {
               front = fillUniqueDigitSets(all, 10);
            }

            Set<String> used = new LinkedHashSet<>(front);
            List<String> ordered = new ArrayList<>(all.size());
            ordered.addAll(front);

            for (String b : all) {
               if (!used.contains(b)) {
                  ordered.add(b);
               }
            }

            return String.join(",", ordered);
         }
      }
   }

   public static String extractZuSanGroups(String pred) {
      if (StrUtil.isBlank(pred)) {
         return "";
      } else {
         Set<String> groups = new LinkedHashSet<>();

         for (String bet : parseBets(pred)) {
            if (bet.length() == 3) {
               int a = bet.charAt(0) - '0';
               int b = bet.charAt(1) - '0';
               int c = bet.charAt(2) - '0';
               if (isPairSet(a, b, c)) {
                  groups.add(sortedKey(a, b, c));
               }
            }
         }

         return String.join(",", groups);
      }
   }

   public static boolean isZuSanHit(String zuSanHm, String realHm) {
      if (!StrUtil.isBlank(zuSanHm) && !StrUtil.isBlank(realHm) && realHm.length() == 3) {
         int a = realHm.charAt(0) - '0';
         int b = realHm.charAt(1) - '0';
         int c = realHm.charAt(2) - '0';
         if (!isPairSet(a, b, c)) {
            return false;
         } else {
            String key = sortedKey(a, b, c);

            for (String g : zuSanHm.split(",")) {
               if (key.equals(g.trim())) {
                  return true;
               }
            }

            return false;
         }
      } else {
         return false;
      }
   }

   public static boolean isPairSet(int a, int b, int c) {
      return a == b && b != c || a == c && a != b || b == c && a != b;
   }

   public static String sortedKey(int a, int b, int c) {
      int[] x = new int[]{a, b, c};
      Arrays.sort(x);
      return "" + x[0] + x[1] + x[2];
   }

   static double[] scoreRanks(int predSize, List<HmCache.CompareDto> history) {
      return scoreRanksForStratified(predSize, history);
   }

   static double[] scoreRanks(int predSize, List<HmCache.CompareDto> history, int lookback, int denseBandWidth) {
      return scoreRanksForStratified(predSize, history);
   }

   static int[] discoverDenseBand(double[] smooth, int n) {
      return discoverDenseBand(smooth, n, 18);
   }

   static int[] discoverDenseBand(double[] smooth, int n, int denseBandWidth) {
      int width = Math.min(denseBandWidth > 0 ? denseBandWidth : 18, Math.max(10, n));
      if (n <= width) {
         return new int[]{1, n};
      } else {
         double bestSum = -1.0;
         int bestLo = 1;

         for (int lo = 1; lo + width - 1 <= n; lo++) {
            double sum = 0.0;

            for (int r = lo; r <= lo + width - 1; r++) {
               sum += smooth[r];
            }

            if (sum > bestSum) {
               bestSum = sum;
               bestLo = lo;
            }
         }

         return new int[]{bestLo, bestLo + width - 1};
      }
   }

   private static List<String> fillUniqueDigitSets(List<String> all, int pick) {
      List<String> selected = new ArrayList<>(pick);
      Set<String> used = new LinkedHashSet<>();

      for (String bet : all) {
         if (selected.size() >= pick) {
            break;
         }

         if (bet != null && bet.length() == 3) {
            String key = digitKey(bet);
            if (used.add(key)) {
               selected.add(bet);
            }
         }
      }

      return selected;
   }

   static int[] hitRankFreq(List<HmCache.CompareDto> history, int lookback) {
      int[] freq = new int[201];
      if (history == null) {
         return freq;
      } else {
         int end = history.size();
         int start = Math.max(0, end - lookback);

         for (int i = start; i < end; i++) {
            HmCache.CompareDto dto = history.get(i);
            if (dto != null && !StrUtil.isBlank(listForRank(dto)) && !StrUtil.isBlank(dto.getRealHm()) && dto.getRealHm().length() == 3) {
               int rank = indexOfBet(listForRank(dto), pad3(dto.getRealHm()));
               if (rank >= 1 && rank <= 200) {
                  freq[rank]++;
               }
            }
         }

         return freq;
      }
   }

   private static String lastRealHm(List<HmCache.CompareDto> history) {
      if (history == null) {
         return null;
      } else {
         for (int i = history.size() - 1; i >= 0; i--) {
            HmCache.CompareDto dto = history.get(i);
            if (dto != null && StrUtil.isNotBlank(dto.getRealHm()) && dto.getRealHm().length() == 3) {
               return pad3(dto.getRealHm());
            }
         }

         return null;
      }
   }

   static List<String> habitNearTickets(String lastReal, List<String> pool, List<HmCache.CompareDto> history) {
      LinkedHashSet<String> want = new LinkedHashSet<>();
      String last = pad3(lastReal);
      if (last.length() != 3) {
         return List.of();
      } else {
         addPerms(want, last);
         if (RECENT_GRP > 0 && history != null) {
            int got = 0;

            for (int i = history.size() - 2; i >= 0 && got < RECENT_GRP; i--) {
               HmCache.CompareDto dto = history.get(i);
               if (dto != null && !StrUtil.isBlank(dto.getRealHm()) && dto.getRealHm().length() == 3) {
                  addPerms(want, pad3(dto.getRealHm()));
                  got++;
               }
            }
         }

         for (int p = 0; p < 3; p++) {
            for (int delta : new int[]{1, 9}) {
               char[] n = last.toCharArray();
               n[p] = (char)(48 + (n[p] - '0' + delta) % 10);
               want.add(new String(n));
            }
         }

         Set<String> have = new LinkedHashSet<>(pool);
         List<String> out = new ArrayList<>();

         for (String w : want) {
            if (have.contains(w)) {
               out.add(w);
            }
         }

         return out;
      }
   }

   private static void addPerms(Set<String> want, String code) {
      if (code != null && code.length() == 3) {
         char[] c = code.toCharArray();
         want.add("" + c[0] + c[2] + c[1]);
         want.add("" + c[1] + c[0] + c[2]);
         want.add("" + c[1] + c[2] + c[0]);
         want.add("" + c[2] + c[0] + c[1]);
         want.add("" + c[2] + c[1] + c[0]);
      }
   }

   static double habitTicketBonus(String bet, String lastReal) {
      if (bet != null && bet.length() == 3 && lastReal != null && lastReal.length() == 3) {
         int[] t = new int[]{bet.charAt(0) - '0', bet.charAt(1) - '0', bet.charAt(2) - '0'};
         int[] last = new int[]{lastReal.charAt(0) - '0', lastReal.charAt(1) - '0', lastReal.charAt(2) - '0'};
         boolean[] lastSet = new boolean[10];
         lastSet[last[0]] = true;
         lastSet[last[1]] = true;
         lastSet[last[2]] = true;
         double s = 0.0;
         int chong = 0;

         for (int p = 0; p < 3; p++) {
            if (lastSet[t[p]]) {
               chong++;
            }

            if (t[p] == last[p]) {
               s += 2.4;
            }

            if (t[p] == (last[p] + 1) % 10 || t[p] == (last[p] + 9) % 10) {
               s++;
            }
         }

         s += chong * 1.6;
         int sum = t[0] + t[1] + t[2];
         if (sum >= 8 && sum <= 19) {
            s++;
         }

         if (t[0] == t[1] && t[1] == t[2]) {
            s -= 4.0;
         }

         return s;
      } else {
         return 0.0;
      }
   }

   private static String listForRank(HmCache.CompareDto dto) {
      if (dto == null) {
         return "";
      } else {
         return StrUtil.isNotBlank(dto.getAiFullHm()) ? dto.getAiFullHm() : dto.getAiHm();
      }
   }

   private static String digitKey(String code) {
      char[] c = code.toCharArray();
      Arrays.sort(c);
      return new String(c);
   }

   private static int indexOfBet(String pred, String real) {
      List<String> bets = parseBets(pred);
      String a = pad3(real);

      for (int i = 0; i < bets.size(); i++) {
         if (bets.get(i).equals(a)) {
            return i + 1;
         }
      }

      return -1;
   }

   private static List<String> parseBets(String pred) {
      List<String> list = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();
      if (StrUtil.isBlank(pred)) {
         return list;
      } else {
         for (String p : pred.split(",")) {
            String t = pad3(p.trim());
            if (t.length() == 3 && seen.add(t)) {
               list.add(t);
            }
         }

         return list;
      }
   }

   private static String pad3(String s) {
      if (s == null) {
         return "";
      } else {
         String t = s.trim();

         while (t.length() < 3) {
            t = "0" + t;
         }

         return t.length() > 3 ? t.substring(t.length() - 3) : t;
      }
   }

   public static String describeHitRanks(List<HmCache.CompareDto> history) {
      int[] q = allocateQuota(history, 200);
      int[] freq = hitRankFreq(history, 40);
      int[] segHits = new int[4];
      int total = 0;

      for (int r = 1; r <= 200; r++) {
         if (freq[r] > 0) {
            int seg = segmentOf(r);
            if (seg >= 0) {
               segHits[seg] += freq[r];
               total += freq[r];
            }
         }
      }

      return String.format(Locale.ROOT, "分层配额=%s 近%d期大底直中落段命中=%s/%d（条件转化选10注）", Arrays.toString(q), 40, Arrays.toString(segHits), total);
   }
}
