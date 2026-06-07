package com.zfl.caipiao.config;

import com.alibaba.excel.EasyExcel;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.CompareVO;
import com.zfl.caipiao.export.Hm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author zfl
 */
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

    @Override
    public void run(ApplicationArguments args) throws Exception {
        //构建缓存
        List<Hm> sdList = EasyExcel.read(fileLocation3d).head(Hm.class)
                .sheet().doReadSync();
        HmCache.setSdCache(sdList);
        System.out.println(HmCache.getSdCache().size());

        List<Hm> p3List = EasyExcel.read(fileLocationPl3).head(Hm.class)
                .sheet().doReadSync();
        HmCache.setPl3Cache(p3List);
        System.out.println(HmCache.getPl3Cache().size());

        System.out.println(fileLocationCompare3d);
        List<CompareVO> sdCompareList = EasyExcel.read(fileLocationCompare3d).head(CompareVO.class)
                .sheet().doReadSync();
        HmCache.setSdCompareCache(sdCompareList.stream().map(compareVO -> new HmCache.CompareDto()
                .setAiHm(compareVO.getAiHm())
                .setRealHm(compareVO.getRealHm())
                .setAiDingWeiHm(compareVO.getDingWeiQm())
        ).toList());
        System.out.println("3dCompareCache:" + HmCache.getSdCompareCache());

        System.out.println(fileLocationComparePl3);
        List<CompareVO> pl3CompareList = EasyExcel.read(fileLocationComparePl3).head(CompareVO.class)
                .sheet().doReadSync();
        HmCache.setPl3CompareCache(pl3CompareList.stream().map(compareVO -> new HmCache.CompareDto()
                .setAiHm(compareVO.getAiHm())
                .setRealHm(compareVO.getRealHm())
                .setAiDingWeiHm(compareVO.getDingWeiQm())
        ).toList());
        System.out.println("pl3CompareCache:" + HmCache.getPl3CompareCache());
    }

}