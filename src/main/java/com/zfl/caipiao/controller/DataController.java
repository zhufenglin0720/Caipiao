package com.zfl.caipiao.controller;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/data")
public class DataController {

    @GetMapping
    public Map<String, Object> getData() {
        Map<String, Object> result = new HashMap<>();

        List<Hm> sdCache = HmCache.getSdCache();
        List<Hm> pl3Cache = HmCache.getPl3Cache();
        List<HmCache.CompareDto> sdCompareCache = HmCache.getSdCompareCache();
        List<HmCache.CompareDto> pl3CompareCache = HmCache.getPl3CompareCache();

        result.put("sd", sdCache);
        result.put("pl3", pl3Cache);
        result.put("sdCompare", sdCompareCache.stream().map(dto -> {
            Map<String, String> map = new HashMap<>();
            map.put("aiHm", dto.getAiHm());
            map.put("aiDingWeiHm", dto.getAiDingWeiHm());
            map.put("realHm", dto.getRealHm());
            return map;
        }).collect(Collectors.toList()));
        result.put("pl3Compare", pl3CompareCache.stream().map(dto -> {
            Map<String, String> map = new HashMap<>();
            map.put("aiHm", dto.getAiHm());
            map.put("aiDingWeiHm", dto.getAiDingWeiHm());
            map.put("realHm", dto.getRealHm());
            return map;
        }).collect(Collectors.toList()));

        return result;
    }
}
