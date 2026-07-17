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
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DadiCompareVO {

    @ExcelProperty("期号")
    private String qh;

    @ExcelProperty("Cursor100注大底")
    private String cursorDadiHm;

    @ExcelProperty("自定义")
    private String customDadiHm;

    @ExcelProperty("真实号码")
    private String realHm;

}
