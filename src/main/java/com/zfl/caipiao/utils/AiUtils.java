package com.zfl.caipiao.utils;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.constant.DingWeiAsk3DContent;
import com.zfl.caipiao.constant.Pl3AskContent;
import com.zfl.caipiao.constant.ThreeDAskContent;
import com.zfl.caipiao.export.Hm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
public class AiUtils {

    private static final String URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions";
    private static final Integer RETRY_COUNT = 5;
    private static final Integer HISTORY_DATA_SIZE = 200;
    private static final Integer COMPARE_HISTORY_SIZE = 15;
    private static final Integer EXPECTED_CONTENT_LENGTH = 19;
    private static final Integer MAX_TOKENS = 24576;
    
    private static final String API_MODEL = "doubao-seed-2-0-pro-260215";
    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String CONTENT_KEY = "content";
    private static final String CHOICES_KEY = "choices";
    private static final String MESSAGE_KEY = "message";
    
    private static final String COMMA_SEPARATOR = ",";
    private static final String PERIOD_PREFIX = "第";
    private static final String PREDICT_FORMAT = "]，实际[";
    private static final String SUFFIX = "];";
    
    private static final RestTemplate restTemplate = SpringUtils.getBean(RestTemplate.class);

    private static final String API_KEY = "889581c1-791e-4608-886c-11bf9ef0c00e";

    private static final String USER_MSG = "全量历史数据样本如下：{list}";

    public static String get3dAi(){
        return predictLottery(HmCache::getSdCache, HmCache::getSdCompareCache, ThreeDAskContent.V1, true);
    }

    public static String getPl3Ai(){
        return predictLottery(HmCache::getPl3Cache, HmCache::getPl3CompareCache, Pl3AskContent.V1, true);
    }

    public static String get3dDingWeiAi(){
        return predictLottery(HmCache::getSdCache, HmCache::getSdCompareCache, DingWeiAsk3DContent.V1, false);
    }

    public static String getPl3DingWeiAi(){
        return predictLottery(HmCache::getPl3Cache, HmCache::getPl3CompareCache, DingWeiAsk3DContent.V1,false);
    }

    private static String predictLottery(DataProvider dataProvider, CompareDataProvider compareProvider, String systemMsgTemplate,
                                         boolean flag) {
        try {
            List<Hm> historyData = dataProvider.get();
            if (historyData == null || historyData.size() < HISTORY_DATA_SIZE) {
                log.info("历史数据不足，当前数据量: {}", historyData == null ? 0 : historyData.size());
                return null;
            }

            String userMsg = null;
            String systemMsg;
            List<HmCache.CompareDto> compareDtoList = compareProvider.get();
            if(flag){
                userMsg = USER_MSG.replace("{list}",
                        historyData.subList(historyData.size() - HISTORY_DATA_SIZE, historyData.size()).toString());
                String compareMsg = buildCompareMessage(compareDtoList);
                systemMsg = systemMsgTemplate.replace("{ycStr}", compareMsg);
            }else{
                HmCache.CompareDto compareDto = compareDtoList.get(compareDtoList.size() - 1);
                systemMsg = systemMsgTemplate
                        .replace("{list}", historyData.subList(historyData.size() - HISTORY_DATA_SIZE, historyData.size()).toString());
                if(compareDto != null && StrUtil.isNotBlank(compareDto.getAiDingWeiHm()) && StrUtil.isNotBlank(compareDto.getRealHm())){
                    systemMsg = systemMsg.replace("{lastYc}", compareDto.getAiDingWeiHm()).replace("{realHm}", compareDto.getRealHm());
                }
            }
            HttpEntity<String> entity = buildRequestEntity(systemMsg, userMsg);
            log.info("开始调用AI预测接口");
            return getContent(entity, 0, userMsg != null);
        } catch (Exception e) {
            log.error("预测彩票时发生异常", e);
        }
        return null;
    }
    
    private static String buildCompareMessage(List<HmCache.CompareDto> compareDtoList) {
        StringBuilder compareMsg = new StringBuilder();
        int size = 0;
        
        if (compareDtoList == null || compareDtoList.isEmpty()) {
            return "";
        }
        
        int start = Math.max(0, compareDtoList.size() - COMPARE_HISTORY_SIZE);
        for (int i = start; i < compareDtoList.size(); i++) {
            HmCache.CompareDto dto = compareDtoList.get(i);
            if (StrUtil.isNotBlank(dto.getAiHm()) && StrUtil.isNotBlank(dto.getRealHm())) {
                compareMsg.append(PERIOD_PREFIX)
                    .append(++size)
                    .append("期：预测[")
                    .append(dto.getAiHm())
                    .append(PREDICT_FORMAT)
                    .append(dto.getRealHm())
                    .append(SUFFIX);
            }
        }
        return compareMsg.toString();
    }
    
    private static HttpEntity<String> buildRequestEntity(String systemMsg, String userMsg) {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add("Authorization", "Bearer " + API_KEY);
        httpHeaders.add("Content-Type", "application/json");
        
        Map<String, Object> requestMap = Map.of(
            "model", API_MODEL,
            "messages", userMsg == null ?
                        List.of(Map.of("role", ROLE_SYSTEM, CONTENT_KEY, systemMsg)) :
                        List.of(
                            Map.of("role", ROLE_SYSTEM, CONTENT_KEY, systemMsg),
                            Map.of("role", ROLE_USER, CONTENT_KEY, userMsg)
                        ),
            "max_tokens", MAX_TOKENS,
            "max_output_tokens", MAX_TOKENS,
            "thinking", Map.of("type", "enabled"),
            "reasoning", Map.of("effort", "high")
        );
        
        String req = JSON.toJSONString(requestMap);
        log.info("AI请求参数: {}", req);
        
        return new HttpEntity<>(req, httpHeaders);
    }

    private static String getContent(HttpEntity<String> entity, int retryCount, boolean checkContentError){
        try {
            String answer = restTemplate.postForObject(URL, entity, String.class);
            log.info("AI响应结果: {}", answer);
            
            if (StrUtil.isBlank(answer)) {
                return handleRetry(entity, retryCount, "响应为空", checkContentError);
            }
            
            return parseResponse(answer, entity, retryCount, checkContentError);
        } catch (Exception e) {
            log.error("调用AI接口异常，重试次数: {}", retryCount, e);
            return handleRetry(entity, retryCount, "接口异常: " + e.getMessage(), checkContentError);
        }
    }
    
    private static String parseResponse(String answer, HttpEntity<String> entity, int retryCount, boolean checkContentError) {
        try {
            JSONObject responseObj = JSON.parseObject(answer);
            if (!responseObj.containsKey(CHOICES_KEY) || responseObj.getJSONArray(CHOICES_KEY).isEmpty()) {
                log.info("响应中缺少choices字段");
                return handleRetry(entity, retryCount, "响应格式错误", checkContentError);
            }
            
            JSONObject messageObj = responseObj.getJSONArray(CHOICES_KEY)
                .getJSONObject(0)
                .getJSONObject(MESSAGE_KEY);
                
            if (!messageObj.containsKey(CONTENT_KEY)) {
                log.info("响应中缺少content字段");
                return handleRetry(entity, retryCount, "响应内容缺失", checkContentError);
            }
            
            String content = messageObj.getString(CONTENT_KEY);
            if (checkContentError && checkContentError(content)) {
                log.error("响应内容格式校验失败: {}", content);
                return handleRetry(entity, retryCount, "内容格式错误", true);
            }
            
            return content;
        } catch (Exception e) {
            log.error("解析响应异常", e);
            return handleRetry(entity, retryCount, "解析异常: " + e.getMessage(), checkContentError);
        }
    }
    
    private static String handleRetry(HttpEntity<String> entity, int retryCount, String reason, boolean checkContentError) {
        if (retryCount < RETRY_COUNT) {
            log.info("{}，进行第{}次重试", reason, retryCount + 1);
            return getContent(entity, retryCount + 1, checkContentError);
        }
        log.error("达到最大重试次数{}，放弃重试", RETRY_COUNT);
        return null;
    }

    private static boolean checkContentError(String content){
        if (StrUtil.isBlank(content)) {
            return true;
        }
        if (content.length() != EXPECTED_CONTENT_LENGTH) {
            return true;
        }
        String[] split = content.split(COMMA_SEPARATOR);
        for (String str : split) {
            try {
                Integer.parseInt(str);
            } catch (NumberFormatException e) {
                return true;
            }
        }
        return false;
    }
    
    @FunctionalInterface
    private interface DataProvider {
        List<Hm> get();
    }
    
    @FunctionalInterface
    private interface CompareDataProvider {
        List<HmCache.CompareDto> get();
    }
}