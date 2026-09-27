package com.zfl.caipiao.job;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.constant.EmailConstant;
import com.zfl.caipiao.export.CompareVO;
import com.zfl.caipiao.export.Hm;
import com.zfl.caipiao.service.DadiService;
import com.zfl.caipiao.utils.DateUtils;
import com.zfl.caipiao.utils.Overfit20PredictUtils;
import com.zfl.caipiao.utils.RecommendBetUtils;
import com.zfl.caipiao.utils.RuleBasedDanMaUtils;
import com.zfl.caipiao.utils.RuleBasedDingWeiUtils;
import com.zfl.caipiao.utils.RuleBasedPredictUtils;
import jakarta.annotation.Resource;
import jakarta.mail.MessagingException;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

   @Scheduled(
      cron = "0 0 15 * * ?"
   )
   public void applyTask() throws Exception {
      Overfit20PredictUtils.PredictResult sdOf = Overfit20PredictUtils.predictResult(
         HmCache.getSdCache(), Overfit20PredictUtils.GameKind.SD, HmCache.getSdCompareCache()
      );
      String sdOverfitPool = sdOf.poolCsv();
      System.out.println("过拟合[3D] " + sdOf.tune);
      String raw200 = RuleBasedPredictUtils.predict(HmCache.getSdCache(), HmCache.getSdCompareCache(), RuleBasedPredictUtils.GameKind.SD_3D, sdOverfitPool);
      this.saveStraightDadi(true, raw200);
      String zuSan = RecommendBetUtils.extractZuSanGroups(raw200);
      String sdRecommend = RecommendBetUtils.pickRecommendBets(raw200, HmCache.getSdCompareCache(), sdOverfitPool, false);
      String sdDanMa = RuleBasedDanMaUtils.get3dDanMa();
      if (StrUtil.isNotBlank(sdRecommend)) {
         HmCache.addSdCompareCache(
            new HmCache.CompareDto()
               .setAiHm(sdRecommend)
               .setAiFullHm(raw200)
               .setAiRecommendHm(sdRecommend)
               .setAiOverfitHm(sdOverfitPool)
               .setAiDanMaHm(sdDanMa)
               .setAiZuSanHm(zuSan)
         );
      }

      Overfit20PredictUtils.PredictResult pl3Of = Overfit20PredictUtils.predictResult(
         HmCache.getPl3Cache(), Overfit20PredictUtils.GameKind.PL3, HmCache.getPl3CompareCache()
      );
      String pl3OverfitPool = pl3Of.poolCsv();
      System.out.println("过拟合[排列三] " + pl3Of.tune);
      raw200 = RuleBasedPredictUtils.predict(HmCache.getPl3Cache(), HmCache.getPl3CompareCache(), RuleBasedPredictUtils.GameKind.PL3, pl3OverfitPool);
      this.saveStraightDadi(false, raw200);
      zuSan = RecommendBetUtils.extractZuSanGroups(raw200);
      String pl3Recommend = RecommendBetUtils.pickRecommendBets(raw200, HmCache.getPl3CompareCache(), pl3OverfitPool, true);
      String pl3DanMa = RuleBasedDanMaUtils.getPl3DanMa();
      if (StrUtil.isNotBlank(pl3Recommend)) {
         HmCache.addPl3CompareCache(
            new HmCache.CompareDto()
               .setAiHm(pl3Recommend)
               .setAiFullHm(raw200)
               .setAiRecommendHm(pl3Recommend)
               .setAiOverfitHm(pl3OverfitPool)
               .setAiDanMaHm(pl3DanMa)
               .setAiZuSanHm(zuSan)
         );
      }

      String msg = "<!DOCTYPE html>\n<html>\n<head>\n    <meta charset=\"UTF-8\">\n    <title>今日彩票预测</title>\n    <style>\n        body { font-family: Arial, sans-serif; margin: 0; padding: 20px; background-color: #f5f5f5; }\n        .container { max-width: 600px; margin: 0 auto; background-color: white; border-radius: 10px; overflow: hidden; box-shadow: 0 4px 12px rgba(0,0,0,0.1); }\n        .header { background: linear-gradient(135deg, #667eea, #764ba2); color: white; text-align: center; padding: 30px 20px; }\n        .content { padding: 30px; }\n        .prediction-box { border: 2px solid #e0e0e0; border-radius: 8px; padding: 20px; margin: 20px 0; background-color: #fafafa; }\n        .game-title { font-size: 18px; font-weight: bold; color: #333; margin-bottom: 15px; }\n        .numbers { display: flex; justify-content: center; gap: 15px; margin: 15px 0; }\n        .number { width: 50px; height: 50px; border-radius: 50%; background: linear-gradient(135deg, #667eea, #764ba2); color: white; display: flex; align-items: center; justify-content: center; font-size: 20px; font-weight: bold; }\n        .footer { text-align: center; padding: 20px; background-color: #f8f9fa; color: #6c757d; font-size: 14px; }\n    </style>\n</head>\n<body>\n    <div class=\"container\">\n        <div class=\"header\">\n            <h1>\ud83c\udfaf 今日彩票预测（高概率10注）</h1>\n        </div>\n        <div class=\"content\">\n            <div class=\"prediction-box\">\n                <div class=\"game-title\">\ud83d\udcca 3D</div>\n                {{3D_NUMBERS}}\n            </div>\n            <div class=\"prediction-box\">\n                <div class=\"game-title\">\ud83c\udfb0 排列三</div>\n                {{PL3_NUMBERS}}\n            </div>\n            <div style=\"text-align: center; margin-top: 20px;\">\n                <p style=\"color: #ff6b6b; font-weight: bold; font-size: 13px\">\ud83d\udca1 温馨提示：理性购彩，祝您好运！</p>\n            </div>\n        </div>\n        <div class=\"footer\">\n            <p>发送时间: {{TIMESTAMP}}</p>\n        </div>\n    </div>\n</body>\n</html>\n"
         .replace("{{3D_NUMBERS}}", EmailConstant.buildNumbersHtml(sdRecommend))
         .replace("{{PL3_NUMBERS}}", EmailConstant.buildNumbersHtml(pl3Recommend))
         .replace("{{TIMESTAMP}}", DateUtil.now());
      this.sendEmailCode("今日3D及排三预测（高概率10注）", msg);
      this.applyDingWeiTask();
   }

   public void applyDingWeiTask() throws Exception {
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

         this.sendEmailCode(
            "3D定位7码推荐",
            "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n    <meta charset=\"UTF-8\">\n    <title>数字组合</title>\n</head>\n<body style=\"margin:0; padding:20px; background:#f5f7fa; font-family:Microsoft YaHei;\">\n    <div style=\"max-width:600px; margin:0 auto; background:#fff; border-radius:12px; padding:30px; box-shadow:0 2px 12px rgba(0,0,0,0.08);\">\n        <h2 style=\"text-align:center; color:#2c3e50; margin-bottom:30px;\">{type}定位七码推荐</h2>\n        <div style=\"margin-bottom:20px;\">\n            <div style=\"font-size:16px; color:#34495e; font-weight:bold; margin-bottom:8px;\">百位：</div>\n            <div style=\"font-size:15px; color:#27ae60; padding:12px; background:#f8fff9; border-radius:8px;\">{bw}</div>\n        </div>\n        <div style=\"margin-bottom:20px;\">\n            <div style=\"font-size:16px; color:#34495e; font-weight:bold; margin-bottom:8px;\">十位：</div>\n            <div style=\"font-size:15px; color:#2980b9; padding:12px; background:#f7fbff; border-radius:8px;\">{sw}</div>\n        </div>\n        <div style=\"margin-bottom:20px;\">\n            <div style=\"font-size:16px; color:#34495e; font-weight:bold; margin-bottom:8px;\">个位：</div>\n            <div style=\"font-size:15px; color:#f39c12; padding:12px; background:#fffbf5; border-radius:8px;\">{gw}</div>\n        </div>\n    </div>\n</body>\n</html>\n"
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

         this.sendEmailCode(
            "排列3定位7码推荐",
            "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n    <meta charset=\"UTF-8\">\n    <title>数字组合</title>\n</head>\n<body style=\"margin:0; padding:20px; background:#f5f7fa; font-family:Microsoft YaHei;\">\n    <div style=\"max-width:600px; margin:0 auto; background:#fff; border-radius:12px; padding:30px; box-shadow:0 2px 12px rgba(0,0,0,0.08);\">\n        <h2 style=\"text-align:center; color:#2c3e50; margin-bottom:30px;\">{type}定位七码推荐</h2>\n        <div style=\"margin-bottom:20px;\">\n            <div style=\"font-size:16px; color:#34495e; font-weight:bold; margin-bottom:8px;\">百位：</div>\n            <div style=\"font-size:15px; color:#27ae60; padding:12px; background:#f8fff9; border-radius:8px;\">{bw}</div>\n        </div>\n        <div style=\"margin-bottom:20px;\">\n            <div style=\"font-size:16px; color:#34495e; font-weight:bold; margin-bottom:8px;\">十位：</div>\n            <div style=\"font-size:15px; color:#2980b9; padding:12px; background:#f7fbff; border-radius:8px;\">{sw}</div>\n        </div>\n        <div style=\"margin-bottom:20px;\">\n            <div style=\"font-size:16px; color:#34495e; font-weight:bold; margin-bottom:8px;\">个位：</div>\n            <div style=\"font-size:15px; color:#f39c12; padding:12px; background:#fffbf5; border-radius:8px;\">{gw}</div>\n        </div>\n    </div>\n</body>\n</html>\n"
               .replace("{type}", "排列三")
               .replace("{bw}", parts[0])
               .replace("{sw}", parts[1])
               .replace("{gw}", parts[2])
         );
      }
   }

   @Scheduled(
      cron = "0 0 22 * * ?"
   )
   public void setDataTask() throws Exception {
      List<Hm> sdCache = HmCache.getSdCache();
      Hm sdHm = sdCache.get(sdCache.size() - 1);
      String lastSdQh = sdHm.getQh();
      String sdQh = DateUtils.getSdQh(lastSdQh);
      System.out.println("3d lastQh:" + lastSdQh + " currentQh:" + sdQh);
      String url = String.format("https://datachart.500.com/sd/zoushi/newinc/jbzs.php?expect=all&from=%s&to=%s", lastSdQh, sdQh);
      this.setKaiJiangCache(url, true, sdQh);
      List<Hm> pl3Cache = HmCache.getPl3Cache();
      Hm pl3Hm = pl3Cache.get(pl3Cache.size() - 1);
      String lastPl3Qh = pl3Hm.getQh();
      String p3Qh = DateUtils.getP3Qh(lastPl3Qh);
      System.out.println("pls lastQh:" + lastPl3Qh + " currentQh:" + p3Qh);
      String p3Url = String.format("https://datachart.500.com/pls/zoushi/newinc/jbzs.php?expect=all&from=%s&to=%s", lastPl3Qh, p3Qh);
      this.setKaiJiangCache(p3Url, false, p3Qh);
      List<HmCache.CompareDto> pl3CompareCache = HmCache.getPl3CompareCache();
      HmCache.CompareDto pl3CompareDto = pl3CompareCache.get(pl3CompareCache.size() - 1);
      List<HmCache.CompareDto> sdCompareCache = HmCache.getSdCompareCache();
      HmCache.CompareDto sdCompareDto = sdCompareCache.get(sdCompareCache.size() - 1);
      String pl3AiHm = displayRecommend(pl3CompareDto);
      String pl3RealHm = pl3CompareDto.getRealHm();
      String sdAiHm = displayRecommend(sdCompareDto);
      String sdRealHm = sdCompareDto.getRealHm();
      String result = !this.checkSuccess(sdAiHm, sdRealHm) && !this.checkSuccess(pl3AiHm, pl3RealHm) ? "很遗憾未中奖，下期必中！！！" : "恭喜，中奖了！！！";
      this.sendEmailCode(
         "今日开奖通知",
         "<!DOCTYPE html>\n<html>\n<head>\n    <meta charset=\"UTF-8\">\n    <title>彩票开奖通知</title>\n    <style>\n        body {\n            font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Helvetica Neue\", Arial, sans-serif;\n            background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);\n            margin: 0;\n            padding: 20px;\n            min-height: 100vh;\n        }\n        .game-title { font-size: 18px; font-weight: bold; color: #333; margin-bottom: 15px; }\n        .container {\n            max-width: 600px;\n            margin: 0 auto;\n        }\n        .card {\n            background: white;\n            border-radius: 12px;\n            box-shadow: 0 6px 20px rgba(0, 0, 0, 0.1);\n            padding: 30px;\n            margin-bottom: 20px;\n            transition: transform 0.3s ease;\n        }\n        .card:hover {\n            transform: translateY(-5px);\n        }\n        h1 {\n            text-align: center;\n            color: #2c3e50;\n            margin-bottom: 25px;\n            font-size: 24px;\n            border-bottom: 2px solid #3498db;\n            padding-bottom: 15px;\n        }\n        .item {\n            display: flex;\n            justify-content: space-between;\n            align-items: center;\n            padding: 15px 0;\n            border-bottom: 1px dashed #ecf0f1;\n        }\n        .item:last-child {\n            border-bottom: none;\n        }\n        .label {\n            font-weight: 600;\n            color: #34495e;\n            min-width: 140px;\n        }\n        .numbers {\n            background: #f8f9fa;\n            padding: 10px 15px;\n            border-radius: 8px;\n            font-family: 'Courier New', monospace;\n            font-weight: 500;\n            letter-spacing: 1px;\n            color: #2980b9;\n            border: 1px solid #e1e8ed;\n        }\n        .actual {\n            background: #e8f4fd;\n            color: #2980b9;\n            font-weight: bold;\n        }\n        .warning {\n            text-align: center;\n            color: #e74c3c;\n            font-size: 14px;\n            margin-top: 20px;\n            padding: 10px;\n            background: #fdf2f2;\n            border-radius: 6px;\n            border-left: 4px solid #e74c3c;\n        }\n    </style>\n</head>\n<body>\n<div class=\"container\">\n    <div class=\"card\">\n        <h1>彩票开奖通知：{{result}}</h1>\n        <div class=\"game-title\">\ud83c\udfb0 排列三</div>\n        <div class=\"item\">\n            <span class=\"label\">预测号码：</span>\n            <span class=\"numbers\">{{str1}}</span>\n        </div>\n        <div class=\"item\">\n            <span class=\"label\">实际开奖号码：</span>\n            <span class=\"numbers actual\">{{str2}}</span>\n        </div>\n        <div class=\"game-title\" style=\"margin-top: 20px\">\ud83d\udcca 3D</div>\n        <div class=\"item\">\n            <span class=\"label\">预测号码：</span>\n            <span class=\"numbers\">{{str3}}</span>\n        </div>\n        <div class=\"item\">\n            <span class=\"label\">实际开奖号码：</span>\n            <span class=\"numbers actual\">{{str4}}</span>\n        </div>\n    </div>\n</div>\n</body>\n</html>\n"
            .replace("{{result}}", result)
            .replace("{{str1}}", pl3AiHm)
            .replace("{{str2}}", pl3RealHm)
            .replace("{{str3}}", sdAiHm)
            .replace("{{str4}}", sdRealHm)
      );

      try {
         Overfit20PredictUtils.PredictResult sdNext = Overfit20PredictUtils.predictResult(HmCache.getSdCache(), Overfit20PredictUtils.GameKind.SD);
         Overfit20PredictUtils.PredictResult pl3Next = Overfit20PredictUtils.predictResult(HmCache.getPl3Cache(), Overfit20PredictUtils.GameKind.PL3);
         System.out.println("过拟合动态调参预演[3D] tickets=" + sdNext.pool.size() + " | " + sdNext.tune);
         System.out.println("过拟合动态调参预演[排列三] tickets=" + pl3Next.pool.size() + " | " + pl3Next.tune);
      } catch (Exception var22) {
         System.out.println("过拟合动态调参预演失败: " + var22.getMessage());
      }
   }

   private void saveStraightDadi(boolean is3D, String pool) {
      if (StrUtil.isBlank(pool)) {
         System.out.println("大底[" + (is3D ? "3D" : "排列三") + "] 生成为空，未落盘");
      } else {
         int count = pool.split(",").length;
         String qh = nextIssue(is3D);
         this.dadiService.saveGeneratedDadi(is3D, qh, pool);
         System.out.println("大底[" + (is3D ? "3D" : "排列三") + "] " + count + "注 期号=" + qh);
      }
   }

   private static String nextIssue(boolean is3D) {
      List<Hm> cache = is3D ? HmCache.getSdCache() : HmCache.getPl3Cache();
      if (!CollUtil.isEmpty(cache) && cache.get(cache.size() - 1) != null && !StrUtil.isBlank(cache.get(cache.size() - 1).getQh())) {
         String last = cache.get(cache.size() - 1).getQh().trim();
         return is3D ? DateUtils.getSdQh(last) : DateUtils.getP3Qh(last);
      } else {
         return null;
      }
   }

   private static String displayRecommend(HmCache.CompareDto dto) {
      if (dto == null) {
         return "";
      } else {
         return StrUtil.isNotBlank(dto.getAiRecommendHm())
            ? dto.getAiRecommendHm()
            : RecommendBetUtils.pickRecommendBets(StrUtil.blankToDefault(dto.getAiFullHm(), dto.getAiHm()), null);
      }
   }

   private boolean checkSuccess(String recommendHm, String realHm) {
      if (!StrUtil.isBlank(recommendHm) && !StrUtil.isBlank(realHm)) {
         for (String str : recommendHm.split(",")) {
            if (realHm.equals(str.trim())) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private void setKaiJiangCache(String url, boolean is3D, String computeQh) throws Exception {
      Hm kaiJiangHm = null;

      try {
         System.out.println(url);
         Document document = Jsoup.connect(url).get();
         Elements elements = document.getElementsByTag("tr");

         for (int i = 1; i < elements.size(); i++) {
            Elements tds = ((Element)elements.get(i)).getElementsByTag("td");
            String dateStr = ((Element)tds.get(0)).text();
            if (DateUtils.isDateStr(dateStr)) {
               String qh = ((Element)tds.get(1)).text();
               if (qh.equals(computeQh)) {
                  String q1 = ((Element)tds.get(2)).text();
                  String q2 = ((Element)tds.get(3)).text();
                  String q3 = ((Element)tds.get(4)).text();
                  kaiJiangHm = Hm.builder().qh(qh).q1(q1).q2(q2).q3(q3).build();
                  break;
               }
            }
         }
      } catch (Exception var14) {
         var14.printStackTrace();
      }

      if (kaiJiangHm != null) {
         if (is3D) {
            HmCache.addSdCache(kaiJiangHm);
         } else {
            HmCache.addPl3Cache(kaiJiangHm);
         }

         String filePath = is3D ? this.fileLocation3d : this.fileLocationPl3;
         String sheetName = is3D ? "3D" : "排列三";
         List<Hm> list = is3D ? HmCache.getSdCache() : HmCache.getPl3Cache();
         EasyExcel.write(filePath, Hm.class).sheet(sheetName).doWrite(list);
         this.dadiService.updateRealHm(is3D, kaiJiangHm.toString());
      }

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

      if (is3D) {
         List<CompareVO> insertList = HmCache.getSdCompareCache()
            .stream()
            .map(
               compareDtox -> CompareVO.builder()
                  .qh(compareDtox.getQh())
                  .aiHm(compareDtox.getAiHm())
                  .aiRecommendHm(compareDtox.getAiRecommendHm())
                  .aiOverfitHm(compareDtox.getAiOverfitHm())
                  .aiFullHm(compareDtox.getAiFullHm())
                  .aiDanMaHm(compareDtox.getAiDanMaHm())
                  .aiZuSanHm(StrUtil.blankToDefault(compareDtox.getAiZuSanHm(), RecommendBetUtils.extractZuSanGroups(compareDtox.getAiHm())))
                  .realHm(compareDtox.getRealHm())
                  .dingWeiQm(compareDtox.getAiDingWeiHm())
                  .build()
            )
            .toList();
         EasyExcel.write(this.fileLocationCompare3d, CompareVO.class).sheet("3D比对结果").doWrite(insertList);
      } else {
         List<CompareVO> insertList = HmCache.getPl3CompareCache()
            .stream()
            .map(
               compareDtox -> CompareVO.builder()
                  .qh(compareDtox.getQh())
                  .aiHm(compareDtox.getAiHm())
                  .aiRecommendHm(compareDtox.getAiRecommendHm())
                  .aiOverfitHm(compareDtox.getAiOverfitHm())
                  .aiFullHm(compareDtox.getAiFullHm())
                  .aiDanMaHm(compareDtox.getAiDanMaHm())
                  .aiZuSanHm(StrUtil.blankToDefault(compareDtox.getAiZuSanHm(), RecommendBetUtils.extractZuSanGroups(compareDtox.getAiHm())))
                  .realHm(compareDtox.getRealHm())
                  .dingWeiQm(compareDtox.getAiDingWeiHm())
                  .build()
            )
            .toList();
         EasyExcel.write(this.fileLocationComparePl3, CompareVO.class).sheet("排列三比对结果").doWrite(insertList);
      }
   }

   private void sendEmailCode(String subject, String sendText) throws MessagingException {
   }
}
