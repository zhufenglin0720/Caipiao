package com.zfl.caipiao.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.annotation.ExcelProperty;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.DadiCompareVO;
import com.zfl.caipiao.export.Hm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.slf4j.LoggerFactory;

public final class DadiRecentExcelExport {
   private static final int EVAL = 30;
   private static final int WARMUP = 110;

   private DadiRecentExcelExport() {
   }

   public static void main(String[] args) throws Exception {
      muteLogs();
      int eval = 30;
      if (args != null && args.length > 0) {
         eval = Integer.parseInt(args[0]);
      }

      Path out = Path.of("X:\\彩票\\大底回测近" + eval + "期.xlsx");
      if (args != null && args.length > 1) {
         out = Path.of(args[1]);
      }

      Files.createDirectories(out.getParent());
      List<DadiRecentExcelExport.Row> sd = run("福彩3D", HistoryDataLoader.load3d(), RuleBasedPredictUtils.GameKind.SD_3D, eval);
      List<DadiRecentExcelExport.Row> pl3 = run("排列三", HistoryDataLoader.loadPl3(), RuleBasedPredictUtils.GameKind.PL3, eval);
      Collections.reverse(sd);
      Collections.reverse(pl3);
      ExcelWriter writer = EasyExcel.write(out.toString(), DadiRecentExcelExport.Row.class).build();
      writer.write(sd, EasyExcel.writerSheet("福彩3D").build());
      writer.write(pl3, EasyExcel.writerSheet("排列三").build());
      writer.finish();
      writeOfficial(Path.of("X:\\彩票\\3D大底对比.xlsx"), "3D大底比对", sd);
      writeOfficial(Path.of("X:\\彩票\\排列三大底对比.xlsx"), "排列三大底比对", pl3);
      System.out.println("已写入: " + out.toAbsolutePath());
      printSummary("福彩3D", sd);
      printSummary("排列三", pl3);
   }

   private static void printSummary(String name, List<DadiRecentExcelExport.Row> rows) {
      int zx = 0;
      int grp = 0;

      for (DadiRecentExcelExport.Row row : rows) {
         if ("是".equals(row.zxHit)) {
            zx++;
         }

         if ("是".equals(row.groupHit)) {
            grp++;
         }
      }

      String from = rows.isEmpty() ? "-" : rows.get(0).qh;
      String to = rows.isEmpty() ? "-" : rows.get(rows.size() - 1).qh;
      System.out.printf("%s %d期 最新%s → 最早%s 直选=%d 组选=%d%n", name, rows.size(), from, to, zx, grp);
   }

   private static void writeOfficial(Path file, String sheet, List<DadiRecentExcelExport.Row> rows) throws Exception {
      Files.createDirectories(file.getParent());
      List<DadiCompareVO> list = new ArrayList<>();

      for (DadiRecentExcelExport.Row row : rows) {
         list.add(DadiCompareVO.builder().qh(row.getQh()).cursorDadiHm(row.getCursorDadiHm()).realHm(row.getRealHm()).build());
      }

      EasyExcel.write(file.toString(), DadiCompareVO.class).sheet(sheet).doWrite(list);
      System.out.println("已写入: " + file.toAbsolutePath());
   }

   private static List<DadiRecentExcelExport.Row> run(String name, List<Hm> all, RuleBasedPredictUtils.GameKind kind, int eval) {
      if (all != null && all.size() >= eval + 110) {
         int start = all.size() - eval;
         int warmStart = Math.max(60, start - 110);
         List<HmCache.CompareDto> compares = new ArrayList<>();

         for (int i = warmStart; i < start; i++) {
            String pred = RuleBasedPredictUtils.predict(all.subList(0, i), compares, kind);
            compares.add(
               new HmCache.CompareDto()
                  .setQh(all.get(i).getQh())
                  .setAiHm(pred == null ? "" : pred)
                  .setAiFullHm(pred == null ? "" : pred)
                  .setRealHm(pad3(all.get(i).toString()))
            );
            trim(compares);
         }

         List<DadiRecentExcelExport.Row> rows = new ArrayList<>();

         for (int i = start; i < all.size(); i++) {
            Hm actualHm = all.get(i);
            String actual = pad3(actualHm.toString());
            String pred = RuleBasedPredictUtils.predict(all.subList(0, i), compares, kind);
            if (pred == null) {
               pred = "";
            }

            int pos = zxPos(pred, actual);
            rows.add(
               DadiRecentExcelExport.Row.builder()
                  .qh(actualHm.getQh())
                  .cursorDadiHm(pred)
                  .realHm(actual)
                  .betCount(pred.isBlank() ? 0 : pred.split(",").length)
                  .zxHit(pos > 0 ? "是" : "否")
                  .zxPos(pos > 0 ? pos : null)
                  .groupHit(groupHit(pred, actual) ? "是" : "否")
                  .build()
            );
            compares.add(new HmCache.CompareDto().setQh(actualHm.getQh()).setAiHm(pred).setAiFullHm(pred).setRealHm(actual));
            trim(compares);
            System.out.printf("%s %d/%d 期号=%s 开奖=%s 直选=%s%n", name, i - start + 1, eval, actualHm.getQh(), actual, pos > 0 ? "是" : "否");
         }

         return rows;
      } else {
         throw new IllegalStateException(name + " 历史不足");
      }
   }

   private static void trim(List<HmCache.CompareDto> compares) {
      int max = Math.max(20, 100) + 5;

      while (compares.size() > max) {
         compares.remove(0);
      }
   }

   private static int zxPos(String pred, String actual) {
      if (pred != null && !pred.isBlank()) {
         String[] parts = pred.split(",");

         for (int i = 0; i < parts.length; i++) {
            if (pad3(parts[i]).equals(actual)) {
               return i + 1;
            }
         }

         return 0;
      } else {
         return 0;
      }
   }

   private static boolean groupHit(String pred, String actual) {
      if (pred != null && !pred.isBlank()) {
         String key = sorted(actual);

         for (String part : pred.split(",")) {
            String t = pad3(part);
            if (t.length() == 3 && sorted(t).equals(key)) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private static String sorted(String s) {
      char[] c = s.toCharArray();
      Arrays.sort(c);
      return new String(c);
   }

   private static String pad3(String s) {
      if (s == null) {
         return "";
      } else {
         String t = s.trim();
         return t.length() >= 3 ? t.substring(t.length() - 3) : "0".repeat(Math.max(0, 3 - t.length())) + t;
      }
   }

   private static void muteLogs() {
      try {
         Logger root = (Logger)LoggerFactory.getLogger("ROOT");
         root.setLevel(Level.ERROR);
      } catch (Throwable var1) {
      }
   }

   public static class Row {
      @ExcelProperty({"期号"})
      private String qh;
      @ExcelProperty({"Cursor500注大底"})
      private String cursorDadiHm;
      @ExcelProperty({"真实号码"})
      private String realHm;
      @ExcelProperty({"注数"})
      private Integer betCount;
      @ExcelProperty({"直选命中"})
      private String zxHit;
      @ExcelProperty({"直选位次"})
      private Integer zxPos;
      @ExcelProperty({"组选命中"})
      private String groupHit;
      Row(
         final String qh,
         final String cursorDadiHm,
         final String realHm,
         final Integer betCount,
         final String zxHit,
         final Integer zxPos,
         final String groupHit
      ) {
         this.qh = qh;
         this.cursorDadiHm = cursorDadiHm;
         this.realHm = realHm;
         this.betCount = betCount;
         this.zxHit = zxHit;
         this.zxPos = zxPos;
         this.groupHit = groupHit;
      }
      public static DadiRecentExcelExport.Row.RowBuilder builder() {
         return new DadiRecentExcelExport.Row.RowBuilder();
      }
      public String getQh() {
         return this.qh;
      }
      public String getCursorDadiHm() {
         return this.cursorDadiHm;
      }
      public String getRealHm() {
         return this.realHm;
      }
      public Integer getBetCount() {
         return this.betCount;
      }
      public String getZxHit() {
         return this.zxHit;
      }
      public Integer getZxPos() {
         return this.zxPos;
      }
      public String getGroupHit() {
         return this.groupHit;
      }
      public void setQh(final String qh) {
         this.qh = qh;
      }
      public void setCursorDadiHm(final String cursorDadiHm) {
         this.cursorDadiHm = cursorDadiHm;
      }
      public void setRealHm(final String realHm) {
         this.realHm = realHm;
      }
      public void setBetCount(final Integer betCount) {
         this.betCount = betCount;
      }
      public void setZxHit(final String zxHit) {
         this.zxHit = zxHit;
      }
      public void setZxPos(final Integer zxPos) {
         this.zxPos = zxPos;
      }
      public void setGroupHit(final String groupHit) {
         this.groupHit = groupHit;
      }
      @Override
      public boolean equals(final Object o) {
         if (o == this) {
            return true;
         } else if (!(o instanceof DadiRecentExcelExport.Row other)) {
            return false;
         } else if (!other.canEqual(this)) {
            return false;
         } else {
            Object this$betCount = this.getBetCount();
            Object other$betCount = other.getBetCount();
            if (this$betCount == null ? other$betCount == null : this$betCount.equals(other$betCount)) {
               Object this$zxPos = this.getZxPos();
               Object other$zxPos = other.getZxPos();
               if (this$zxPos == null ? other$zxPos == null : this$zxPos.equals(other$zxPos)) {
                  Object this$qh = this.getQh();
                  Object other$qh = other.getQh();
                  if (this$qh == null ? other$qh == null : this$qh.equals(other$qh)) {
                     Object this$cursorDadiHm = this.getCursorDadiHm();
                     Object other$cursorDadiHm = other.getCursorDadiHm();
                     if (this$cursorDadiHm == null ? other$cursorDadiHm == null : this$cursorDadiHm.equals(other$cursorDadiHm)) {
                        Object this$realHm = this.getRealHm();
                        Object other$realHm = other.getRealHm();
                        if (this$realHm == null ? other$realHm == null : this$realHm.equals(other$realHm)) {
                           Object this$zxHit = this.getZxHit();
                           Object other$zxHit = other.getZxHit();
                           if (this$zxHit == null ? other$zxHit == null : this$zxHit.equals(other$zxHit)) {
                              Object this$groupHit = this.getGroupHit();
                              Object other$groupHit = other.getGroupHit();
                              return this$groupHit == null ? other$groupHit == null : this$groupHit.equals(other$groupHit);
                           } else {
                              return false;
                           }
                        } else {
                           return false;
                        }
                     } else {
                        return false;
                     }
                  } else {
                     return false;
                  }
               } else {
                  return false;
               }
            } else {
               return false;
            }
         }
      }
      protected boolean canEqual(final Object other) {
         return other instanceof DadiRecentExcelExport.Row;
      }
      @Override
      public int hashCode() {
         int PRIME = 59;
         int result = 1;
         Object $betCount = this.getBetCount();
         result = result * 59 + ($betCount == null ? 43 : $betCount.hashCode());
         Object $zxPos = this.getZxPos();
         result = result * 59 + ($zxPos == null ? 43 : $zxPos.hashCode());
         Object $qh = this.getQh();
         result = result * 59 + ($qh == null ? 43 : $qh.hashCode());
         Object $cursorDadiHm = this.getCursorDadiHm();
         result = result * 59 + ($cursorDadiHm == null ? 43 : $cursorDadiHm.hashCode());
         Object $realHm = this.getRealHm();
         result = result * 59 + ($realHm == null ? 43 : $realHm.hashCode());
         Object $zxHit = this.getZxHit();
         result = result * 59 + ($zxHit == null ? 43 : $zxHit.hashCode());
         Object $groupHit = this.getGroupHit();
         return result * 59 + ($groupHit == null ? 43 : $groupHit.hashCode());
      }
      @Override
      public String toString() {
         return "DadiRecentExcelExport.Row(qh="
            + this.getQh()
            + ", cursorDadiHm="
            + this.getCursorDadiHm()
            + ", realHm="
            + this.getRealHm()
            + ", betCount="
            + this.getBetCount()
            + ", zxHit="
            + this.getZxHit()
            + ", zxPos="
            + this.getZxPos()
            + ", groupHit="
            + this.getGroupHit()
            + ")";
      }
      public static class RowBuilder {
         private String qh;
         private String cursorDadiHm;
         private String realHm;
         private Integer betCount;
         private String zxHit;
         private Integer zxPos;
         private String groupHit;
         RowBuilder() {
         }
         public DadiRecentExcelExport.Row.RowBuilder qh(final String qh) {
            this.qh = qh;
            return this;
         }
         public DadiRecentExcelExport.Row.RowBuilder cursorDadiHm(final String cursorDadiHm) {
            this.cursorDadiHm = cursorDadiHm;
            return this;
         }
         public DadiRecentExcelExport.Row.RowBuilder realHm(final String realHm) {
            this.realHm = realHm;
            return this;
         }
         public DadiRecentExcelExport.Row.RowBuilder betCount(final Integer betCount) {
            this.betCount = betCount;
            return this;
         }
         public DadiRecentExcelExport.Row.RowBuilder zxHit(final String zxHit) {
            this.zxHit = zxHit;
            return this;
         }
         public DadiRecentExcelExport.Row.RowBuilder zxPos(final Integer zxPos) {
            this.zxPos = zxPos;
            return this;
         }
         public DadiRecentExcelExport.Row.RowBuilder groupHit(final String groupHit) {
            this.groupHit = groupHit;
            return this;
         }
         public DadiRecentExcelExport.Row build() {
            return new DadiRecentExcelExport.Row(this.qh, this.cursorDadiHm, this.realHm, this.betCount, this.zxHit, this.zxPos, this.groupHit);
         }
         @Override
         public String toString() {
            return "DadiRecentExcelExport.Row.RowBuilder(qh="
               + this.qh
               + ", cursorDadiHm="
               + this.cursorDadiHm
               + ", realHm="
               + this.realHm
               + ", betCount="
               + this.betCount
               + ", zxHit="
               + this.zxHit
               + ", zxPos="
               + this.zxPos
               + ", groupHit="
               + this.groupHit
               + ")";
         }
      }
   }
}
