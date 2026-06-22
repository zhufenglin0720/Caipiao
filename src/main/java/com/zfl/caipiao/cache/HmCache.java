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

    private static final Integer COMPARE_SAVE_SIZE = 50;

    private static final List<Hm> SD_CACHE = new ArrayList<>();

    private static final List<Hm> PL3_CACHE = new ArrayList<>();

    private static final List<CompareDto> SD_COMPARE_CACHE = new ArrayList<>();

    private static final List<CompareDto> PL3_COMPARE_CACHE = new ArrayList<>();

    private static final List<DadiCompareDto> SD_DADI_COMPARE_CACHE = new ArrayList<>();

    private static final List<DadiCompareDto> PL3_DADI_COMPARE_CACHE = new ArrayList<>();

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

    public static void addSdCompareCache(CompareDto compareDto) {
        if(SD_COMPARE_CACHE.size() >= COMPARE_SAVE_SIZE){
            SD_COMPARE_CACHE.remove(0);
        }
        SD_COMPARE_CACHE.add(compareDto);
    }

    public static void addPl3CompareCache(CompareDto compareDto) {
        if(PL3_COMPARE_CACHE.size() >= COMPARE_SAVE_SIZE){
            PL3_COMPARE_CACHE.remove(0);
        }
        PL3_COMPARE_CACHE.add(compareDto);
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

        private String aiHm;

        private String aiDingWeiHm;

        private String realHm;

    }

    @Data
    @Accessors(chain = true)
    public static class DadiCompareDto {

        private String qh;

        private String aiDadiHm;

        private String realHm;

    }
}