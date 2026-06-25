package com.zfl.caipiao.utils;

import com.alibaba.excel.EasyExcel;
import com.zfl.caipiao.export.Kl8Hm;

import java.util.List;

public class TestUtils {

    public static void main(String[] args) {
        List<Kl8Hm> objects = EasyExcel.read("D://彩票//快乐八.xlsx").head(Kl8Hm.class).doReadAllSync();
        System.out.println(objects.subList(objects.size() - 230, objects.size()));
    }
}
