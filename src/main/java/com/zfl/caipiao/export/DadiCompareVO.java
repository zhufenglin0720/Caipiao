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

    @ExcelProperty("Cursor500注大底")
    private String cursorDadiHm;

    @ExcelProperty("DeepSeek500注大底")
    private String deepseekDadiHm;

    @ExcelProperty("真实号码")
    private String realHm;

}
