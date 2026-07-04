package com.zfl.caipiao.utils;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * @author zfl
 */
public class DateUtils {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static String getSdQh(String lastQh){
        String year = lastQh.substring(0, 4);
        int qh = Integer.parseInt(lastQh.substring(4));
        String nowYear = String.valueOf(LocalDate.now().getYear());
        if(Objects.equals(year, nowYear)){
            return year + String.format("%03d", qh + 1);
        }else{
            return nowYear + "001";
        }
    }

    public static String getP3Qh(String lastQh){
        String year = lastQh.substring(0, 2);
        int qh = Integer.parseInt(lastQh.substring(2));
        String nowYear = String.valueOf(LocalDate.now().getYear()).substring(2);
        if(Objects.equals(year, nowYear)){
            return year + String.format("%03d", qh + 1);
        }else{
            return nowYear + "001";
        }
    }

    public static boolean isDateStr(String dateStr){
        if (StrUtil.isBlank(dateStr)) {
            return false;
        }
        try {
            LocalDate.parse(dateStr, FORMATTER);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}