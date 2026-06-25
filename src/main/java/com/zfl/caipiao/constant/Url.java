package com.zfl.caipiao.constant;

import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.zfl.caipiao.export.Kl8Hm;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;

/**
 * @author zfl
 */
public class Url {

    public static final String PL3_URL = "https://datachart.500.com/pls/zoushi/newinc/jbzs.php?expect=all&from=%s&to=%s";

    public static final String SD_URL = "https://datachart.500.com/sd/zoushi/newinc/jbzs.php?expect=all&from=%s&to=%s";

    public static final String KL8_URL = "https://datachart.500.com/kl8/zoushi/newinc/jbzs_redblue.php?expect=all&from=%s&to=%s";

    public static void main(String[] args) throws Exception{
        Document document = Jsoup.connect(String.format(KL8_URL, "2024001", "2026165")).get();
        Elements elements = document.getElementsByTag("tr");
        List<Kl8Hm> list = new ArrayList<>();
        for (int i = 1; i < elements.size(); i++) {
            Elements tds = elements.get(i).getElementsByTag("td");
            String qh = tds.get(0).text();
            if(StrUtil.isBlank(qh) || !qh.startsWith("20")){
                continue;
            }
            List<Element> chartBalls = tds.stream().filter(td -> td.hasClass("chartBall01")).toList();
            if(chartBalls.size() == 20){
                list.add(Kl8Hm.builder().qh(qh)
                        .q1(chartBalls.get(0).text())
                        .q2(chartBalls.get(1).text())
                        .q3(chartBalls.get(2).text())
                        .q4(chartBalls.get(3).text())
                        .q5(chartBalls.get(4).text())
                        .q6(chartBalls.get(5).text())
                        .q7(chartBalls.get(6).text())
                        .q8(chartBalls.get(7).text())
                        .q9(chartBalls.get(8).text())
                        .q10(chartBalls.get(9).text())
                        .q11(chartBalls.get(10).text())
                        .q12(chartBalls.get(11).text())
                        .q13(chartBalls.get(12).text())
                        .q14(chartBalls.get(13).text())
                        .q15(chartBalls.get(14).text())
                        .q16(chartBalls.get(15).text())
                        .q17(chartBalls.get(16).text())
                        .q18(chartBalls.get(17).text())
                        .q19(chartBalls.get(18).text())
                        .q20(chartBalls.get(19).text())
                        .build());
            }
        }
        EasyExcel.write("D:\\彩票\\快乐八.xlsx", Kl8Hm.class).sheet().doWrite(list);
    }

}