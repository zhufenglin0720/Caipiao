package com.zfl.caipiao.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.zfl.caipiao.export.Hm;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.LoggerFactory;

public final class Overfit20Backtest {
   private static final int ZX_TARGET = 4;
   private static final int GROUP_TARGET = 3;

   private Overfit20Backtest() {
   }

   public static void main(String[] args) throws Exception {
      muteLogs();
      StringBuilder sb = new StringBuilder();
      sb.append("========== 近").append(30).append("期开奖过拟合 · 只回测近").append(10).append("期（不做往期） ==========\n");
      sb.append("规则：每期仅用之前近").append(30).append("期开奖；从最新期往前、只在近").append(10).append("期上动态调参（band/配额/预测单位置±1）；只推").append(250).append("组\n");
      sb.append("目标：直选≥").append(4).append("、组选≥").append(3).append('\n');
      sb.append("禁止硬编码开奖号与写死 band/槽位表。\n\n");
      Overfit20Backtest.Result sd = runOne("福彩3D", HistoryDataLoader.load3d(), Overfit20PredictUtils.GameKind.SD, sb);
      sb.append('\n');
      Overfit20Backtest.Result pl3 = runOne("排列三", HistoryDataLoader.loadPl3(), Overfit20PredictUtils.GameKind.PL3, sb);
      sb.append("\n========== 汇总 ==========\n");
      sb.append(String.format(Locale.ROOT, "%-8s | 直选 | 组选 | 结果%n", "彩种"));
      appendRow(sb, sd);
      appendRow(sb, pl3);
      boolean allPass = sd.pass && pl3.pass;
      sb.append(allPass ? "\n【全部达标】\n" : "\n【存在未达标】\n");
      Path out = Path.of("reports/overfit20_backtest.txt");
      Files.createDirectories(out.getParent());
      Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
      sb.append("结果已写入: ").append(out.toAbsolutePath()).append('\n');
      System.out.println(sb);
      if (!allPass) {
         System.exit(2);
      }
   }

   private static void appendRow(StringBuilder sb, Overfit20Backtest.Result r) {
      sb.append(String.format(Locale.ROOT, "%-8s | %d/%d | %d/%d | %s%n", r.name, r.zx, r.n, r.group, r.n, r.pass ? "达标" : "未达标"));
   }

   static Overfit20Backtest.Result runOne(String name, List<Hm> all, Overfit20PredictUtils.GameKind kind, StringBuilder out) {
      out.append("---------- ").append(name).append(" ----------\n");
      if (all != null && !all.isEmpty()) {
         if (all.size() < 40) {
            out.append("历史不足\n");
            return Overfit20Backtest.Result.fail(name);
         } else {
            int eval = 10;
            int zx = 0;
            int group = 0;
            List<String> details = new ArrayList<>();
            long t0 = System.currentTimeMillis();

            for (int i = all.size() - eval; i < all.size(); i++) {
               List<Hm> hist = all.subList(Math.max(0, i - 30), i);
               Overfit20PredictUtils.PredictResult pred = Overfit20PredictUtils.predictResult(hist, kind);
               String actual = Overfit20PredictUtils.pad3(all.get(i).toString());
               String qh = all.get(i).getQh();
               boolean hitZx = Overfit20PredictUtils.isZxHit(pred.pool, actual);
               boolean hitGp = Overfit20PredictUtils.isGroupHit(pred.pool, actual);
               boolean near = Overfit20PredictUtils.isPlusMinus1NearMiss(pred.pool, actual);
               if (hitZx) {
                  zx++;
               }

               if (hitGp) {
                  group++;
               }

               details.add(
                  String.format(
                     Locale.ROOT,
                     "期号=%s 组合池(%d注)=%s 开奖=%s 直选=%s 组选=%s ±1近失=%s | %s",
                     qh,
                     pred.pool.size(),
                     pred.poolCsv(),
                     actual,
                     hitZx ? "是" : "否",
                     hitGp ? "是" : "否",
                     near ? "是" : "否",
                     pred.tune
                  )
               );
            }

            long cost = System.currentTimeMillis() - t0;
            boolean pass = zx >= 4 && group >= 3;
            out.append(String.format(Locale.ROOT, "回测完成：评估=%d期 耗时=%dms%n", eval, cost));
            out.append(Overfit20PredictUtils.summarizeHits(zx, group, eval)).append('\n');
            out.append("--- 逐期明细（组合池 / 开奖 / 命中）---\n");

            for (String d : details) {
               out.append(d).append('\n');
            }

            out.append(String.format(Locale.ROOT, "【%s】直选%d 组选%d %s%n", name, zx, group, pass ? "达标" : "未达标"));
            return new Overfit20Backtest.Result(name, zx, group, eval, pass);
         }
      } else {
         out.append("无数据\n");
         return Overfit20Backtest.Result.fail(name);
      }
   }

   private static void muteLogs() {
      try {
         Logger root = (Logger)LoggerFactory.getLogger("ROOT");
         root.setLevel(Level.WARN);
         ((Logger)LoggerFactory.getLogger("com.zfl.caipiao.utils")).setLevel(Level.WARN);
      } catch (Throwable var1) {
      }
   }

   static final class Result {
      final String name;
      final int zx;
      final int group;
      final int n;
      final boolean pass;

      Result(String name, int zx, int group, int n, boolean pass) {
         this.name = name;
         this.zx = zx;
         this.group = group;
         this.n = n;
         this.pass = pass;
      }

      static Overfit20Backtest.Result fail(String name) {
         return new Overfit20Backtest.Result(name, 0, 0, 10, false);
      }
   }
}
