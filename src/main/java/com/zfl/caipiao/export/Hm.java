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
public class Hm {

    @ExcelProperty("期号")
    private String qh;

    @ExcelProperty("号码1")
    private String q1;

    @ExcelProperty("号码2")
    private String q2;

    @ExcelProperty("号码3")
    private String q3;

    @Override
    public String toString() {
        return qh + ":" + q1 + q2 + q3;
    }
}