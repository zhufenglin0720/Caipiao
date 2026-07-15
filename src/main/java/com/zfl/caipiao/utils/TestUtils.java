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

        List<Hm> pl3s = EasyExcel.read("D:\\彩票\\排列三.xlsx").head(Hm.class).doReadAllSync();
        System.out.println(pl3s);
    }
}
