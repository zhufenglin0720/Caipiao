package com.zfl.caipiao.export;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author zfl
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Kl8Hm {

    @ExcelProperty("期号")
    private String qh;

    @ExcelProperty("号码1")
    private String q1;

    @ExcelProperty("号码2")
    private String q2;

    @ExcelProperty("号码3")
    private String q3;

    @ExcelProperty("号码4")
    private String q4;

    @ExcelProperty("号码5")
    private String q5;

    @ExcelProperty("号码6")
    private String q6;

    @ExcelProperty("号码7")
    private String q7;

    @ExcelProperty("号码8")
    private String q8;

    @ExcelProperty("号码9")
    private String q9;

    @ExcelProperty("号码10")
    private String q10;

    @ExcelProperty("号码11")
    private String q11;

    @ExcelProperty("号码12")
    private String q12;

    @ExcelProperty("号码13")
    private String q13;

    @ExcelProperty("号码14")
    private String q14;

    @ExcelProperty("号码15")
    private String q15;

    @ExcelProperty("号码16")
    private String q16;

    @ExcelProperty("号码17")
    private String q17;

    @ExcelProperty("号码18")
    private String q18;

    @ExcelProperty("号码19")
    private String q19;

    @ExcelProperty("号码20")
    private String q20;

    @Override
    public String toString() {
        return String.join(",", q1, q2, q3, q4, q5, q6, q7, q8, q9, q10, q11, q12, q13, q14, q15, q16, q17, q18, q19, q20);
    }
}