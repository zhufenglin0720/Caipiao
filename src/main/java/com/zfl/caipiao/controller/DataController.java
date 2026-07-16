package com.zfl.caipiao.controller;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import com.zfl.caipiao.service.DadiService;
import com.zfl.caipiao.service.PnlService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    @Resource
    private PnlService pnlService;

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

    @PostMapping("/pnl/auth")
    public Map<String, Object> authPnl(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        String password = body.get("password");
        if (!pnlService.verifyPassword(password)) {
            result.put("success", false);
            result.put("message", "密码错误");
            return result;
        }
        result.put("success", true);
        result.put("token", pnlService.createAuthToken());
        result.put("message", "验证成功");
        return result;
    }

    @GetMapping("/pnl")
    public Map<String, Object> getPnl(@RequestHeader(value = "X-Pnl-Token", required = false) String token) {
        Map<String, Object> unauthorized = unauthorizedPnl(token);
        if (unauthorized != null) {
            return unauthorized;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("records", pnlService.listRecords());
        result.put("summary", pnlService.summary());
        return result;
    }

    @PostMapping("/pnl")
    public Map<String, Object> savePnl(@RequestHeader(value = "X-Pnl-Token", required = false) String token,
                                       @RequestBody Map<String, Object> body) {
        Map<String, Object> unauthorized = unauthorizedPnl(token);
        if (unauthorized != null) {
            return unauthorized;
        }
        Map<String, Object> result = new HashMap<>();
        String date = body.get("date") != null ? body.get("date").toString() : null;
        Object ticketObj = body.get("ticketAmount");
        Object winObj = body.get("winAmount");
        if (date == null || ticketObj == null || winObj == null) {
            result.put("success", false);
            result.put("message", "参数不完整");
            return result;
        }
        try {
            double ticketAmount = Double.parseDouble(ticketObj.toString());
            double winAmount = Double.parseDouble(winObj.toString());
            pnlService.saveRecord(date, ticketAmount, winAmount);
            result.put("success", true);
            result.put("message", "保存成功");
            result.put("records", pnlService.listRecords());
            result.put("summary", pnlService.summary());
        } catch (NumberFormatException e) {
            result.put("success", false);
            result.put("message", "金额格式无效");
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "保存失败：" + e.getMessage());
        }
        return result;
    }

    @PostMapping("/pnl/delete")
    public Map<String, Object> deletePnl(@RequestHeader(value = "X-Pnl-Token", required = false) String token,
                                         @RequestBody Map<String, String> body) {
        Map<String, Object> unauthorized = unauthorizedPnl(token);
        if (unauthorized != null) {
            return unauthorized;
        }
        Map<String, Object> result = new HashMap<>();
        String date = body.get("date");
        if (date == null) {
            result.put("success", false);
            result.put("message", "参数不完整");
            return result;
        }
        try {
            pnlService.deleteRecord(date);
            result.put("success", true);
            result.put("message", "删除成功");
            result.put("records", pnlService.listRecords());
            result.put("summary", pnlService.summary());
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "删除失败：" + e.getMessage());
        }
        return result;
    }

    private Map<String, Object> unauthorizedPnl(String token) {
        if (pnlService.isValidToken(token)) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "未授权，请先输入密码");
        result.put("unauthorized", true);
        return result;
    }

    @PostMapping("/dadi")
    public Map<String, Object> saveDadi(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        String type = body.get("type");
        String model = body.get("model");
        String numbers = body.get("numbers");
        if (type == null || model == null || numbers == null) {
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
            dadiService.saveDadi(is3D, model.toLowerCase(), numbers);
            result.put("success", true);
            result.put("message", "大底录入成功");
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "保存失败：" + e.getMessage());
        }
        return result;
    }

    @PostMapping("/dadi/update")
    public Map<String, Object> updateDadi(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        String type = (String) body.get("type");
        String model = (String) body.get("model");
        String numbers = (String) body.get("numbers");
        Object indexObj = body.get("index");
        if (type == null || model == null || numbers == null || indexObj == null) {
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
        int index;
        try {
            index = Integer.parseInt(indexObj.toString());
        } catch (NumberFormatException e) {
            result.put("success", false);
            result.put("message", "索引无效");
            return result;
        }
        try {
            dadiService.updateDadi(is3D, index, model.toLowerCase(), numbers);
            result.put("success", true);
            result.put("message", "大底修改成功");
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "修改失败：" + e.getMessage());
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
            map.put("cursorDadiHm", dto.getCursorDadiHm());
            map.put("customDadiHm", dto.getCustomDadiHm());
            map.put("realHm", dto.getRealHm());
            return map;
        }).collect(Collectors.toList());
    }
}
