package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HitRateMetaTuner {
   private static final Logger log = LoggerFactory.getLogger(HitRateMetaTuner.class);
   static final int STREAK_LOOKBACK = 15;
   static final int SHORT_WINDOW = 8;
   static final int EXTREME_WINDOW = 12;

   private HitRateMetaTuner() {
   }

   public static HitRateMetaTuner.Snapshot analyze(List<HmCache.CompareDto> compares, boolean pl3) {
      if (compares != null && !compares.isEmpty()) {
         List<HitRateMetaTuner.Period> periods = toPeriods(compares);
         if (periods.isEmpty()) {
            return HitRateMetaTuner.Snapshot.neutral();
         } else {
            int missZx = tailMiss(periods, p -> p.hasTicket, p -> p.zx);
            int missGroup = tailMiss(periods, p -> p.hasTicket, p -> p.group);
            int missDingWei = tailMiss(periods, p -> p.hasDingWei, p -> p.dwFull);
            int[] missDwPos = new int[3];

            for (int pos = 0; pos < 3; pos++) {
               int p = pos;
               missDwPos[pos] = tailMiss(periods, x -> x.hasDingWei, x -> x.dwPos[p]);
            }

            int zxHits8 = countWhere(periods, 8, p -> p.hasTicket, p -> p.zx);
            int zxSamples8 = countWhere(periods, 8, p -> p.hasTicket, p -> true);
            int zxHits12 = countWhere(periods, 12, p -> p.hasTicket, p -> p.zx);
            int zxSamples12 = countWhere(periods, 12, p -> p.hasTicket, p -> true);
            int groupHits8 = countWhere(periods, 8, p -> p.hasTicket, p -> p.group);
            int dwHits8 = countWhere(periods, 8, p -> p.hasDingWei, p -> p.dwFull);
            int droughtLevel = droughtLevel(missZx, missGroup, missDingWei, zxHits8, zxSamples8, zxHits12, zxSamples12, groupHits8);
            HitRateMetaTuner.Snapshot snap = HitRateMetaTuner.Snapshot.fromSignals(droughtLevel, missZx, missGroup, missDingWei, missDwPos, pl3);
            log.info(
               "元调参: {} pl3={} win8 zx={}/{} grp={} dw={} win12 zx={}/{}",
               new Object[]{snap.describe(), pl3, zxHits8, zxSamples8, groupHits8, dwHits8, zxHits12, zxSamples12}
            );
            return snap;
         }
      } else {
         return HitRateMetaTuner.Snapshot.neutral();
      }
   }

   static int droughtLevel(int missZx, int missGroup, int missDw, int zxHits8, int zxSamples8, int zxHits12, int zxSamples12, int groupHits8) {
      if (missDw < 0) {
         return 0;
      } else {
         int level = 0;
         if (missZx >= 3 || missGroup >= 2) {
            level = 1;
         }

         if (missZx >= 5 || missGroup >= 4) {
            level = 2;
         }

         if (missZx >= 8 || missGroup >= 6) {
            level = 3;
         }

         if (zxSamples8 >= 6 && zxHits8 == 0) {
            level = Math.max(level, 2);
         }

         if (zxSamples8 >= 6 && zxHits8 <= 1 && groupHits8 == 0) {
            level = Math.max(level, 2);
         }

         if (zxSamples12 >= 10 && zxHits12 == 0) {
            level = Math.max(level, 3);
         }

         return level;
      }
   }

   private static int tailMiss(List<HitRateMetaTuner.Period> periods, Predicate<HitRateMetaTuner.Period> sample, Predicate<HitRateMetaTuner.Period> hit) {
      int miss = 0;
      int scanned = 0;

      for (int i = periods.size() - 1; i >= 0 && scanned < 15; i--) {
         HitRateMetaTuner.Period p = periods.get(i);
         if (sample.test(p)) {
            scanned++;
            if (hit.test(p)) {
               break;
            }

            miss++;
         }
      }

      return miss;
   }

   private static int countWhere(
      List<HitRateMetaTuner.Period> periods, int window, Predicate<HitRateMetaTuner.Period> sample, Predicate<HitRateMetaTuner.Period> hit
   ) {
      int n = 0;
      int scanned = 0;

      for (int i = periods.size() - 1; i >= 0 && scanned < window; i--) {
         HitRateMetaTuner.Period p = periods.get(i);
         if (sample.test(p)) {
            scanned++;
            if (hit.test(p)) {
               n++;
            }
         }
      }

      return n;
   }

   private static List<HitRateMetaTuner.Period> toPeriods(List<HmCache.CompareDto> compares) {
      List<HitRateMetaTuner.Period> out = new ArrayList<>();

      for (HmCache.CompareDto dto : compares) {
         if (dto != null && dto.getRealHm() != null && !dto.getRealHm().isBlank()) {
            String actual = pad3(dto.getRealHm());
            if (actual.length() == 3) {
               HitRateMetaTuner.Period p = new HitRateMetaTuner.Period();
               String tickets = ticketList(dto);
               if (tickets != null && !tickets.isBlank()) {
                  p.hasTicket = true;
                  boolean[] hits = ticketHits(tickets, actual);
                  p.zx = hits[0];
                  p.group = hits[1];
               }

               if (dto.getAiDingWeiHm() != null && !dto.getAiDingWeiHm().isBlank() && RuleBasedDingWeiUtils.parseParts(dto.getAiDingWeiHm()) != null) {
                  boolean[] pos = dingWeiPosHits(dto.getAiDingWeiHm(), actual);
                  p.hasDingWei = true;
                  p.dwPos = pos;
                  p.dwFull = pos[0] && pos[1] && pos[2];
               }

               if (p.hasTicket || p.hasDingWei) {
                  out.add(p);
               }
            }
         }
      }

      return out;
   }

   private static String ticketList(HmCache.CompareDto dto) {
      return dto.getAiFullHm() != null && !dto.getAiFullHm().isBlank() ? dto.getAiFullHm() : dto.getAiHm();
   }

   private static boolean[] ticketHits(String pred, String actual) {
      boolean zx = false;
      boolean group = false;
      char[] ak = actual.toCharArray();
      Arrays.sort(ak);
      String aKey = new String(ak);

      for (String part : pred.split(",")) {
         String t = pad3(part.trim());
         if (t.length() == 3) {
            if (t.equals(actual)) {
               zx = true;
               group = true;
               break;
            }

            char[] ck = t.toCharArray();
            Arrays.sort(ck);
            if (new String(ck).equals(aKey)) {
               group = true;
            }
         }
      }

      return new boolean[]{zx, group};
   }

   private static boolean[] dingWeiPosHits(String dingWei, String actual) {
      boolean[] hit = new boolean[3];
      String[] parts = RuleBasedDingWeiUtils.parseParts(dingWei);
      if (parts != null && actual != null && actual.length() == 3) {
         for (int pos = 0; pos < 3; pos++) {
            char target = actual.charAt(pos);

            for (String d : parts[pos].split(",")) {
               String s = d.trim();
               if (s.length() == 1 && s.charAt(0) == target) {
                  hit[pos] = true;
                  break;
               }
            }
         }

         return hit;
      } else {
         return hit;
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

         if (t.length() > 3) {
            t = t.substring(t.length() - 3);
         }

         return t;
      }
   }

   private static final class Period {
      boolean hasTicket;
      boolean hasDingWei;
      boolean zx;
      boolean group;
      boolean dwFull;
      boolean[] dwPos = new boolean[3];
   }

   public static final class Snapshot {
      public final int droughtLevel;
      public final int missZx;
      public final int missGroup;
      public final int missDingWei;
      public final int rankBandLoDelta;
      public final int rankBandHiDelta;
      public final int groupUniqueBoost;
      public final int pairQuotaBoost;
      public final int permExpandBoost;
      public final int pl3ScatterBoost;
      public final int pl3ExpandBoost;
      public final int overfitInject;
      public final int extendMode;
      public final int[] missDwPos;
      public final double softNeighMul;
      public final double softOmitMul;
      public final int[] dwBandLoDelta;
      public final int[] dwBandHiDelta;

      private Snapshot(
         int droughtLevel,
         int missZx,
         int missGroup,
         int missDingWei,
         int rankBandLoDelta,
         int rankBandHiDelta,
         int groupUniqueBoost,
         int pairQuotaBoost,
         int permExpandBoost,
         int pl3ScatterBoost,
         int pl3ExpandBoost,
         int overfitInject,
         int extendMode,
         int[] missDwPos,
         double softNeighMul,
         double softOmitMul,
         int[] dwBandLoDelta,
         int[] dwBandHiDelta
      ) {
         this.droughtLevel = droughtLevel;
         this.missZx = missZx;
         this.missGroup = missGroup;
         this.missDingWei = missDingWei;
         this.rankBandLoDelta = rankBandLoDelta;
         this.rankBandHiDelta = rankBandHiDelta;
         this.groupUniqueBoost = groupUniqueBoost;
         this.pairQuotaBoost = pairQuotaBoost;
         this.permExpandBoost = permExpandBoost;
         this.pl3ScatterBoost = pl3ScatterBoost;
         this.pl3ExpandBoost = pl3ExpandBoost;
         this.overfitInject = overfitInject;
         this.extendMode = extendMode;
         this.missDwPos = missDwPos == null ? new int[3] : missDwPos;
         this.softNeighMul = softNeighMul;
         this.softOmitMul = softOmitMul;
         this.dwBandLoDelta = dwBandLoDelta;
         this.dwBandHiDelta = dwBandHiDelta;
      }

      static HitRateMetaTuner.Snapshot neutral() {
         return new HitRateMetaTuner.Snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, new int[3], 1.0, 1.0, new int[3], new int[3]);
      }

      static HitRateMetaTuner.Snapshot fromSignals(int level, int missZx, int missGroup, int missDw, int[] missDwPos, boolean pl3) {
         int rankLo = 0;
         int rankHi = 0;
         if (level >= 2) {
            rankHi = 1;
         }

         if (level >= 3) {
            rankLo = -1;
            rankHi = 1;
         }

         int groupBoost = 0;
         int pairBoost = 0;
         int permBoost = 0;
         int scatter = 0;
         int expand = 0;
         int inject = 0;
         if (level >= 1) {
            groupBoost = pl3 ? 6 : 4;
            if (missGroup >= 2) {
               pairBoost = pl3 ? 2 : 3;
            }

            if (missZx >= 3) {
               permBoost = pl3 ? 4 : 6;
            }
         }

         if (level >= 2) {
            groupBoost = pl3 ? 10 : 8;
            pairBoost = Math.max(pairBoost, pl3 ? 2 : 4);
            permBoost = Math.max(permBoost, pl3 ? 6 : 8);
            if (pl3) {
               scatter = 6;
               expand = 4;
            }
         }

         if (level >= 3) {
            groupBoost = pl3 ? 16 : 12;
            pairBoost = Math.max(pairBoost, pl3 ? 4 : 6);
            permBoost = Math.max(permBoost, pl3 ? 8 : 10);
            if (pl3) {
               scatter = 8;
               expand = 6;
            }

            inject = pl3 ? 6 : 4;
            if (missZx >= 10) {
               inject += 2;
            }
         }

         double neighMul = 1.0 + 0.15 * level;
         double omitMul = 1.0 + 0.1 * level;
         int extendMode = 0;
         boolean zxDry = pl3 ? missZx >= 2 : missZx >= 3;
         if (missGroup >= 2) {
            extendMode = 2;
         } else if (zxDry) {
            extendMode = 1;
         }

         int[] dwLo = new int[3];
         int[] dwHi = new int[3];

         for (int p = 0; p < 3; p++) {
            int posMiss = missDwPos == null ? 0 : missDwPos[p];
            if (posMiss >= 2 || level >= 2) {
               dwHi[p] = 1;
            }

            if (posMiss >= 4 || level >= 3) {
               dwLo[p] = -1;
               dwHi[p] = 1;
            }
         }

         int[] dwMiss = missDwPos == null ? new int[3] : Arrays.copyOf(missDwPos, 3);
         return new HitRateMetaTuner.Snapshot(
            level,
            missZx,
            missGroup,
            missDw,
            rankLo,
            rankHi,
            groupBoost,
            pairBoost,
            permBoost,
            scatter,
            expand,
            inject,
            extendMode,
            dwMiss,
            neighMul,
            omitMul,
            dwLo,
            dwHi
         );
      }

      public String describe() {
         return String.format(
            "drought=L%d missZx=%d missGrp=%d missDw=%d extendMode=%d bandΔ=%+d/%+d quota(+g%d +p%d +e%d) of=%d pl3(+s%d +e%d) dwMiss=%s dwBand=%s/%s soft=%.2f/%.2f",
            this.droughtLevel,
            this.missZx,
            this.missGroup,
            this.missDingWei,
            this.extendMode,
            this.rankBandLoDelta,
            this.rankBandHiDelta,
            this.groupUniqueBoost,
            this.pairQuotaBoost,
            this.permExpandBoost,
            this.overfitInject,
            this.pl3ScatterBoost,
            this.pl3ExpandBoost,
            Arrays.toString(this.missDwPos),
            Arrays.toString(this.dwBandLoDelta),
            Arrays.toString(this.dwBandHiDelta),
            this.softNeighMul,
            this.softOmitMul
         );
      }
   }
}
