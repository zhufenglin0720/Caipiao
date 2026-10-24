package com.zfl.caipiao.utils;

import com.alibaba.excel.EasyExcel;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.CompareVO;
import com.zfl.caipiao.export.Hm;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 回补近 N 期双胆预测到对比缓存，并写回 Excel。
 */
public final class ShuangDanBackfill {
    private static final Logger log = LoggerFactory.getLogger(ShuangDanBackfill.class);
    public static final int DEFAULT_PERIODS = 60;
    private static final int MIN_HIST = 40;

    private ShuangDanBackfill() {
    }

    /**
     * 对缓存中近 {@code periods} 条对比记录补齐双胆；返回实际写入条数。
     */
    public static int backfillCache(List<Hm> history, List<HmCache.CompareDto> compares, int periods) {
        return backfillCache(history, compares, periods, false);
    }

    public static int backfillCache(List<Hm> history, List<HmCache.CompareDto> compares, int periods, boolean pl3) {
        if (history == null || history.size() < MIN_HIST || compares == null || compares.isEmpty()) {
            return 0;
        }
        Map<String, Integer> qhIndex = new HashMap<>();
        Map<String, Integer> realIndex = new HashMap<>();
        for (int i = 0; i < history.size(); i++) {
            Hm hm = history.get(i);
            if (hm == null) {
                continue;
            }
            if (hm.getQh() != null && !hm.getQh().isBlank()) {
                qhIndex.put(hm.getQh().trim(), i);
            }
            String real = pad3(hm.toString());
            realIndex.put(real, i);
        }

        int n = compares.size();
        int from = Math.max(0, n - Math.max(1, periods));
        int filled = 0;
        for (int i = from; i < n; i++) {
            HmCache.CompareDto dto = compares.get(i);
            if (dto == null) {
                continue;
            }
            int histIdx = resolveHistIndex(dto, qhIndex, realIndex, history.size());
            if (histIdx < MIN_HIST) {
                continue;
            }
            // 预测用开奖前历史：histIdx 对应本期开奖；近窗强制重算以刷新算法
            String pred = RuleBasedShuangDanUtils.predict(history.subList(0, histIdx), pl3);
            if (pred == null || pred.isBlank()) {
                continue;
            }
            String old = dto.getAiShuangDanHm();
            if (pred.equals(old)) {
                continue;
            }
            dto.setAiShuangDanHm(pred);
            filled++;
        }
        return filled;
    }

    /** 启动时回补并落盘 */
    public static int backfillAndPersist(boolean is3d, String compareFile, int periods) {
        List<Hm> history = is3d ? HmCache.getSdCache() : HmCache.getPl3Cache();
        List<HmCache.CompareDto> compares = is3d ? HmCache.getSdCompareCache() : HmCache.getPl3CompareCache();
        int filled = backfillCache(history, compares, periods, !is3d);
        if (filled > 0 && compareFile != null && !compareFile.isBlank()) {
            persist(is3d, compareFile, compares);
            log.info("双胆回补[{}] {} 条 → {}", is3d ? "3D" : "排列三", filled, compareFile);
        } else if (filled == 0) {
            log.info("双胆回补[{}] 无需补充（近{}期已有或历史不足）", is3d ? "3D" : "排列三", periods);
        }
        return filled;
    }

    private static void persist(boolean is3d, String file, List<HmCache.CompareDto> compares) {
        List<CompareVO> rows = new ArrayList<>(compares.size());
        for (HmCache.CompareDto d : compares) {
            rows.add(CompareVO.builder()
                    .qh(d.getQh())
                    .aiHm(d.getAiHm())
                    .aiRecommendHm(d.getAiRecommendHm())
                    .aiOverfitHm(d.getAiOverfitHm())
                    .aiFullHm(d.getAiFullHm())
                    .aiDanMaHm(d.getAiDanMaHm())
                    .aiShuangDanHm(d.getAiShuangDanHm())
                    .aiZuSanHm(d.getAiZuSanHm())
                    .dingWeiQm(d.getAiDingWeiHm())
                    .realHm(d.getRealHm())
                    .build());
        }
        String sheet = is3d ? "3D比对结果" : "排列三比对结果";
        EasyExcel.write(file, CompareVO.class).sheet(sheet).doWrite(rows);
    }

    private static int resolveHistIndex(HmCache.CompareDto dto,
                                        Map<String, Integer> qhIndex,
                                        Map<String, Integer> realIndex,
                                        int histSize) {
        if (dto.getQh() != null && !dto.getQh().isBlank()) {
            Integer ix = qhIndex.get(dto.getQh().trim());
            if (ix != null) {
                return ix;
            }
        }
        if (dto.getRealHm() != null && !dto.getRealHm().isBlank()) {
            Integer ix = realIndex.get(pad3(dto.getRealHm()));
            if (ix != null) {
                return ix;
            }
        }
        // 待开奖：用全部已开历史预测下一期
        if (dto.getRealHm() == null || dto.getRealHm().isBlank()) {
            return histSize;
        }
        return -1;
    }

    private static String pad3(String s) {
        if (s == null) {
            return "000";
        }
        String t = s.trim();
        return t.length() >= 3 ? t.substring(t.length() - 3) : "0".repeat(3 - t.length()) + t;
    }

    /** 独立运行：回补近 60 期。用法见 main */
    public static void main(String[] args) throws Exception {
        int periods = args != null && args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PERIODS;
        String sdFile = "D:\\彩票\\3D对比.xlsx";
        String pl3File = "D:\\彩票\\排列三对比.xlsx";
        List<Hm> sd = HistoryDataLoader.load3dPreferOnline();
        List<Hm> pl3 = HistoryDataLoader.loadPl3PreferOnline();
        HmCache.setSdCache(sd);
        HmCache.setPl3Cache(pl3);

        List<CompareVO> sdVo = EasyExcel.read(sdFile).head(CompareVO.class).sheet().doReadSync();
        HmCache.setSdCompareCache(sdVo.stream().map(ShuangDanBackfill::toDto).toList());
        List<CompareVO> plVo = EasyExcel.read(pl3File).head(CompareVO.class).sheet().doReadSync();
        HmCache.setPl3CompareCache(plVo.stream().map(ShuangDanBackfill::toDto).toList());

        System.out.println("=== 双胆回补近 " + periods + " 期 ===");
        System.out.println("3D 补齐: " + backfillAndPersist(true, sdFile, periods));
        System.out.println("排列三 补齐: " + backfillAndPersist(false, pl3File, periods));
        System.out.println("=== 完成，请重启应用 ===");
    }

    private static HmCache.CompareDto toDto(CompareVO v) {
        return new HmCache.CompareDto()
                .setQh(v.getQh())
                .setAiHm(v.getAiHm())
                .setAiRecommendHm(v.getAiRecommendHm())
                .setAiOverfitHm(v.getAiOverfitHm())
                .setAiFullHm(v.getAiFullHm())
                .setAiDanMaHm(v.getAiDanMaHm())
                .setAiShuangDanHm(v.getAiShuangDanHm())
                .setAiZuSanHm(v.getAiZuSanHm())
                .setAiDingWeiHm(v.getDingWeiQm())
                .setRealHm(v.getRealHm());
    }
}
