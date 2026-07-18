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
public class CompareVO {

    @ExcelProperty("期号")
    private String qh;

    @ExcelProperty("AI预测号码")
    private String aiHm;

    @ExcelProperty("推荐号码")
    private String aiRecommendHm;

    @ExcelProperty("AI组三")
    private String aiZuSanHm;

    @ExcelProperty("AI预测七码")
    private String dingWeiQm;

    @ExcelProperty("真实号码")
    private String realHm;

}