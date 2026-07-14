package com.zfl.caipiao.utils;

import com.alibaba.excel.EasyExcel;
import com.zfl.caipiao.export.CompareVO;
import com.zfl.caipiao.export.Hm;

import java.util.List;

/**
 * @author zfl
 */
public class TestUtils {

    public static void main(String[] args) {

        List<Hm> threeDs = EasyExcel.read("D:\\彩票\\3D.xlsx").head(Hm.class).doReadAllSync();
        System.out.println(threeDs.subList(threeDs.size() - 215, threeDs.size()));

        List<CompareVO> threeDCompares = EasyExcel.read("D:\\彩票\\3D对比.xlsx").head(CompareVO.class).doReadAllSync();
        List<CompareVO> hms = threeDCompares.subList(threeDCompares.size() - 25, threeDCompares.size());
        for (int i = 0; i < hms.size(); i++){
            CompareVO compareVO = hms.get(i);
            System.out.println("第" + (i + 1) + "期，AI预测：" + compareVO.getAiHm() + "，实际号码：" + compareVO.getRealHm());
        }

        System.out.println("------------------------------------------------------");

        List<Hm> pl3s = EasyExcel.read("D:\\彩票\\排列三.xlsx").head(Hm.class).doReadAllSync();
        System.out.println(pl3s.subList(pl3s.size() - 215, pl3s.size()));
        List<CompareVO> pl3Compares = EasyExcel.read("D:\\彩票\\排列三对比.xlsx").head(CompareVO.class).doReadAllSync();
        List<CompareVO> pl3Hms = pl3Compares.subList(pl3Compares.size() - 25, pl3Compares.size());
        for (int i = 0; i < pl3Hms.size(); i++){
            CompareVO compareVO = pl3Hms.get(i);
            System.out.println("第" + (i + 1) + "期，AI预测：" + compareVO.getAiHm() + "，实际号码：" + compareVO.getRealHm());
        }
    }
}
