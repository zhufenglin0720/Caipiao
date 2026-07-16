package com.zfl.caipiao.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.zfl.caipiao.cache.HmCache;
import com.zfl.caipiao.export.ProfitLossVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author zfl
 */
@Service
public class PnlService {

    /** 累积盈亏初始值（历史结余） */
    public static final double INITIAL_CUMULATIVE = 6185D;

    @Value("${file.location.pnl}")
    private String fileLocationPnl;

    @Value("${pnl.password}")
    private String pnlPassword;

    /** 登录后下发的会话 token，进程内有效 */
    private final Map<String, Long> authTokens = new ConcurrentHashMap<>();

    private static final long TOKEN_TTL_MS = 12 * 60 * 60 * 1000L;

    public boolean verifyPassword(String password) {
        return StrUtil.isNotBlank(password) && password.equals(pnlPassword);
    }

    public String createAuthToken() {
        cleanupExpiredTokens();
        String token = UUID.randomUUID().toString().replace("-", "");
        authTokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
        return token;
    }

    public boolean isValidToken(String token) {
        if (StrUtil.isBlank(token)) {
            return false;
        }
        Long expireAt = authTokens.get(token);
        if (expireAt == null) {
            return false;
        }
        if (expireAt < System.currentTimeMillis()) {
            authTokens.remove(token);
            return false;
        }
        return true;
    }

    private void cleanupExpiredTokens() {
        long now = System.currentTimeMillis();
        authTokens.entrySet().removeIf(e -> e.getValue() < now);
    }

    public void loadFromExcel() {
        File file = new File(fileLocationPnl);
        if (!file.exists()) {
            return;
        }
        List<ProfitLossVO> list = EasyExcel.read(fileLocationPnl).head(ProfitLossVO.class)
                .sheet().doReadSync();
        if (CollUtil.isEmpty(list)) {
            return;
        }
        List<HmCache.PnlRecordDto> dtos = list.stream()
                .filter(vo -> StrUtil.isNotBlank(vo.getDate()))
                .map(vo -> new HmCache.PnlRecordDto()
                        .setDate(vo.getDate().trim())
                        .setTicketAmount(defaultAmount(vo.getTicketAmount()))
                        .setWinAmount(defaultAmount(vo.getWinAmount())))
                .sorted(Comparator.comparing(HmCache.PnlRecordDto::getDate))
                .toList();
        HmCache.setPnlCache(dtos);
    }

    public void saveRecord(String date, double ticketAmount, double winAmount) {
        if (StrUtil.isBlank(date)) {
            throw new IllegalArgumentException("日期不能为空");
        }
        if (ticketAmount < 0 || winAmount < 0) {
            throw new IllegalArgumentException("金额不能为负数");
        }
        String normalizedDate = date.trim();
        List<HmCache.PnlRecordDto> cache = HmCache.getPnlCache();
        for (HmCache.PnlRecordDto dto : cache) {
            if (normalizedDate.equals(dto.getDate())) {
                dto.setTicketAmount(ticketAmount);
                dto.setWinAmount(winAmount);
                sortAndWrite();
                return;
            }
        }
        HmCache.addPnlCache(new HmCache.PnlRecordDto()
                .setDate(normalizedDate)
                .setTicketAmount(ticketAmount)
                .setWinAmount(winAmount));
        sortAndWrite();
    }

    public void deleteRecord(String date) {
        if (StrUtil.isBlank(date)) {
            throw new IllegalArgumentException("日期不能为空");
        }
        String normalizedDate = date.trim();
        List<HmCache.PnlRecordDto> cache = HmCache.getPnlCache();
        boolean removed = cache.removeIf(dto -> normalizedDate.equals(dto.getDate()));
        if (!removed) {
            throw new IllegalArgumentException("记录不存在");
        }
        writeExcel();
    }

    public List<Map<String, Object>> listRecords() {
        List<HmCache.PnlRecordDto> sorted = new ArrayList<>(HmCache.getPnlCache());
        sorted.sort(Comparator.comparing(HmCache.PnlRecordDto::getDate));
        List<Map<String, Object>> result = new ArrayList<>();
        double cumulative = INITIAL_CUMULATIVE;
        for (HmCache.PnlRecordDto dto : sorted) {
            double ticket = defaultAmount(dto.getTicketAmount());
            double win = defaultAmount(dto.getWinAmount());
            double daily = win - ticket;
            cumulative += daily;
            Map<String, Object> row = new HashMap<>();
            row.put("date", dto.getDate());
            row.put("ticketAmount", ticket);
            row.put("winAmount", win);
            row.put("dailyPnl", daily);
            row.put("cumulativePnl", cumulative);
            result.add(row);
        }
        return result;
    }

    public Map<String, Object> summary() {
        List<Map<String, Object>> records = listRecords();
        double totalTicket = 0;
        double totalWin = 0;
        for (Map<String, Object> row : records) {
            totalTicket += ((Number) row.get("ticketAmount")).doubleValue();
            totalWin += ((Number) row.get("winAmount")).doubleValue();
        }
        double cumulative = records.isEmpty() ? INITIAL_CUMULATIVE
                : ((Number) records.get(records.size() - 1).get("cumulativePnl")).doubleValue();
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalDays", records.size());
        summary.put("totalTicket", totalTicket);
        summary.put("totalWin", totalWin);
        summary.put("cumulativePnl", cumulative);
        summary.put("initialCumulative", INITIAL_CUMULATIVE);
        return summary;
    }

    private void sortAndWrite() {
        List<HmCache.PnlRecordDto> cache = HmCache.getPnlCache();
        cache.sort(Comparator.comparing(HmCache.PnlRecordDto::getDate));
        writeExcel();
    }

    private void writeExcel() {
        FileUtil.mkParentDirs(fileLocationPnl);
        List<HmCache.PnlRecordDto> cache = HmCache.getPnlCache();
        List<ProfitLossVO> insertList = cache.stream()
                .map(dto -> ProfitLossVO.builder()
                        .date(dto.getDate())
                        .ticketAmount(defaultAmount(dto.getTicketAmount()))
                        .winAmount(defaultAmount(dto.getWinAmount()))
                        .build())
                .toList();
        EasyExcel.write(fileLocationPnl, ProfitLossVO.class).sheet("盈亏记录").doWrite(insertList);
    }

    private double defaultAmount(Double amount) {
        return amount == null ? 0 : amount;
    }

}
