package com.zfl.caipiao.cache;

import com.zfl.caipiao.export.Hm;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * @author zfl
 */
public class HmCache {

    private static final Integer COMPARE_SAVE_SIZE = 200;

    private static final List<Hm> SD_CACHE = new ArrayList<>();

    private static final List<Hm> PL3_CACHE = new ArrayList<>();

    private static final List<CompareDto> SD_COMPARE_CACHE = new ArrayList<>();

    private static final List<CompareDto> PL3_COMPARE_CACHE = new ArrayList<>();

    private static final List<DadiCompareDto> SD_DADI_COMPARE_CACHE = new ArrayList<>();

    private static final List<DadiCompareDto> PL3_DADI_COMPARE_CACHE = new ArrayList<>();

    private static final List<PnlRecordDto> PNL_CACHE = new ArrayList<>();

    public static List<Hm> getSdCache() {
        return SD_CACHE;
    }

    public static List<Hm> getPl3Cache() {
        return PL3_CACHE;
    }

    public static List<CompareDto> getSdCompareCache() {
        return SD_COMPARE_CACHE;
    }

    public static List<CompareDto> getPl3CompareCache() {
        return PL3_COMPARE_CACHE;
    }

    public static List<DadiCompareDto> getSdDadiCompareCache() {
        return SD_DADI_COMPARE_CACHE;
    }

    public static List<DadiCompareDto> getPl3DadiCompareCache() {
        return PL3_DADI_COMPARE_CACHE;
    }

    public static List<PnlRecordDto> getPnlCache() {
        return PNL_CACHE;
    }

    public static void addPnlCache(PnlRecordDto dto) {
        PNL_CACHE.add(dto);
    }

    public static void setPnlCache(List<PnlRecordDto> dtos) {
        PNL_CACHE.clear();
        PNL_CACHE.addAll(dtos);
    }

    public static void addSdCompareCache(CompareDto compareDto) {
        upsertPendingCompare(SD_COMPARE_CACHE, compareDto);
    }

    public static void addPl3CompareCache(CompareDto compareDto) {
        upsertPendingCompare(PL3_COMPARE_CACHE, compareDto);
    }

    /**
     * 同期重复预测：若最新一条尚未开奖（realHm 为空），覆盖更新，避免追加脏数据导致结果漂移。
     */
    private static void upsertPendingCompare(List<CompareDto> cache, CompareDto compareDto) {
        if (compareDto == null) {
            return;
        }
        if (!cache.isEmpty()) {
            CompareDto last = cache.get(cache.size() - 1);
            if (last.getRealHm() == null || last.getRealHm().isBlank()) {
                last.setAiHm(compareDto.getAiHm());
                last.setAiFullHm(compareDto.getAiFullHm());
                last.setAiZuSanHm(compareDto.getAiZuSanHm());
                last.setAiRecommendHm(compareDto.getAiRecommendHm());
                if (compareDto.getAiOverfitHm() != null) {
                    last.setAiOverfitHm(compareDto.getAiOverfitHm());
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

    public static void addSdDadiCompareCache(DadiCompareDto dadiCompareDto) {
        if (SD_DADI_COMPARE_CACHE.size() >= COMPARE_SAVE_SIZE) {
            SD_DADI_COMPARE_CACHE.remove(0);
        }
        SD_DADI_COMPARE_CACHE.add(dadiCompareDto);
    }

    public static void addPl3DadiCompareCache(DadiCompareDto dadiCompareDto) {
        if (PL3_DADI_COMPARE_CACHE.size() >= COMPARE_SAVE_SIZE) {
            PL3_DADI_COMPARE_CACHE.remove(0);
        }
        PL3_DADI_COMPARE_CACHE.add(dadiCompareDto);
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

    public static void setSdCompareCache(List<CompareDto> compareDtos) {
        SD_COMPARE_CACHE.addAll(compareDtos);
    }

    public static void setPl3CompareCache(List<CompareDto> compareDtos) {
        PL3_COMPARE_CACHE.addAll(compareDtos);
    }

    public static void setSdDadiCompareCache(List<DadiCompareDto> dadiCompareDtos) {
        SD_DADI_COMPARE_CACHE.clear();
        SD_DADI_COMPARE_CACHE.addAll(dadiCompareDtos);
    }

    public static void setPl3DadiCompareCache(List<DadiCompareDto> dadiCompareDtos) {
        PL3_DADI_COMPARE_CACHE.clear();
        PL3_DADI_COMPARE_CACHE.addAll(dadiCompareDtos);
    }

    @Data
    @Accessors(chain = true)
    public static class CompareDto{

        private String qh;

        /** 落盘/展示大底：组选形态去重后（同号不同序只留第一注） */
        private String aiHm;

        /** 原始≤200注大底（未去重），供邮件选号与命中位次统计 */
        private String aiFullHm;

        /** 组三组选（去重形态，如 112,334） */
        private String aiZuSanHm;

        /** 展示/邮件推荐注（固定10注，基于原始200注挑选） */
        private String aiRecommendHm;

        /** 近20期过拟合五组（动态覆盖算法，非硬编码） */
        private String aiOverfitHm;

        private String aiDingWeiHm;

        private String realHm;

    }

    @Data
    @Accessors(chain = true)
    public static class DadiCompareDto {

        private String qh;

        private String cursorDadiHm;

        private String customDadiHm;

        private String realHm;

    }

    @Data
    @Accessors(chain = true)
    public static class PnlRecordDto {

        private String date;

        private Double ticketAmount;

        private Double winAmount;

    }
}