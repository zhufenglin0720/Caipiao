package com.zfl.caipiao.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.annotation.ExcelProperty;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.DadiCompareVO;
import com.zfl.caipiao.export.Hm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.LoggerFactory;

/**
 * 将大底对比 Excel 补全到近 N 期（默认 60）：
 * - 历史开奖优先在线拉取（17500）
 * - 已有大底预测保留，缺失期用当前过拟合 + 规则大底逻辑补齐
 * - 写回官方 3D / 排列三大底对比表（最新期在前）
 */
public final class DadiRecentExcelExport {

    private static final int DEFAULT_EVAL = 60;
    private static final int WARMUP = 110;
    private static final Path SD_OFFICIAL = Path.of("D:\\彩票\\3D大底对比.xlsx");
    private static final Path PL3_OFFICIAL = Path.of("D:\\彩票\\排列三大底对比.xlsx");

    private DadiRecentExcelExport() {
    }

    public static void main(String[] args) throws Exception {
        muteLogs();
        int eval = DEFAULT_EVAL;
        if (args != null && args.length > 0) {
            eval = Integer.parseInt(args[0]);
        }

        System.out.println("=== 大底补全：目标近 " + eval + " 期（在线开奖 + 当前预测逻辑）===");
        List<Hm> sdHist = HistoryDataLoader.load3dPreferOnline();
        List<Hm> pl3Hist = HistoryDataLoader.loadPl3PreferOnline();

        Map<String, DadiCompareVO> sdExist = loadExisting(SD_OFFICIAL);
        Map<String, DadiCompareVO> pl3Exist = loadExisting(PL3_OFFICIAL);
        System.out.println("已有大底记录: 3D=" + sdExist.size() + " 排列三=" + pl3Exist.size());

        List<Row> sd = run("福彩3D", sdHist, RuleBasedPredictUtils.GameKind.SD_3D,
                Overfit20PredictUtils.GameKind.SD, eval, sdExist);
        List<Row> pl3 = run("排列三", pl3Hist, RuleBasedPredictUtils.GameKind.PL3,
                Overfit20PredictUtils.GameKind.PL3, eval, pl3Exist);

        // 官方表：最新期在前
        Collections.reverse(sd);
        Collections.reverse(pl3);

        Path report = Path.of("D:\\彩票\\大底回测近" + eval + "期.xlsx");
        Files.createDirectories(report.getParent());
        var writer = EasyExcel.write(report.toString(), Row.class).build();
        writer.write(sd, EasyExcel.writerSheet("福彩3D").build());
        writer.write(pl3, EasyExcel.writerSheet("排列三").build());
        writer.finish();
        System.out.println("回测报告: " + report.toAbsolutePath());

        writeOfficial(SD_OFFICIAL, "3D大底比对", sd, sdExist);
        writeOfficial(PL3_OFFICIAL, "排列三大底比对", pl3, pl3Exist);
        printSummary("福彩3D", sd);
        printSummary("排列三", pl3);
        System.out.println("=== 完成：请重启应用使缓存加载新 Excel ===");
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

    private static List<Row> run(String name, List<Hm> all, RuleBasedPredictUtils.GameKind predKind,
                                 Overfit20PredictUtils.GameKind ofKind, int eval,
                                 Map<String, DadiCompareVO> existing) {
        if (all == null || all.size() < eval + 40) {
            throw new IllegalStateException(name + " 历史不足，当前=" + (all == null ? 0 : all.size()));
        }
        int start = all.size() - eval;
        int warmStart = Math.max(40, start - WARMUP);
        List<HmCache.CompareDto> compares = new ArrayList<>();

        // 预热：仅跑规则大底，构建连挂元调参所需 compares
        for (int i = warmStart; i < start; i++) {
            String pred = RuleBasedPredictUtils.predict(all.subList(0, i), compares, predKind);
            compares.add(new HmCache.CompareDto()
                    .setQh(all.get(i).getQh())
                    .setAiHm(pred == null ? "" : pred)
                    .setAiFullHm(pred == null ? "" : pred)
                    .setRealHm(pad3(all.get(i).toString())));
            trim(compares);
        }

        List<Row> rows = new ArrayList<>();
        int reused = 0;
        int generated = 0;
        for (int i = start; i < all.size(); i++) {
            Hm actualHm = all.get(i);
            String qh = actualHm.getQh() == null ? "" : actualHm.getQh().trim();
            String actual = pad3(actualHm.toString());
            DadiCompareVO old = existing.get(qh);
            String pred;
            boolean fromExist = old != null && old.getCursorDadiHm() != null && !old.getCursorDadiHm().isBlank();
            if (fromExist) {
                pred = old.getCursorDadiHm().trim();
                reused++;
            } else {
                // 当前线上逻辑：过拟合池 + 规则大底
                String ofCsv = Overfit20PredictUtils.predictResult(all.subList(0, i), ofKind, compares).poolCsv();
                pred = RuleBasedPredictUtils.predict(all.subList(0, i), compares, predKind, ofCsv);
                if (pred == null) {
                    pred = "";
                }
                generated++;
            }

            int pos = zxPos(pred, actual);
            rows.add(Row.builder()
                    .qh(qh)
                    .cursorDadiHm(pred)
                    .realHm(actual)
                    .betCount(pred.isBlank() ? 0 : pred.split(",").length)
                    .zxHit(pos > 0 ? "是" : "否")
                    .zxPos(pos > 0 ? pos : null)
                    .groupHit(groupHit(pred, actual) ? "是" : "否")
                    .build());
            compares.add(new HmCache.CompareDto()
                    .setQh(qh)
                    .setAiHm(pred)
                    .setAiFullHm(pred)
                    .setRealHm(actual));
            trim(compares);
            System.out.printf("%s %d/%d 期号=%s 开奖=%s 直选=%s %s%n",
                    name, i - start + 1, eval, qh, actual, pos > 0 ? "是" : "否",
                    fromExist ? "[保留]" : "[新算]");
        }
        System.out.printf("%s 完成：保留=%d 新算=%d 合计=%d%n", name, reused, generated, rows.size());
        return rows;
    }

    private static void writeOfficial(Path file, String sheet, List<Row> rows,
                                      Map<String, DadiCompareVO> existing) throws Exception {
        Files.createDirectories(file.getParent());
        List<DadiCompareVO> list = new ArrayList<>();
        for (Row row : rows) {
            DadiCompareVO old = existing.get(row.getQh());
            list.add(DadiCompareVO.builder()
                    .qh(row.getQh())
                    .cursorDadiHm(row.getCursorDadiHm())
                    .customDadiHm(old == null ? null : old.getCustomDadiHm())
                    .realHm(row.getRealHm())
                    .build());
        }
        Path tmp = file.resolveSibling(file.getFileName().toString().replace(".xlsx", ".writing.xlsx"));
        Path fallback = file.resolveSibling(file.getFileName().toString().replace(".xlsx", "_补全60期.xlsx"));
        EasyExcel.write(tmp.toString(), DadiCompareVO.class).sheet(sheet).doWrite(list);
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            System.out.println("已写入: " + file.toAbsolutePath() + " 期数=" + list.size());
        } catch (Exception e) {
            try {
                Files.move(tmp, fallback, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e2) {
                Files.copy(tmp, fallback, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(tmp);
            }
            System.out.println("原文件被占用，已写入备用: " + fallback.toAbsolutePath()
                    + " 期数=" + list.size() + "（请关闭 Excel/应用后替换原文件）");
        }
    }

    private static void printSummary(String name, List<Row> rows) {
        int zx = 0;
        int grp = 0;
        for (Row row : rows) {
            if ("是".equals(row.zxHit)) {
                zx++;
            }
            if ("是".equals(row.groupHit)) {
                grp++;
            }
        }
        String newest = rows.isEmpty() ? "-" : rows.get(0).qh;
        String oldest = rows.isEmpty() ? "-" : rows.get(rows.size() - 1).qh;
        System.out.printf("%s %d期 最新%s → 最早%s 直选=%d 组选=%d%n",
                name, rows.size(), newest, oldest, zx, grp);
    }

    private static void trim(List<HmCache.CompareDto> compares) {
        int max = 105;
        while (compares.size() > max) {
            compares.remove(0);
        }
    }

    private static int zxPos(String pred, String actual) {
        if (pred == null || pred.isBlank()) {
            return 0;
        }
        String[] parts = pred.split(",");
        for (int i = 0; i < parts.length; i++) {
            if (pad3(parts[i]).equals(actual)) {
                return i + 1;
            }
        }
        return 0;
    }

    private static boolean groupHit(String pred, String actual) {
        if (pred == null || pred.isBlank()) {
            return false;
        }
        String key = sorted(actual);
        for (String part : pred.split(",")) {
            String t = pad3(part);
            if (t.length() == 3 && sorted(t).equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static String sorted(String s) {
        char[] c = s.toCharArray();
        Arrays.sort(c);
        return new String(c);
    }

    private static String pad3(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() >= 3 ? t.substring(t.length() - 3) : "0".repeat(Math.max(0, 3 - t.length())) + t;
    }

    private static void muteLogs() {
        try {
            Logger root = (Logger) LoggerFactory.getLogger("ROOT");
            root.setLevel(Level.ERROR);
        } catch (Throwable ignored) {
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        @ExcelProperty("期号")
        private String qh;
        @ExcelProperty("Cursor500注大底")
        private String cursorDadiHm;
        @ExcelProperty("真实号码")
        private String realHm;
        @ExcelProperty("注数")
        private Integer betCount;
        @ExcelProperty("直选命中")
        private String zxHit;
        @ExcelProperty("直选位次")
        private Integer zxPos;
        @ExcelProperty("组选命中")
        private String groupHit;
    }
}
