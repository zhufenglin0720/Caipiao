package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RuleBasedDanMaUtils {
   private static final Logger log = LoggerFactory.getLogger(RuleBasedDanMaUtils.class);
   public static final int PER_POS = 3;
   public static final int SWITCH_META = 50;
   public static final int FIT_VAL = 40;
   private static final int[] FREQ_WINDOWS = new int[]{10, 12, 15, 18, 22, 25, 30, 35, 40, 50, 60};
   private static final int MIN_HISTORY = 30;
   private static final Pattern POS_PAT = Pattern.compile("([百十个])位[:：]([0-9,]+)");

   private RuleBasedDanMaUtils() {
   }

   public static String get3dDanMa() {
      return predict(HmCache.getSdCache(), HmCache.getSdCompareCache(), RuleBasedDanMaUtils.GameKind.SD_3D);
   }

   public static String getPl3DanMa() {
      return predict(HmCache.getPl3Cache(), HmCache.getPl3CompareCache(), RuleBasedDanMaUtils.GameKind.PL3);
   }

   public static String predict(List<Hm> history, List<HmCache.CompareDto> compares, RuleBasedDanMaUtils.GameKind kind) {
      if (history != null && history.size() >= 30) {
         int[][] pick = pickFor(kind, history, compares);
         String out = formatMulti(pick);
         log.info("胆码预测[{}]: {} | mode={}", new Object[]{kind, out, kind == RuleBasedDanMaUtils.GameKind.PL3 ? "habit" : "legacy-fit"});
         return out;
      } else {
         return "";
      }
   }

   static int[][] pickFor(RuleBasedDanMaUtils.GameKind kind, List<Hm> history, List<HmCache.CompareDto> compares) {
      return kind == RuleBasedDanMaUtils.GameKind.PL3 ? pickHabit(history, compares) : pickPositional(history);
   }

   static int[][] adaptCoverMulti(List<Hm> history, int ignoredVal) {
      return pickPositional(history);
   }

   static int[][] adaptCoverMulti(List<Hm> history, RuleBasedDanMaUtils.GameKind kind) {
      return pickFor(kind == null ? RuleBasedDanMaUtils.GameKind.SD_3D : kind, history, null);
   }

   static int[] adaptCover(List<Hm> history, int val) {
      int[][] m = adaptCoverMulti(history, val);
      return new int[]{m[0][0], m[1][0], m[2][0]};
   }

   static int[][] pickPositional(List<Hm> history) {
      if (history.size() < 120) {
         return pickBase(history);
      } else {
         int n = history.size();
         int from = Math.max(70, n - 50);
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
   }

   static int[][] pickHabit(List<Hm> history, List<HmCache.CompareDto> compares) {
      int[][] digits = toDigits(history);
      DrawHabit habit = DrawHabit.of(digits);
      int[][] prev = lastDanMaPick(compares);
      int[][] out = new int[3][3];

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
         s[d] += (d * 7 + pos * 3 + n) % 10 * 0.08;
      }

      if (prevPick != null) {
         for (int d : prevPick) {
            if (d >= 0 && d <= 9 && d != lastD) {
               s[d] -= 4.5;
            }
         }
      }

      s[lastD] += 8.0;
      int nbHi = habit.freq8[pos][(lastD + 1) % 10] >= habit.freq8[pos][(lastD + 9) % 10] ? (lastD + 1) % 10 : (lastD + 9) % 10;
      s[nbHi] += 5.0;
      int[] top = topKDouble(s, 3);
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
         if (!used[d]) {
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
      }

      return best < 0 ? (keep[2] + 1) % 10 : best;
   }

   private static boolean sameSet(int[] a, int[] b) {
      if (a != null && b != null && a.length == b.length) {
         int[] x = Arrays.copyOf(a, a.length);
         int[] y = Arrays.copyOf(b, b.length);
         Arrays.sort(x);
         Arrays.sort(y);
         return Arrays.equals(x, y);
      } else {
         return false;
      }
   }

   private static int[][] lastDanMaPick(List<HmCache.CompareDto> compares) {
      if (compares != null && !compares.isEmpty()) {
         for (int i = compares.size() - 1; i >= 0; i--) {
            HmCache.CompareDto dto = compares.get(i);
            if (dto != null && dto.getAiDanMaHm() != null && !dto.getAiDanMaHm().isBlank()) {
               return parseMulti(dto.getAiDanMaHm());
            }
         }

         return null;
      } else {
         return null;
      }
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
      int[][] out = new int[3][3];

      for (int pos = 0; pos < 3; pos++) {
         out[pos] = topKDouble(scorePositionPositional(history, pos), 3);
      }

      return out;
   }

   static int[][] pickFitFreq(List<Hm> history) {
      int[][] out = new int[3][3];

      for (int pos = 0; pos < 3; pos++) {
         out[pos] = fitFreqWindow(history, pos);
      }

      return out;
   }

   static int[] fitFreqWindow(List<Hm> history, int pos) {
      int n = history.size();
      int from = Math.max(1, n - 40);
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

         for (int newStart = Math.max(0, t + 1 - w); winStart < newStart; winStart++) {
            freq[digitsOf(history.get(winStart).toString())[pos]]--;
         }
      }

      return hits;
   }

   private static int[] topKFromFreq(int[] freq) {
      double[] s = new double[10];

      for (int d = 0; d < 10; d++) {
         s[d] = freq[d];
      }

      return topKDouble(s, 3);
   }

   static int[] topKFreq(List<Hm> history, int pos, int w) {
      int[] freq = new int[10];

      for (Hm x : tail(history, w)) {
         freq[digitsOf(x.toString())[pos]]++;
      }

      return topKFromFreq(freq);
   }

   static double[] scorePositionPositional(List<Hm> history, int pos) {
      double[] s = new double[10];
      int[][] windows = new int[][]{{12, 2}, {25, 3}, {40, 2}, {60, 1}};

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

      double mean = 0.0;

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

      s[(lastD + 1) % 10] = s[(lastD + 1) % 10] + 0.35;
      s[(lastD + 9) % 10] = s[(lastD + 9) % 10] + 0.35;
      return s;
   }

   private static List<Hm> tail(List<Hm> list, int n) {
      return list.size() <= n ? list : list.subList(list.size() - n, list.size());
   }

   static String formatMulti(int[][] pick) {
      return String.format(Locale.ROOT, "百位:%s 十位:%s 个位:%s", join(pick[0]), join(pick[1]), join(pick[2]));
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
      if (answer != null && !answer.isBlank()) {
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
            if (pos >= 0) {
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
         }

         return out[0] != null && out[1] != null && out[2] != null ? out : null;
      } else {
         return null;
      }
   }

   public static int[] parseDigits(String answer) {
      int[][] m = parseMulti(answer);
      return m == null ? null : new int[]{m[0][0], m[1][0], m[2][0]};
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
      if (pick != null && actual != null && pick.length >= 3 && actual.length >= 3) {
         for (int p = 0; p < 3; p++) {
            if (posContains(pick[p], actual[p])) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   @Deprecated
   public static boolean isUnionHit(String danMa, String realHm) {
      return isAnyPosHit(danMa, realHm);
   }

   @Deprecated
   public static boolean isUnionHit(int[] pick, int[] actual) {
      if (pick != null && actual != null) {
         for (int p = 0; p < 3 && p < pick.length && p < actual.length; p++) {
            if (pick[p] == actual[p]) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public static boolean[] posHits(String danMa, String realHm) {
      int[][] m = parseMulti(danMa);
      if (m != null && realHm != null && realHm.length() >= 3) {
         int[] act = digitsOf(realHm);
         return new boolean[]{posContains(m[0], act[0]), posContains(m[1], act[1]), posContains(m[2], act[2])};
      } else {
         return null;
      }
   }

   public static boolean[] posHits(int[][] pick, int[] actual) {
      return pick != null && actual != null
         ? new boolean[]{posContains(pick[0], actual[0]), posContains(pick[1], actual[1]), posContains(pick[2], actual[2])}
         : null;
   }

   private static boolean posContains(int[] cands, int digit) {
      if (cands == null) {
         return false;
      } else {
         for (int v : cands) {
            if (v == digit) {
               return true;
            }
         }

         return false;
      }
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
      } else {
         String t = s.trim();
         return t.length() >= 3 ? t.substring(t.length() - 3) : "0".repeat(3 - t.length()) + t;
      }
   }

   public static enum GameKind {
      SD_3D,
      PL3;
   }
}
