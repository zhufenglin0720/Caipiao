package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RuleBasedPredictUtils {
   private static final Logger log = LoggerFactory.getLogger(RuleBasedPredictUtils.class);
   private static final int MAX_BET_LIMIT = 500;
   private static final int CORE_BET = 200;
   static int BET_LIMIT_OVERRIDE = 0;
   private static int MIN_BET = 60;
   private static int TARGET_BET = 200;
   private static final int TOP_N = 8;
   private static int GROUP_DIGIT_POOL = 10;
   private static int RANK_BAND_LO = 3;
   private static int RANK_BAND_HI = 8;
   private static int GROUP_UNIQUE_TARGET = 50;
   private static int PAIR_GROUP_QUOTA = 28;
   private static int PERM_EXPAND_GROUPS = 40;
   private static final int SHAPE_PROB_WINDOW = 20;
   private static boolean PREFER_PAIR_EXPAND = true;
   private static RuleBasedPredictUtils.GameKind CURRENT_KIND = RuleBasedPredictUtils.GameKind.SD_3D;
   static int TUNE_SCATTER = 0;
   static int TUNE_EXPAND = 0;
   static volatile boolean ENABLE_NEIGHBOR_INJECT = true;

   private RuleBasedPredictUtils() {
   }

   public static int maxBetLimit() {
      return BET_LIMIT_OVERRIDE > 0 ? BET_LIMIT_OVERRIDE : 500;
   }

   public static void setBetLimitOverride(int limit) {
      BET_LIMIT_OVERRIDE = limit;
   }

   private static int coreBetLimit() {
      return Math.min(200, maxBetLimit());
   }

   public static String get3dPredict() {
      CURRENT_KIND = RuleBasedPredictUtils.GameKind.SD_3D;
      String overfit = Overfit20PredictUtils.get3dPool();
      return predict(HmCache.getSdCache(), HmCache.getSdCompareCache(), RuleBasedPredictUtils.GameKind.SD_3D, overfit);
   }

   public static String getPl3Predict() {
      CURRENT_KIND = RuleBasedPredictUtils.GameKind.PL3;
      String overfit = Overfit20PredictUtils.getPl3Pool();
      return predict(HmCache.getPl3Cache(), HmCache.getPl3CompareCache(), RuleBasedPredictUtils.GameKind.PL3, overfit);
   }

   public static String predict(List<Hm> history, List<HmCache.CompareDto> compares) {
      return predict(history, compares, RuleBasedPredictUtils.GameKind.SD_3D, null);
   }

   public static String predict(List<Hm> history, List<HmCache.CompareDto> compares, RuleBasedPredictUtils.GameKind kind) {
      return predict(history, compares, kind, null);
   }

   public static String predict(List<Hm> history, List<HmCache.CompareDto> compares, RuleBasedPredictUtils.GameKind kind, String overfitPool) {
      if (history != null && history.size() >= 20) {
         applyGameProfile(kind == null ? RuleBasedPredictUtils.GameKind.SD_3D : kind);
         boolean pl3 = CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3;
         List<HmCache.CompareDto> streakCompares = mergeDadiTickets(compares, pl3);
         HitRateMetaTuner.Snapshot meta = HitRateMetaTuner.analyze(streakCompares, pl3);
         List<HmCache.CompareDto> biasCompares = compares;
         if (pl3) {
            compares = null;
         }

         int[][] digits = toDigitMatrix(history);
         RuleBasedPredictUtils.ShapeProb shapeProb = shapeProb(digits);
         log.info(
            "{} 走势：组三倾向={} 组六倾向={} (近窗组三{}% 组六{}%)", new Object[]{CURRENT_KIND, shapeProb.pairScore, shapeProb.zu6Score, shapeProb.pairPct, shapeProb.zu6Pct}
         );
         logBiasDiagnostics(compares);
         RecentFeatureStats feat = RecentFeatureStats.of(digits);
         DrawHabit habit = DrawHabit.of(digits);
         BiasSeedCorrector seeds = BiasSeedCorrector.of(biasCompares);
         HitRankStats hitRank = HitRankStats.of(digits);
         if (pl3) {
            RANK_BAND_LO = Math.min(hitRank.bandLo, RANK_BAND_LO);
            RANK_BAND_HI = Math.max(hitRank.bandHi, RANK_BAND_HI);
            RANK_BAND_LO = Math.max(1, Math.min(RANK_BAND_LO, 4));
            RANK_BAND_HI = Math.min(10, Math.max(RANK_BAND_HI, 7));
         } else {
            RANK_BAND_LO = Math.max(hitRank.bandLo, RANK_BAND_LO);
            RANK_BAND_HI = Math.min(hitRank.bandHi, RANK_BAND_HI);
         }

         RANK_BAND_LO = Math.max(1, RANK_BAND_LO + meta.rankBandLoDelta);
         RANK_BAND_HI = Math.min(10, RANK_BAND_HI + meta.rankBandHiDelta);
         if (RANK_BAND_LO > RANK_BAND_HI) {
            RANK_BAND_LO = hitRank.bandLo;
            RANK_BAND_HI = hitRank.bandHi;
         }

         log.info("偏差纠偏: {}", seeds.describe());
         log.info("{} {}", CURRENT_KIND, hitRank.describe());
         int[][] scores = new int[3][10];
         int[][] topPos = new int[3][8];

         for (int pos = 0; pos < 3; pos++) {
            scores[pos] = scoreAllDigits(digits, pos, compares, feat);

            for (int d = 0; d < 10; d++) {
               scores[pos][d] = scores[pos][d] + habit.posBonus(pos, d);
            }

            topPos[pos] = buildBandAwareTop(scores[pos], 8);
            topPos[pos] = applyBiasSeedToTop(topPos[pos], pos, seeds, scores[pos]);
            log.info("位置{} 名次池{}(命中带{}-{})={}", new Object[]{posName(pos), 8, RANK_BAND_LO, RANK_BAND_HI, Arrays.toString(topPos[pos])});
         }

         seeds.boostScores(scores);
         applyTrendBoost(digits, scores, shapeProb);
         adjustQuotasByShape(shapeProb);
         applyMissStreakQuotaBoost(streakCompares);
         applyMetaQuotaBoost(meta);
         List<int[]> selected = selectGroupFirst(digits, scores, topPos, feat, seeds);
         if (selected == null || selected.size() < MIN_BET) {
            List<int[]> pool = buildLoosePool(topPos, scores, feat);
            selected = takeTopUnique(pool, TARGET_BET);
         }

         if (selected != null && selected.size() >= MIN_BET) {
            selected = applyBiasCorrectTickets(selected, scores, feat, seeds);
            if (pl3) {
               if (TUNE_SCATTER <= 0) {
                  TUNE_SCATTER = 70 + meta.pl3ScatterBoost;
               }

               if (TUNE_EXPAND <= 0) {
                  TUNE_EXPAND = 22 + meta.pl3ExpandBoost;
               }

               selected = fillWithTopGroupPerms(selected, scores, feat);
               int needGroups = TARGET_BET >= 200 ? 110 + Math.min(20, meta.groupUniqueBoost / 2) : 95;
               selected = ensureGroupCoverageKeepPerms(selected, scores, feat, needGroups, 2);
               TUNE_SCATTER = 0;
               TUNE_EXPAND = 0;
            }

            if (selected.size() > TARGET_BET) {
               selected = new ArrayList<>(selected.subList(0, TARGET_BET));
            }

            int neighborCap = CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 ? 12 : 8;
            selected = injectSinglePosNeighbors(selected, scores, feat, overfitPool, neighborCap);
            selected = rerankPromoteEarlierHits(selected, feat, overfitPool);
            if (meta.overfitInject > 0 && overfitPool != null && !overfitPool.isBlank()) {
               selected = injectOverfitTickets(selected, overfitPool, meta.overfitInject);
               log.info("{} 过拟合注入{}注 → 大底共{}注 drought=L{}", new Object[]{CURRENT_KIND, meta.overfitInject, selected.size(), meta.droughtLevel});
            }

            int fullLimit = maxBetLimit();
            if (fullLimit > selected.size()) {
               int before = selected.size();
               selected = extendCoverage(selected, scores, digits, feat, fullLimit, meta.extendMode);
               log.info("{} 大底扩展 {}→{} extendMode={}（0稳定/1直选连挂补排列/2组选连挂换新组）", new Object[]{CURRENT_KIND, before, selected.size(), meta.extendMode});
            }

            StringBuilder sb = new StringBuilder();
            int n = Math.min(fullLimit, selected.size());

            for (int i = 0; i < n; i++) {
               if (i > 0) {
                  sb.append(',');
               }

               int[] t = selected.get(i);
               sb.append(t[0]).append(t[1]).append(t[2]);
            }

            String result = sb.toString();
            log.info("{} 规则预测结果(≤{}注) meta={}", new Object[]{CURRENT_KIND, n, meta.describe()});
            return result;
         } else {
            log.error("规则预测无法凑齐{}注", MIN_BET);
            return null;
         }
      } else {
         log.warn("历史数据不足，无法规则预测，size={}", history == null ? 0 : history.size());
         return null;
      }
   }

   private static List<int[]> rerankPromoteEarlierHits(List<int[]> selected, RecentFeatureStats feat, String overfitPool) {
      if (selected != null && !selected.isEmpty()) {
         Set<String> of = new LinkedHashSet<>();
         if (overfitPool != null && !overfitPool.isBlank()) {
            for (int[] t : parseTicketList(overfitPool)) {
               of.add("" + t[0] + t[1] + t[2]);
            }
         }

         Map<String, Integer> nextFullW = new HashMap<>();

         for (int i = 0; i < feat.nextFullCount; i++) {
            int[] nf = feat.nextFullCodes[i];
            nextFullW.put("" + nf[0] + nf[1] + nf[2], nf[3]);
         }

         int OF_SHIFT = 90;
         int NF_SHIFT = 45;
         List<int[]> keyed = new ArrayList<>(selected.size());
         int ofHit = 0;
         int nfHit = 0;

         for (int i = 0; i < selected.size(); i++) {
            int[] t = selected.get(i);
            String key = "" + t[0] + t[1] + t[2];
            int shift = 0;
            if (of.contains(key)) {
               shift += 90;
               ofHit++;
            }

            if (nextFullW.containsKey(key)) {
               shift += 45;
               nfHit++;
            }

            keyed.add(new int[]{t[0], t[1], t[2], i - shift, i});
         }

         keyed.sort((a, b) -> {
            int c = Integer.compare(a[3], b[3]);
            return c != 0 ? c : Integer.compare(a[4], b[4]);
         });
         List<int[]> out = new ArrayList<>(keyed.size());

         for (int[] s : keyed) {
            out.add(new int[]{s[0], s[1], s[2]});
         }

         log.info("位次前移: n={} overfit相交={} 转移全号={} shift={}/{}", new Object[]{out.size(), ofHit, nfHit, 90, 45});
         return out;
      } else {
         return selected;
      }
   }

   private static List<int[]> injectSinglePosNeighbors(List<int[]> selected, int[][] scores, RecentFeatureStats feat, String overfitPool, int maxInject) {
      if (ENABLE_NEIGHBOR_INJECT && selected != null && !selected.isEmpty() && maxInject > 0) {
         LinkedHashSet<String> have = new LinkedHashSet<>();

         for (int[] t : selected) {
            have.add(ticketKey(t));
         }

         Set<String> ofSeeds = new LinkedHashSet<>();
         List<int[]> ofList = new ArrayList<>();
         if (overfitPool != null && !overfitPool.isBlank()) {
            for (int[] t : parseTicketList(overfitPool)) {
               String k = ticketKey(t);
               ofSeeds.add(k);
               ofList.add(t);
            }
         }

         Map<String, int[]> cands = new HashMap<>();

         for (int[] seed : ofList) {
            addNeighborCands(cands, have, seed, 220, scores, feat);
         }

         int[][] bands = CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 ? new int[][]{{55, 130}} : new int[][]{{25, 55}, {100, 155}};
         int bandSeedN = 0;

         for (int[] band : bands) {
            int from = Math.max(0, band[0] - 1);
            int to = Math.min(selected.size(), band[1]);
            int mid = (band[0] + band[1]) / 2;

            for (int i = from; i < to; i++) {
               int[] seed = selected.get(i);
               if (!ofSeeds.contains(ticketKey(seed))) {
                  int rank1 = i + 1;
                  int w = 75 + Math.max(0, 35 - Math.abs(rank1 - mid) / 2);
                  if (ticketScore(seed, scores, feat) < 0) {
                     w = Math.max(20, w / 2);
                  }

                  addNeighborCands(cands, have, seed, w, scores, feat);
                  bandSeedN++;
               }
            }
         }

         log.info(
            "邻号种子带: {} ofSeeds={} bandSeeds={}",
            new Object[]{CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 ? "55-130" : "25-55∪100-155", ofList.size(), bandSeedN}
         );
         if (cands.isEmpty()) {
            return selected;
         } else {
            List<int[]> ranked = new ArrayList<>(cands.values());
            ranked.sort((a, b) -> Integer.compare(b[3], a[3]));
            int take = Math.min(maxInject, ranked.size());
            if (take <= 0) {
               return selected;
            } else {
               List<int[]> out = new ArrayList<>(selected);
               int protectHead = Math.min(out.size(), CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 ? 40 : 60);
               int[] weakScore = new int[out.size()];

               for (int j = 0; j < out.size(); j++) {
                  weakScore[j] = ticketScore(out.get(j), scores, feat);
               }

               int replaced = 0;

               for (int ix = 0; ix < take; ix++) {
                  int[] n = ranked.get(ix);
                  String nk = ticketKey(n);
                  if (!have.contains(nk)) {
                     int victim = -1;
                     int victimSc = Integer.MAX_VALUE;

                     for (int j = protectHead; j < out.size(); j++) {
                        String vk = ticketKey(out.get(j));
                        if (!ofSeeds.contains(vk) && weakScore[j] < victimSc) {
                           victimSc = weakScore[j];
                           victim = j;
                        }
                     }

                     if (victim < 0) {
                        break;
                     }

                     have.remove(ticketKey(out.get(victim)));
                     out.set(victim, new int[]{n[0], n[1], n[2]});
                     have.add(nk);
                     weakScore[victim] = ticketScore(out.get(victim), scores, feat);
                     replaced++;
                  }
               }

               if (out.size() > TARGET_BET) {
                  out = new ArrayList<>(out.subList(0, TARGET_BET));
               }

               log.info("单位置±1邻号注入: 候选={} 替换={} cap={} protectHead={}", new Object[]{cands.size(), replaced, maxInject, protectHead});
               return out;
            }
         }
      } else {
         return selected;
      }
   }

   private static void addNeighborCands(Map<String, int[]> cands, Set<String> have, int[] seed, int seedWeight, int[][] scores, RecentFeatureStats feat) {
      for (int[] n : singlePosPlusMinus1Neighbors(seed)) {
         String nk = ticketKey(n);
         if (!have.contains(nk)) {
            int sc = seedWeight + ticketScore(n, scores, feat) / 4;

            for (int pos = 0; pos < 3; pos++) {
               if (n[pos] != seed[pos]) {
                  sc += scores[pos][n[pos]] / 3;
                  sc += feat.digitBonus(pos, n[pos]);
               }
            }

            int[] old = cands.get(nk);
            if (old != null && sc <= old[3]) {
               old[3] = Math.min(9999, old[3] + Math.max(6, seedWeight / 5));
            } else {
               cands.put(nk, new int[]{n[0], n[1], n[2], sc});
            }
         }
      }
   }

   static List<int[]> singlePosPlusMinus1Neighbors(int[] t) {
      List<int[]> out = new ArrayList<>(6);

      for (int pos = 0; pos < 3; pos++) {
         for (int delta : new int[]{1, 9}) {
            int[] n = new int[]{t[0], t[1], t[2]};
            n[pos] = (n[pos] + delta) % 10;
            out.add(n);
         }
      }

      return out;
   }

   private static String ticketKey(int[] t) {
      return "" + t[0] + t[1] + t[2];
   }

   private static void applyMetaQuotaBoost(HitRateMetaTuner.Snapshot meta) {
      if (meta != null) {
         if (meta.groupUniqueBoost > 0 || meta.pairQuotaBoost > 0 || meta.permExpandBoost > 0) {
            int groupCap = CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 ? 140 : 115;
            int pairCap = CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 ? 32 : 50;
            int permCap = CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 ? 64 : 75;
            GROUP_UNIQUE_TARGET = Math.min(groupCap, GROUP_UNIQUE_TARGET + meta.groupUniqueBoost);
            PAIR_GROUP_QUOTA = Math.min(pairCap, PAIR_GROUP_QUOTA + meta.pairQuotaBoost);
            PERM_EXPAND_GROUPS = Math.min(permCap, PERM_EXPAND_GROUPS + meta.permExpandBoost);
            log.info("元调参配额 → 散组={} 组三={} 排列展开={} drought=L{}", new Object[]{GROUP_UNIQUE_TARGET, PAIR_GROUP_QUOTA, PERM_EXPAND_GROUPS, meta.droughtLevel});
         }
      }
   }

   private static List<int[]> injectOverfitTickets(List<int[]> selected, String overfitPool, int inject) {
      if (selected != null && !selected.isEmpty() && inject > 0) {
         List<int[]> of = parseTicketList(overfitPool);
         if (of.isEmpty()) {
            return selected;
         } else {
            LinkedHashSet<String> used = new LinkedHashSet<>();
            List<int[]> out = new ArrayList<>(TARGET_BET);

            for (int[] t : selected) {
               String key = "" + t[0] + t[1] + t[2];
               if (used.add(key)) {
                  out.add(t);
               }
            }

            int taken = 0;

            for (int[] tx : of) {
               if (taken >= inject) {
                  break;
               }

               String key = "" + tx[0] + tx[1] + tx[2];
               if (used.add(key)) {
                  if (out.size() >= TARGET_BET) {
                     out.remove(out.size() - 1);
                  }

                  out.add(tx);
                  taken++;
               }
            }

            return (List<int[]>)(out.size() > TARGET_BET ? new ArrayList<>(out.subList(0, TARGET_BET)) : out);
         }
      } else {
         return selected;
      }
   }

   private static List<int[]> parseTicketList(String csv) {
      List<int[]> list = new ArrayList<>();
      if (csv != null && !csv.isBlank()) {
         for (String p : csv.split(",")) {
            String t = p.trim();

            while (t.length() < 3) {
               t = "0" + t;
            }

            if (t.length() > 3) {
               t = t.substring(t.length() - 3);
            }

            if (t.length() == 3) {
               int a = t.charAt(0) - '0';
               int b = t.charAt(1) - '0';
               int c = t.charAt(2) - '0';
               if (a >= 0 && a <= 9 && b >= 0 && b <= 9 && c >= 0 && c <= 9) {
                  list.add(new int[]{a, b, c});
               }
            }
         }

         return list;
      } else {
         return list;
      }
   }

   private static void applyGameProfile(RuleBasedPredictUtils.GameKind kind) {
      CURRENT_KIND = kind;
      TARGET_BET = coreBetLimit();
      MIN_BET = Math.min(80, TARGET_BET);
      GROUP_DIGIT_POOL = 10;
      if (kind == RuleBasedPredictUtils.GameKind.SD_3D) {
         RANK_BAND_LO = 3;
         RANK_BAND_HI = 9;
         GROUP_UNIQUE_TARGET = TARGET_BET >= 200 ? 90 : 75;
         PAIR_GROUP_QUOTA = TARGET_BET >= 200 ? 34 : 28;
         PERM_EXPAND_GROUPS = TARGET_BET >= 200 ? 36 : 28;
         PREFER_PAIR_EXPAND = true;
      } else {
         RANK_BAND_LO = 2;
         RANK_BAND_HI = 9;
         GROUP_UNIQUE_TARGET = TARGET_BET >= 200 ? 105 : 90;
         PAIR_GROUP_QUOTA = TARGET_BET >= 200 ? 18 : 14;
         PERM_EXPAND_GROUPS = TARGET_BET >= 200 ? 34 : 20;
         PREFER_PAIR_EXPAND = false;
      }
   }

   private static void adjustQuotasByShape(RuleBasedPredictUtils.ShapeProb shape) {
      if (CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3) {
         if (shape.pairHigher) {
            PAIR_GROUP_QUOTA = Math.min(22, PAIR_GROUP_QUOTA + 4);
            PERM_EXPAND_GROUPS = Math.min(36, PERM_EXPAND_GROUPS + 6);
         } else {
            GROUP_UNIQUE_TARGET = Math.min(100, Math.max(GROUP_UNIQUE_TARGET, GROUP_UNIQUE_TARGET + 6));
            PERM_EXPAND_GROUPS = Math.min(42, Math.max(PERM_EXPAND_GROUPS, 22));
         }
      } else {
         if (shape.pairHigher) {
            PAIR_GROUP_QUOTA = Math.min(42, PAIR_GROUP_QUOTA + 6);
            PERM_EXPAND_GROUPS = Math.min(60, PERM_EXPAND_GROUPS + 6);
         } else {
            GROUP_UNIQUE_TARGET = Math.min(85, Math.max(GROUP_UNIQUE_TARGET, GROUP_UNIQUE_TARGET + 6));
            PAIR_GROUP_QUOTA = Math.max(16, PAIR_GROUP_QUOTA - 2);
            PERM_EXPAND_GROUPS = Math.min(55, PERM_EXPAND_GROUPS + 6);
         }
      }
   }

   private static void applyMissStreakQuotaBoost(List<HmCache.CompareDto> compares) {
      if (compares != null && !compares.isEmpty()) {
         int missZx = 0;
         int missGroup = 0;
         boolean stopZx = false;
         boolean stopGroup = false;

         for (int i = compares.size() - 1; i >= 0 && i >= compares.size() - 15; i--) {
            HmCache.CompareDto dto = compares.get(i);
            String tickets = dto == null ? null : (dto.getAiFullHm() != null && !dto.getAiFullHm().isBlank() ? dto.getAiFullHm() : dto.getAiHm());
            if (dto != null && tickets != null && !tickets.isBlank() && dto.getRealHm() != null && !dto.getRealHm().isBlank()) {
               String actual = pad3(dto.getRealHm());
               if (actual.length() == 3) {
                  char[] ak = actual.toCharArray();
                  Arrays.sort(ak);
                  String aKey = new String(ak);
                  boolean zx = false;
                  boolean group = false;

                  for (String p : tickets.split(",")) {
                     String t = p.trim();

                     while (t.length() < 3) {
                        t = "0" + t;
                     }

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

                  if (!stopZx) {
                     if (zx) {
                        stopZx = true;
                     } else {
                        missZx++;
                     }
                  }

                  if (!stopGroup) {
                     if (group) {
                        stopGroup = true;
                     } else {
                        missGroup++;
                     }
                  }

                  if (stopZx && stopGroup) {
                     break;
                  }
               }
            }
         }

         if (missGroup >= 2) {
            GROUP_UNIQUE_TARGET = Math.min(CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 ? 130 : 108, GROUP_UNIQUE_TARGET + 6 + Math.min(10, missGroup));
            PAIR_GROUP_QUOTA = Math.min(CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 ? 28 : 45, PAIR_GROUP_QUOTA + 3);
            log.info("组选连挂{}期 → 散组目标={} 组三配额={}", new Object[]{missGroup, GROUP_UNIQUE_TARGET, PAIR_GROUP_QUOTA});
         }

         if (missZx >= (CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 ? 2 : 3)) {
            PERM_EXPAND_GROUPS = Math.min(CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 ? 58 : 65, PERM_EXPAND_GROUPS + 6 + Math.min(12, missZx));
            log.info("直选连挂{}期 → 排列展开组数={}", missZx, PERM_EXPAND_GROUPS);
         }
      }
   }

   private static List<HmCache.CompareDto> mergeDadiTickets(List<HmCache.CompareDto> compares, boolean pl3) {
      if (compares != null && !compares.isEmpty()) {
         List<HmCache.DadiCompareDto> dadi = pl3 ? HmCache.getPl3DadiCompareCache() : HmCache.getSdDadiCompareCache();
         if (dadi != null && !dadi.isEmpty()) {
            Map<String, String> byReal = new HashMap<>();
            Map<String, String> byQh = new HashMap<>();

            for (HmCache.DadiCompareDto d : dadi) {
               if (d != null && d.getCursorDadiHm() != null && !d.getCursorDadiHm().isBlank()) {
                  if (d.getQh() != null && !d.getQh().isBlank()) {
                     byQh.put(d.getQh().trim(), d.getCursorDadiHm());
                  }

                  if (d.getRealHm() != null && !d.getRealHm().isBlank()) {
                     byReal.put(pad3(d.getRealHm()), d.getCursorDadiHm());
                  }
               }
            }

            if (byReal.isEmpty() && byQh.isEmpty()) {
               return compares;
            } else {
               List<HmCache.CompareDto> out = new ArrayList<>(compares.size());

               for (HmCache.CompareDto src : compares) {
                  if (src != null) {
                     if (src.getAiFullHm() != null && !src.getAiFullHm().isBlank()) {
                        out.add(src);
                     } else {
                        String full = src.getQh() == null ? null : byQh.get(src.getQh().trim());
                        if (full == null && src.getRealHm() != null && !src.getRealHm().isBlank()) {
                           full = byReal.get(pad3(src.getRealHm()));
                        }

                        if (full == null) {
                           out.add(src);
                        } else {
                           out.add(
                              new HmCache.CompareDto()
                                 .setQh(src.getQh())
                                 .setAiHm(src.getAiHm())
                                 .setAiFullHm(full)
                                 .setAiDingWeiHm(src.getAiDingWeiHm())
                                 .setAiDanMaHm(src.getAiDanMaHm())
                                 .setRealHm(src.getRealHm())
                           );
                        }
                     }
                  }
               }

               return out;
            }
         } else {
            return compares;
         }
      } else {
         return compares;
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

   private static List<int[]> extendCoverage(List<int[]> core, int[][] scores, int[][] digits, RecentFeatureStats feat, int limit, int extendMode) {
      LinkedHashSet<String> used = new LinkedHashSet<>();
      List<int[]> out = new ArrayList<>(limit);
      if (core != null) {
         for (int[] t : core) {
            if (out.size() >= limit) {
               return out;
            }

            if (t != null && used.add(ticketKey(t))) {
               out.add(new int[]{t[0], t[1], t[2]});
            }
         }
      }

      if (out.size() >= limit) {
         return out;
      } else {
         int room = limit - out.size();
         int permBudget;
         if (extendMode == 1) {
            permBudget = (int)Math.round(room * 0.92);
         } else if (extendMode >= 2) {
            permBudget = (int)Math.round(room * 0.22);
         } else {
            permBudget = (int)Math.round(room * 0.7);
         }

         int permAdded = appendTickets(out, used, missingPerms(out, scores, feat), permBudget, limit);
         int freshBudget = room - permAdded;
         appendTickets(out, used, freshCombos(digits, scores, feat, used, extendMode >= 2), freshBudget, limit);
         if (out.size() < limit) {
            appendTickets(out, used, freshCombos(digits, scores, feat, used, false), limit - out.size(), limit);
         }

         return out;
      }
   }

   private static int appendTickets(List<int[]> out, Set<String> used, List<int[]> cand, int budget, int limit) {
      if (budget > 0 && cand != null && !cand.isEmpty()) {
         int added = 0;

         for (int[] t : cand) {
            if (added >= budget || out.size() >= limit) {
               break;
            }

            if (used.add(ticketKey(t))) {
               out.add(new int[]{t[0], t[1], t[2]});
               added++;
            }
         }

         return added;
      } else {
         return 0;
      }
   }

   private static List<int[]> missingPerms(List<int[]> selected, int[][] scores, RecentFeatureStats feat) {
      LinkedHashSet<String> groups = new LinkedHashSet<>();

      for (int[] t : selected) {
         groups.add(groupKey(t[0], t[1], t[2]));
      }

      List<int[]> cand = new ArrayList<>();
      Set<String> seen = new HashSet<>();

      for (String g : groups) {
         int a = g.charAt(0) - '0';
         int b = g.charAt(1) - '0';
         int c = g.charAt(2) - '0';
         int[] s = new int[]{a, b, c};
         int[][] ord = new int[][]{{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};

         for (int[] o : ord) {
            int[] t = new int[]{s[o[0]], s[o[1]], s[o[2]]};
            if (seen.add(ticketKey(t))) {
               cand.add(t);
            }
         }
      }

      cand.sort((x, y) -> Integer.compare(ticketScore(y, scores, feat), ticketScore(x, scores, feat)));
      return cand;
   }

   private static List<int[]> freshCombos(int[][] digits, int[][] scores, RecentFeatureStats feat, Set<String> used, boolean droughtGroups) {
      int[][] omit = new int[3][10];
      int n = digits.length;

      for (int pos = 0; pos < 3; pos++) {
         Arrays.fill(omit[pos], n);

         for (int d = 0; d < 10; d++) {
            for (int i = n - 1; i >= 0; i--) {
               if (digits[i][pos] == d) {
                  omit[pos][d] = n - 1 - i;
                  break;
               }
            }
         }
      }

      int[] last = digits[n - 1];
      Set<String> coreGroups = new HashSet<>();
      if (droughtGroups) {
         for (String key : used) {
            if (key.length() == 3) {
               coreGroups.add(groupKey(key.charAt(0) - '0', key.charAt(1) - '0', key.charAt(2) - '0'));
            }
         }
      }

      List<int[]> cand = new ArrayList<>(1000);
      int[] sc = new int[1000];
      int m = 0;

      for (int a = 0; a < 10; a++) {
         for (int b = 0; b < 10; b++) {
            for (int c = 0; c < 10; c++) {
               int[] t = new int[]{a, b, c};
               if (!used.contains(ticketKey(t))) {
                  int s = ticketScore(t, scores, feat);
                  if (droughtGroups) {
                     s += omitBoost(omit, a, b, c);
                     s += nearLastBoost(last, a, b, c);
                     if (coreGroups.contains(groupKey(a, b, c))) {
                        s -= 400;
                     }
                  }

                  cand.add(t);
                  sc[m++] = s;
               }
            }
         }
      }

      int[] scoreView = Arrays.copyOf(sc, m);
      Integer[] order = new Integer[m];

      for (int ix = 0; ix < m; ix++) {
         order[ix] = ix;
      }

      Arrays.sort(order, (ix, j) -> Integer.compare(scoreView[j], scoreView[ix]));
      List<int[]> sorted = new ArrayList<>(m);
      Integer[] var28 = order;
      int var30 = order.length;

      for (int var17 = 0; var17 < var30; var17++) {
         int ix = var28[var17];
         sorted.add(cand.get(ix));
      }

      return sorted;
   }

   private static int omitBoost(int[][] omit, int a, int b, int c) {
      int s = 0;
      int[] ds = new int[]{a, b, c};

      for (int pos = 0; pos < 3; pos++) {
         int om = omit[pos][ds[pos]];
         if (om >= 3 && om <= 12) {
            s += 22;
         }
      }

      return s;
   }

   private static int nearLastBoost(int[] last, int a, int b, int c) {
      int s = 0;
      int[] ds = new int[]{a, b, c};

      for (int pos = 0; pos < 3; pos++) {
         int nb1 = (last[pos] + 1) % 10;
         int nb2 = (last[pos] + 9) % 10;
         if (ds[pos] == nb1 || ds[pos] == nb2 || ds[pos] == last[pos]) {
            s += 14;
         }
      }

      return s;
   }

   private static void applyTrendBoost(int[][] digits, int[][] scores, RuleBasedPredictUtils.ShapeProb shape) {
      int n = digits.length;
      int from = Math.max(0, n - 12);
      int[] sumCnt = new int[28];
      int[] oddCnt = new int[4];

      for (int i = from; i < n; i++) {
         int a = digits[i][0];
         int b = digits[i][1];
         int c = digits[i][2];
         int sum = a + b + c;
         if (sum >= 0 && sum < 28) {
            sumCnt[sum]++;
         }

         oddCnt[a % 2 + b % 2 + c % 2]++;
      }

      Integer[] sumOrder = new Integer[28];

      for (int i = 0; i < 28; i++) {
         sumOrder[i] = i;
      }

      Arrays.sort(sumOrder, (x, y) -> Integer.compare(sumCnt[y], sumCnt[x]));
      boolean[] hotSum = new boolean[28];

      for (int i = 0; i < 4; i++) {
         int s = sumOrder[i];
         if (sumCnt[s] <= 0) {
            break;
         }

         hotSum[s] = true;
         if (s > 0) {
            hotSum[s - 1] = true;
         }

         if (s < 27) {
            hotSum[s + 1] = true;
         }
      }

      int[] digitInHotSum = new int[10];

      for (int a = 0; a < 10; a++) {
         for (int b = 0; b < 10; b++) {
            for (int c = 0; c < 10; c++) {
               if (hotSum[a + b + c]) {
                  digitInHotSum[a]++;
                  digitInHotSum[b]++;
                  digitInHotSum[c]++;
               }
            }
         }
      }

      int boost = CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 ? 2 : 1;

      for (int pos = 0; pos < 3; pos++) {
         for (int d = 0; d < 10; d++) {
            scores[pos][d] = scores[pos][d] + Math.min(8, digitInHotSum[d] / 30) * boost;
         }
      }

      int preferOddSlots = 0;

      for (int k = 1; k <= 3; k++) {
         if (oddCnt[k] > oddCnt[preferOddSlots]) {
            preferOddSlots = k;
         }
      }

      if (CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 && preferOddSlots >= 2) {
         for (int pos = 0; pos < 3; pos++) {
            for (int d = 1; d < 10; d += 2) {
               scores[pos][d] = scores[pos][d] + 3;
            }
         }
      }

      int[] last = digits[n - 1];

      for (int pos = 0; pos < 3; pos++) {
         scores[pos][last[pos]] = scores[pos][last[pos]] + (CURRENT_KIND == RuleBasedPredictUtils.GameKind.SD_3D ? 2 : 1);
         scores[pos][(last[pos] + 1) % 10] = scores[pos][(last[pos] + 1) % 10] + 4;
         scores[pos][(last[pos] + 9) % 10] = scores[pos][(last[pos] + 9) % 10] + 4;
      }

      if (shape.pairHigher && CURRENT_KIND == RuleBasedPredictUtils.GameKind.SD_3D) {
         for (int pos = 0; pos < 3; pos++) {
            scores[pos][last[pos]] = scores[pos][last[pos]] + 3;
         }
      }
   }

   private static List<int[]> ensureGroupCoverageKeepPerms(List<int[]> picked, int[][] scores, RecentFeatureStats feat, int needGroups, int keepPerms) {
      if (picked != null && !picked.isEmpty()) {
         keepPerms = Math.max(1, keepPerms);
         Map<String, List<Integer>> byGroup = new LinkedHashMap<>();

         for (int i = 0; i < picked.size(); i++) {
            int[] t = picked.get(i);
            byGroup.computeIfAbsent(groupKey(t[0], t[1], t[2]), kx -> new ArrayList<>()).add(i);
         }

         if (byGroup.size() >= needGroups) {
            return (List<int[]>)(picked.size() > TARGET_BET ? new ArrayList<>(picked.subList(0, TARGET_BET)) : picked);
         } else {
            List<Integer> replaceable = new ArrayList<>();

            for (List<Integer> idxs : byGroup.values()) {
               List<Integer> sorted = new ArrayList<>(idxs);
               sorted.sort((i, j) -> {
                  int[] a = picked.get(i);
                  int[] b = picked.get(j);
                  return Integer.compare(ticketScore(b, scores, feat), ticketScore(a, scores, feat));
               });

               for (int i = keepPerms; i < sorted.size(); i++) {
                  replaceable.add(sorted.get(i));
               }
            }

            replaceable.sort((i, j) -> Integer.compare(ticketScore(picked.get(i), scores, feat), ticketScore(picked.get(j), scores, feat)));
            List<int[]> pool = new ArrayList<>();

            for (int a = 0; a < 10; a++) {
               for (int b = 0; b < 10; b++) {
                  for (int c = 0; c < 10; c++) {
                     if (looseMorphOk(new int[]{a, b, c})) {
                        String gk = groupKey(a, b, c);
                        if (!byGroup.containsKey(gk)) {
                           pool.add(new int[]{a, b, c, ticketScore(new int[]{a, b, c}, scores, feat)});
                        }
                     }
                  }
               }
            }

            pool.sort((x, y) -> Integer.compare(y[3], x[3]));
            List<int[]> out = new ArrayList<>(picked.size());

            for (int[] t : picked) {
               out.add(new int[]{t[0], t[1], t[2]});
            }

            Set<String> usedTicket = new HashSet<>();

            for (int[] t : out) {
               usedTicket.add("" + t[0] + t[1] + t[2]);
            }

            Set<String> groups = new LinkedHashSet<>(byGroup.keySet());
            int poolIdx = 0;

            for (int ri : replaceable) {
               if (groups.size() >= needGroups || poolIdx >= pool.size()) {
                  break;
               }

               while (poolIdx < pool.size()) {
                  int[] cx = pool.get(poolIdx++);
                  String gk = groupKey(cx[0], cx[1], cx[2]);
                  if (!groups.contains(gk)) {
                     String k = "" + cx[0] + cx[1] + cx[2];
                     if (!usedTicket.contains(k)) {
                        int[] old = out.get(ri);
                        usedTicket.remove("" + old[0] + old[1] + old[2]);
                        out.set(ri, new int[]{cx[0], cx[1], cx[2]});
                        usedTicket.add(k);
                        groups.add(gk);
                        break;
                     }
                  }
               }
            }

            log.info("排三保{}排列补散组后不同组={}", keepPerms, groups.size());
            return (List<int[]>)(out.size() > TARGET_BET ? new ArrayList<>(out.subList(0, TARGET_BET)) : out);
         }
      } else {
         return picked;
      }
   }

   private static List<int[]> ensureGroupCoverage(List<int[]> picked, int[][] scores, RecentFeatureStats feat) {
      return ensureGroupCoverage(picked, scores, feat, CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 ? 52 : 78);
   }

   private static List<int[]> ensureGroupCoverage(List<int[]> picked, int[][] scores, RecentFeatureStats feat, int needGroups) {
      if (picked != null && !picked.isEmpty()) {
         Map<String, List<Integer>> byGroup = new LinkedHashMap<>();

         for (int i = 0; i < picked.size(); i++) {
            int[] t = picked.get(i);
            byGroup.computeIfAbsent(groupKey(t[0], t[1], t[2]), kx -> new ArrayList<>()).add(i);
         }

         if (byGroup.size() >= needGroups) {
            return (List<int[]>)(picked.size() > TARGET_BET ? new ArrayList<>(picked.subList(0, TARGET_BET)) : picked);
         } else {
            List<Integer> replaceable = new ArrayList<>();

            for (List<Integer> idxs : byGroup.values()) {
               for (int i = 1; i < idxs.size(); i++) {
                  replaceable.add(idxs.get(i));
               }
            }

            replaceable.sort(Integer::compareTo);
            Collections.reverse(replaceable);
            List<int[]> pool = new ArrayList<>();

            for (int a = 0; a < 10; a++) {
               for (int b = 0; b < 10; b++) {
                  for (int c = 0; c < 10; c++) {
                     if (looseMorphOk(new int[]{a, b, c})) {
                        String gk = groupKey(a, b, c);
                        if (!byGroup.containsKey(gk)) {
                           pool.add(new int[]{a, b, c, ticketScore(new int[]{a, b, c}, scores, feat)});
                        }
                     }
                  }
               }
            }

            pool.sort((x, y) -> Integer.compare(y[3], x[3]));
            List<int[]> out = new ArrayList<>(picked.size());

            for (int[] t : picked) {
               out.add(new int[]{t[0], t[1], t[2]});
            }

            Set<String> usedTicket = new HashSet<>();

            for (int[] t : out) {
               usedTicket.add("" + t[0] + t[1] + t[2]);
            }

            Set<String> groups = new LinkedHashSet<>(byGroup.keySet());
            int poolIdx = 0;

            for (int ri : replaceable) {
               if (groups.size() >= needGroups || poolIdx >= pool.size()) {
                  break;
               }

               while (poolIdx < pool.size()) {
                  int[] cx = pool.get(poolIdx++);
                  String gk = groupKey(cx[0], cx[1], cx[2]);
                  if (!groups.contains(gk)) {
                     String k = "" + cx[0] + cx[1] + cx[2];
                     if (!usedTicket.contains(k)) {
                        int[] old = out.get(ri);
                        usedTicket.remove("" + old[0] + old[1] + old[2]);
                        out.set(ri, new int[]{cx[0], cx[1], cx[2]});
                        usedTicket.add(k);
                        groups.add(gk);
                        break;
                     }
                  }
               }
            }

            log.info("补散组后不同组={} (目标≥{})", groups.size(), needGroups);
            return (List<int[]>)(out.size() > TARGET_BET ? new ArrayList<>(out.subList(0, TARGET_BET)) : out);
         }
      } else {
         return picked;
      }
   }

   private static List<int[]> fillWithTopGroupPerms(List<int[]> picked, int[][] scores, RecentFeatureStats feat) {
      if (picked != null && !picked.isEmpty()) {
         Map<String, int[]> best = new LinkedHashMap<>();

         for (int[] t : picked) {
            String gk = groupKey(t[0], t[1], t[2]);
            int sc = ticketScore(t, scores, feat);
            int[] old = best.get(gk);
            if (old == null || sc > old[3]) {
               best.put(gk, new int[]{t[0], t[1], t[2], sc});
            }
         }

         List<int[]> groups = new ArrayList<>(best.values());
         groups.sort((a, b) -> Integer.compare(b[3], a[3]));
         List<int[]> out = new ArrayList<>(TARGET_BET);
         Set<String> used = new HashSet<>();
         Set<String> usedGroup = new HashSet<>();
         int scatterTarget = Math.min(TUNE_SCATTER > 0 ? TUNE_SCATTER : 72, groups.size());

         for (int i = 0; i < scatterTarget && out.size() < TARGET_BET; i++) {
            int[] g = groups.get(i);
            String gk = groupKey(g[0], g[1], g[2]);
            if (usedGroup.add(gk)) {
               int[] bestArr = bestArrangementForScores(g[0], g[1], g[2], scores);
               String k = "" + bestArr[0] + bestArr[1] + bestArr[2];
               if (used.add(k)) {
                  out.add(bestArr);
               }
            }
         }

         if (TUNE_EXPAND == 0) {
            List<int[]> permPool = new ArrayList<>();
            int scan = Math.min(groups.size(), Math.max(scatterTarget, 40));

            for (int ix = 0; ix < scan; ix++) {
               int[] g = groups.get(ix);

               for (int[] p : uniquePerms(g[0], g[1], g[2])) {
                  String k = "" + p[0] + p[1] + p[2];
                  if (!used.contains(k)) {
                     permPool.add(new int[]{p[0], p[1], p[2], scores[0][p[0]] + scores[1][p[1]] + scores[2][p[2]]});
                  }
               }
            }

            permPool.sort((a, b) -> Integer.compare(b[3], a[3]));

            for (int[] tx : permPool) {
               if (out.size() >= TARGET_BET) {
                  break;
               }

               String k = "" + tx[0] + tx[1] + tx[2];
               if (used.add(k)) {
                  out.add(new int[]{tx[0], tx[1], tx[2]});
               }
            }
         } else {
            int expandGroups = Math.min(TUNE_EXPAND > 0 ? TUNE_EXPAND : 30, groups.size());

            for (int ix = 0; ix < expandGroups && out.size() < TARGET_BET; ix++) {
               int[] g = groups.get(ix);

               for (int[] px : uniquePerms(g[0], g[1], g[2])) {
                  if (out.size() >= TARGET_BET) {
                     break;
                  }

                  String k = "" + px[0] + px[1] + px[2];
                  if (used.add(k)) {
                     out.add(px);
                  }
               }
            }
         }

         for (int ix = scatterTarget; ix < groups.size() && out.size() < TARGET_BET; ix++) {
            int[] g = groups.get(ix);
            String gk = groupKey(g[0], g[1], g[2]);
            if (usedGroup.add(gk)) {
               int[] bestArr = bestArrangementForScores(g[0], g[1], g[2], scores);
               String k = "" + bestArr[0] + bestArr[1] + bestArr[2];
               if (used.add(k)) {
                  out.add(bestArr);
               }
            }
         }

         if (out.size() < TARGET_BET) {
            List<int[]> extras = new ArrayList<>();

            for (int[] tx : picked) {
               String k = "" + tx[0] + tx[1] + tx[2];
               if (!used.contains(k)) {
                  extras.add(new int[]{tx[0], tx[1], tx[2], ticketScore(tx, scores, feat)});
               }
            }

            extras.sort((a, b) -> Integer.compare(b[3], a[3]));

            for (int[] txx : extras) {
               if (out.size() >= TARGET_BET) {
                  break;
               }

               String k = "" + txx[0] + txx[1] + txx[2];
               if (used.add(k)) {
                  out.add(new int[]{txx[0], txx[1], txx[2]});
               }
            }
         }

         log.info("排三散组优先: 最终={}注 不同组={}", out.size(), usedGroup.size());
         return out;
      } else {
         return picked;
      }
   }

   private static int[] bestArrangementForScores(int a, int b, int c, int[][] scores) {
      int[] best = new int[]{a, b, c};
      int bestSc = scores[0][a] + scores[1][b] + scores[2][c];

      for (int[] p : uniquePerms(a, b, c)) {
         int sc = scores[0][p[0]] + scores[1][p[1]] + scores[2][p[2]];
         if (sc > bestSc) {
            bestSc = sc;
            best = p;
         }
      }

      return best;
   }

   private static RuleBasedPredictUtils.ShapeProb shapeProb(int[][] digits) {
      int n = digits.length;
      int from = Math.max(0, n - 20);
      int pair = 0;
      int zu6 = 0;

      for (int i = from; i < n; i++) {
         int a = digits[i][0];
         int b = digits[i][1];
         int c = digits[i][2];
         if (a != b || b != c) {
            if (isPairSet(a, b, c)) {
               pair++;
            } else {
               zu6++;
            }
         }
      }

      int tot = pair + zu6;
      int pairPct = tot == 0 ? 0 : pair * 100 / tot;
      int zu6Pct = tot == 0 ? 0 : zu6 * 100 / tot;
      int pairScore = (pairPct - 27) * 3;
      int zu6Score = (zu6Pct - 72) * 3;
      int from5 = Math.max(0, n - 5);
      int pair5 = 0;
      int zu65 = 0;

      for (int ix = from5; ix < n; ix++) {
         int a = digits[ix][0];
         int b = digits[ix][1];
         int c = digits[ix][2];
         if (a != b || b != c) {
            if (isPairSet(a, b, c)) {
               pair5++;
            } else {
               zu65++;
            }
         }
      }

      pairScore += pair5 * 18;
      zu6Score += zu65 * 12;
      int[] last = digits[n - 1];
      boolean lastPair = isPairSet(last[0], last[1], last[2]);
      boolean lastBaozi = last[0] == last[1] && last[1] == last[2];
      if (!lastBaozi) {
         if (lastPair) {
            pairScore += 15;
            zu6Score += 8;
         } else {
            pairScore += 22;
            zu6Score += 5;
         }
      }

      int pairOmit = 0;

      for (int ixx = n - 1; ixx >= 0 && pairOmit < 15; ixx--) {
         int a = digits[ixx][0];
         int b = digits[ixx][1];
         int c = digits[ixx][2];
         if (isPairSet(a, b, c)) {
            break;
         }

         pairOmit++;
      }

      if (pairOmit >= 3) {
         pairScore += Math.min(40, pairOmit * 6);
      }

      return new RuleBasedPredictUtils.ShapeProb(pairScore > zu6Score, pairPct, zu6Pct, pairScore, zu6Score);
   }

   private static List<int[]> applyBiasCorrectTickets(List<int[]> picked, int[][] scores, RecentFeatureStats feat, BiasSeedCorrector seeds) {
      if (picked != null && !picked.isEmpty()) {
         Map<String, int[]> map = new LinkedHashMap<>();

         for (int[] t : picked) {
            int sc = posStraightScore(t, scores, feat);
            map.put("" + t[0] + t[1] + t[2], new int[]{t[0], t[1], t[2], sc});
         }

         int n = Math.min(picked.size(), 30);

         for (int i = 0; i < n; i++) {
            int[] t = picked.get(i);
            int[] add = new int[]{seeds.shiftAdd(t[0], 0), seeds.shiftAdd(t[1], 1), seeds.shiftAdd(t[2], 2)};
            int[] sub = new int[]{seeds.shiftSub(t[0], 0), seeds.shiftSub(t[1], 1), seeds.shiftSub(t[2], 2)};
            offerScored(map, add, posStraightScore(add, scores, feat) + 15);
            offerScored(map, sub, posStraightScore(sub, scores, feat) + 10);

            for (int pos = 0; pos < 3; pos++) {
               int[] one = new int[]{t[0], t[1], t[2]};
               one[pos] = seeds.shiftAdd(t[pos], pos);
               offerScored(map, one, posStraightScore(one, scores, feat) + 8);
               int[] oneSub = new int[]{t[0], t[1], t[2]};
               oneSub[pos] = seeds.shiftSub(t[pos], pos);
               offerScored(map, oneSub, posStraightScore(oneSub, scores, feat) + 5);
            }
         }

         List<int[]> ranked = new ArrayList<>(map.values());
         ranked.sort((a, b) -> Integer.compare(b[3], a[3]));
         List<int[]> out = new ArrayList<>(TARGET_BET);

         for (int[] s : ranked) {
            if (out.size() >= TARGET_BET) {
               break;
            }

            out.add(new int[]{s[0], s[1], s[2]});
         }

         return out;
      } else {
         return picked;
      }
   }

   private static void offerScored(Map<String, int[]> map, int[] t, int score) {
      if (t != null && t.length >= 3) {
         if (t[0] != t[1] || t[1] != t[2]) {
            String key = "" + t[0] + t[1] + t[2];
            int[] old = map.get(key);
            if (old == null || score > old[3]) {
               map.put(key, new int[]{t[0], t[1], t[2], score});
            }
         }
      }
   }

   private static List<int[]> selectGroupFirst(int[][] digits, int[][] scores, int[][] topPos, RecentFeatureStats feat, BiasSeedCorrector seeds) {
      int[] global = globalDigitScore(scores, feat, digits);
      int[] hotPool = buildHotPool(digits, global, feat, GROUP_DIGIT_POOL);
      log.info("直选覆盖热核{}码={}", GROUP_DIGIT_POOL, Arrays.toString(hotPool));
      int[] groupHitProxy = new int[1000];
      int from20 = Math.max(0, digits.length - 20);

      for (int i = from20; i < digits.length; i++) {
         groupHitProxy[sortedKeyCode(digits[i][0], digits[i][1], digits[i][2])]++;
      }

      Map<String, int[]> ticketMap = new LinkedHashMap<>();
      String lastGk = groupKey(feat.last[0], feat.last[1], feat.last[2]);

      for (int i = 0; i < feat.nextFullCount; i++) {
         int[] c = feat.nextFullCodes[i];
         if (!groupKey(c[0], c[1], c[2]).equals(lastGk)) {
            int wBoost = 80 + Math.min(40, c[3] / 2);
            if (CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3) {
               wBoost += 50;
            }

            for (int[] p : uniquePerms(c[0], c[1], c[2])) {
               offerTicket(ticketMap, p, ticketScore(p, scores, feat) + wBoost);
            }

            offerTicket(ticketMap, c, ticketScore(c, scores, feat) + wBoost + 30);
         }
      }

      for (int[] s : broadTransferCandidates(digits, feat)) {
         if (!groupKey(s[0], s[1], s[2]).equals(lastGk)) {
            for (int[] p : uniquePerms(s[0], s[1], s[2])) {
               int boost = CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 ? 70 : 45;
               offerTicket(ticketMap, p, ticketScore(p, scores, feat) + boost);
            }
         }
      }

      for (int pos = 0; pos < 3; pos++) {
         for (int d = -2; d <= 2; d++) {
            if (d != 0) {
               int[] m = new int[]{feat.last[0], feat.last[1], feat.last[2]};
               m[pos] = (m[pos] + d + 10) % 10;
               int boost = Math.abs(d) == 1 ? 35 : 18;

               for (int[] p : uniquePerms(m[0], m[1], m[2])) {
                  offerTicket(ticketMap, p, ticketScore(p, scores, feat) + boost);
               }
            }
         }
      }

      int[][] bandPos = new int[3][];

      for (int p = 0; p < 3; p++) {
         bandPos[p] = digitsInRankBand(scores[p], RANK_BAND_LO, RANK_BAND_HI);
      }

      for (int a : bandPos[0]) {
         for (int b : bandPos[1]) {
            for (int c : bandPos[2]) {
               int[] t = new int[]{a, b, c};
               offerTicket(ticketMap, t, ticketScore(t, scores, feat) + 25);
            }
         }
      }

      int[][] softPos = new int[3][];

      for (int p = 0; p < 3; p++) {
         softPos[p] = digitsInRankBand(scores[p], 2, 9);
      }

      for (int a : softPos[0]) {
         for (int b : softPos[1]) {
            for (int c : softPos[2]) {
               int[] t = new int[]{a, b, c};
               offerTicket(ticketMap, t, ticketScore(t, scores, feat) + 8);
            }
         }
      }

      for (int[] sx : sumChannelCandidates(global, feat, groupHitProxy, digits, 90)) {
         int gBoost = isPairSet(sx[0], sx[1], sx[2]) ? 28 : 15;

         for (int[] p : uniquePerms(sx[0], sx[1], sx[2])) {
            offerTicket(ticketMap, p, ticketScore(p, scores, feat) + gBoost);
         }
      }

      for (int ix = 0; ix < hotPool.length; ix++) {
         for (int j = ix + 1; j < hotPool.length; j++) {
            for (int k = j + 1; k < hotPool.length; k++) {
               for (int[] p : uniquePerms(hotPool[ix], hotPool[j], hotPool[k])) {
                  offerTicket(ticketMap, p, ticketScore(p, scores, feat) + 5);
               }
            }
         }
      }

      for (int ix = 0; ix < hotPool.length; ix++) {
         for (int j = 0; j < hotPool.length; j++) {
            if (ix != j) {
               for (int[] p : uniquePerms(hotPool[ix], hotPool[ix], hotPool[j])) {
                  offerTicket(ticketMap, p, ticketScore(p, scores, feat) + 22);
               }
            }
         }
      }

      LinkedHashSet<Integer> pairDigits = new LinkedHashSet<>();

      for (int dx : hotPool) {
         pairDigits.add(dx);
      }

      for (int p = 0; p < 3; p++) {
         for (int dx : softPos[p]) {
            pairDigits.add(dx);
         }
      }

      Integer[] pairArr = pairDigits.toArray(new Integer[0]);

      for (int ix = 0; ix < pairArr.length; ix++) {
         for (int jx = 0; jx < pairArr.length; jx++) {
            if (ix != jx) {
               int rep = pairArr[ix];
               int single = pairArr[jx];

               for (int[] p : uniquePerms(rep, rep, single)) {
                  offerTicket(ticketMap, p, ticketScore(p, scores, feat) + 18);
               }
            }
         }
      }

      for (int[] t : buildLoosePool(topPos, scores, feat)) {
         offerTicket(ticketMap, t, t[3] + 10);
      }

      List<int[]> base = new ArrayList<>(ticketMap.values());
      base.sort((x, y) -> Integer.compare(y[3], x[3]));
      int seedN = Math.min(50, base.size());

      for (int ix = 0; ix < seedN; ix++) {
         int[] sx = base.get(ix);
         int[] add = new int[]{seeds.shiftAdd(sx[0], 0), seeds.shiftAdd(sx[1], 1), seeds.shiftAdd(sx[2], 2)};
         int[] sub = new int[]{seeds.shiftSub(sx[0], 0), seeds.shiftSub(sx[1], 1), seeds.shiftSub(sx[2], 2)};
         offerTicket(ticketMap, add, ticketScore(add, scores, feat) + 12);
         offerTicket(ticketMap, sub, ticketScore(sub, scores, feat) + 8);
      }

      List<int[]> ranked = new ArrayList<>(ticketMap.values());
      ranked.sort((x, y) -> Integer.compare(y[3], x[3]));
      Map<String, int[]> bestByGroup = new LinkedHashMap<>();

      for (int[] sx : ranked) {
         String gk = groupKey(sx[0], sx[1], sx[2]);
         int[] old = bestByGroup.get(gk);
         if (old == null || sx[3] > old[3]) {
            bestByGroup.put(gk, sx);
         }
      }

      List<int[]> groupRanked = new ArrayList<>(bestByGroup.values());
      groupRanked.sort((x, y) -> Integer.compare(y[3], x[3]));
      List<int[]> picked = new ArrayList<>();
      Set<String> usedTicket = new HashSet<>();
      Set<String> usedGroup = new HashSet<>();
      List<String> expandOrder = new ArrayList<>();
      if (CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3) {
         List<int[]> transferGroups = new ArrayList<>();

         for (int ix = 0; ix < feat.nextFullCount; ix++) {
            transferGroups.add(feat.nextFullCodes[ix]);
         }

         transferGroups.addAll(broadTransferCandidates(digits, feat));

         for (int[] c : transferGroups) {
            if (usedGroup.size() >= GROUP_UNIQUE_TARGET) {
               break;
            }

            String gk = groupKey(c[0], c[1], c[2]);
            if (!groupKey(feat.last[0], feat.last[1], feat.last[2]).equals(gk) && !usedGroup.contains(gk)) {
               int[] best = bestArrangementForScores(c[0], c[1], c[2], scores);
               String tKey = "" + best[0] + best[1] + best[2];
               if (usedTicket.add(tKey)) {
                  usedGroup.add(gk);
                  expandOrder.add(gk);
                  bestByGroup.putIfAbsent(gk, new int[]{best[0], best[1], best[2], ticketScore(best, scores, feat) + 100});
                  picked.add(best);
               }
            }
         }
      }

      int pairAdded = 0;

      for (int[] sxx : groupRanked) {
         if (pairAdded >= PAIR_GROUP_QUOTA || usedGroup.size() >= GROUP_UNIQUE_TARGET) {
            break;
         }

         if (isPairSet(sxx[0], sxx[1], sxx[2])) {
            String gk = groupKey(sxx[0], sxx[1], sxx[2]);
            if (!usedGroup.contains(gk)) {
               pickOne(picked, usedTicket, usedGroup, expandOrder, sxx);
               pairAdded++;
            }
         }
      }

      for (int[] sxx : groupRanked) {
         if (usedGroup.size() >= GROUP_UNIQUE_TARGET) {
            break;
         }

         String gk = groupKey(sxx[0], sxx[1], sxx[2]);
         if (!usedGroup.contains(gk)
            && (CURRENT_KIND != RuleBasedPredictUtils.GameKind.PL3 || !isPairSet(sxx[0], sxx[1], sxx[2]) || pairAdded < PAIR_GROUP_QUOTA)) {
            pickOne(picked, usedTicket, usedGroup, expandOrder, sxx);
         }
      }

      List<String> expandPairFirst = new ArrayList<>();
      List<String> expandZu6 = new ArrayList<>();

      for (String gk : expandOrder) {
         int[] best = bestByGroup.get(gk);
         if (best != null && isPairSet(best[0], best[1], best[2])) {
            expandPairFirst.add(gk);
         } else {
            expandZu6.add(gk);
         }
      }

      List<String> expandSeq = new ArrayList<>();
      if (PREFER_PAIR_EXPAND) {
         expandSeq.addAll(expandPairFirst);
         expandSeq.addAll(expandZu6);
      } else {
         expandSeq.addAll(expandZu6);
         expandSeq.addAll(expandPairFirst);
      }

      int expandN = Math.min(PERM_EXPAND_GROUPS, expandSeq.size());

      for (int ix = 0; ix < expandN && picked.size() < TARGET_BET; ix++) {
         String gkx = expandSeq.get(ix);
         int[] best = bestByGroup.get(gkx);
         if (best != null) {
            for (int[] p : permsByPosScore(best[0], best[1], best[2], scores, feat)) {
               if (picked.size() >= TARGET_BET) {
                  break;
               }

               String tKey = "" + p[0] + p[1] + p[2];
               if (!usedTicket.contains(tKey)) {
                  usedTicket.add(tKey);
                  picked.add(new int[]{p[0], p[1], p[2]});
               }
            }
         }
      }

      List<int[]> midRanked = new ArrayList<>();

      for (int[] sxx : ranked) {
         int mid = midRankPreferScore(scores, sxx[0], sxx[1], sxx[2]);
         if (mid >= 200) {
            int bonus = 0;
            if (CURRENT_KIND == RuleBasedPredictUtils.GameKind.SD_3D && isPairSet(sxx[0], sxx[1], sxx[2])) {
               bonus = 40;
            } else if (CURRENT_KIND == RuleBasedPredictUtils.GameKind.PL3 && !isPairSet(sxx[0], sxx[1], sxx[2])) {
               bonus = 35;
            }

            midRanked.add(new int[]{sxx[0], sxx[1], sxx[2], mid + bonus});
         }
      }

      midRanked.sort((x, y) -> Integer.compare(y[3], x[3]));
      int midAdded = 0;
      int midQuota = Math.min(40, TARGET_BET / 3);

      for (int[] sxxx : midRanked) {
         if (midAdded >= midQuota || picked.size() >= TARGET_BET) {
            break;
         }

         String tKey = "" + sxxx[0] + sxxx[1] + sxxx[2];
         if (!usedTicket.contains(tKey)) {
            usedTicket.add(tKey);
            usedGroup.add(groupKey(sxxx[0], sxxx[1], sxxx[2]));
            picked.add(new int[]{sxxx[0], sxxx[1], sxxx[2]});
            midAdded++;
         }
      }

      List<int[]> byPos = new ArrayList<>();

      for (int[] sxxx : ranked) {
         int sc = posStraightScore(new int[]{sxxx[0], sxxx[1], sxxx[2]}, scores, feat);
         if (isPairSet(sxxx[0], sxxx[1], sxxx[2])) {
            sc += 80;
         }

         byPos.add(new int[]{sxxx[0], sxxx[1], sxxx[2], sc});
      }

      byPos.sort((x, y) -> Integer.compare(y[3], x[3]));

      for (int[] sxxx : byPos) {
         if (picked.size() >= TARGET_BET) {
            break;
         }

         String tKey = "" + sxxx[0] + sxxx[1] + sxxx[2];
         if (!usedTicket.contains(tKey)) {
            usedTicket.add(tKey);
            usedGroup.add(groupKey(sxxx[0], sxxx[1], sxxx[2]));
            picked.add(new int[]{sxxx[0], sxxx[1], sxxx[2]});
         }
      }

      if (picked.size() < MIN_BET) {
         List<int[]> fallback = takeTopUnique(buildLoosePool(topPos, scores, feat), TARGET_BET);
         log.warn("直选覆盖不足，回退宽松池 size={}", fallback.size());
         return fallback;
      } else {
         if (CURRENT_KIND == RuleBasedPredictUtils.GameKind.SD_3D) {
            picked = correctByPosRearrange(picked, scores, feat, seeds);
            picked = ensureGroupCoverage(picked, scores, feat);
         }

         log.info("{} 选号完成: {}注 不同组选={} 组三配额={}", new Object[]{CURRENT_KIND, picked.size(), countUniqueGroups(picked), pairAdded});
         return (List<int[]>)(picked.size() > TARGET_BET ? new ArrayList<>(picked.subList(0, TARGET_BET)) : picked);
      }
   }

   private static List<int[]> correctByPosRearrange(List<int[]> picked, int[][] scores, RecentFeatureStats feat, BiasSeedCorrector seeds) {
      if (picked != null && !picked.isEmpty()) {
         List<int[]> tickets = new ArrayList<>(picked.size());

         for (int[] t : picked) {
            tickets.add(new int[]{t[0], t[1], t[2]});
         }

         Map<String, List<Integer>> groupIdx = new LinkedHashMap<>();

         for (int i = 0; i < tickets.size(); i++) {
            int[] t = tickets.get(i);
            groupIdx.computeIfAbsent(groupKey(t[0], t[1], t[2]), kx -> new ArrayList<>()).add(i);
         }

         List<String> groupOrder = new ArrayList<>(groupIdx.keySet());
         Set<String> have = new HashSet<>();

         for (int[] t : tickets) {
            have.add("" + t[0] + t[1] + t[2]);
         }

         int preferN = Math.min(PERM_EXPAND_GROUPS + 10, groupOrder.size());
         List<int[]> toInject = new ArrayList<>();
         Set<String> injectKeys = new HashSet<>();

         for (int i = 0; i < preferN; i++) {
            String gk = groupOrder.get(i);
            List<Integer> idxs = groupIdx.get(gk);
            if (idxs != null && idxs.size() == 1) {
               int[] sample = tickets.get(idxs.get(0));
               List<int[]> ranked = permsByPosScore(sample[0], sample[1], sample[2], scores, feat);
               int addCount = 0;
               int maxAdd = isPairSet(sample[0], sample[1], sample[2]) ? 2 : 1;

               for (int[] p : ranked) {
                  String k = "" + p[0] + p[1] + p[2];
                  if (!have.contains(k) && !injectKeys.contains(k) && (p[0] != sample[0] || p[1] != sample[1] || p[2] != sample[2])) {
                     toInject.add(p);
                     injectKeys.add(k);
                     if (++addCount >= maxAdd) {
                        break;
                     }
                  }
               }

               if (!ranked.isEmpty() && addCount < maxAdd) {
                  int[] best = ranked.get(0);
                  int[] add = new int[]{seeds.shiftAdd(best[0], 0), seeds.shiftAdd(best[1], 1), seeds.shiftAdd(best[2], 2)};
                  if (groupKey(add[0], add[1], add[2]).equals(gk)) {
                     String k = "" + add[0] + add[1] + add[2];
                     if (!have.contains(k) && injectKeys.add(k)) {
                        toInject.add(add);
                     }
                  }
               }
            }
         }

         if (toInject.isEmpty()) {
            log.info("位分重排校正: 无需补入");
            return tickets;
         } else {
            int sacrificeFrom = Math.max(preferN, groupOrder.size() * 2 / 3);
            List<Integer> victims = new ArrayList<>();

            for (int gi = groupOrder.size() - 1; gi >= sacrificeFrom; gi--) {
               String gk = groupOrder.get(gi);
               List<Integer> idxs = groupIdx.get(gk);
               if (idxs != null && idxs.size() == 1) {
                  victims.add(idxs.get(0));
               }
            }

            int injected = 0;
            int groupsSacrificed = 0;

            for (int[] need : toInject) {
               String k = "" + need[0] + need[1] + need[2];
               if (!have.contains(k) && !victims.isEmpty()) {
                  int vi = victims.remove(0);
                  int[] old = tickets.get(vi);
                  String oldGk = groupKey(old[0], old[1], old[2]);
                  if (groupIdx.getOrDefault(oldGk, List.of()).size() == 1) {
                     tickets.set(vi, need);
                     have.remove("" + old[0] + old[1] + old[2]);
                     have.add(k);
                     groupIdx.remove(oldGk);
                     String newGk = groupKey(need[0], need[1], need[2]);
                     groupIdx.computeIfAbsent(newGk, x -> new ArrayList<>()).add(vi);
                     injected++;
                     groupsSacrificed++;
                  }
               }
            }

            log.info("位分重排校正: 补入直选排列={} 牺牲边缘组选={} 剩余组数={}", new Object[]{injected, groupsSacrificed, groupIdx.size()});
            return tickets;
         }
      } else {
         return picked;
      }
   }

   private static int posStraightScore(int[] t, int[][] scores, RecentFeatureStats feat) {
      int s = scores[0][t[0]] + scores[1][t[1]] + scores[2][t[2]];
      s += feat.digitBonus(0, t[0]) + feat.digitBonus(1, t[1]) + feat.digitBonus(2, t[2]);
      return s + rankBandBonus(scores, t[0], t[1], t[2]);
   }

   private static List<int[]> permsByPosScore(int a, int b, int c, int[][] scores, RecentFeatureStats feat) {
      List<int[]> list = new ArrayList<>();

      for (int[] p : uniquePerms(a, b, c)) {
         list.add(new int[]{p[0], p[1], p[2], posStraightScore(p, scores, feat)});
      }

      list.sort((x, y) -> Integer.compare(y[3], x[3]));
      List<int[]> out = new ArrayList<>(list.size());

      for (int[] p : list) {
         out.add(new int[]{p[0], p[1], p[2]});
      }

      return out;
   }

   private static int countUniqueGroups(List<int[]> tickets) {
      Set<String> set = new HashSet<>();

      for (int[] t : tickets) {
         set.add(groupKey(t[0], t[1], t[2]));
      }

      return set.size();
   }

   private static void pickOne(List<int[]> picked, Set<String> usedTicket, Set<String> usedGroup, List<String> expandOrder, int[] s) {
      String gk = groupKey(s[0], s[1], s[2]);
      String tKey = "" + s[0] + s[1] + s[2];
      usedTicket.add(tKey);
      usedGroup.add(gk);
      expandOrder.add(gk);
      picked.add(new int[]{s[0], s[1], s[2]});
   }

   private static boolean isPairSet(int a, int b, int c) {
      return a == b && b != c || a == c && a != b || b == c && a != b;
   }

   private static void offerTicket(Map<String, int[]> ticketMap, int[] t, int score) {
      if (t != null && t.length >= 3) {
         String key = "" + t[0] + t[1] + t[2];
         int[] old = ticketMap.get(key);
         if (old == null || score > old[3]) {
            ticketMap.put(key, new int[]{t[0], t[1], t[2], score});
         }
      }
   }

   private static int ticketScore(int[] t, int[][] scores, RecentFeatureStats feat) {
      return scores[0][t[0]] + scores[1][t[1]] + scores[2][t[2]] + feat.shapeBonus(t[0], t[1], t[2]) + rankBandBonus(scores, t[0], t[1], t[2]);
   }

   private static int digitRank(int[] score, int digit) {
      int better = 0;

      for (int d = 0; d < 10; d++) {
         if (score[d] > score[digit] || score[d] == score[digit] && d < digit) {
            better++;
         }
      }

      return better + 1;
   }

   private static boolean inRankBand(int rank) {
      return rank >= RANK_BAND_LO && rank <= RANK_BAND_HI;
   }

   private static int midRankPreferScore(int[][] scores, int a, int b, int c) {
      int sum = 0;
      int[] ds = new int[]{a, b, c};

      for (int pos = 0; pos < 3; pos++) {
         int r = digitRank(scores[pos], ds[pos]);
         if (inRankBand(r)) {
            sum += 100;
         } else if (r == RANK_BAND_LO - 1 || r == RANK_BAND_HI + 1) {
            sum += 40;
         } else if (r == 1 || r == 2) {
            sum += 70;
         }
      }

      return sum;
   }

   private static int rankBandBonus(int[][] scores, int a, int b, int c) {
      int r0 = digitRank(scores[0], a);
      int r1 = digitRank(scores[1], b);
      int r2 = digitRank(scores[2], c);
      int bonus = 0;

      for (int r : new int[]{r0, r1, r2}) {
         if (inRankBand(r)) {
            bonus += 40;
         } else if (r == RANK_BAND_LO - 1 || r == RANK_BAND_HI + 1) {
            bonus += 12;
         } else if (r == 1) {
            bonus += 18;
         } else if (r == 2) {
            bonus += 12;
         } else {
            bonus -= 4;
         }
      }

      if (inRankBand(r0) && inRankBand(r1) && inRankBand(r2)) {
         bonus += 40;
      }

      return bonus;
   }

   private static int[] digitsInRankBand(int[] score, int lo, int hi) {
      Integer[] order = new Integer[10];

      for (int i = 0; i < 10; i++) {
         order[i] = i;
      }

      Arrays.sort(order, (a, b) -> Integer.compare(score[b], score[a]));
      List<Integer> list = new ArrayList<>();

      for (int i = 0; i < 10; i++) {
         int rank = i + 1;
         if (rank >= lo && rank <= hi) {
            list.add(order[i]);
         }
      }

      int[] r = new int[list.size()];

      for (int ix = 0; ix < list.size(); ix++) {
         r[ix] = list.get(ix);
      }

      return r;
   }

   private static int[] buildBandAwareTop(int[] score, int n) {
      Integer[] order = new Integer[10];

      for (int i = 0; i < 10; i++) {
         order[i] = i;
      }

      Arrays.sort(order, (a, b) -> Integer.compare(score[b], score[a]));
      LinkedHashSet<Integer> set = new LinkedHashSet<>();

      for (int i = 0; i < 4 && set.size() < n; i++) {
         set.add(order[i]);
      }

      for (int i = 0; i < 10 && set.size() < n; i++) {
         int rank = i + 1;
         if (rank >= RANK_BAND_LO && rank <= RANK_BAND_HI) {
            set.add(order[i]);
         }
      }

      for (int ix : new int[]{4, 5, 8, 9}) {
         if (set.size() >= n) {
            break;
         }

         set.add(order[ix]);
      }

      Integer[] var11 = order;
      int ix = order.length;

      for (int var16 = 0; var16 < ix; var16++) {
         int d = var11[var16];
         if (set.size() >= n) {
            break;
         }

         set.add(d);
      }

      int[] r = new int[Math.min(n, set.size())];
      ix = 0;

      for (int d : set) {
         r[ix++] = d;
      }

      return r;
   }

   private static List<int[]> sumChannelCandidates(int[] global, RecentFeatureStats feat, int[] groupHitProxy, int[][] digits, int limit) {
      Set<Integer> sums = new HashSet<>();

      for (int s : feat.topSums) {
         sums.add(s);
         sums.add(Math.max(0, s - 1));
         sums.add(Math.min(27, s + 1));
      }

      List<int[]> sets = new ArrayList<>();

      for (int a = 0; a < 10; a++) {
         for (int b = a; b < 10; b++) {
            for (int c = b; c < 10; c++) {
               if (sums.contains(a + b + c)) {
                  int sc = scoreGroupSet(a, b, c, global, feat, groupHitProxy, digits);
                  sets.add(new int[]{a, b, c, sc});
               }
            }
         }
      }

      sets.sort((x, y) -> Integer.compare(y[3], x[3]));
      return (List<int[]>)(sets.size() > limit ? new ArrayList<>(sets.subList(0, limit)) : sets);
   }

   private static int[] buildHotPool(int[][] digits, int[] global, RecentFeatureStats feat, int size) {
      int n = digits.length;
      int[] omitScore = new int[10];
      int[] lastSeen = new int[10];
      Arrays.fill(lastSeen, -1);

      for (int i = 0; i < n; lastSeen[digits[i][2]] = i++) {
         lastSeen[digits[i][0]] = i;
         lastSeen[digits[i][1]] = i;
      }

      for (int d = 0; d < 10; d++) {
         int gap = lastSeen[d] < 0 ? n : n - 1 - lastSeen[d];
         omitScore[d] = Math.min(gap, 12);
      }

      int[] cnt15 = new int[10];
      int from15 = Math.max(0, n - 15);

      for (int i = from15; i < n; i++) {
         cnt15[digits[i][0]]++;
         cnt15[digits[i][1]]++;
         cnt15[digits[i][2]]++;
      }

      int[] combined = new int[10];

      for (int d = 0; d < 10; d++) {
         combined[d] = global[d] + cnt15[d] * 10 + omitScore[d] * 2 + feat.digitBonus(0, d) + feat.digitBonus(1, d) + feat.digitBonus(2, d);
      }

      for (int p = 0; p < 3; p++) {
         combined[feat.last[p]] = combined[feat.last[p]] + 6;
         combined[(feat.last[p] + 1) % 10] = combined[(feat.last[p] + 1) % 10] + 10;
         combined[(feat.last[p] + 9) % 10] = combined[(feat.last[p] + 9) % 10] + 10;
         combined[(feat.last[p] + 2) % 10] = combined[(feat.last[p] + 2) % 10] + 5;
         combined[(feat.last[p] + 8) % 10] = combined[(feat.last[p] + 8) % 10] + 5;
      }

      Integer[] order = new Integer[10];

      for (int i = 0; i < 10; i++) {
         order[i] = i;
      }

      Arrays.sort(order, (a, b) -> Integer.compare(combined[b], combined[a]));
      int[] r = new int[size];

      for (int i = 0; i < size; i++) {
         r[i] = order[i];
      }

      return r;
   }

   private static List<int[]> broadTransferCandidates(int[][] dig, RecentFeatureStats feat) {
      int n = dig.length;
      int[] last = feat.last;
      Map<String, int[]> best = new LinkedHashMap<>();
      Map<String, Integer> wmap = new LinkedHashMap<>();
      int from = Math.max(1, n - 60);

      for (int i = from; i < n; i++) {
         int[] prev = dig[i - 1];
         int match = 0;

         for (int p = 0; p < 3; p++) {
            if (prev[p] == last[p]) {
               match++;
            }
         }

         if (match >= 1) {
            int[] cur = dig[i];
            String g = groupKey(cur[0], cur[1], cur[2]);
            int w = match * 12 + (i - from);
            Integer old = wmap.get(g);
            if (old == null || w > old) {
               wmap.put(g, w);
               best.put(g, new int[]{cur[0], cur[1], cur[2], w});
            }
         }
      }

      List<int[]> list = new ArrayList<>(best.values());
      list.sort((a, b) -> Integer.compare(b[3], a[3]));
      return list;
   }

   private static int scoreGroupSet(int a, int b, int c, int[] global, RecentFeatureStats feat, int[] groupHitProxy, int[][] digits) {
      int score = global[a] + global[b] + global[c];
      int key = sortedKeyCode(a, b, c);
      score += groupHitProxy[key] * 25;
      score += feat.shapeBonus(a, b, c);
      int from = Math.max(0, digits.length - 10);

      for (int i = from; i < digits.length; i++) {
         if (sortedKeyCode(digits[i][0], digits[i][1], digits[i][2]) == key) {
            score += 15;
         }
      }

      boolean[] lastSet = new boolean[10];
      lastSet[feat.last[0]] = true;
      lastSet[feat.last[1]] = true;
      lastSet[feat.last[2]] = true;
      int chong = (lastSet[a] ? 1 : 0) + (lastSet[b] ? 1 : 0) + (lastSet[c] ? 1 : 0);
      return score + chong * 10;
   }

   private static int[][] uniquePerms(int a, int b, int c) {
      List<int[]> list = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      int[] raw = new int[]{a, b, c};

      for (int i = 0; i < 3; i++) {
         for (int j = 0; j < 3; j++) {
            if (j != i) {
               for (int k = 0; k < 3; k++) {
                  if (k != i && k != j) {
                     int[] p = new int[]{raw[i], raw[j], raw[k]};
                     String key = "" + p[0] + p[1] + p[2];
                     if (seen.add(key)) {
                        list.add(p);
                     }
                  }
               }
            }
         }
      }

      return list.toArray(new int[0][]);
   }

   private static boolean looseMorphOk(int[] t) {
      int sum = t[0] + t[1] + t[2];
      if (sum >= 3 && sum <= 27) {
         int max = Math.max(t[0], Math.max(t[1], t[2]));
         int min = Math.min(t[0], Math.min(t[1], t[2]));
         return max != min;
      } else {
         return false;
      }
   }

   private static int[] globalDigitScore(int[][] scores, RecentFeatureStats feat, int[][] digits) {
      int[] g = new int[10];

      for (int d = 0; d < 10; d++) {
         g[d] = scores[0][d] + scores[1][d] + scores[2][d];
         g[d] += feat.digitBonus(0, d) + feat.digitBonus(1, d) + feat.digitBonus(2, d);
      }

      int from = Math.max(0, digits.length - 20);

      for (int i = from; i < digits.length; i++) {
         g[digits[i][0]] = g[digits[i][0]] + 2;
         g[digits[i][1]] = g[digits[i][1]] + 2;
         g[digits[i][2]] = g[digits[i][2]] + 2;
      }

      return g;
   }

   private static List<int[]> buildLoosePool(int[][] topPos, int[][] scores, RecentFeatureStats feat) {
      List<int[]> pool = new ArrayList<>();

      for (int a : topPos[0]) {
         for (int b : topPos[1]) {
            for (int c : topPos[2]) {
               int[] t = new int[]{a, b, c};
               if (looseMorphOk(t)) {
                  int score = scores[0][a] + scores[1][b] + scores[2][c] + feat.shapeBonus(a, b, c) + rankBandBonus(scores, a, b, c);
                  pool.add(new int[]{a, b, c, score});
               }
            }
         }
      }

      pool.sort((x, y) -> Integer.compare(y[3], x[3]));
      return pool;
   }

   private static List<int[]> takeTopUnique(List<int[]> pool, int n) {
      List<int[]> out = new ArrayList<>();
      Set<String> used = new HashSet<>();
      Set<String> groups = new HashSet<>();

      for (int[] c : pool) {
         String tKey = "" + c[0] + c[1] + c[2];
         String gKey = groupKey(c[0], c[1], c[2]);
         if (!used.contains(tKey) && (!groups.contains(gKey) || out.size() < 3)) {
            used.add(tKey);
            groups.add(gKey);
            out.add(new int[]{c[0], c[1], c[2]});
            if (out.size() >= n) {
               break;
            }
         }
      }

      return out;
   }

   private static String groupKey(int a, int b, int c) {
      int[] x = new int[]{a, b, c};
      Arrays.sort(x);
      return "" + x[0] + x[1] + x[2];
   }

   private static int sortedKeyCode(int a, int b, int c) {
      int[] x = new int[]{a, b, c};
      Arrays.sort(x);
      return x[0] * 100 + x[1] * 10 + x[2];
   }

   private static int[] applyBiasSeedToTop(int[] top, int pos, BiasSeedCorrector seeds, int[] score) {
      boolean[] mark = new boolean[10];

      for (int d : top) {
         seeds.expandDigit(d, pos, mark);
      }

      Integer[] cands = new Integer[10];
      int n = 0;

      for (int d = 0; d < 10; d++) {
         if (mark[d]) {
            cands[n++] = d;
         }
      }

      Integer[] arr = Arrays.copyOf(cands, n);
      boolean[] inOrig = new boolean[10];

      for (int dx : top) {
         inOrig[dx] = true;
      }

      Arrays.sort(arr, (a, b) -> {
         int sa = score[a] + (inOrig[a] ? 100 : 0) + biasAlignBonus(a, top, pos, seeds);
         int sb = score[b] + (inOrig[b] ? 100 : 0) + biasAlignBonus(b, top, pos, seeds);
         return Integer.compare(sb, sa);
      });
      int[] result = new int[8];

      for (int i = 0; i < 8; i++) {
         result[i] = i < arr.length ? arr[i] : top[Math.min(i, top.length - 1)];
      }

      return result;
   }

   private static int biasAlignBonus(int digit, int[] top, int pos, BiasSeedCorrector seeds) {
      for (int d : top) {
         if (seeds.shiftAdd(d, pos) == digit || seeds.shiftSub(d, pos) == digit) {
            return 40;
         }
      }

      return 0;
   }

   private static int[] scoreAllDigits(int[][] digits, int pos, List<HmCache.CompareDto> compares, RecentFeatureStats feat) {
      int[] freq5 = freq(digits, pos, 5);
      int[] freq10 = freq(digits, pos, 10);
      int[] freq20 = freq(digits, pos, 20);
      int[] omit = omission(digits, pos);
      int last = digits[digits.length - 1][pos];
      int[] actual15 = new int[10];
      int[] pred15 = new int[10];
      fillComparePosStats(compares, pos, actual15, pred15);
      int[] score = new int[10];

      for (int d = 0; d < 10; d++) {
         int s = freq10[d] * 3 + freq20[d];
         if (omit[d] >= 2 && omit[d] <= 6) {
            s += 4;
         } else if (omit[d] >= 7 && omit[d] <= 12) {
            s += 2;
         }

         if (freq5[d] >= 2) {
            s += 3;
         }

         if (d == last) {
            s--;
         }

         if (d == neighbor(last, -1) || d == neighbor(last, 1)) {
            s += 2;
         }

         if (actual15[d] > 0 && pred15[d] <= 1) {
            s += 5;
         }

         if (pred15[d] >= 4 && actual15[d] == 0) {
            s -= 8;
         }

         s += feat.digitBonus(pos, d);
         score[d] = s;
      }

      return score;
   }

   private static void logBiasDiagnostics(List<HmCache.CompareDto> compares) {
      if (compares != null && !compares.isEmpty()) {
         List<HmCache.CompareDto> valid = new ArrayList<>();

         for (HmCache.CompareDto dto : compares) {
            if (dto != null && dto.getAiHm() != null && !dto.getAiHm().isBlank() && dto.getRealHm() != null && dto.getRealHm().length() == 3) {
               valid.add(dto);
            }
         }

         if (!valid.isEmpty()) {
            int start = Math.max(0, valid.size() - 15);
            List<HmCache.CompareDto> last15 = valid.subList(start, valid.size());
            int neighborMiss = 0;
            int extremeFail = 0;
            int checked = 0;
            boolean[] posMiss3 = new boolean[3];

            for (HmCache.CompareDto dtox : last15) {
               int[] real = parseCode(dtox.getRealHm());
               if (real != null) {
                  checked++;
                  Set<Integer> predDigits = new HashSet<>();

                  for (String p : dtox.getAiHm().split(",")) {
                     int[] t = parseCode(p.trim());
                     if (t != null) {
                        predDigits.add(t[0]);
                        predDigits.add(t[1]);
                        predDigits.add(t[2]);
                     }
                  }

                  boolean hitNeighbor = false;

                  for (int pos = 0; pos < 3; pos++) {
                     int d = real[pos];
                     if (predDigits.contains(neighbor(d, -1)) || predDigits.contains(neighbor(d, 1)) || predDigits.contains(d)) {
                        hitNeighbor = true;
                        break;
                     }
                  }

                  if (!hitNeighbor) {
                     neighborMiss++;
                  }

                  boolean anyHit = Arrays.asList(dtox.getAiHm().split(",")).contains(dtox.getRealHm());
                  if (!anyHit && isPredExtreme(dtox.getAiHm())) {
                     extremeFail++;
                  }
               }
            }

            int from = Math.max(0, last15.size() - 3);

            for (int posx = 0; posx < 3; posx++) {
               boolean miss3 = last15.size() >= 3;

               for (int i = from; i < last15.size() && miss3; i++) {
                  HmCache.CompareDto dtoxx = last15.get(i);
                  int[] real = parseCode(dtoxx.getRealHm());
                  if (real == null) {
                     miss3 = false;
                     break;
                  }

                  boolean covered = false;

                  for (String px : dtoxx.getAiHm().split(",")) {
                     int[] t = parseCode(px.trim());
                     if (t != null && t[posx] == real[posx]) {
                        covered = true;
                        break;
                     }
                  }

                  if (covered) {
                     miss3 = false;
                  }
               }

               posMiss3[posx] = miss3;
            }

            int recent = Math.min(5, last15.size());
            int posCoverMiss = 0;

            for (int i = last15.size() - recent; i < last15.size(); i++) {
               HmCache.CompareDto dtoxxx = last15.get(i);
               int[] realx = parseCode(dtoxxx.getRealHm());
               if (realx != null) {
                  boolean[] posHit = new boolean[3];

                  for (String pxx : dtoxxx.getAiHm().split(",")) {
                     int[] t = parseCode(pxx.trim());
                     if (t != null) {
                        for (int posx = 0; posx < 3; posx++) {
                           if (t[posx] == realx[posx]) {
                              posHit[posx] = true;
                           }
                        }
                     }
                  }

                  for (boolean h : posHit) {
                     if (!h) {
                        posCoverMiss++;
                     }
                  }
               }
            }

            log.info(
               "近{}期偏差诊断: 邻号漏检率≈{}/{}, 极端形态失败≈{}/{}, 位覆盖缺失={}, 连续3期避开位={}",
               new Object[]{recent, neighborMiss, checked, extremeFail, checked, posCoverMiss, Arrays.toString(posMiss3)}
            );
         }
      }
   }

   private static boolean isPredExtreme(String aiHm) {
      int hotish = 0;
      int coldish = 0;
      int total = 0;

      for (String p : aiHm.split(",")) {
         int[] t = parseCode(p.trim());
         if (t != null) {
            total++;
            int sum = t[0] + t[1] + t[2];
            if (sum >= 20) {
               hotish++;
            }

            if (sum <= 8) {
               coldish++;
            }
         }
      }

      return total > 0 && (hotish * 2 >= total || coldish * 2 >= total);
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

   private static int[] parseCode(String code) {
      if (code == null) {
         return null;
      } else {
         String c = code.trim();
         if (c.length() == 1) {
            c = "00" + c;
         } else if (c.length() == 2) {
            c = "0" + c;
         }

         if (c.length() != 3) {
            return null;
         } else {
            try {
               return new int[]{c.charAt(0) - '0', c.charAt(1) - '0', c.charAt(2) - '0'};
            } catch (Exception var3) {
               return null;
            }
         }
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

   private static void fillComparePosStats(List<HmCache.CompareDto> compares, int pos, int[] actual, int[] pred) {
      if (compares != null) {
         List<HmCache.CompareDto> valid = new ArrayList<>();

         for (HmCache.CompareDto dto : compares) {
            if (dto != null && dto.getRealHm() != null && dto.getRealHm().length() == 3 && dto.getAiHm() != null && !dto.getAiHm().isBlank()) {
               valid.add(dto);
            }
         }

         int start = Math.max(0, valid.size() - 15);

         for (int i = start; i < valid.size(); i++) {
            HmCache.CompareDto dtox = valid.get(i);
            int[] real = parseCode(dtox.getRealHm());
            if (real != null) {
               actual[real[pos]]++;
            }

            for (String p : dtox.getAiHm().split(",")) {
               int[] t = parseCode(p.trim());
               if (t != null) {
                  pred[t[pos]]++;
               }
            }
         }
      }
   }

   private static int neighbor(int d, int delta) {
      return (d + delta + 10) % 10;
   }

   private static String posName(int pos) {
      return switch (pos) {
         case 0 -> "百";
         case 1 -> "十";
         default -> "个";
      };
   }

   public static String predictFromCodes(List<String> codes, List<HmCache.CompareDto> compares) {
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

      return predict(list, compares, RuleBasedPredictUtils.GameKind.PL3);
   }

   public static enum GameKind {
      SD_3D,
      PL3;
   }

   private static final class ShapeProb {
      final boolean pairHigher;
      final int pairPct;
      final int zu6Pct;
      final int pairScore;
      final int zu6Score;

      ShapeProb(boolean pairHigher, int pairPct, int zu6Pct, int pairScore, int zu6Score) {
         this.pairHigher = pairHigher;
         this.pairPct = pairPct;
         this.zu6Pct = zu6Pct;
         this.pairScore = pairScore;
         this.zu6Score = zu6Score;
      }
   }
}
