package com.zfl.caipiao.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.DadiCompareVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author zfl
 */
@Service
public class DadiService {

    private static final int DADI_MAX_SIZE = 100;

    private static final Set<String> VALID_MODELS = Set.of("cursor", "custom");

    @Value("${file.location.compare3DDadi}")
    private String fileLocationCompare3dDadi;

    @Value("${file.location.comparePl3Dadi}")
    private String fileLocationComparePl3Dadi;

    public List<String> parseNumbers(String text) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        return Arrays.stream(text.split("[,，\\s\\n\\r]+"))
                .map(String::trim)
                .filter(s -> s.matches("\\d{3}"))
                .collect(Collectors.toList());
    }

    public void updateDadi(boolean is3D, int index, String model, String numbersText) {
        if (!VALID_MODELS.contains(model)) {
            throw new IllegalArgumentException("模型无效，请使用 cursor 或 custom");
        }
        List<String> numbers = parseNumbers(numbersText);
        validateDadiNumbers(numbers);
        List<HmCache.DadiCompareDto> cache = is3D ? HmCache.getSdDadiCompareCache() : HmCache.getPl3DadiCompareCache();
        if (index < 0 || index >= cache.size()) {
            throw new IllegalArgumentException("记录不存在或索引无效");
        }
        setModelDadiHm(cache.get(index), model, String.join(",", numbers));
        writeExcel(is3D);
    }

    public void saveDadi(boolean is3D, String model, String numbersText) {
        if (!VALID_MODELS.contains(model)) {
            throw new IllegalArgumentException("模型无效，请使用 cursor 或 custom");
        }
        List<String> numbers = parseNumbers(numbersText);
        validateDadiNumbers(numbers);
        String dadiHm = String.join(",", numbers);

        List<HmCache.DadiCompareDto> cache = is3D ? HmCache.getSdDadiCompareCache() : HmCache.getPl3DadiCompareCache();
        if (CollUtil.isNotEmpty(cache)) {
            HmCache.DadiCompareDto latest = cache.get(cache.size() - 1);
            if (StrUtil.isBlank(latest.getRealHm())) {
                setModelDadiHm(latest, model, dadiHm);
                writeExcel(is3D);
                return;
            }
        }
        HmCache.DadiCompareDto dto = new HmCache.DadiCompareDto()
                .setQh(null)
                .setRealHm(null);
        setModelDadiHm(dto, model, dadiHm);
        if (is3D) {
            HmCache.addSdDadiCompareCache(dto);
        } else {
            HmCache.addPl3DadiCompareCache(dto);
        }
        writeExcel(is3D);
    }

    private void validateDadiNumbers(List<String> numbers) {
        if (numbers.isEmpty()) {
            throw new IllegalArgumentException("请至少录入1注三位数号码");
        }
        if (numbers.size() > DADI_MAX_SIZE) {
            throw new IllegalArgumentException("最多录入100注三位数号码，当前有效号码数：" + numbers.size());
        }
    }

    private void setModelDadiHm(HmCache.DadiCompareDto dto, String model, String dadiHm) {
        switch (model) {
            case "cursor" -> dto.setCursorDadiHm(dadiHm);
            case "custom" -> dto.setCustomDadiHm(dadiHm);
            default -> throw new IllegalArgumentException("未知模型：" + model);
        }
    }

    public void loadFromExcel(boolean is3D, String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return;
        }
        List<DadiCompareVO> list = EasyExcel.read(filePath).head(DadiCompareVO.class)
                .sheet().doReadSync();
        if (CollUtil.isEmpty(list)) {
            return;
        }
        List<HmCache.DadiCompareDto> dtos = list.stream()
                .map(vo -> new HmCache.DadiCompareDto()
                        .setQh(vo.getQh())
                        .setCursorDadiHm(vo.getCursorDadiHm())
                        .setCustomDadiHm(vo.getCustomDadiHm())
                        .setRealHm(vo.getRealHm()))
                .toList();
        if (is3D) {
            HmCache.setSdDadiCompareCache(dtos);
        } else {
            HmCache.setPl3DadiCompareCache(dtos);
        }
    }

    public void writeExcel(boolean is3D) {
        String filePath = is3D ? fileLocationCompare3dDadi : fileLocationComparePl3Dadi;
        FileUtil.mkParentDirs(filePath);
        List<HmCache.DadiCompareDto> cache = is3D ? HmCache.getSdDadiCompareCache() : HmCache.getPl3DadiCompareCache();
        List<DadiCompareVO> insertList = cache.stream()
                .map(dto -> DadiCompareVO.builder()
                        .qh(dto.getQh())
                        .cursorDadiHm(dto.getCursorDadiHm())
                        .customDadiHm(dto.getCustomDadiHm())
                        .realHm(dto.getRealHm())
                        .build())
                .toList();
        String sheetName = is3D ? "3D大底比对" : "排列三大底比对";
        EasyExcel.write(filePath, DadiCompareVO.class).sheet(sheetName).doWrite(insertList);
    }

    public void updateRealHm(boolean is3D, String realHm) {
        List<HmCache.DadiCompareDto> cache = is3D ? HmCache.getSdDadiCompareCache() : HmCache.getPl3DadiCompareCache();
        if (CollUtil.isEmpty(cache) || StrUtil.isBlank(realHm)) {
            return;
        }
        HmCache.DadiCompareDto latest = cache.get(cache.size() - 1);
        if (StrUtil.isBlank(latest.getRealHm()) && hasAnyDadiHm(latest)) {
            latest.setRealHm(realHm);
            writeExcel(is3D);
        }
    }

    private boolean hasAnyDadiHm(HmCache.DadiCompareDto dto) {
        return StrUtil.isNotBlank(dto.getCursorDadiHm())
                || StrUtil.isNotBlank(dto.getCustomDadiHm());
    }

}
