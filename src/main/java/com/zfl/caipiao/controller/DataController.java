package com.zfl.caipiao.controller;

import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.Hm;
import com.zfl.caipiao.service.PnlService;
import com.zfl.caipiao.utils.Flow1000Ranker;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/data"})
public class DataController {
   @Resource
   private PnlService pnlService;

   @GetMapping
   public Map<String, Object> getData() {
      Map<String, Object> result = new HashMap<>();
      List<Hm> sdCache = HmCache.getSdCache();
      List<Hm> pl3Cache = HmCache.getPl3Cache();
      List<HmCache.CompareDto> sdCompareCache = HmCache.getSdCompareCache();
      List<HmCache.CompareDto> pl3CompareCache = HmCache.getPl3CompareCache();
      List<Map<String, String>> sdCompare = this.toCompareList(sdCompareCache, HmCache.getSdDadiCompareCache());
      List<Map<String, String>> pl3Compare = this.toCompareList(pl3CompareCache, HmCache.getPl3DadiCompareCache());
      int[][] sdDigits = Flow1000Ranker.digitsOf(sdCache);
      int[][] plDigits = Flow1000Ranker.digitsOf(pl3Cache);
      boolean sdTrendReady = Flow1000Ranker.trendReady(sdDigits, false);
      boolean plTrendReady = Flow1000Ranker.trendReady(plDigits, true);
      result.put("sd", sdCache);
      result.put("pl3", pl3Cache);
      result.put("sdCompare", sdCompare);
      result.put("pl3Compare", pl3Compare);
      result.put("sdTrend", sdTrendReady ? trendRows(sdCache, sdDigits, false) : List.of());
      result.put("pl3Trend", plTrendReady ? trendRows(pl3Cache, plDigits, true) : List.of());
      result.put("sdTrendReady", sdTrendReady);
      result.put("plTrendReady", plTrendReady);
      result.put("sdTrendNote", Flow1000Ranker.trendNote(false));
      result.put("plTrendNote", Flow1000Ranker.trendNote(true));
      result.put("sdDadi", toDadiList(HmCache.getSdDadiCompareCache(), sdCompareCache));
      result.put("pl3Dadi", toDadiList(HmCache.getPl3DadiCompareCache(), pl3CompareCache));
      return result;
   }

   @PostMapping({"/pnl/auth"})
   public Map<String, Object> authPnl(@RequestBody Map<String, String> body) {
      Map<String, Object> result = new HashMap<>();
      String password = body.get("password");
      if (!this.pnlService.verifyPassword(password)) {
         result.put("success", false);
         result.put("message", "密码错误");
         return result;
      } else {
         result.put("success", true);
         result.put("token", this.pnlService.createAuthToken());
         result.put("message", "验证成功");
         return result;
      }
   }

   @GetMapping({"/pnl"})
   public Map<String, Object> getPnl(@RequestHeader(value = "X-Pnl-Token",required = false) String token) {
      Map<String, Object> unauthorized = this.unauthorizedPnl(token);
      if (unauthorized != null) {
         return unauthorized;
      } else {
         Map<String, Object> result = new HashMap<>();
         result.put("success", true);
         result.put("records", this.pnlService.listRecords());
         result.put("summary", this.pnlService.summary());
         return result;
      }
   }

   @PostMapping({"/pnl"})
   public Map<String, Object> savePnl(@RequestHeader(value = "X-Pnl-Token",required = false) String token, @RequestBody Map<String, Object> body) {
      Map<String, Object> unauthorized = this.unauthorizedPnl(token);
      if (unauthorized != null) {
         return unauthorized;
      } else {
         Map<String, Object> result = new HashMap<>();
         String date = body.get("date") != null ? body.get("date").toString() : null;
         Object ticketObj = body.get("ticketAmount");
         Object winObj = body.get("winAmount");
         if (date != null && ticketObj != null && winObj != null) {
            try {
               double ticketAmount = Double.parseDouble(ticketObj.toString());
               double winAmount = Double.parseDouble(winObj.toString());
               this.pnlService.saveRecord(date, ticketAmount, winAmount);
               result.put("success", true);
               result.put("message", "保存成功");
               result.put("records", this.pnlService.listRecords());
               result.put("summary", this.pnlService.summary());
            } catch (NumberFormatException var12) {
               result.put("success", false);
               result.put("message", "金额格式无效");
            } catch (IllegalArgumentException var13) {
               result.put("success", false);
               result.put("message", var13.getMessage());
            } catch (Exception var14) {
               result.put("success", false);
               result.put("message", "保存失败：" + var14.getMessage());
            }

            return result;
         } else {
            result.put("success", false);
            result.put("message", "参数不完整");
            return result;
         }
      }
   }

   @PostMapping({"/pnl/delete"})
   public Map<String, Object> deletePnl(@RequestHeader(value = "X-Pnl-Token",required = false) String token, @RequestBody Map<String, String> body) {
      Map<String, Object> unauthorized = this.unauthorizedPnl(token);
      if (unauthorized != null) {
         return unauthorized;
      } else {
         Map<String, Object> result = new HashMap<>();
         String date = body.get("date");
         if (date == null) {
            result.put("success", false);
            result.put("message", "参数不完整");
            return result;
         } else {
            try {
               this.pnlService.deleteRecord(date);
               result.put("success", true);
               result.put("message", "删除成功");
               result.put("records", this.pnlService.listRecords());
               result.put("summary", this.pnlService.summary());
            } catch (IllegalArgumentException var7) {
               result.put("success", false);
               result.put("message", var7.getMessage());
            } catch (Exception var8) {
               result.put("success", false);
               result.put("message", "删除失败：" + var8.getMessage());
            }

            return result;
         }
      }
   }

   private Map<String, Object> unauthorizedPnl(String token) {
      if (this.pnlService.isValidToken(token)) {
         return null;
      } else {
         Map<String, Object> result = new HashMap<>();
         result.put("success", false);
         result.put("message", "未授权，请先输入密码");
         result.put("unauthorized", true);
         return result;
      }
   }

   private List<Map<String, String>> toCompareList(List<HmCache.CompareDto> list, List<HmCache.DadiCompareDto> dadiList) {
      int pendingIdx = -1;

      for (int i = list.size() - 1; i >= 0; i--) {
         HmCache.CompareDto dto = list.get(i);
         if (dto != null && (dto.getRealHm() == null || dto.getRealHm().isBlank())) {
            pendingIdx = i;
            break;
         }
      }

      String pendingDadi = pendingCursorDadi(dadiList);
      String pendingDadi1000 = pendingCursorDadi1000(dadiList);
      Map<String, String> byQh = new HashMap<>();
      Map<String, String> byReal = new HashMap<>();
      Map<String, String> byQh1000 = new HashMap<>();
      Map<String, String> byReal1000 = new HashMap<>();
      if (dadiList != null) {
         for (HmCache.DadiCompareDto d : dadiList) {
            if (d == null) {
               continue;
            }
            if (d.getCursorDadiHm() != null && !d.getCursorDadiHm().isBlank()) {
               if (d.getQh() != null && !d.getQh().isBlank()) {
                  byQh.put(d.getQh().trim(), d.getCursorDadiHm());
               }

               if (d.getRealHm() != null && !d.getRealHm().isBlank()) {
                  byReal.put(pad3(d.getRealHm()), d.getCursorDadiHm());
               }
            }
            if (d.getCursorDadi1000Hm() != null && !d.getCursorDadi1000Hm().isBlank()) {
               if (d.getQh() != null && !d.getQh().isBlank()) {
                  byQh1000.put(d.getQh().trim(), d.getCursorDadi1000Hm());
               }
               if (d.getRealHm() != null && !d.getRealHm().isBlank()) {
                  byReal1000.put(pad3(d.getRealHm()), d.getCursorDadi1000Hm());
               }
            }
         }
      }

      List<Map<String, String>> out = new ArrayList<>(list.size());

      for (int ix = 0; ix < list.size(); ix++) {
         HmCache.CompareDto dto = list.get(ix);
         Map<String, String> map = new HashMap<>();
         map.put("qh", dto.getQh());
         map.put("aiRecommendHm", firstRecommend(dto));
         map.put("aiOverfitHm", dto.getAiOverfitHm());
         map.put("aiDadiHm", resolveDadi(dto, ix == pendingIdx ? pendingDadi : null, byQh, byReal));
         map.put("aiDadi1000Hm", resolveDadi1000(dto, ix == pendingIdx ? pendingDadi1000 : null, byQh1000, byReal1000));
         map.put("aiDanMaHm", dto.getAiDanMaHm());
         map.put("aiShuangDanHm", dto.getAiShuangDanHm());
         map.put("aiZuSanHm", dto.getAiZuSanHm());
         map.put("aiDingWeiHm", dto.getAiDingWeiHm());
         map.put("realHm", dto.getRealHm());
         out.add(map);
      }

      return out;
   }

   private static List<Map<String, String>> toDadiList(List<HmCache.DadiCompareDto> dadiList, List<HmCache.CompareDto> compareList) {
      List<Map<String, String>> out = new ArrayList<>();
      Set<String> seenReal = new HashSet<>();
      boolean hasPending = false;
      if (dadiList != null) {
         for (HmCache.DadiCompareDto d : dadiList) {
            if (d != null && d.getCursorDadiHm() != null && !d.getCursorDadiHm().isBlank()) {
               boolean pending = d.getRealHm() == null || d.getRealHm().isBlank();
               if (pending) {
                  hasPending = true;
               } else {
                  seenReal.add(pad3(d.getRealHm()));
               }

               Map<String, String> map = new HashMap<>();
               map.put("qh", d.getQh());
               map.put("aiDadiHm", d.getCursorDadiHm());
               map.put("aiDadi1000Hm", d.getCursorDadi1000Hm());
               map.put("realHm", d.getRealHm());
               out.add(map);
            }
         }
      }

      if (compareList != null) {
         for (HmCache.CompareDto dto : compareList) {
            if (dto != null && dto.getAiFullHm() != null && !dto.getAiFullHm().isBlank()) {
               boolean pending = dto.getRealHm() == null || dto.getRealHm().isBlank();
               if (pending) {
                  if (hasPending) {
                     continue;
                  }

                  hasPending = true;
               } else {
                  if (seenReal.contains(pad3(dto.getRealHm()))) {
                     continue;
                  }

                  seenReal.add(pad3(dto.getRealHm()));
               }

               Map<String, String> map = new HashMap<>();
               map.put("qh", dto.getQh());
               map.put("aiDadiHm", dto.getAiFullHm());
               map.put("realHm", dto.getRealHm());
               out.add(map);
            }
         }
      }

      return out;
   }

   private static String resolveDadi(HmCache.CompareDto dto, String pendingDadi, Map<String, String> byQh, Map<String, String> byReal) {
      if (dto.getAiFullHm() != null && !dto.getAiFullHm().isBlank()) {
         return dto.getAiFullHm();
      } else if (dto.getQh() != null && byQh.containsKey(dto.getQh().trim())) {
         return byQh.get(dto.getQh().trim());
      } else {
         if (dto.getRealHm() != null && !dto.getRealHm().isBlank()) {
            String hit = byReal.get(pad3(dto.getRealHm()));
            if (hit != null) {
               return hit;
            }
         }

         return pendingDadi == null ? "" : pendingDadi;
      }
   }

   private static String resolveDadi1000(HmCache.CompareDto dto, String pendingDadi1000,
                                         Map<String, String> byQh, Map<String, String> byReal) {
      if (dto.getQh() != null && byQh.containsKey(dto.getQh().trim())) {
         return byQh.get(dto.getQh().trim());
      }
      if (dto.getRealHm() != null && !dto.getRealHm().isBlank()) {
         String hit = byReal.get(pad3(dto.getRealHm()));
         if (hit != null) {
            return hit;
         }
      }
      return pendingDadi1000 == null ? "" : pendingDadi1000;
   }

   private static String pendingCursorDadi(List<HmCache.DadiCompareDto> dadiList) {
      if (dadiList == null) {
         return "";
      } else {
         for (int i = 0; i < dadiList.size(); i++) {
            HmCache.DadiCompareDto d = dadiList.get(i);
            if (d != null && d.getCursorDadiHm() != null && !d.getCursorDadiHm().isBlank() && (d.getRealHm() == null || d.getRealHm().isBlank())) {
               return d.getCursorDadiHm();
            }
         }

         return "";
      }
   }

   private static String pendingCursorDadi1000(List<HmCache.DadiCompareDto> dadiList) {
      if (dadiList == null) {
         return "";
      }
      for (HmCache.DadiCompareDto d : dadiList) {
         if (d != null && d.getCursorDadi1000Hm() != null && !d.getCursorDadi1000Hm().isBlank()
                 && (d.getRealHm() == null || d.getRealHm().isBlank())) {
            return d.getCursorDadi1000Hm();
         }
      }
      return "";
   }

   /** 最近 200 期回放，已落盘的预测优先。下一期只展示定时任务留下、尚未写开奖号的一行，页面不现算。 */
   private static List<Map<String, String>> trendRows(List<Hm> history, int[][] digits, boolean pl3) {
      List<Map<String, String>> rows = new ArrayList<>();
      if (digits == null || history == null) {
         return rows;
      }
      int n = Math.min(digits.length, history.size());
      int from = Math.max(40, n - Flow1000Ranker.TREND_LOOK);
      for (int i = from; i < n; i++) {
         Hm hm = history.get(i);
         String qh = hm == null || hm.getQh() == null ? "" : hm.getQh();
         Map<String, String> map = new HashMap<>();
         map.put("qh", qh);
         map.put("realHm", hm == null ? "" : hm.toString());
         String issued = Flow1000Ranker.trendIssuedBets(pl3, qh);
         map.put("aiTrendHm", issued != null ? issued : Flow1000Ranker.trendCsv(digits, i, pl3));
         rows.add(map);
      }
      String[] open = Flow1000Ranker.trendOpenIssue(pl3);
      if (open == null) {
         return rows;
      }
      boolean openShown = false;
      if (!open[0].isBlank()) {
         for (Map<String, String> row : rows) {
            if (open[0].equals(row.get("qh"))) {
               openShown = true;
               break;
            }
         }
      }
      if (!openShown) {
         Map<String, String> next = new HashMap<>();
         next.put("qh", open[0].isBlank() ? "下一期" : open[0]);
         next.put("realHm", "");
         next.put("aiTrendHm", open[1]);
         rows.add(next);
      }
      return rows;
   }

   private static String pad3(String s) {
      String t = s == null ? "" : s.trim();
      return t.length() >= 3 ? t.substring(t.length() - 3) : "0".repeat(3 - t.length()) + t;
   }

   private static String firstRecommend(HmCache.CompareDto dto) {
      if (dto.getAiRecommendHm() != null && !dto.getAiRecommendHm().isBlank()) {
         return dto.getAiRecommendHm();
      } else if (dto.getAiHm() != null && !dto.getAiHm().isBlank()) {
         String[] parts = dto.getAiHm().split(",");
         StringBuilder sb = new StringBuilder();
         int n = 0;

         for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) {
               if (n > 0) {
                  sb.append(',');
               }

               sb.append(t);
               if (++n >= 20) {
                  break;
               }
            }
         }

         return sb.toString();
      } else {
         return "";
      }
   }
}
