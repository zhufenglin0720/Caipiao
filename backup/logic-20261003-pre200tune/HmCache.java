package com.zfl.caipiao.cache;

import com.zfl.caipiao.export.Hm;
import java.util.ArrayList;
import java.util.List;

public class HmCache {
   private static final Integer COMPARE_SAVE_SIZE = 200;
   private static final List<Hm> SD_CACHE = new ArrayList<>();
   private static final List<Hm> PL3_CACHE = new ArrayList<>();
   private static final List<HmCache.CompareDto> SD_COMPARE_CACHE = new ArrayList<>();
   private static final List<HmCache.CompareDto> PL3_COMPARE_CACHE = new ArrayList<>();
   private static final List<HmCache.DadiCompareDto> SD_DADI_COMPARE_CACHE = new ArrayList<>();
   private static final List<HmCache.DadiCompareDto> PL3_DADI_COMPARE_CACHE = new ArrayList<>();
   private static final List<HmCache.PnlRecordDto> PNL_CACHE = new ArrayList<>();

   public static List<Hm> getSdCache() {
      return SD_CACHE;
   }

   public static List<Hm> getPl3Cache() {
      return PL3_CACHE;
   }

   public static List<HmCache.CompareDto> getSdCompareCache() {
      return SD_COMPARE_CACHE;
   }

   public static List<HmCache.CompareDto> getPl3CompareCache() {
      return PL3_COMPARE_CACHE;
   }

   public static List<HmCache.DadiCompareDto> getSdDadiCompareCache() {
      return SD_DADI_COMPARE_CACHE;
   }

   public static List<HmCache.DadiCompareDto> getPl3DadiCompareCache() {
      return PL3_DADI_COMPARE_CACHE;
   }

   public static List<HmCache.PnlRecordDto> getPnlCache() {
      return PNL_CACHE;
   }

   public static void addPnlCache(HmCache.PnlRecordDto dto) {
      PNL_CACHE.add(dto);
   }

   public static void setPnlCache(List<HmCache.PnlRecordDto> dtos) {
      PNL_CACHE.clear();
      PNL_CACHE.addAll(dtos);
   }

   public static void addSdCompareCache(HmCache.CompareDto compareDto) {
      upsertPendingCompare(SD_COMPARE_CACHE, compareDto);
   }

   public static void addPl3CompareCache(HmCache.CompareDto compareDto) {
      upsertPendingCompare(PL3_COMPARE_CACHE, compareDto);
   }

   private static void upsertPendingCompare(List<HmCache.CompareDto> cache, HmCache.CompareDto compareDto) {
      if (compareDto != null) {
         if (!cache.isEmpty()) {
            HmCache.CompareDto last = cache.get(cache.size() - 1);
            if (last.getRealHm() == null || last.getRealHm().isBlank()) {
               last.setAiHm(compareDto.getAiHm());
               last.setAiFullHm(compareDto.getAiFullHm());
               last.setAiZuSanHm(compareDto.getAiZuSanHm());
               last.setAiRecommendHm(compareDto.getAiRecommendHm());
               if (compareDto.getAiOverfitHm() != null) {
                  last.setAiOverfitHm(compareDto.getAiOverfitHm());
               }

               if (compareDto.getAiDanMaHm() != null) {
                  last.setAiDanMaHm(compareDto.getAiDanMaHm());
               }

               if (compareDto.getAiDingWeiHm() != null) {
                  last.setAiDingWeiHm(compareDto.getAiDingWeiHm());
               }

               if (compareDto.getQh() != null) {
                  last.setQh(compareDto.getQh());
               }

               return;
            }
         }

         if (cache.size() >= COMPARE_SAVE_SIZE) {
            cache.remove(0);
         }

         cache.add(compareDto);
      }
   }

   public static void addSdDadiCompareCache(HmCache.DadiCompareDto dadiCompareDto) {
      SD_DADI_COMPARE_CACHE.add(0, dadiCompareDto);
   }

   public static void addPl3DadiCompareCache(HmCache.DadiCompareDto dadiCompareDto) {
      PL3_DADI_COMPARE_CACHE.add(0, dadiCompareDto);
   }

   public static void addSdCache(Hm hm) {
      SD_CACHE.add(hm);
   }

   public static void addPl3Cache(Hm hm) {
      PL3_CACHE.add(hm);
   }

   public static void setSdCache(List<Hm> sdCache) {
      SD_CACHE.addAll(sdCache);
   }

   public static void setPl3Cache(List<Hm> pl3Cache) {
      PL3_CACHE.addAll(pl3Cache);
   }

   public static void setSdCompareCache(List<HmCache.CompareDto> compareDtos) {
      SD_COMPARE_CACHE.addAll(compareDtos);
   }

   public static void setPl3CompareCache(List<HmCache.CompareDto> compareDtos) {
      PL3_COMPARE_CACHE.addAll(compareDtos);
   }

   public static void setSdDadiCompareCache(List<HmCache.DadiCompareDto> dadiCompareDtos) {
      SD_DADI_COMPARE_CACHE.clear();
      SD_DADI_COMPARE_CACHE.addAll(dadiCompareDtos);
   }

   public static void setPl3DadiCompareCache(List<HmCache.DadiCompareDto> dadiCompareDtos) {
      PL3_DADI_COMPARE_CACHE.clear();
      PL3_DADI_COMPARE_CACHE.addAll(dadiCompareDtos);
   }

   public static class CompareDto {
      private String qh;
      private String aiHm;
      private String aiFullHm;
      private String aiZuSanHm;
      private String aiRecommendHm;
      private String aiOverfitHm;
      private String aiDanMaHm;
      private String aiDingWeiHm;
      private String realHm;
      public String getQh() {
         return this.qh;
      }
      public String getAiHm() {
         return this.aiHm;
      }
      public String getAiFullHm() {
         return this.aiFullHm;
      }
      public String getAiZuSanHm() {
         return this.aiZuSanHm;
      }
      public String getAiRecommendHm() {
         return this.aiRecommendHm;
      }
      public String getAiOverfitHm() {
         return this.aiOverfitHm;
      }
      public String getAiDanMaHm() {
         return this.aiDanMaHm;
      }
      public String getAiDingWeiHm() {
         return this.aiDingWeiHm;
      }
      public String getRealHm() {
         return this.realHm;
      }
      public HmCache.CompareDto setQh(final String qh) {
         this.qh = qh;
         return this;
      }
      public HmCache.CompareDto setAiHm(final String aiHm) {
         this.aiHm = aiHm;
         return this;
      }
      public HmCache.CompareDto setAiFullHm(final String aiFullHm) {
         this.aiFullHm = aiFullHm;
         return this;
      }
      public HmCache.CompareDto setAiZuSanHm(final String aiZuSanHm) {
         this.aiZuSanHm = aiZuSanHm;
         return this;
      }
      public HmCache.CompareDto setAiRecommendHm(final String aiRecommendHm) {
         this.aiRecommendHm = aiRecommendHm;
         return this;
      }
      public HmCache.CompareDto setAiOverfitHm(final String aiOverfitHm) {
         this.aiOverfitHm = aiOverfitHm;
         return this;
      }
      public HmCache.CompareDto setAiDanMaHm(final String aiDanMaHm) {
         this.aiDanMaHm = aiDanMaHm;
         return this;
      }
      public HmCache.CompareDto setAiDingWeiHm(final String aiDingWeiHm) {
         this.aiDingWeiHm = aiDingWeiHm;
         return this;
      }
      public HmCache.CompareDto setRealHm(final String realHm) {
         this.realHm = realHm;
         return this;
      }
      @Override
      public boolean equals(final Object o) {
         if (o == this) {
            return true;
         } else if (!(o instanceof HmCache.CompareDto other)) {
            return false;
         } else if (!other.canEqual(this)) {
            return false;
         } else {
            Object this$qh = this.getQh();
            Object other$qh = other.getQh();
            if (this$qh == null ? other$qh == null : this$qh.equals(other$qh)) {
               Object this$aiHm = this.getAiHm();
               Object other$aiHm = other.getAiHm();
               if (this$aiHm == null ? other$aiHm == null : this$aiHm.equals(other$aiHm)) {
                  Object this$aiFullHm = this.getAiFullHm();
                  Object other$aiFullHm = other.getAiFullHm();
                  if (this$aiFullHm == null ? other$aiFullHm == null : this$aiFullHm.equals(other$aiFullHm)) {
                     Object this$aiZuSanHm = this.getAiZuSanHm();
                     Object other$aiZuSanHm = other.getAiZuSanHm();
                     if (this$aiZuSanHm == null ? other$aiZuSanHm == null : this$aiZuSanHm.equals(other$aiZuSanHm)) {
                        Object this$aiRecommendHm = this.getAiRecommendHm();
                        Object other$aiRecommendHm = other.getAiRecommendHm();
                        if (this$aiRecommendHm == null ? other$aiRecommendHm == null : this$aiRecommendHm.equals(other$aiRecommendHm)) {
                           Object this$aiOverfitHm = this.getAiOverfitHm();
                           Object other$aiOverfitHm = other.getAiOverfitHm();
                           if (this$aiOverfitHm == null ? other$aiOverfitHm == null : this$aiOverfitHm.equals(other$aiOverfitHm)) {
                              Object this$aiDanMaHm = this.getAiDanMaHm();
                              Object other$aiDanMaHm = other.getAiDanMaHm();
                              if (this$aiDanMaHm == null ? other$aiDanMaHm == null : this$aiDanMaHm.equals(other$aiDanMaHm)) {
                                 Object this$aiDingWeiHm = this.getAiDingWeiHm();
                                 Object other$aiDingWeiHm = other.getAiDingWeiHm();
                                 if (this$aiDingWeiHm == null ? other$aiDingWeiHm == null : this$aiDingWeiHm.equals(other$aiDingWeiHm)) {
                                    Object this$realHm = this.getRealHm();
                                    Object other$realHm = other.getRealHm();
                                    return this$realHm == null ? other$realHm == null : this$realHm.equals(other$realHm);
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
               } else {
                  return false;
               }
            } else {
               return false;
            }
         }
      }
      protected boolean canEqual(final Object other) {
         return other instanceof HmCache.CompareDto;
      }
      @Override
      public int hashCode() {
         int PRIME = 59;
         int result = 1;
         Object $qh = this.getQh();
         result = result * 59 + ($qh == null ? 43 : $qh.hashCode());
         Object $aiHm = this.getAiHm();
         result = result * 59 + ($aiHm == null ? 43 : $aiHm.hashCode());
         Object $aiFullHm = this.getAiFullHm();
         result = result * 59 + ($aiFullHm == null ? 43 : $aiFullHm.hashCode());
         Object $aiZuSanHm = this.getAiZuSanHm();
         result = result * 59 + ($aiZuSanHm == null ? 43 : $aiZuSanHm.hashCode());
         Object $aiRecommendHm = this.getAiRecommendHm();
         result = result * 59 + ($aiRecommendHm == null ? 43 : $aiRecommendHm.hashCode());
         Object $aiOverfitHm = this.getAiOverfitHm();
         result = result * 59 + ($aiOverfitHm == null ? 43 : $aiOverfitHm.hashCode());
         Object $aiDanMaHm = this.getAiDanMaHm();
         result = result * 59 + ($aiDanMaHm == null ? 43 : $aiDanMaHm.hashCode());
         Object $aiDingWeiHm = this.getAiDingWeiHm();
         result = result * 59 + ($aiDingWeiHm == null ? 43 : $aiDingWeiHm.hashCode());
         Object $realHm = this.getRealHm();
         return result * 59 + ($realHm == null ? 43 : $realHm.hashCode());
      }
      @Override
      public String toString() {
         return "HmCache.CompareDto(qh="
            + this.getQh()
            + ", aiHm="
            + this.getAiHm()
            + ", aiFullHm="
            + this.getAiFullHm()
            + ", aiZuSanHm="
            + this.getAiZuSanHm()
            + ", aiRecommendHm="
            + this.getAiRecommendHm()
            + ", aiOverfitHm="
            + this.getAiOverfitHm()
            + ", aiDanMaHm="
            + this.getAiDanMaHm()
            + ", aiDingWeiHm="
            + this.getAiDingWeiHm()
            + ", realHm="
            + this.getRealHm()
            + ")";
      }
   }

   public static class DadiCompareDto {
      private String qh;
      private String cursorDadiHm;
      private String customDadiHm;
      private String realHm;
      public String getQh() {
         return this.qh;
      }
      public String getCursorDadiHm() {
         return this.cursorDadiHm;
      }
      public String getCustomDadiHm() {
         return this.customDadiHm;
      }
      public String getRealHm() {
         return this.realHm;
      }
      public HmCache.DadiCompareDto setQh(final String qh) {
         this.qh = qh;
         return this;
      }
      public HmCache.DadiCompareDto setCursorDadiHm(final String cursorDadiHm) {
         this.cursorDadiHm = cursorDadiHm;
         return this;
      }
      public HmCache.DadiCompareDto setCustomDadiHm(final String customDadiHm) {
         this.customDadiHm = customDadiHm;
         return this;
      }
      public HmCache.DadiCompareDto setRealHm(final String realHm) {
         this.realHm = realHm;
         return this;
      }
      @Override
      public boolean equals(final Object o) {
         if (o == this) {
            return true;
         } else if (!(o instanceof HmCache.DadiCompareDto other)) {
            return false;
         } else if (!other.canEqual(this)) {
            return false;
         } else {
            Object this$qh = this.getQh();
            Object other$qh = other.getQh();
            if (this$qh == null ? other$qh == null : this$qh.equals(other$qh)) {
               Object this$cursorDadiHm = this.getCursorDadiHm();
               Object other$cursorDadiHm = other.getCursorDadiHm();
               if (this$cursorDadiHm == null ? other$cursorDadiHm == null : this$cursorDadiHm.equals(other$cursorDadiHm)) {
                  Object this$customDadiHm = this.getCustomDadiHm();
                  Object other$customDadiHm = other.getCustomDadiHm();
                  if (this$customDadiHm == null ? other$customDadiHm == null : this$customDadiHm.equals(other$customDadiHm)) {
                     Object this$realHm = this.getRealHm();
                     Object other$realHm = other.getRealHm();
                     return this$realHm == null ? other$realHm == null : this$realHm.equals(other$realHm);
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
         return other instanceof HmCache.DadiCompareDto;
      }
      @Override
      public int hashCode() {
         int PRIME = 59;
         int result = 1;
         Object $qh = this.getQh();
         result = result * 59 + ($qh == null ? 43 : $qh.hashCode());
         Object $cursorDadiHm = this.getCursorDadiHm();
         result = result * 59 + ($cursorDadiHm == null ? 43 : $cursorDadiHm.hashCode());
         Object $customDadiHm = this.getCustomDadiHm();
         result = result * 59 + ($customDadiHm == null ? 43 : $customDadiHm.hashCode());
         Object $realHm = this.getRealHm();
         return result * 59 + ($realHm == null ? 43 : $realHm.hashCode());
      }
      @Override
      public String toString() {
         return "HmCache.DadiCompareDto(qh="
            + this.getQh()
            + ", cursorDadiHm="
            + this.getCursorDadiHm()
            + ", customDadiHm="
            + this.getCustomDadiHm()
            + ", realHm="
            + this.getRealHm()
            + ")";
      }
   }

   public static class PnlRecordDto {
      private String date;
      private Double ticketAmount;
      private Double winAmount;
      public String getDate() {
         return this.date;
      }
      public Double getTicketAmount() {
         return this.ticketAmount;
      }
      public Double getWinAmount() {
         return this.winAmount;
      }
      public HmCache.PnlRecordDto setDate(final String date) {
         this.date = date;
         return this;
      }
      public HmCache.PnlRecordDto setTicketAmount(final Double ticketAmount) {
         this.ticketAmount = ticketAmount;
         return this;
      }
      public HmCache.PnlRecordDto setWinAmount(final Double winAmount) {
         this.winAmount = winAmount;
         return this;
      }
      @Override
      public boolean equals(final Object o) {
         if (o == this) {
            return true;
         } else if (!(o instanceof HmCache.PnlRecordDto other)) {
            return false;
         } else if (!other.canEqual(this)) {
            return false;
         } else {
            Object this$ticketAmount = this.getTicketAmount();
            Object other$ticketAmount = other.getTicketAmount();
            if (this$ticketAmount == null ? other$ticketAmount == null : this$ticketAmount.equals(other$ticketAmount)) {
               Object this$winAmount = this.getWinAmount();
               Object other$winAmount = other.getWinAmount();
               if (this$winAmount == null ? other$winAmount == null : this$winAmount.equals(other$winAmount)) {
                  Object this$date = this.getDate();
                  Object other$date = other.getDate();
                  return this$date == null ? other$date == null : this$date.equals(other$date);
               } else {
                  return false;
               }
            } else {
               return false;
            }
         }
      }
      protected boolean canEqual(final Object other) {
         return other instanceof HmCache.PnlRecordDto;
      }
      @Override
      public int hashCode() {
         int PRIME = 59;
         int result = 1;
         Object $ticketAmount = this.getTicketAmount();
         result = result * 59 + ($ticketAmount == null ? 43 : $ticketAmount.hashCode());
         Object $winAmount = this.getWinAmount();
         result = result * 59 + ($winAmount == null ? 43 : $winAmount.hashCode());
         Object $date = this.getDate();
         return result * 59 + ($date == null ? 43 : $date.hashCode());
      }
      @Override
      public String toString() {
         return "HmCache.PnlRecordDto(date=" + this.getDate() + ", ticketAmount=" + this.getTicketAmount() + ", winAmount=" + this.getWinAmount() + ")";
      }
   }
}
