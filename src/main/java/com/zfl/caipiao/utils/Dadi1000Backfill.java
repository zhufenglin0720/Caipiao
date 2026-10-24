package com.zfl.caipiao.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.alibaba.excel.EasyExcel;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.DadiCompareVO;
import com.zfl.caipiao.export.Hm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.LoggerFactory;

/**
 * 为已有大底对比 Excel 补齐「完整1000注」列；保留原 500 注不动。
 * 用法：mvn -q -DskipTests exec:java -Dexec.mainClass=com.zfl.caipiao.utils.Dadi1000Backfill
 * 可选参数：期数（默认=Excel全部已有记录对应历史能算的期；传数字则只补近 N 期）
 */
public final class Dadi1000Backfill {

    private static final Path SD_OFFICIAL = Path.of("D:\\彩票\\3D大底对比.xlsx");
    private static final Path PL3_OFFICIAL = Path.of("D:\\彩票\\排列三大底对比.xlsx");
    private static final int WARMUP = 110;

    private Dadi1000Backfill() {
    }

    public static void main(String[] args) throws Exception {
        muteLogs();
        Integer onlyRecent = null;
        if (args != null && args.length > 0) {
            onlyRecent = Integer.parseInt(args[0]);
        }
        System.out.println("=== 大底1000注补齐（保留原500注）===");
        List<Hm> sdHist = HistoryDataLoader.load3dPreferOnline();
        List<Hm> pl3Hist = HistoryDataLoader.loadPl3PreferOnline();
        backfill("福彩3D", SD_OFFICIAL, "3D大底比对", sdHist,
                RuleBasedPredictUtils.GameKind.SD_3D, Overfit20PredictUtils.GameKind.SD, onlyRecent);
        backfill("排列三", PL3_OFFICIAL, "排列三大底比对", pl3Hist,
                RuleBasedPredictUtils.GameKind.PL3, Overfit20PredictUtils.GameKind.PL3, onlyRecent);
        System.out.println("=== 完成：请重启应用加载新 Excel ===");
    }

    private static void backfill(String name, Path file, String sheet, List<Hm> all,
                                 RuleBasedPredictUtils.GameKind predKind,
                                 Overfit20PredictUtils.GameKind ofKind,
                                 Integer onlyRecent) throws Exception {
        Map<String, DadiCompareVO> existing = loadExisting(file);
        if (existing.isEmpty()) {
            System.out.println(name + " 无已有记录，跳过: " + file);
            return;
        }
        if (all == null || all.size() < WARMUP + 20) {
            throw new IllegalStateException(name + " 历史不足");
        }

        Map<String, Integer> qhIndex = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            String qh = all.get(i).getQh() == null ? "" : all.get(i).getQh().trim();
            if (!qh.isBlank()) {
                qhIndex.put(qh, i);
            }
        }

        List<String> targets = new ArrayList<>(existing.keySet());
        if (onlyRecent != null && onlyRecent > 0 && targets.size() > onlyRecent) {
            // existing 读入顺序即最新在前（Excel 顺序）
            targets = new ArrayList<>(targets.subList(0, onlyRecent));
        }

        // 为需要计算的期构建 compares 预热：从最早目标期往前 WARMUP
        int minIdx = all.size();
        for (String qh : targets) {
            Integer ix = qhIndex.get(qh);
            if (ix != null) {
                minIdx = Math.min(minIdx, ix);
            }
        }
        int warmStart = Math.max(40, minIdx - WARMUP);
        List<HmCache.CompareDto> compares = new ArrayList<>();
        for (int i = warmStart; i < minIdx; i++) {
            String ofCsv = Overfit20PredictUtils.predictResult(all.subList(0, i), ofKind, compares).poolCsv();
            String pred = RuleBasedPredictUtils.predict(all.subList(0, i), compares, predKind, ofCsv);
            compares.add(new HmCache.CompareDto()
                    .setQh(all.get(i).getQh())
                    .setAiHm(pred == null ? "" : pred)
                    .setAiFullHm(pred == null ? "" : pred)
                    .setAiOverfitHm(ofCsv)
                    .setRealHm(pad3(all.get(i).toString())));
            trim(compares);
        }

        Map<String, String> computed1000 = new LinkedHashMap<>();
        int reused = 0;
        int generated = 0;
        int missingHist = 0;
        // 按历史时间升序推进 compares，保证因果
        List<Map.Entry<String, Integer>> ordered = new ArrayList<>();
        for (String qh : targets) {
            Integer ix = qhIndex.get(qh);
            if (ix == null) {
                missingHist++;
                continue;
            }
            ordered.add(Map.entry(qh, ix));
        }
        ordered.sort(Map.Entry.comparingByValue());

        int cursor = minIdx;
        for (Map.Entry<String, Integer> e : ordered) {
            String qh = e.getKey();
            int i = e.getValue();
            DadiCompareVO old = existing.get(qh);
            if (old != null && old.getCursorDadi1000Hm() != null && !old.getCursorDadi1000Hm().isBlank()
                    && countBets(old.getCursorDadi1000Hm()) >= 900) {
                computed1000.put(qh, old.getCursorDadi1000Hm().trim());
                reused++;
            } else {
                while (cursor < i) {
                    stepCompare(all, cursor, compares, predKind, ofKind);
                    cursor++;
                }
                String ofCsv = Overfit20PredictUtils.predictResult(all.subList(0, i), ofKind, compares).poolCsv();
                RuleBasedPredictUtils.setBetLimitOverride(1000);
                String pred1000;
                try {
                    pred1000 = RuleBasedPredictUtils.predict(all.subList(0, i), compares, predKind, ofCsv);
                } finally {
                    RuleBasedPredictUtils.setBetLimitOverride(0);
                }
                if (pred1000 == null) {
                    pred1000 = "";
                }
                computed1000.put(qh, pred1000);
                generated++;
                System.out.printf(Locale.ROOT, "%s 补齐 %s 1000注=%d%n",
                        name, qh, countBets(pred1000));
            }
            if (cursor == i) {
                stepCompare(all, i, compares, predKind, ofKind);
                cursor = i + 1;
            }
        }

        // 写回：保持 Excel 原顺序（existing key 顺序）
        List<DadiCompareVO> out = new ArrayList<>();
        for (Map.Entry<String, DadiCompareVO> en : existing.entrySet()) {
            DadiCompareVO old = en.getValue();
            String d1000 = computed1000.getOrDefault(en.getKey(), old.getCursorDadi1000Hm());
            out.add(DadiCompareVO.builder()
                    .qh(old.getQh())
                    .cursorDadiHm(old.getCursorDadiHm())
                    .cursorDadi1000Hm(d1000)
                    .customDadiHm(old.getCustomDadiHm())
                    .realHm(old.getRealHm())
                    .build());
        }
        writeOfficial(file, sheet, out);
        System.out.printf(Locale.ROOT, "%s 完成：保留已有1000=%d 新算=%d 无历史=%d 合计=%d%n",
                name, reused, generated, missingHist, out.size());
    }

    private static void stepCompare(List<Hm> all, int i, List<HmCache.CompareDto> compares,
                                    RuleBasedPredictUtils.GameKind predKind,
                                    Overfit20PredictUtils.GameKind ofKind) {
        String ofCsv = Overfit20PredictUtils.predictResult(all.subList(0, i), ofKind, compares).poolCsv();
        String pred = RuleBasedPredictUtils.predict(all.subList(0, i), compares, predKind, ofCsv);
        compares.add(new HmCache.CompareDto()
                .setQh(all.get(i).getQh())
                .setAiHm(pred == null ? "" : pred)
                .setAiFullHm(pred == null ? "" : pred)
                .setAiOverfitHm(ofCsv)
                .setRealHm(pad3(all.get(i).toString())));
        trim(compares);
    }

    private static Map<String, DadiCompareVO> loadExisting(Path file) {
        Map<String, DadiCompareVO> map = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return map;
        }
        try {
            List<DadiCompareVO> list = EasyExcel.read(file.toString()).head(DadiCompareVO.class).sheet().doReadSync();
            if (list == null) {
                return map;
            }
            for (DadiCompareVO vo : list) {
                if (vo == null || vo.getQh() == null || vo.getQh().isBlank()) {
                    continue;
                }
                map.put(vo.getQh().trim(), vo);
            }
            System.out.println("读取已有: " + file + " 条数=" + map.size());
        } catch (Exception e) {
            System.out.println("读取已有失败 " + file + ": " + e.getMessage());
        }
        return map;
    }

    private static void writeOfficial(Path file, String sheet, List<DadiCompareVO> list) throws Exception {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName().toString().replace(".xlsx", ".writing.xlsx"));
        Path fallback = file.resolveSibling(file.getFileName().toString().replace(".xlsx", "_1000补齐.xlsx"));
        EasyExcel.write(tmp.toString(), DadiCompareVO.class).sheet(sheet).doWrite(list);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("已写入: " + file.toAbsolutePath() + " 期数=" + list.size());
        } catch (Exception e) {
            try {
                Files.move(tmp, fallback, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e2) {
                Files.copy(tmp, fallback, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(tmp);
            }
            System.out.println("原文件被占用，已写入备用: " + fallback.toAbsolutePath()
                    + "（请关闭 Excel/应用后替换原文件）");
        }
    }

    private static int countBets(String csv) {
        if (csv == null || csv.isBlank()) {
            return 0;
        }
        int n = 0;
        for (String p : csv.split(",")) {
            if (!p.trim().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static void trim(List<HmCache.CompareDto> compares) {
        while (compares.size() > 105) {
            compares.remove(0);
        }
    }

    private static String pad3(String s) {
        if (s == null) {
            return "000";
        }
        String t = s.trim();
        while (t.length() < 3) {
            t = "0" + t;
        }
        return t.length() > 3 ? t.substring(t.length() - 3) : t;
    }

    private static void muteLogs() {
        try {
            Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.ERROR);
        } catch (Throwable ignored) {
        }
    }
}
