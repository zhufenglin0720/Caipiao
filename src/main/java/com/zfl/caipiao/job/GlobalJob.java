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
import com.zfl.caipiao.utils.AiUtils;
import com.zfl.caipiao.utils.DateUtils;
import com.zfl.caipiao.utils.ThreadUtils;
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
import java.util.concurrent.CountDownLatch;

/**
 * @author zfl
 */
@Component
public class GlobalJob {

    @Resource
    private JavaMailSender javaMailSender;
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

    private void sendEmailCode(String subject, String sendText) throws MessagingException {
        MimeMessage message = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(to.split(","));
        helper.setSubject(subject);
        helper.setText(sendText, true);
        javaMailSender.send(message);
    }

    @Scheduled(cron = "0 50 18 * * ?")
    public void applyDingWeiTask() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        ThreadUtils.run(() -> {
            try {
                String aiAnswer = AiUtils.get3dDingWeiAi();
                //获取百位号码
                if(StrUtil.isNotBlank(aiAnswer) && aiAnswer.contains("百位:") && aiAnswer.contains("十位:") && aiAnswer.contains("个位:")){
                    String bwAnswer = aiAnswer.substring(3, 16);
                    String swAnswer = aiAnswer.substring(20, 33);
                    String gwAnswer = aiAnswer.substring(37, 50);
                    List<HmCache.CompareDto> sdCompareCache = HmCache.getSdCompareCache();
                    if(CollUtil.isNotEmpty(sdCompareCache)){
                        HmCache.CompareDto compareDto = sdCompareCache.get(sdCompareCache.size() - 1);
                        if(compareDto.getAiDingWeiHm() == null){
                            compareDto.setAiDingWeiHm(aiAnswer);
                        }
                    }
                    sendEmailCode("3D定位7码推荐", EmailConstant.DingWeiAskContent
                            .replace("{type}", "3D")
                            .replace("{bw}", bwAnswer)
                            .replace("{sw}", swAnswer)
                            .replace("{gw}", gwAnswer)
                    );
                }
            } catch (MessagingException e) {
                e.printStackTrace();
            } finally {
                latch.countDown();
            }
        });

        ThreadUtils.run(() -> {
            try {
                String pl3Answer = AiUtils.getPl3DingWeiAi();
                if(StrUtil.isNotBlank(pl3Answer) && pl3Answer.contains("百位:") && pl3Answer.contains("十位:") && pl3Answer.contains("个位:")){
                    String bwAnswer = pl3Answer.substring(3, 16);
                    String swAnswer = pl3Answer.substring(20, 33);
                    String gwAnswer = pl3Answer.substring(37, 50);
                    List<HmCache.CompareDto> pl3CompareCache = HmCache.getPl3CompareCache();
                    if(CollUtil.isNotEmpty(pl3CompareCache)){
                        HmCache.CompareDto compareDto = pl3CompareCache.get(pl3CompareCache.size() - 1);
                        if(compareDto.getAiDingWeiHm() == null){
                            compareDto.setAiDingWeiHm(pl3Answer);
                        }
                    }
                    sendEmailCode("排列3定位7码推荐", EmailConstant.DingWeiAskContent
                            .replace("{type}", "排列三")
                            .replace("{bw}", bwAnswer)
                            .replace("{sw}", swAnswer)
                            .replace("{gw}", gwAnswer)
                    );
                }
            }catch (Exception e){
                e.printStackTrace();
            }finally {
                latch.countDown();
            }
        });
        latch.await();
    }

    @Scheduled(cron = "0 40 18 * * ?")
    public void applyTask() throws MessagingException, InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);

        final String[] msg = {EmailConstant.EMAIL_TEMPLATE};
        ThreadUtils.run(() -> {
            try {
                String sdNumber = AiUtils.get3dAi();
                if(StrUtil.isNotBlank(sdNumber)){
                    String change3dNumber = sdNumber.replace(",", "");
                    char[] charArray = change3dNumber.toCharArray();
                    for (int i = 1; i <= charArray.length; i++) {
                        msg[0] = msg[0].replace("{{num_" + i + "}}", String.valueOf(charArray[i-1]));
                    }
                    //添加缓存
                    HmCache.addSdCompareCache(new HmCache.CompareDto().setAiHm(sdNumber));
                }
            }finally {
                latch.countDown();
            }
        });

        ThreadUtils.run(() -> {
            try {
                String pl3Number = AiUtils.getPl3Ai();
                if(StrUtil.isNotBlank(pl3Number)){
                    String changePl3Number = pl3Number.replace(",", "");
                    char[] charArray = changePl3Number.toCharArray();
                    for (int i = 1; i <= charArray.length; i++) {
                        msg[0] = msg[0].replace("{{num_" + (i + 15) + "}}", String.valueOf(charArray[i-1]));
                    }
                    HmCache.addPl3CompareCache(new HmCache.CompareDto().setAiHm(pl3Number));
                }
            }finally {
                latch.countDown();
            }
        });

        latch.await();
        msg[0] = msg[0].replace("{{TIMESTAMP}}", DateUtil.now());
        sendEmailCode("今日3D及排三预测", msg[0]);
    }

    @Scheduled(cron = "0 30 22 * * ?")
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

        String pl3AiHm = pl3CompareDto.getAiHm();
        String pl3RealHm = pl3CompareDto.getRealHm();
        String sdAiHm = sdCompareDto.getAiHm();
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

    private boolean checkSuccess(String aiHm, String realHm){
        // 1. 空值/空字符串校验（保留原逻辑）
        if (StrUtil.isBlank(aiHm) || StrUtil.isBlank(realHm)) {
            return false;
        }

        // 2. 提前判断：realHm长度 > aiHm，必然有字符不包含，直接返回false
        if (realHm.length() > aiHm.length()) {
            return false;
        }

        // 3. 将aiHm的字符存入Set，查询时间复杂度降为O(1)
        String[] split = aiHm.split(",");
        for (String str : split){
            if(realHm.equals(str)){
                return true;
            }
        }
        return false;
    }

    private void setKaiJiangCache(String url, boolean is3D, String computeQh) throws Exception{
        Hm kaiJiangHm = null;
        try {
            System.out.println(url);
            Document document = Jsoup.connect(url).get();
            Elements elements = document.getElementsByTag("tr");
            for (int i = 1; i < elements.size(); i++) {
                Elements tds = elements.get(i).getElementsByTag("td");
                String dateStr = tds.get(0).text();
                if(!DateUtils.isDateStr(dateStr)){
                    continue;
                }
                String qh = tds.get(1).text();
                if(!qh.equals(computeQh)){
                    continue;
                }
                String q1 = tds.get(2).text();
                String q2 = tds.get(3).text();
                String q3 = tds.get(4).text();
                kaiJiangHm = Hm.builder().qh(qh).q1(q1)
                        .q2(q2).q3(q3).build();
                break;
            }
        }catch (Exception e){
            e.printStackTrace();
        }

        if(kaiJiangHm != null){
            if(is3D){
                HmCache.addSdCache(kaiJiangHm);
            }else{
                HmCache.addPl3Cache(kaiJiangHm);
            }
            //重新写回excel文件
            String filePath = is3D ? fileLocation3d : fileLocationPl3;
            String sheetName = is3D ? "3D" : "排列三";
            List<Hm> list = is3D ? HmCache.getSdCache() : HmCache.getPl3Cache();
            EasyExcel.write(filePath, Hm.class).sheet(sheetName).doWrite(list);
        }

        //设置下比较缓存的真实值
        if(is3D){
            List<HmCache.CompareDto> sdCompareCache = HmCache.getSdCompareCache();
            if(CollUtil.isNotEmpty(sdCompareCache)){
                HmCache.CompareDto compareDto = sdCompareCache.get(sdCompareCache.size() - 1);
                if(kaiJiangHm == null){
                    sdCompareCache.remove(compareDto);
                }else{
                    compareDto.setRealHm(kaiJiangHm.toString());
                }
            }
        }else{
            List<HmCache.CompareDto> pl3CompareCache = HmCache.getPl3CompareCache();
            if(CollUtil.isNotEmpty(pl3CompareCache)){
                HmCache.CompareDto compareDto = pl3CompareCache.get(pl3CompareCache.size() - 1);
                if(kaiJiangHm == null){
                    pl3CompareCache.remove(compareDto);
                }else{
                    compareDto.setRealHm(kaiJiangHm.toString());
                }
            }
        }

        //录入本地预测结果
        if(is3D){
            List<CompareVO> insertList = HmCache.getSdCompareCache().stream().map(compareDto -> CompareVO.builder().aiHm(compareDto.getAiHm()).realHm(compareDto.getRealHm()).dingWeiQm(compareDto.getAiDingWeiHm()).build()).toList();
            EasyExcel.write(fileLocationCompare3d, CompareVO.class).sheet("3D比对结果").doWrite(insertList);
        }else{
            List<CompareVO> insertList = HmCache.getPl3CompareCache().stream().map(compareDto -> CompareVO.builder().aiHm(compareDto.getAiHm()).realHm(compareDto.getRealHm()).dingWeiQm(compareDto.getAiDingWeiHm()).build()).toList();
            EasyExcel.write(fileLocationComparePl3, CompareVO.class).sheet("排列三比对结果").doWrite(insertList);
        }
    }
}