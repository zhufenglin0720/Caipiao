package com.zfl.caipiao.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.LoggerFactory;

public final class DanMaBacktest {
   private static final int EVAL_PERIODS = 500;
   private static final double ANY_POS_TARGET = 67.0;

   private DanMaBacktest() {
   }

   public static void main(String[] args) throws Exception {
      muteLogs();
      int eval = 500;
      if (args != null && args.length > 0) {
         eval = Integer.parseInt(args[0]);
      }

      StringBuilder sb = new StringBuilder();
      sb.append("========== 胆码近").append(eval).append("期回测 ==========\n");
      sb.append("命中口径：对应位置命中（开奖位数字落在该位候选中，跨位不算）\n");
      sb.append("主指标：至少1位定位 目标≥").append(67).append("% | 每位").append(3).append("码\n");
      sb.append("策略：3D=近窗拟合；排列三=习惯轮换\n");
      sb.append("说明：随机基线≈").append(String.format(Locale.ROOT, "%.1f", (1.0 - Math.pow(0.7, 3.0)) * 100.0)).append("%（至少1位）。\n\n");
      DanMaBacktest.Result sd = runOne("福彩3D", HistoryDataLoader.load3d(), RuleBasedDanMaUtils.GameKind.SD_3D, eval, sb);
      sb.append('\n');
      DanMaBacktest.Result pl3 = runOne("排列三", HistoryDataLoader.loadPl3(), RuleBasedDanMaUtils.GameKind.PL3, eval, sb);
      sb.append("\n========== 汇总 ==========\n");
      sb.append(String.format(Locale.ROOT, "%-8s | 至少1位定位 | 分位均 | 百/十/个 | 三位全中 | 结果%n", "彩种"));
      appendRow(sb, sd);
      appendRow(sb, pl3);
      boolean pass = sd.pass && pl3.pass;
      sb.append(pass ? "\n【全部达标】至少1位定位≥67%\n" : "\n【存在未达标】\n");
      Path out = Path.of("reports/danma_backtest_500.txt");
      Files.createDirectories(out.getParent());
      Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
      sb.append("结果已写入: ").append(out.toAbsolutePath()).append('\n');
      System.out.println(sb);
      if (!pass) {
         System.exit(2);
      }
   }

   private static void appendRow(StringBuilder sb, DanMaBacktest.Result r) {
      sb.append(
         String.format(
            Locale.ROOT,
            "%-8s | %4d/%d (%5.1f%%) | %5.2f%% | %d/%d/%d | %3d/%d | %s%n",
            r.name,
            r.anyPos,
            r.n,
            pct(r.anyPos, r.n),
            r.posSum * 100.0 / (r.n * 3.0),
            r.pos[0],
            r.pos[1],
            r.pos[2],
            r.full,
            r.n,
            r.pass ? "达标" : "未达标"
         )
      );
   }

   static DanMaBacktest.Result runOne(String name, List<Hm> all, RuleBasedDanMaUtils.GameKind kind, int eval, StringBuilder out) {
      out.append("---------- ").append(name).append(" ----------\n");
      if (all != null && !all.isEmpty()) {
         out.append("历史期数=").append(all.size()).append(" 最近期=").append(all.get(all.size() - 1).getQh()).append('\n');
         int start = all.size() - eval;
         if (start < 80) {
            out.append("历史过短\n");
            return DanMaBacktest.Result.fail(name, eval);
         } else {
            DanMaBacktest.Result r = new DanMaBacktest.Result(name, eval);
            List<HmCache.CompareDto> compares = new ArrayList<>();
            long t0 = System.currentTimeMillis();

            for (int i = start; i < all.size(); i++) {
               List<Hm> hist = all.subList(0, i);
               int[][] pick = RuleBasedDanMaUtils.pickFor(kind, hist, compares);
               int[] act = RuleBasedDanMaUtils.digitsOf(all.get(i).toString());
               boolean[] hits = RuleBasedDanMaUtils.posHits(pick, act);
               int hp = 0;

               for (int p = 0; p < 3; p++) {
                  if (hits != null && hits[p]) {
                     r.posSum++;
                     r.pos[p]++;
                     hp++;
                  }
               }

               if (hp > 0) {
                  r.anyPos++;
               }

               if (hp == 3) {
                  r.full++;
               }

               compares.add(
                  new HmCache.CompareDto()
                     .setAiDanMaHm(RuleBasedDanMaUtils.formatMulti(pick))
                     .setRealHm(String.format(Locale.ROOT, "%d%d%d", act[0], act[1], act[2]))
               );

               while (compares.size() > 20) {
                  compares.remove(0);
               }

               if ((i - start + 1) % 100 == 0) {
                  System.out.printf(Locale.ROOT, "%s 进度 %d/%d 至少1位=%d%n", name, i - start + 1, eval, r.anyPos);
               }
            }

            long cost = System.currentTimeMillis() - t0;
            r.pass = pct(r.anyPos, r.n) >= 67.0;
            out.append(String.format(Locale.ROOT, "回测完成：%d期 耗时=%dms 每位%d码%n", r.n, cost, 3));
            out.append(String.format(Locale.ROOT, "至少1位定位=%d/%d (%.2f%%) %s  目标≥%.0f%%%n", r.anyPos, r.n, pct(r.anyPos, r.n), r.pass ? "达标" : "未达标", 67.0));
            out.append(
               String.format(Locale.ROOT, "分位命中：均=%.2f%% 百=%d 十=%d 个=%d | 三位全中=%d%n", r.posSum * 100.0 / (r.n * 3.0), r.pos[0], r.pos[1], r.pos[2], r.full)
            );
            return r;
         }
      } else {
         out.append("无数据\n");
         return DanMaBacktest.Result.fail(name, eval);
      }
   }

   private static double pct(int hit, int n) {
      return n == 0 ? 0.0 : hit * 100.0 / n;
   }

   private static void muteLogs() {
      try {
         Logger root = (Logger)LoggerFactory.getLogger("ROOT");
         root.setLevel(Level.ERROR);
         ((Logger)LoggerFactory.getLogger("com.zfl.caipiao.utils")).setLevel(Level.ERROR);
      } catch (Throwable var1) {
      }
   }

   static final class Result {
      final String name;
      final int n;
      int posSum;
      final int[] pos = new int[3];
      int anyPos;
      int full;
      boolean pass;

      Result(String name, int n) {
         this.name = name;
         this.n = n;
      }

      static DanMaBacktest.Result fail(String name, int n) {
         DanMaBacktest.Result r = new DanMaBacktest.Result(name, n);
         r.pass = false;
         return r;
      }
   }
}
