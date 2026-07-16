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
public class ProfitLossVO {

    @ExcelProperty("日期")
    private String date;

    @ExcelProperty("打票金额")
    private Double ticketAmount;

    @ExcelProperty("中奖金额")
    private Double winAmount;

}
