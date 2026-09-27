package com.zfl.caipiao.config;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.read.builder.ExcelReaderBuilder;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.CompareVO;
import com.zfl.caipiao.export.Hm;
import com.zfl.caipiao.service.DadiService;
import com.zfl.caipiao.service.PnlService;
import jakarta.annotation.Resource;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AppStarter implements ApplicationRunner {
   @Value("${file.location.3d}")
   private String fileLocation3d;
   @Value("${file.location.pl3}")
   private String fileLocationPl3;
   @Value("${file.location.compare3D}")
   private String fileLocationCompare3d;
   @Value("${file.location.comparePl3}")
   private String fileLocationComparePl3;
   @Value("${file.location.compare3DDadi}")
   private String fileLocationCompare3dDadi;
   @Value("${file.location.comparePl3Dadi}")
   private String fileLocationComparePl3Dadi;
   @Resource
   private DadiService dadiService;
   @Resource
   private PnlService pnlService;

   public void run(ApplicationArguments args) throws Exception {
      List<Hm> sdList = ((ExcelReaderBuilder)EasyExcel.read(this.fileLocation3d).head(Hm.class)).sheet().doReadSync();
      HmCache.setSdCache(sdList);
      System.out.println(HmCache.getSdCache().size());
      List<Hm> p3List = ((ExcelReaderBuilder)EasyExcel.read(this.fileLocationPl3).head(Hm.class)).sheet().doReadSync();
      HmCache.setPl3Cache(p3List);
      System.out.println(HmCache.getPl3Cache().size());
      System.out.println(this.fileLocationCompare3d);
      List<CompareVO> sdCompareList = ((ExcelReaderBuilder)EasyExcel.read(this.fileLocationCompare3d).head(CompareVO.class)).sheet().doReadSync();
      HmCache.setSdCompareCache(
         sdCompareList.stream()
            .map(
               compareVO -> new HmCache.CompareDto()
                  .setQh(compareVO.getQh())
                  .setAiHm(compareVO.getAiHm())
                  .setAiRecommendHm(compareVO.getAiRecommendHm())
                  .setAiOverfitHm(compareVO.getAiOverfitHm())
                  .setAiFullHm(compareVO.getAiFullHm())
                  .setAiDanMaHm(compareVO.getAiDanMaHm())
                  .setAiZuSanHm(compareVO.getAiZuSanHm())
                  .setRealHm(compareVO.getRealHm())
                  .setAiDingWeiHm(compareVO.getDingWeiQm())
            )
            .toList()
      );
      System.out.println("3dCompareCache:" + HmCache.getSdCompareCache());
      System.out.println(this.fileLocationComparePl3);
      List<CompareVO> pl3CompareList = ((ExcelReaderBuilder)EasyExcel.read(this.fileLocationComparePl3).head(CompareVO.class)).sheet().doReadSync();
      HmCache.setPl3CompareCache(
         pl3CompareList.stream()
            .map(
               compareVO -> new HmCache.CompareDto()
                  .setQh(compareVO.getQh())
                  .setAiHm(compareVO.getAiHm())
                  .setAiRecommendHm(compareVO.getAiRecommendHm())
                  .setAiOverfitHm(compareVO.getAiOverfitHm())
                  .setAiFullHm(compareVO.getAiFullHm())
                  .setAiDanMaHm(compareVO.getAiDanMaHm())
                  .setAiZuSanHm(compareVO.getAiZuSanHm())
                  .setRealHm(compareVO.getRealHm())
                  .setAiDingWeiHm(compareVO.getDingWeiQm())
            )
            .toList()
      );
      System.out.println("pl3CompareCache:" + HmCache.getPl3CompareCache());
      this.dadiService.loadFromExcel(true, this.fileLocationCompare3dDadi);
      System.out.println("3dDadiCompareCache:" + HmCache.getSdDadiCompareCache().size());
      this.dadiService.loadFromExcel(false, this.fileLocationComparePl3Dadi);
      System.out.println("pl3DadiCompareCache:" + HmCache.getPl3DadiCompareCache().size());
      this.pnlService.loadFromExcel();
      System.out.println("pnlCache:" + HmCache.getPnlCache().size());
   }
}
