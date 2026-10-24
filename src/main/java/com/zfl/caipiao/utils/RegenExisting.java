package com.zfl.caipiao.utils;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 用当前算法重写已有对比表、大底表。过拟合、组三、自定义、开奖号保留。
 */
public final class RegenExisting {

    private static final Path SD_HIST = Path.of("D:/彩票/3D.xlsx");
    private static final Path PL_HIST = Path.of("D:/彩票/排列三.xlsx");
    private static final Path SD_COMPARE = Path.of("D:/彩票/3D对比.xlsx");
    private static final Path PL_COMPARE = Path.of("D:/彩票/排列三对比.xlsx");
    private static final Path SD_DADI = Path.of("D:/彩票/3D大底对比.xlsx");
    private static final Path PL_DADI = Path.of("D:/彩票/排列三大底对比.xlsx");

    private static final Pattern ROW = Pattern.compile("<row r=\"(\\d+)\">(.*?)</row>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<c r=\"([A-Z]+)\\d+\"[^>]*>(.*?)</c>", Pattern.DOTALL);
    private static final Pattern TEXT = Pattern.compile("<t[^>]*>([^<]*)</t>");

    private RegenExisting() {
    }

    public static void main(String[] args) throws Exception {
        Hist sd = readHist(SD_HIST);
        Hist pl = readHist(PL_HIST);
        System.out.println("3D历史 " + sd.issues.size() + " 期 首=" + sd.issues.get(0) + " 末=" + sd.issues.get(sd.issues.size() - 1));
        System.out.println("排三历史 " + pl.issues.size() + " 期 首=" + pl.issues.get(0) + " 末=" + pl.issues.get(pl.issues.size() - 1));
        regenCompare("福彩3D", SD_COMPARE, sd, false);
        regenCompare("排列三", PL_COMPARE, pl, true);
        regenDadi("福彩3D", SD_DADI, sd, false);
        regenDadi("排列三", PL_DADI, pl, true);
        System.out.println("写回完成。重启应用后页面会读到新表。");
    }

    private static void regenCompare(String name, Path file, Hist hist, boolean pl3) throws Exception {
        Sheet sheet = readSheet(file);
        int updated = 0;
        int kept = 0;
        int future = 0;
        for (int i = 1; i < sheet.rows.size(); i++) {
            Map<String, String> row = sheet.rows.get(i);
            String qh = row.getOrDefault("A", "").trim();
            Pack pack = predict(hist, qh, pl3);
            if (pack == null) {
                kept++;
                System.out.println(name + " 对比 " + qh + " 对不上历史，原值保留");
                continue;
            }
            if (pack.future) {
                future++;
            }
            row.put("B", pack.sanma);
            row.put("C", pack.sanma);
            row.put("E", pack.pool500);
            row.put("F", pack.dan);
            row.put("G", pack.shuang);
            row.put("I", pack.qi);
            updated++;
        }
        Map<String, String> sample = sheet.rows.get(sheet.rows.size() - 1);
        String keepOver = sample.get("D");
        String keepZu = sample.get("H");
        String keepReal = sample.get("J");
        writeSheet(file, sheet);
        Map<String, String> back = readSheet(file).rows.get(sheet.rows.size() - 1);
        if (!same(keepOver, back.get("D")) || !same(keepZu, back.get("H")) || !same(keepReal, back.get("J"))) {
            throw new IllegalStateException(name + " 对比写回后过拟合、组三或开奖号变了");
        }
        System.out.printf(Locale.ROOT, "%s 对比 重写%d 保留%d 其中未开奖按全历史预测%d | 末行%s 三码%d注 大底%d注 七码%s 双胆%s 定位%s%n",
                name, updated, kept, future, back.get("A"),
                bets(back.get("C")), bets(back.get("E")), back.get("I"), back.get("G"), back.get("F"));
    }

    private static void regenDadi(String name, Path file, Hist hist, boolean pl3) throws Exception {
        Sheet sheet = readSheet(file);
        int updated = 0;
        int kept = 0;
        int future = 0;
        for (int i = 1; i < sheet.rows.size(); i++) {
            Map<String, String> row = sheet.rows.get(i);
            String qh = row.getOrDefault("A", "").trim();
            Pack pack = predict(hist, qh, pl3);
            if (pack == null) {
                kept++;
                System.out.println(name + " 大底 " + qh + " 对不上历史，原值保留");
                continue;
            }
            if (pack.future) {
                future++;
            }
            row.put("B", pack.pool500);
            row.put("C", pack.pool1000);
            updated++;
        }
        Map<String, String> sample = sheet.rows.get(1);
        String keepCustom = sample.get("D");
        String keepReal = sample.get("E");
        writeSheet(file, sheet);
        Map<String, String> back = readSheet(file).rows.get(1);
        if (!same(keepCustom, back.get("D")) || !same(keepReal, back.get("E"))) {
            throw new IllegalStateException(name + " 大底写回后自定义或开奖号变了");
        }
        System.out.printf(Locale.ROOT, "%s 大底 重写%d 保留%d 其中未开奖按全历史预测%d | 首行%s 500注=%d 1000注=%d%n",
                name, updated, kept, future, back.get("A"), bets(back.get("B")), bets(back.get("C")));
    }

    private static boolean same(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private static int bets(String csv) {
        if (csv == null || csv.isBlank()) {
            return 0;
        }
        return csv.split(",").length;
    }

    private static final Map<String, Pack> CACHE = new LinkedHashMap<>();

    private static Pack predict(Hist hist, String qh, boolean pl3) {
        if (qh.isEmpty() || !qh.chars().allMatch(Character::isDigit)) {
            return null;
        }
        String key = (pl3 ? "p:" : "s:") + qh;
        Pack cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Integer ix = hist.index.get(qh);
        int[][] prefix;
        boolean future = false;
        if (ix != null) {
            prefix = copyPrefix(hist.digits, ix);
        } else {
            long n = Long.parseLong(qh);
            long last = Long.parseLong(hist.issues.get(hist.issues.size() - 1));
            if (n <= last) {
                return null;
            }
            prefix = hist.digits;
            future = true;
        }
        if (prefix.length < 40) {
            return null;
        }
        Pack pack = new Pack();
        pack.future = future;
        pack.sanma = Flow1000Ranker.csv(Flow1000Ranker.sanma(prefix, pl3));
        pack.pool500 = Flow1000Ranker.csv(Flow1000Ranker.pool(prefix, pl3, 500));
        pack.pool1000 = Flow1000Ranker.csv(Flow1000Ranker.pool(prefix, pl3, 1000));
        pack.dan = posLine(Flow1000Ranker.digitTop(prefix, pl3, 3));
        pack.qi = posLine(Flow1000Ranker.digitTop(prefix, pl3, 7));
        pack.shuang = shuangLine(Flow1000Ranker.shuangDan(prefix, pl3));
        CACHE.put(key, pack);
        return pack;
    }

    private static int[][] copyPrefix(int[][] digits, int end) {
        int[][] out = new int[end][3];
        for (int i = 0; i < end; i++) {
            out[i][0] = digits[i][0];
            out[i][1] = digits[i][1];
            out[i][2] = digits[i][2];
        }
        return out;
    }

    private static String posLine(int[][] top) {
        String[] name = {"百位", "十位", "个位"};
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < 3; p++) {
            if (p > 0) {
                sb.append(' ');
            }
            sb.append(name[p]).append(':');
            for (int i = 0; i < top[p].length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(top[p][i]);
            }
        }
        return sb.toString();
    }

    private static String shuangLine(int[][] pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            int a = Math.min(pairs[i][0], pairs[i][1]);
            int b = Math.max(pairs[i][0], pairs[i][1]);
            sb.append("组").append(i + 1).append(':').append(a).append(',').append(b);
        }
        return sb.toString();
    }

    private static Hist readHist(Path file) throws Exception {
        Sheet sheet = readSheet(file);
        Hist hist = new Hist();
        List<int[]> rows = new ArrayList<>();
        for (int i = 1; i < sheet.rows.size(); i++) {
            Map<String, String> row = sheet.rows.get(i);
            String qh = row.getOrDefault("A", "").trim();
            String a = row.getOrDefault("B", "").trim();
            String b = row.getOrDefault("C", "").trim();
            String c = row.getOrDefault("D", "").trim();
            if (!qh.chars().allMatch(Character::isDigit) || a.length() != 1 || b.length() != 1 || c.length() != 1) {
                continue;
            }
            hist.index.put(qh, rows.size());
            hist.issues.add(qh);
            rows.add(new int[]{a.charAt(0) - '0', b.charAt(0) - '0', c.charAt(0) - '0'});
        }
        hist.digits = new int[rows.size()][3];
        for (int i = 0; i < rows.size(); i++) {
            hist.digits[i] = rows.get(i);
        }
        return hist;
    }

    private static Sheet readSheet(Path file) throws Exception {
        String xml;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var entry = zip.getEntry("xl/worksheets/sheet1.xml");
            try (InputStream in = zip.getInputStream(entry)) {
                xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        Sheet sheet = new Sheet();
        sheet.xml = xml;
        Matcher rows = ROW.matcher(xml);
        while (rows.find()) {
            Map<String, String> cells = new LinkedHashMap<>();
            Matcher cell = CELL.matcher(rows.group(2));
            while (cell.find()) {
                Matcher text = TEXT.matcher(cell.group(2));
                String value = text.find() ? unescape(text.group(1)) : "";
                cells.put(cell.group(1), value);
            }
            sheet.rows.add(cells);
        }
        if (sheet.rows.isEmpty()) {
            throw new IllegalStateException("空表 " + file);
        }
        return sheet;
    }

    private static void writeSheet(Path file, Sheet sheet) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < sheet.rows.size(); i++) {
            int r = i + 1;
            String style = r == 1 ? "1" : "2";
            body.append("<row r=\"").append(r).append("\">");
            for (Map.Entry<String, String> e : sheet.rows.get(i).entrySet()) {
                body.append("<c r=\"").append(e.getKey()).append(r).append("\" s=\"").append(style).append("\" t=\"inlineStr\"><is><t>");
                body.append(escape(e.getValue()));
                body.append("</t></is></c>");
            }
            body.append("</row>");
        }
        int dataAt = sheet.xml.indexOf("<sheetData>");
        int dataEnd = sheet.xml.indexOf("</sheetData>");
        if (dataAt < 0 || dataEnd < 0) {
            throw new IllegalStateException("找不到 sheetData " + file);
        }
        String xml = sheet.xml.substring(0, dataAt + "<sheetData>".length())
                + body
                + sheet.xml.substring(dataEnd);
        int cols = sheet.rows.get(0).size();
        String dim = "A1:" + (char) ('A' + cols - 1) + sheet.rows.size();
        xml = xml.replaceFirst("dimension ref=\"[^\"]*\"", "dimension ref=\"" + dim + "\"");
        Path bak = Path.of(file.toString() + ".bak");
        if (!Files.exists(bak)) {
            Files.copy(file, bak);
        }
        Path tmp = Path.of(file.toString() + ".tmp");
        try (ZipFile zip = new ZipFile(file.toFile());
             OutputStream fout = Files.newOutputStream(tmp);
             ZipOutputStream zos = new ZipOutputStream(fout)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                zos.putNextEntry(new ZipEntry(entry.getName()));
                if ("xl/worksheets/sheet1.xml".equals(entry.getName())) {
                    zos.write(xml.getBytes(StandardCharsets.UTF_8));
                } else {
                    try (InputStream in = zip.getInputStream(entry)) {
                        in.transferTo(zos);
                    }
                }
                zos.closeEntry();
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String escape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static final class Hist {
        final Map<String, Integer> index = new LinkedHashMap<>();
        final List<String> issues = new ArrayList<>();
        int[][] digits;
    }

    private static final class Sheet {
        String xml;
        final List<Map<String, String>> rows = new ArrayList<>();
    }

    private static final class Pack {
        boolean future;
        String sanma;
        String pool500;
        String pool1000;
        String dan;
        String qi;
        String shuang;
    }
}
