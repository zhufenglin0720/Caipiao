package com.zfl.caipiao.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.CompareVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author zfl
 */
@Service
public class Kl8Service {

    private static final int KL8_MAX_SIZE = 2;

    @Value("${file.location.compareKl8}")
    private String fileLocationCompareKl8;

    public List<String> parseNumbers(String text) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        return Arrays.stream(text.split("[,，\\s\\n\\r]+"))
                .map(String::trim)
                .filter(s -> s.matches("\\d{1,2}"))
                .map(this::formatNumber)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public void savePredict(String numbersText) {
        List<String> numbers = deduplicate(parseNumbers(numbersText));
        validateNumbers(numbers);

        List<HmCache.CompareDto> cache = HmCache.getKl8CompareCache();
        if (CollUtil.isNotEmpty(cache)) {
            HmCache.CompareDto latest = cache.get(cache.size() - 1);
            if (StrUtil.isBlank(latest.getRealHm())) {
                latest.setAiHm(String.join(",", numbers));
                writeExcel();
                return;
            }
        }
        HmCache.addKl8CompareCache(new HmCache.CompareDto().setAiHm(String.join(",", numbers)));
        writeExcel();
    }

    public void updatePredict(int index, String numbersText) {
        List<String> numbers = deduplicate(parseNumbers(numbersText));
        validateNumbers(numbers);

        List<HmCache.CompareDto> cache = HmCache.getKl8CompareCache();
        if (index < 0 || index >= cache.size()) {
            throw new IllegalArgumentException("记录不存在或索引无效");
        }
        HmCache.CompareDto dto = cache.get(index);
        if (StrUtil.isNotBlank(dto.getRealHm())) {
            throw new IllegalArgumentException("已开奖的记录不可修改");
        }
        dto.setAiHm(String.join(",", numbers));
        writeExcel();
    }

    public void writeExcel() {
        FileUtil.mkParentDirs(fileLocationCompareKl8);
        List<CompareVO> insertList = HmCache.getKl8CompareCache().stream()
                .map(compareDto -> CompareVO.builder()
                        .qh(compareDto.getQh())
                        .aiHm(compareDto.getAiHm())
                        .realHm(compareDto.getRealHm())
                        .build())
                .toList();
        EasyExcel.write(fileLocationCompareKl8, CompareVO.class).sheet("快乐8比对结果").doWrite(insertList);
    }

    private List<String> deduplicate(List<String> numbers) {
        Set<String> unique = new LinkedHashSet<>(numbers);
        return new ArrayList<>(unique);
    }

    private void validateNumbers(List<String> numbers) {
        if (numbers.size() != KL8_MAX_SIZE) {
            throw new IllegalArgumentException("请录入" + KL8_MAX_SIZE + "个号码，当前有效号码数：" + numbers.size());
        }
        for (String number : numbers) {
            int value = Integer.parseInt(number);
            if (value < 1 || value > 80) {
                throw new IllegalArgumentException("号码须在01-80之间，无效号码：" + number);
            }
        }
    }

    private String formatNumber(String raw) {
        int value = Integer.parseInt(raw);
        if (value < 1 || value > 80) {
            throw new IllegalArgumentException("号码须在01-80之间，无效号码：" + raw);
        }
        return String.format("%02d", value);
    }
}
