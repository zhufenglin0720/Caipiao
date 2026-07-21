package com.zfl.caipiao.utils;

import com.alibaba.excel.EasyExcel;
import com.zfl.caipiao.export.Hm;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 加载历史开奖：优先本地 Excel（F:\彩票 / D:\彩票），否则回退 17500 文本或项目 data 目录。
 */
public final class HistoryDataLoader {

    private static final Pattern ISSUE = Pattern.compile("^\\d{5,8}$");

    private HistoryDataLoader() {
    }

    public static List<Hm> load3d() {
        return loadPreferExcel(new String[]{
                "F:\\彩票\\3D.xlsx",
                "D:\\彩票\\3D.xlsx",
                "F:/彩票/3D.xlsx",
                "D:/彩票/3D.xlsx",
                "data/lottery/3D.xlsx"
        }, new String[]{
                "F:\\彩票\\3d_asc.txt",
                "D:\\彩票\\3d_asc.txt",
                "data/lottery/3d_asc.txt",
                "http://data.17500.cn/3d_asc.txt"
        }, true);
    }

    public static List<Hm> loadPl3() {
        return loadPreferExcel(new String[]{
                "F:\\彩票\\排列三.xlsx",
                "D:\\彩票\\排列三.xlsx",
                "F:/彩票/排列三.xlsx",
                "D:/彩票/排列三.xlsx",
                "data/lottery/排列三.xlsx"
        }, new String[]{
                "F:\\彩票\\pl3_asc.txt",
                "D:\\彩票\\pl3_asc.txt",
                "data/lottery/pl3_asc.txt",
                "http://data.17500.cn/pl3_asc.txt"
        }, false);
    }

    private static List<Hm> loadPreferExcel(String[] excelPaths, String[] txtPaths, boolean is3d) {
        for (String p : excelPaths) {
            Path path = Path.of(p);
            if (Files.isRegularFile(path)) {
                List<Hm> list = EasyExcel.read(path.toString()).head(Hm.class).doReadAllSync();
                if (list != null && !list.isEmpty()) {
                    System.out.println("读取Excel: " + path.toAbsolutePath() + " 期数=" + list.size()
                            + (is3d ? " [3D]" : " [排列三]"));
                    return normalize(list);
                }
            }
        }
        for (String p : txtPaths) {
            try {
                List<Hm> list = loadAscText(p, is3d);
                if (list != null && !list.isEmpty()) {
                    System.out.println("读取文本: " + p + " 期数=" + list.size()
                            + (is3d ? " [3D]" : " [排列三]"));
                    return list;
                }
            } catch (Exception e) {
                System.out.println("跳过 " + p + ": " + e.getMessage());
            }
        }
        throw new IllegalStateException((is3d ? "3D" : "排列三") + " 历史数据未找到，请把 Excel 放到 F:\\彩票");
    }

    static List<Hm> loadAscText(String location, boolean is3d) throws Exception {
        String text;
        if (location.startsWith("http://") || location.startsWith("https://")) {
            text = fetchUrl(location);
        } else {
            Path path = Path.of(location);
            if (!Files.isRegularFile(path)) {
                return List.of();
            }
            byte[] raw = Files.readAllBytes(path);
            text = decode(raw);
        }
        return parseAsc(text);
    }

    private static String fetchUrl(String url) throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        java.net.http.HttpResponse<byte[]> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return decode(resp.body());
    }

    private static String decode(byte[] raw) {
        for (Charset cs : List.of(StandardCharsets.UTF_8, Charset.forName("GB18030"), Charset.forName("GBK"))) {
            try {
                return new String(raw, cs);
            } catch (Exception ignored) {
                // try next
            }
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static List<Hm> parseAsc(String text) {
        List<Hm> out = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.contains("期号")) {
                continue;
            }
            String[] tokens = line.split("[\\s,\\t]+");
            int issueIdx = -1;
            for (int i = 0; i < tokens.length; i++) {
                if (ISSUE.matcher(tokens[i]).matches()) {
                    issueIdx = i;
                    break;
                }
            }
            if (issueIdx < 0 || issueIdx + 4 >= tokens.length) {
                continue;
            }
            // issue date d1 d2 d3 ...
            String qh = tokens[issueIdx];
            String d1 = tokens[issueIdx + 2];
            String d2 = tokens[issueIdx + 3];
            String d3 = tokens[issueIdx + 4];
            if (!d1.matches("\\d") || !d2.matches("\\d") || !d3.matches("\\d")) {
                // maybe compact 3-digit after date
                if (tokens[issueIdx + 2].matches("\\d{3}")) {
                    String n = tokens[issueIdx + 2];
                    d1 = n.substring(0, 1);
                    d2 = n.substring(1, 2);
                    d3 = n.substring(2, 3);
                } else {
                    continue;
                }
            }
            out.add(Hm.builder().qh(qh).q1(d1).q2(d2).q3(d3).build());
        }
        return out;
    }

    private static List<Hm> normalize(List<Hm> list) {
        List<Hm> out = new ArrayList<>(list.size());
        for (Hm hm : list) {
            if (hm == null) {
                continue;
            }
            String a = digit(hm.getQ1());
            String b = digit(hm.getQ2());
            String c = digit(hm.getQ3());
            out.add(Hm.builder().qh(hm.getQh()).q1(a).q2(b).q3(c).build());
        }
        return out;
    }

    private static String digit(String s) {
        if (s == null || s.isEmpty()) {
            return "0";
        }
        char ch = s.charAt(s.length() - 1);
        return Character.isDigit(ch) ? String.valueOf(ch) : "0";
    }
}
