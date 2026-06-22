package com.zfl.caipiao.controller;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import com.zfl.caipiao.service.DadiService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/data")
public class DataController {

    @Resource
    private DadiService dadiService;

    @GetMapping
    public Map<String, Object> getData() {
        Map<String, Object> result = new HashMap<>();

        List<Hm> sdCache = HmCache.getSdCache();
        List<Hm> pl3Cache = HmCache.getPl3Cache();
        List<HmCache.CompareDto> sdCompareCache = HmCache.getSdCompareCache();
        List<HmCache.CompareDto> pl3CompareCache = HmCache.getPl3CompareCache();
        List<HmCache.DadiCompareDto> sdDadiCompareCache = HmCache.getSdDadiCompareCache();
        List<HmCache.DadiCompareDto> pl3DadiCompareCache = HmCache.getPl3DadiCompareCache();

        result.put("sd", sdCache);
        result.put("pl3", pl3Cache);
        result.put("sdCompare", toCompareList(sdCompareCache));
        result.put("pl3Compare", toCompareList(pl3CompareCache));
        result.put("sdDadiCompare", toDadiCompareList(sdDadiCompareCache));
        result.put("pl3DadiCompare", toDadiCompareList(pl3DadiCompareCache));

        return result;
    }

    @PostMapping("/dadi")
    public Map<String, Object> saveDadi(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        String type = body.get("type");
        String numbers = body.get("numbers");
        if (type == null || numbers == null) {
            result.put("success", false);
            result.put("message", "参数不完整");
            return result;
        }
        boolean is3D = "3d".equalsIgnoreCase(type);
        if (!is3D && !"pl3".equalsIgnoreCase(type)) {
            result.put("success", false);
            result.put("message", "类型无效，请使用 3d 或 pl3");
            return result;
        }
        try {
            dadiService.saveDadi(is3D, numbers);
            result.put("success", true);
            result.put("message", "500注大底录入成功");
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "保存失败：" + e.getMessage());
        }
        return result;
    }

    private List<Map<String, String>> toCompareList(List<HmCache.CompareDto> list) {
        return list.stream().map(dto -> {
            Map<String, String> map = new HashMap<>();
            map.put("qh", dto.getQh());
            map.put("aiHm", dto.getAiHm());
            map.put("aiDingWeiHm", dto.getAiDingWeiHm());
            map.put("realHm", dto.getRealHm());
            return map;
        }).collect(Collectors.toList());
    }

    private List<Map<String, String>> toDadiCompareList(List<HmCache.DadiCompareDto> list) {
        return list.stream().map(dto -> {
            Map<String, String> map = new HashMap<>();
            map.put("qh", dto.getQh());
            map.put("aiDadiHm", dto.getAiDadiHm());
            map.put("realHm", dto.getRealHm());
            return map;
        }).collect(Collectors.toList());
    }
}
