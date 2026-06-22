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
import java.util.stream.Collectors;

/**
 * @author zfl
 */
@Service
public class DadiService {

    private static final int DADI_SIZE = 500;

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

    public void saveDadi(boolean is3D, String numbersText) {
        List<String> numbers = parseNumbers(numbersText);
        if (numbers.size() != DADI_SIZE) {
            throw new IllegalArgumentException("请输入恰好500注三位数号码，当前有效号码数：" + numbers.size());
        }
        String aiDadiHm = String.join(",", numbers);
        // 录入时不设置期号和实际开奖号，等 22:30 定时任务自动回填
        HmCache.DadiCompareDto dto = new HmCache.DadiCompareDto()
                .setAiDadiHm(aiDadiHm)
                .setQh(null)
                .setRealHm(null);

        List<HmCache.DadiCompareDto> cache = is3D ? HmCache.getSdDadiCompareCache() : HmCache.getPl3DadiCompareCache();
        if (CollUtil.isNotEmpty(cache)) {
            HmCache.DadiCompareDto latest = cache.get(cache.size() - 1);
            if (StrUtil.isBlank(latest.getRealHm())) {
                latest.setAiDadiHm(aiDadiHm);
                latest.setQh(null);
                latest.setRealHm(null);
                writeExcel(is3D);
                return;
            }
        }
        if (is3D) {
            HmCache.addSdDadiCompareCache(dto);
        } else {
            HmCache.addPl3DadiCompareCache(dto);
        }
        writeExcel(is3D);
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
                        .setAiDadiHm(vo.getAiDadiHm())
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
                        .aiDadiHm(dto.getAiDadiHm())
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
        // 仅最新一条且 realHm 为空时才回填，已有开奖号则跳过
        if (StrUtil.isBlank(latest.getRealHm()) && StrUtil.isNotBlank(latest.getAiDadiHm())) {
            latest.setRealHm(realHm);
            writeExcel(is3D);
        }
    }

}
