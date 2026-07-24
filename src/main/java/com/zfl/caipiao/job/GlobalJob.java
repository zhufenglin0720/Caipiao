package com.zfl.caipiao.job;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.constant.EmailConstant;
import com.zfl.caipiao.constant.Url;
import com.zfl.caipiao.export.CompareVO;
import com.zfl.caipiao.export.Hm;
import com.zfl.caipiao.service.DadiService;
import com.zfl.caipiao.utils.DateUtils;
import com.zfl.caipiao.utils.Overfit20PredictUtils;
import com.zfl.caipiao.utils.RecommendBetUtils;
import com.zfl.caipiao.utils.RuleBasedDingWeiUtils;
import com.zfl.caipiao.utils.RuleBasedPredictUtils;
import jakarta.annotation.Resource;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author zfl
 */
@Component
public class GlobalJob {

    @Resource
    private JavaMailSender javaMailSender;
    @Resource
    private DadiService dadiService;
    @Value("${spring.mail.username}")
    private String from;
    @Value("${spring.mail.to}")
    private String to;
    @Value("${file.location.3d}")
    private String fileLocation3d;
    @Value("${file.location.pl3}")
    private String fileLocationPl3;
    @Value("${file.location.compare3D}")
    private String fileLocationCompare3d;
    @Value("${file.location.comparePl3}")
    private String fileLocationComparePl3;

    @Scheduled(cron = "0 40 18 * * ?")
    public void applyTask() throws MessagingException, InterruptedException {
        // 1) 算出≤200注  2) 组选去重后落盘  3) 邮件10注基于原始200注（回测直选更高）
        // 4) 近20期过拟合五组（动态覆盖，非硬编码）
        String raw200 = RuleBasedPredictUtils.get3dPredict();
        String sdDadi = RecommendBetUtils.dedupeByGroupKeepFirst(raw200);
        String zuSan = RecommendBetUtils.extractZuSanGroups(sdDadi);
        String sdRecommend = RecommendBetUtils.pickRecommendBets(raw200, HmCache.getSdCompareCache());
        String sdOverfit = Overfit20PredictUtils.get3dPredict();
        if (StrUtil.isNotBlank(sdDadi)) {
            HmCache.addSdCompareCache(new HmCache.CompareDto()
                    .setAiHm(sdDadi)
                    .setAiFullHm(raw200)
                    .setAiRecommendHm(sdRecommend)
                    .setAiOverfitHm(sdOverfit)
                    .setAiZuSanHm(zuSan));
        }

        raw200 = RuleBasedPredictUtils.getPl3Predict();
        String pl3Dadi = RecommendBetUtils.dedupeByGroupKeepFirst(raw200);
        zuSan = RecommendBetUtils.extractZuSanGroups(pl3Dadi);
        String pl3Recommend = RecommendBetUtils.pickRecommendBets(raw200, HmCache.getPl3CompareCache());
        String pl3Overfit = Overfit20PredictUtils.getPl3Predict();
        if (StrUtil.isNotBlank(pl3Dadi)) {
            HmCache.addPl3CompareCache(new HmCache.CompareDto()
                    .setAiHm(pl3Dadi)
                    .setAiFullHm(raw200)
                    .setAiRecommendHm(pl3Recommend)
                    .setAiOverfitHm(pl3Overfit)
                    .setAiZuSanHm(zuSan));
        }
        String msg = EmailConstant.EMAIL_TEMPLATE
                .replace("{{3D_NUMBERS}}", EmailConstant.buildNumbersHtml(sdRecommend))
                .replace("{{PL3_NUMBERS}}", EmailConstant.buildNumbersHtml(pl3Recommend))
                .replace("{{TIMESTAMP}}", DateUtil.now());
        sendEmailCode("今日3D及排三预测（高概率10注）", msg);

        if (StrUtil.isNotBlank(sdOverfit) || StrUtil.isNotBlank(pl3Overfit)) {
            String overfitMsg = EmailConstant.EMAIL_TEMPLATE
                    .replace("{{3D_NUMBERS}}", EmailConstant.buildNumbersHtml(sdOverfit))
                    .replace("{{PL3_NUMBERS}}", EmailConstant.buildNumbersHtml(pl3Overfit))
                    .replace("{{TIMESTAMP}}", DateUtil.now());
            sendEmailCode("今日3D及排三过拟合五组（近20期）", overfitMsg);
        }
    }

    @Scheduled(cron = "0 50 18 * * ?")
    public void applyDingWeiTask() throws Exception {
        // 七码定位改为纯规则引擎，不再调用 AI
        String aiAnswer = RuleBasedDingWeiUtils.get3dDingWei();
        String[] parts = RuleBasedDingWeiUtils.parseParts(aiAnswer);
        if (parts != null) {
            List<HmCache.CompareDto> sdCompareCache = HmCache.getSdCompareCache();
            if (CollUtil.isNotEmpty(sdCompareCache)) {
                HmCache.CompareDto compareDto = sdCompareCache.get(sdCompareCache.size() - 1);
                if (compareDto.getAiDingWeiHm() == null) {
                    compareDto.setAiDingWeiHm(aiAnswer);
                }
            }
            sendEmailCode("3D定位7码推荐", EmailConstant.DingWeiAskContent
                    .replace("{type}", "3D")
                    .replace("{bw}", parts[0])
                    .replace("{sw}", parts[1])
                    .replace("{gw}", parts[2])
            );
        }


        String pl3Answer = RuleBasedDingWeiUtils.getPl3DingWei();
        parts = RuleBasedDingWeiUtils.parseParts(pl3Answer);
        if (parts != null) {
            List<HmCache.CompareDto> pl3CompareCache = HmCache.getPl3CompareCache();
            if (CollUtil.isNotEmpty(pl3CompareCache)) {
                HmCache.CompareDto compareDto = pl3CompareCache.get(pl3CompareCache.size() - 1);
                if (compareDto.getAiDingWeiHm() == null) {
                    compareDto.setAiDingWeiHm(pl3Answer);
                }
            }
            sendEmailCode("排列3定位7码推荐", EmailConstant.DingWeiAskContent
                    .replace("{type}", "排列三")
                    .replace("{bw}", parts[0])
                    .replace("{sw}", parts[1])
                    .replace("{gw}", parts[2])
            );
        }
    }

    @Scheduled(cron = "0 0 22 * * ?")
    public void setDataTask() throws Exception {
        List<Hm> sdCache = HmCache.getSdCache();
        Hm sdHm = sdCache.get(sdCache.size() - 1);
        String lastSdQh = sdHm.getQh();

        String sdQh = DateUtils.getSdQh(lastSdQh);
        System.out.println("3d lastQh:" + lastSdQh + " currentQh:" + sdQh);
        String url = String.format(Url.SD_URL, lastSdQh, sdQh);
        setKaiJiangCache(url, true, sdQh);

        List<Hm> pl3Cache = HmCache.getPl3Cache();
        Hm pl3Hm = pl3Cache.get(pl3Cache.size() - 1);
        String lastPl3Qh = pl3Hm.getQh();
        String p3Qh = DateUtils.getP3Qh(lastPl3Qh);
        System.out.println("pls lastQh:" + lastPl3Qh + " currentQh:" + p3Qh);
        String p3Url = String.format(Url.PL3_URL, lastPl3Qh, p3Qh);
        setKaiJiangCache(p3Url, false, p3Qh);

        //开完奖后发送通知邮件
        List<HmCache.CompareDto> pl3CompareCache = HmCache.getPl3CompareCache();
        HmCache.CompareDto pl3CompareDto = pl3CompareCache.get(pl3CompareCache.size() - 1);

        List<HmCache.CompareDto> sdCompareCache = HmCache.getSdCompareCache();
        HmCache.CompareDto sdCompareDto = sdCompareCache.get(sdCompareCache.size() - 1);

        String pl3AiHm = displayRecommend(pl3CompareDto);
        String pl3RealHm = pl3CompareDto.getRealHm();
        String sdAiHm = displayRecommend(sdCompareDto);
        String sdRealHm = sdCompareDto.getRealHm();
        String result = (checkSuccess(sdAiHm, sdRealHm) || checkSuccess(pl3AiHm, pl3RealHm)) ? "恭喜，中奖了！！！" : "很遗憾未中奖，下期必中！！！";
        sendEmailCode("今日开奖通知", EmailConstant.NOTICE_MSG
                .replace("{{result}}", result)
                .replace("{{str1}}", pl3AiHm)
                .replace("{{str2}}", pl3RealHm)
                .replace("{{str3}}", sdAiHm)
                .replace("{{str4}}", sdRealHm))
        ;
    }

    /** 开奖通知比对用推荐注 */
    private static String displayRecommend(HmCache.CompareDto dto) {
        if (dto == null) {
            return "";
        }
        if (StrUtil.isNotBlank(dto.getAiRecommendHm())) {
            return dto.getAiRecommendHm();
        }
        return RecommendBetUtils.pickRecommendBets(
                StrUtil.blankToDefault(dto.getAiFullHm(), dto.getAiHm()), null);
    }

    private boolean checkSuccess(String recommendHm, String realHm) {
        if (StrUtil.isBlank(recommendHm) || StrUtil.isBlank(realHm)) {
            return false;
        }
        for (String str : recommendHm.split(",")) {
            if (realHm.equals(str.trim())) {
                return true;
            }
        }
        return false;
    }

    private void setKaiJiangCache(String url, boolean is3D, String computeQh) throws Exception {
        Hm kaiJiangHm = null;
        try {
            System.out.println(url);
            Document document = Jsoup.connect(url).get();
            Elements elements = document.getElementsByTag("tr");
            for (int i = 1; i < elements.size(); i++) {
                Elements tds = elements.get(i).getElementsByTag("td");
                String dateStr = tds.get(0).text();
                if (!DateUtils.isDateStr(dateStr)) {
                    continue;
                }
                String qh = tds.get(1).text();
                if (!qh.equals(computeQh)) {
                    continue;
                }
                String q1 = tds.get(2).text();
                String q2 = tds.get(3).text();
                String q3 = tds.get(4).text();
                kaiJiangHm = Hm.builder().qh(qh).q1(q1)
                        .q2(q2).q3(q3).build();
                break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (kaiJiangHm != null) {
            if (is3D) {
                HmCache.addSdCache(kaiJiangHm);
            } else {
                HmCache.addPl3Cache(kaiJiangHm);
            }
            //重新写回excel文件
            String filePath = is3D ? fileLocation3d : fileLocationPl3;
            String sheetName = is3D ? "3D" : "排列三";
            List<Hm> list = is3D ? HmCache.getSdCache() : HmCache.getPl3Cache();
            EasyExcel.write(filePath, Hm.class).sheet(sheetName).doWrite(list);
            // 大底独立回填：仅当最新一条 realHm 为空时写入开奖号
            dadiService.updateRealHm(is3D, kaiJiangHm.toString());
        }

        //设置下比较缓存的真实值
        if (is3D) {
            List<HmCache.CompareDto> sdCompareCache = HmCache.getSdCompareCache();
            if (CollUtil.isNotEmpty(sdCompareCache)) {
                HmCache.CompareDto compareDto = sdCompareCache.get(sdCompareCache.size() - 1);
                if (kaiJiangHm == null) {
                    sdCompareCache.remove(compareDto);
                } else {
                    compareDto.setRealHm(kaiJiangHm.toString());
                    compareDto.setQh(kaiJiangHm.getQh());
                }
            }
        } else {
            List<HmCache.CompareDto> pl3CompareCache = HmCache.getPl3CompareCache();
            if (CollUtil.isNotEmpty(pl3CompareCache)) {
                HmCache.CompareDto compareDto = pl3CompareCache.get(pl3CompareCache.size() - 1);
                if (kaiJiangHm == null) {
                    pl3CompareCache.remove(compareDto);
                } else {
                    compareDto.setRealHm(kaiJiangHm.toString());
                    compareDto.setQh(kaiJiangHm.getQh());
                }
            }
        }

        //录入本地预测结果
        if (is3D) {
            List<CompareVO> insertList = HmCache.getSdCompareCache().stream().map(compareDto -> CompareVO.builder()
                    .qh(compareDto.getQh())
                    .aiHm(compareDto.getAiHm())
                    .aiRecommendHm(compareDto.getAiRecommendHm())
                    .aiOverfitHm(compareDto.getAiOverfitHm())
                    .aiZuSanHm(StrUtil.blankToDefault(compareDto.getAiZuSanHm(),
                            RecommendBetUtils.extractZuSanGroups(compareDto.getAiHm())))
                    .realHm(compareDto.getRealHm())
                    .dingWeiQm(compareDto.getAiDingWeiHm())
                    .build()).toList();
            EasyExcel.write(fileLocationCompare3d, CompareVO.class).sheet("3D比对结果").doWrite(insertList);
        } else {
            List<CompareVO> insertList = HmCache.getPl3CompareCache().stream().map(compareDto -> CompareVO.builder()
                    .qh(compareDto.getQh())
                    .aiHm(compareDto.getAiHm())
                    .aiRecommendHm(compareDto.getAiRecommendHm())
                    .aiOverfitHm(compareDto.getAiOverfitHm())
                    .aiZuSanHm(StrUtil.blankToDefault(compareDto.getAiZuSanHm(),
                            RecommendBetUtils.extractZuSanGroups(compareDto.getAiHm())))
                    .realHm(compareDto.getRealHm())
                    .dingWeiQm(compareDto.getAiDingWeiHm())
                    .build()).toList();
            EasyExcel.write(fileLocationComparePl3, CompareVO.class).sheet("排列三比对结果").doWrite(insertList);
        }
    }

    private void sendEmailCode(String subject, String sendText) throws MessagingException {
        MimeMessage message = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(to.split(","));
        helper.setSubject(subject);
        helper.setText(sendText, true);
        javaMailSender.send(message);
    }
}