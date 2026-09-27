package com.zfl.caipiao.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.read.builder.ExcelReaderBuilder;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.DadiCompareVO;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DadiService {
   private static final int DADI_MAX_SIZE = 500;
   private static final Set<String> VALID_MODELS = Set.of("cursor", "custom");
   @Value("${file.location.compare3DDadi}")
   private String fileLocationCompare3dDadi;
   @Value("${file.location.comparePl3Dadi}")
   private String fileLocationComparePl3Dadi;

   public List<String> parseNumbers(String text) {
      return StrUtil.isBlank(text)
         ? List.of()
         : Arrays.stream(text.split("[,，\\s\\n\\r]+")).map(String::trim).filter(s -> s.matches("\\d{3}")).collect(Collectors.toList());
   }

   public void updateDadi(boolean is3D, int index, String model, String numbersText) {
      if (!VALID_MODELS.contains(model)) {
         throw new IllegalArgumentException("模型无效，请使用 cursor 或 custom");
      } else {
         List<String> numbers = this.parseNumbers(numbersText);
         this.validateDadiNumbers(numbers);
         List<HmCache.DadiCompareDto> cache = is3D ? HmCache.getSdDadiCompareCache() : HmCache.getPl3DadiCompareCache();
         if (index >= 0 && index < cache.size()) {
            this.setModelDadiHm(cache.get(index), model, String.join(",", numbers));
            this.writeExcel(is3D);
         } else {
            throw new IllegalArgumentException("记录不存在或索引无效");
         }
      }
   }

   public void saveGeneratedDadi(boolean is3D, String qh, String numbersText) {
      List<String> numbers = this.parseNumbers(numbersText);
      if (numbers.size() > 500) {
         numbers = numbers.subList(0, 500);
      }

      this.validateDadiNumbers(numbers);
      String dadiHm = String.join(",", numbers);
      List<HmCache.DadiCompareDto> cache = is3D ? HmCache.getSdDadiCompareCache() : HmCache.getPl3DadiCompareCache();
      if (CollUtil.isNotEmpty(cache)) {
         HmCache.DadiCompareDto latest = cache.get(0);
         if (StrUtil.isBlank(latest.getRealHm())) {
            latest.setCursorDadiHm(dadiHm);
            if (StrUtil.isNotBlank(qh)) {
               latest.setQh(qh);
               cache.removeIf(d -> d != latest && qh.equals(StrUtil.trim(d.getQh())));
            }

            this.writeExcel(is3D);
            return;
         }
      }

      HmCache.DadiCompareDto dto = new HmCache.DadiCompareDto().setQh(qh).setRealHm(null).setCursorDadiHm(dadiHm);
      if (CollUtil.isNotEmpty(cache) && StrUtil.isNotBlank(qh)) {
         cache.removeIf(d -> qh.equals(StrUtil.trim(d.getQh())));
      }

      if (is3D) {
         HmCache.addSdDadiCompareCache(dto);
      } else {
         HmCache.addPl3DadiCompareCache(dto);
      }

      this.writeExcel(is3D);
   }

   public void saveDadi(boolean is3D, String model, String numbersText) {
      if (!VALID_MODELS.contains(model)) {
         throw new IllegalArgumentException("模型无效，请使用 cursor 或 custom");
      } else {
         List<String> numbers = this.parseNumbers(numbersText);
         this.validateDadiNumbers(numbers);
         String dadiHm = String.join(",", numbers);
         List<HmCache.DadiCompareDto> cache = is3D ? HmCache.getSdDadiCompareCache() : HmCache.getPl3DadiCompareCache();
         if (CollUtil.isNotEmpty(cache)) {
            HmCache.DadiCompareDto latest = cache.get(0);
            if (StrUtil.isBlank(latest.getRealHm())) {
               this.setModelDadiHm(latest, model, dadiHm);
               this.writeExcel(is3D);
               return;
            }
         }

         HmCache.DadiCompareDto dto = new HmCache.DadiCompareDto().setQh(null).setRealHm(null);
         this.setModelDadiHm(dto, model, dadiHm);
         if (is3D) {
            HmCache.addSdDadiCompareCache(dto);
         } else {
            HmCache.addPl3DadiCompareCache(dto);
         }

         this.writeExcel(is3D);
      }
   }

   private void validateDadiNumbers(List<String> numbers) {
      if (numbers.isEmpty()) {
         throw new IllegalArgumentException("请至少录入1注三位数号码");
      } else if (numbers.size() > 500) {
         throw new IllegalArgumentException("最多录入500注三位数号码，当前有效号码数：" + numbers.size());
      }
   }

   private void setModelDadiHm(HmCache.DadiCompareDto dto, String model, String dadiHm) {
      switch (model) {
         case "cursor":
            dto.setCursorDadiHm(dadiHm);
            break;
         case "custom":
            dto.setCustomDadiHm(dadiHm);
            break;
         default:
            throw new IllegalArgumentException("未知模型：" + model);
      }
   }

   public void loadFromExcel(boolean is3D, String filePath) {
      File file = new File(filePath);
      if (file.exists()) {
         List<DadiCompareVO> list = ((ExcelReaderBuilder)EasyExcel.read(filePath).head(DadiCompareVO.class)).sheet().doReadSync();
         if (!CollUtil.isEmpty(list)) {
            List<HmCache.DadiCompareDto> loaded = list.stream()
               .map(
                  vo -> new HmCache.DadiCompareDto()
                     .setQh(vo.getQh())
                     .setCursorDadiHm(vo.getCursorDadiHm())
                     .setCustomDadiHm(vo.getCustomDadiHm())
                     .setRealHm(vo.getRealHm())
               )
               .toList();
            List<HmCache.DadiCompareDto> dtos = loaded.size() > 30 ? new ArrayList<>(loaded.subList(0, 30)) : new ArrayList<>(loaded);
            int before = dtos.size();
            dtos = dropSettledDuplicateOfPending(dtos);
            if (is3D) {
               HmCache.setSdDadiCompareCache(dtos);
            } else {
               HmCache.setPl3DadiCompareCache(dtos);
            }

            if (dtos.size() != before) {
               this.writeExcel(is3D);
            }
         }
      }
   }

   private static List<HmCache.DadiCompareDto> dropSettledDuplicateOfPending(List<HmCache.DadiCompareDto> dtos) {
      if (dtos.isEmpty()) {
         return dtos;
      } else {
         HmCache.DadiCompareDto head = dtos.get(0);
         if (head != null && !StrUtil.isNotBlank(head.getRealHm()) && !StrUtil.isBlank(head.getQh())) {
            String qh = head.getQh().trim();
            List<HmCache.DadiCompareDto> kept = new ArrayList<>();
            kept.add(head);

            for (int i = 1; i < dtos.size(); i++) {
               HmCache.DadiCompareDto d = dtos.get(i);
               if (d == null || !qh.equals(StrUtil.trim(d.getQh()))) {
                  kept.add(d);
               }
            }

            return kept;
         } else {
            return dtos;
         }
      }
   }

   public void writeExcel(boolean is3D) {
      String filePath = is3D ? this.fileLocationCompare3dDadi : this.fileLocationComparePl3Dadi;
      FileUtil.mkParentDirs(filePath);
      List<HmCache.DadiCompareDto> cache = is3D ? HmCache.getSdDadiCompareCache() : HmCache.getPl3DadiCompareCache();
      List<DadiCompareVO> insertList = cache.stream()
         .map(
            dto -> DadiCompareVO.builder()
               .qh(dto.getQh())
               .cursorDadiHm(dto.getCursorDadiHm())
               .customDadiHm(dto.getCustomDadiHm())
               .realHm(dto.getRealHm())
               .build()
         )
         .toList();
      String sheetName = is3D ? "3D大底比对" : "排列三大底比对";
      EasyExcel.write(filePath, DadiCompareVO.class).sheet(sheetName).doWrite(insertList);
   }

   public void updateRealHm(boolean is3D, String realHm) {
      List<HmCache.DadiCompareDto> cache = is3D ? HmCache.getSdDadiCompareCache() : HmCache.getPl3DadiCompareCache();
      if (!CollUtil.isEmpty(cache) && !StrUtil.isBlank(realHm)) {
         HmCache.DadiCompareDto latest = cache.get(0);
         if (StrUtil.isBlank(latest.getRealHm()) && this.hasAnyDadiHm(latest)) {
            latest.setRealHm(realHm);
            this.writeExcel(is3D);
         }
      }
   }

   private boolean hasAnyDadiHm(HmCache.DadiCompareDto dto) {
      return StrUtil.isNotBlank(dto.getCursorDadiHm()) || StrUtil.isNotBlank(dto.getCustomDadiHm());
   }
}
