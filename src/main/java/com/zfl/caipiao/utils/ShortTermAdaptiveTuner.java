package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

/**
 * 短期走势自适应：在近 10/12/15/18/20/25 等窗口中择优，
 * 并结合往期预测-实开差异动态调参，使三码/大底/七码更贴合近期盘面。
 */
@Slf4j
final class ShortTermAdaptiveTuner {

    /** 候选短期窗口（期数） */
    static final int[] CANDIDATE_WINDOWS = {10, 12, 15, 18, 20, 25};
    /** 择窗时滚动评估的近期期数 */
    private static final int EVAL_PERIODS = 8;
    /** 差异诊断默认回看期数 */
    private static final int DIFF_LOOKBACK = 12;

    private ShortTermAdaptiveTuner() {
    }

    /**
     * 三码 / 150 注大底调参结果。
     */
    static final class PredictTune {
        /** 频次/形态主窗口 */
        final int featureWindow;
        /** 转移样本窗口 */
        final int transferWindow;
        /** 偏差纠偏回看 */
        final int biasLookback;
        /** 命中名次统计回看 */
        final int hitRankLookback;
        /** 热号权重倍率 */
        final double hotMul;
        /** 冷/遗漏回补倍率 */
        final double coldMul;
        /** 邻号倍率 */
        final double neighborMul;
        /** 重号/延续倍率 */
        final double repeatMul;
        /** 组三配额增量 */
        final int pairQuotaDelta;
        /** 散组目标增量 */
        final int groupTargetDelta;
        /** 名次带下界偏移（可负） */
        final int bandLoDelta;
        /** 名次带上界偏移 */
        final int bandHiDelta;
        /** 连续未中组选期数 */
        final int missGroupStreak;
        /** 连续未中直选期数 */
        final int missZxStreak;

        PredictTune(int featureWindow, int transferWindow, int biasLookback, int hitRankLookback,
                    double hotMul, double coldMul, double neighborMul, double repeatMul,
                    int pairQuotaDelta, int groupTargetDelta, int bandLoDelta, int bandHiDelta,
                    int missGroupStreak, int missZxStreak) {
            this.featureWindow = featureWindow;
            this.transferWindow = transferWindow;
            this.biasLookback = biasLookback;
            this.hitRankLookback = hitRankLookback;
            this.hotMul = hotMul;
            this.coldMul = coldMul;
            this.neighborMul = neighborMul;
            this.repeatMul = repeatMul;
            this.pairQuotaDelta = pairQuotaDelta;
            this.groupTargetDelta = groupTargetDelta;
            this.bandLoDelta = bandLoDelta;
            this.bandHiDelta = bandHiDelta;
            this.missGroupStreak = missGroupStreak;
            this.missZxStreak = missZxStreak;
        }

        String describe() {
            return String.format(
                    "短期窗=%d 转移窗=%d 纠偏回看=%d 名次回看=%d | 热×%.2f 冷×%.2f 邻×%.2f 重×%.2f | 组三Δ=%+d 散组Δ=%+d 名次带Δ=[%+d,%+d] | 连挂组选=%d 直选=%d",
                    featureWindow, transferWindow, biasLookback, hitRankLookback,
                    hotMul, coldMul, neighborMul, repeatMul,
                    pairQuotaDelta, groupTargetDelta, bandLoDelta, bandHiDelta,
                    missGroupStreak, missZxStreak);
        }
    }

    /**
     * 七码定位调参结果。
     */
    static final class DingWeiTune {
        final int featureWindow;
        final int transferWindow;
        final double recentFreqMul;
        final double omitMul;
        final double neighborMul;
        final double repeatMul;
        final int bandLoDelta;
        final int bandHiDelta;
        final int[] posMissStreak;

        DingWeiTune(int featureWindow, int transferWindow, double recentFreqMul, double omitMul,
                    double neighborMul, double repeatMul, int bandLoDelta, int bandHiDelta,
                    int[] posMissStreak) {
            this.featureWindow = featureWindow;
            this.transferWindow = transferWindow;
            this.recentFreqMul = recentFreqMul;
            this.omitMul = omitMul;
            this.neighborMul = neighborMul;
            this.repeatMul = repeatMul;
            this.bandLoDelta = bandLoDelta;
            this.bandHiDelta = bandHiDelta;
            this.posMissStreak = posMissStreak;
        }

        String describe() {
            return String.format(
                    "七码短期窗=%d 转移窗=%d | 近频×%.2f 遗漏×%.2f 邻×%.2f 重×%.2f | 名次带Δ=[%+d,%+d] | 位连挂=%s",
                    featureWindow, transferWindow, recentFreqMul, omitMul, neighborMul, repeatMul,
                    bandLoDelta, bandHiDelta, Arrays.toString(posMissStreak));
        }
    }

    static PredictTune tuneForPredict(int[][] digits, List<HmCache.CompareDto> compares) {
        int featureWindow = selectBestFeatureWindow(digits);
        int transferWindow = Math.min(40, Math.max(featureWindow + 5, featureWindow * 2));
        // 转移窗也在候选里取与 feature 接近的最优
        transferWindow = selectBestTransferWindow(digits, featureWindow);

        DiffStats diff = analyzePredictDiff(compares, digits);

        double hotMul = 1.0;
        double coldMul = 1.0;
        double neighborMul = 1.0;
        double repeatMul = 1.0;
        int pairDelta = 0;
        int groupDelta = 0;
        int bandLoDelta = 0;
        int bandHiDelta = 0;

        // 近窗越短，越倚重热号与邻号；越长则略抬冷码回补
        if (featureWindow <= 12) {
            hotMul += 0.35;
            neighborMul += 0.25;
            coldMul -= 0.10;
        } else if (featureWindow >= 20) {
            coldMul += 0.25;
            hotMul -= 0.05;
        } else {
            hotMul += 0.15;
            coldMul += 0.10;
        }

        // 预测-实开差异驱动
        if (diff.neighborMissRate >= 0.35) {
            neighborMul += 0.40;
            bandHiDelta += 1;
        }
        if (diff.coldAppearRate >= 0.40) {
            coldMul += 0.45;
            hotMul -= 0.10;
        }
        if (diff.hotMissRate >= 0.45) {
            hotMul -= 0.20;
            coldMul += 0.15;
            bandLoDelta = Math.max(bandLoDelta, 0);
            bandHiDelta += 1;
        }
        if (diff.posCoverMissRate >= 0.40) {
            bandLoDelta -= 1;
            bandHiDelta += 1;
            neighborMul += 0.15;
        }
        if (diff.missGroupStreak >= 2) {
            groupDelta += 8;
            coldMul += 0.20;
            if (diff.recentPairActual >= diff.recentZu6Actual) {
                pairDelta += 6;
            } else {
                pairDelta -= 2;
                groupDelta += 4;
            }
        }
        if (diff.missGroupStreak >= 3) {
            groupDelta += 6;
            coldMul += 0.25;
            hotMul = Math.max(0.6, hotMul - 0.15);
        }
        if (diff.missZxStreak >= 3) {
            // 有组无直：收紧名次带偏中段，抬邻号/重号
            neighborMul += 0.20;
            repeatMul += 0.25;
            bandLoDelta += 0;
            bandHiDelta = Math.min(bandHiDelta, 1);
        }
        if (diff.pairMissWhenActualPair >= 2) {
            pairDelta += 8;
            repeatMul += 0.20;
        }

        // 连续位避开 → 强制该位扩带
        for (int pos = 0; pos < 3; pos++) {
            if (diff.posMissStreak[pos] >= 3) {
                bandLoDelta -= 1;
                bandHiDelta += 1;
                neighborMul += 0.08;
            }
        }

        hotMul = clamp(hotMul, 0.55, 1.80);
        coldMul = clamp(coldMul, 0.55, 1.90);
        neighborMul = clamp(neighborMul, 0.70, 1.90);
        repeatMul = clamp(repeatMul, 0.70, 1.80);
        pairDelta = clampInt(pairDelta, -10, 16);
        groupDelta = clampInt(groupDelta, -10, 20);
        bandLoDelta = clampInt(bandLoDelta, -2, 2);
        bandHiDelta = clampInt(bandHiDelta, -1, 3);

        int biasLookback = Math.min(20, Math.max(10, featureWindow));
        int hitRankLookback = Math.min(25, Math.max(12, featureWindow + 5));

        PredictTune tune = new PredictTune(
                featureWindow, transferWindow, biasLookback, hitRankLookback,
                hotMul, coldMul, neighborMul, repeatMul,
                pairDelta, groupDelta, bandLoDelta, bandHiDelta,
                diff.missGroupStreak, diff.missZxStreak);
        log.info("短期自适应(三码/大底): {}", tune.describe());
        return tune;
    }

    static DingWeiTune tuneForDingWei(int[][] digits, List<HmCache.CompareDto> compares) {
        int featureWindow = selectBestFeatureWindow(digits);
        int transferWindow = selectBestTransferWindow(digits, featureWindow);
        DiffStats diff = analyzeDingWeiDiff(compares, digits);

        double recentFreqMul = 1.0;
        double omitMul = 1.0;
        double neighborMul = 1.0;
        double repeatMul = 1.0;
        int bandLoDelta = 0;
        int bandHiDelta = 0;

        if (featureWindow <= 12) {
            recentFreqMul += 0.40;
            neighborMul += 0.30;
            repeatMul += 0.20;
        } else if (featureWindow >= 20) {
            omitMul += 0.30;
            recentFreqMul += 0.10;
        } else {
            recentFreqMul += 0.25;
            omitMul += 0.15;
            neighborMul += 0.15;
        }

        // 七码差异：某位连挂则扩该位带、抬邻号/遗漏
        int missPosCount = 0;
        for (int pos = 0; pos < 3; pos++) {
            if (diff.posMissStreak[pos] >= 2) {
                missPosCount++;
                neighborMul += 0.12;
                omitMul += 0.10;
                bandHiDelta += 1;
            }
            if (diff.posMissStreak[pos] >= 3) {
                bandLoDelta -= 1;
                recentFreqMul += 0.08;
            }
        }
        if (missPosCount >= 2) {
            // 多位同时偏离：更信短期热号与邻域
            recentFreqMul += 0.20;
            neighborMul += 0.20;
            bandLoDelta -= 1;
            bandHiDelta += 1;
        }
        if (diff.fullMissStreak >= 2) {
            omitMul += 0.25;
            neighborMul += 0.20;
            bandHiDelta += 1;
        }
        if (diff.coldAppearRate >= 0.35) {
            omitMul += 0.35;
            recentFreqMul = Math.max(0.7, recentFreqMul - 0.10);
        }

        recentFreqMul = clamp(recentFreqMul, 0.60, 1.85);
        omitMul = clamp(omitMul, 0.60, 1.90);
        neighborMul = clamp(neighborMul, 0.70, 1.90);
        repeatMul = clamp(repeatMul, 0.70, 1.80);
        bandLoDelta = clampInt(bandLoDelta, -2, 2);
        bandHiDelta = clampInt(bandHiDelta, -1, 3);

        DingWeiTune tune = new DingWeiTune(
                featureWindow, transferWindow, recentFreqMul, omitMul,
                neighborMul, repeatMul, bandLoDelta, bandHiDelta,
                Arrays.copyOf(diff.posMissStreak, 3));
        log.info("短期自适应(七码): {}", tune.describe());
        return tune;
    }

    /**
     * 用轻量位分代理：近 EVAL 期，各窗口 Top4 覆盖实开位的得分择优。
     */
    private static int selectBestFeatureWindow(int[][] digits) {
        if (digits == null || digits.length < 25) {
            return 15;
        }
        int n = digits.length;
        int evalStart = Math.max(20, n - EVAL_PERIODS);
        int bestW = 15;
        double bestScore = -1;
        for (int w : CANDIDATE_WINDOWS) {
            if (evalStart < w + 2) {
                continue;
            }
            double score = 0;
            int samples = 0;
            for (int i = evalStart; i < n; i++) {
                int[][] hist = Arrays.copyOfRange(digits, 0, i);
                for (int pos = 0; pos < 3; pos++) {
                    int[] sc = lightweightScore(hist, pos, w);
                    int rank = rankOf(sc, digits[i][pos]);
                    // 越靠前越好；落入 Top4 额外奖
                    score += Math.max(0, 11 - rank);
                    if (rank <= 4) {
                        score += 4;
                    }
                    if (rank <= 7) {
                        score += 2;
                    }
                }
                samples++;
            }
            if (samples == 0) {
                continue;
            }
            double avg = score / samples;
            // 略偏好更短窗口（短期走势）
            avg += (25 - w) * 0.08;
            if (avg > bestScore) {
                bestScore = avg;
                bestW = w;
            }
        }
        return bestW;
    }

    private static int selectBestTransferWindow(int[][] digits, int featureWindow) {
        if (digits == null || digits.length < 30) {
            return Math.min(30, Math.max(15, featureWindow * 2));
        }
        int n = digits.length;
        int evalStart = Math.max(25, n - EVAL_PERIODS);
        int[] cands = {
                Math.max(10, featureWindow),
                Math.max(12, featureWindow + 3),
                Math.min(40, featureWindow * 2),
                Math.min(35, featureWindow + 10),
                20, 25, 30
        };
        int bestW = Math.min(30, featureWindow * 2);
        double bestScore = -1;
        for (int w : cands) {
            if (w < 10 || evalStart < w + 2) {
                continue;
            }
            double score = 0;
            int samples = 0;
            for (int i = evalStart; i < n; i++) {
                int[] last = digits[i - 1];
                int[] actual = digits[i];
                int[] hit = new int[10];
                int from = Math.max(1, i - w);
                for (int j = from; j < i; j++) {
                    int[] prev = digits[j - 1];
                    int match = 0;
                    for (int p = 0; p < 3; p++) {
                        if (prev[p] == last[p]) {
                            match++;
                        }
                    }
                    if (match >= 1) {
                        hit[digits[j][0]]++;
                        hit[digits[j][1]]++;
                        hit[digits[j][2]]++;
                    }
                }
                for (int p = 0; p < 3; p++) {
                    int rank = rankOf(hit, actual[p]);
                    score += Math.max(0, 11 - rank);
                }
                samples++;
            }
            if (samples == 0) {
                continue;
            }
            double avg = score / samples;
            if (avg > bestScore) {
                bestScore = avg;
                bestW = w;
            }
        }
        return bestW;
    }

    private static DiffStats analyzePredictDiff(List<HmCache.CompareDto> compares, int[][] digits) {
        DiffStats s = new DiffStats();
        if (compares == null || compares.isEmpty()) {
            // 无对比时用开奖自身短期形态粗估
            fillShapeFromDigits(s, digits);
            return s;
        }
        int end = compares.size();
        int start = Math.max(0, end - DIFF_LOOKBACK);
        int checked = 0;
        int neighborMiss = 0;
        int coldAppear = 0;
        int hotMiss = 0;
        int posCoverMissSlots = 0;
        int posCoverSlots = 0;

        for (int i = start; i < end; i++) {
            HmCache.CompareDto dto = compares.get(i);
            if (dto == null || isBlank(dto.getAiHm()) || isBlank(dto.getRealHm()) || dto.getRealHm().length() != 3) {
                continue;
            }
            int[] real = parseCode(dto.getRealHm());
            if (real == null) {
                continue;
            }
            checked++;
            boolean[] predDigit = new boolean[10];
            int[] predPosFreq = new int[3];
            boolean[][] posHas = new boolean[3][10];
            for (String p : dto.getAiHm().split(",")) {
                int[] t = parseCode(p.trim());
                if (t == null) {
                    continue;
                }
                predDigit[t[0]] = true;
                predDigit[t[1]] = true;
                predDigit[t[2]] = true;
                posHas[0][t[0]] = true;
                posHas[1][t[1]] = true;
                posHas[2][t[2]] = true;
            }
            boolean hitNeighbor = false;
            for (int pos = 0; pos < 3; pos++) {
                int d = real[pos];
                if (predDigit[d] || predDigit[neighbor(d, -1)] || predDigit[neighbor(d, 1)]) {
                    hitNeighbor = true;
                }
                posCoverSlots++;
                if (!posHas[pos][d]) {
                    posCoverMissSlots++;
                    s.posMissStreak[pos]++; // 临时累加，后面改成从尾部重算
                }
                predPosFreq[pos]++;
            }
            if (!hitNeighbor) {
                neighborMiss++;
            }

            // 冷热：相对近15期频次
            int[] recentFreq = recentAnyFreq(digits, 15);
            int coldCnt = 0, hotCnt = 0;
            for (int pos = 0; pos < 3; pos++) {
                int f = recentFreq[real[pos]];
                if (f <= 1) {
                    coldCnt++;
                }
                if (f >= 4) {
                    hotCnt++;
                }
            }
            if (coldCnt >= 1) {
                coldAppear++;
            }
            // 热号实际出现但预测码池未覆盖
            boolean hotMissed = false;
            for (int pos = 0; pos < 3; pos++) {
                if (recentFreq[real[pos]] >= 4 && !predDigit[real[pos]]) {
                    hotMissed = true;
                }
            }
            if (hotMissed) {
                hotMiss++;
            }

            boolean zx = containsBet(dto.getAiHm(), dto.getRealHm());
            boolean group = containsGroup(dto.getAiHm(), dto.getRealHm());
            if (isPairSet(real[0], real[1], real[2])) {
                s.recentPairActual++;
                if (!group) {
                    s.pairMissWhenActualPair++;
                }
            } else if (!(real[0] == real[1] && real[1] == real[2])) {
                s.recentZu6Actual++;
            }
            // 连挂在循环外从尾部重算
            if (zx) {
                s.lastZxHit = true;
            }
            if (group) {
                s.lastGroupHit = true;
            }
        }

        // 从最近往前重算连挂与位连挂
        Arrays.fill(s.posMissStreak, 0);
        s.missZxStreak = 0;
        s.missGroupStreak = 0;
        boolean stopZx = false, stopGroup = false;
        boolean[] stopPos = new boolean[3];
        for (int i = end - 1; i >= start; i--) {
            HmCache.CompareDto dto = compares.get(i);
            if (dto == null || isBlank(dto.getAiHm()) || isBlank(dto.getRealHm()) || dto.getRealHm().length() != 3) {
                continue;
            }
            boolean zx = containsBet(dto.getAiHm(), dto.getRealHm());
            boolean group = containsGroup(dto.getAiHm(), dto.getRealHm());
            if (!stopZx) {
                if (zx) {
                    stopZx = true;
                } else {
                    s.missZxStreak++;
                }
            }
            if (!stopGroup) {
                if (group) {
                    stopGroup = true;
                } else {
                    s.missGroupStreak++;
                }
            }
            int[] real = parseCode(dto.getRealHm());
            if (real == null) {
                continue;
            }
            boolean[][] posHas = new boolean[3][10];
            for (String p : dto.getAiHm().split(",")) {
                int[] t = parseCode(p.trim());
                if (t == null) {
                    continue;
                }
                posHas[0][t[0]] = true;
                posHas[1][t[1]] = true;
                posHas[2][t[2]] = true;
            }
            for (int pos = 0; pos < 3; pos++) {
                if (stopPos[pos]) {
                    continue;
                }
                if (posHas[pos][real[pos]]) {
                    stopPos[pos] = true;
                } else {
                    s.posMissStreak[pos]++;
                }
            }
            if (stopZx && stopGroup && stopPos[0] && stopPos[1] && stopPos[2]) {
                break;
            }
        }

        if (checked > 0) {
            s.neighborMissRate = (double) neighborMiss / checked;
            s.coldAppearRate = (double) coldAppear / checked;
            s.hotMissRate = (double) hotMiss / checked;
            s.posCoverMissRate = posCoverSlots == 0 ? 0 : (double) posCoverMissSlots / posCoverSlots;
        }
        return s;
    }

    private static DiffStats analyzeDingWeiDiff(List<HmCache.CompareDto> compares, int[][] digits) {
        DiffStats s = new DiffStats();
        if (compares == null || compares.isEmpty()) {
            fillShapeFromDigits(s, digits);
            return s;
        }
        int end = compares.size();
        int start = Math.max(0, end - DIFF_LOOKBACK);
        int checked = 0;
        int coldAppear = 0;
        boolean[] stopPos = new boolean[3];
        boolean stopFull = false;

        for (int i = end - 1; i >= start; i--) {
            HmCache.CompareDto dto = compares.get(i);
            if (dto == null || isBlank(dto.getAiDingWeiHm()) || isBlank(dto.getRealHm())
                    || dto.getRealHm().length() != 3) {
                continue;
            }
            String[] parts = RuleBasedDingWeiUtils.parseParts(dto.getAiDingWeiHm());
            if (parts == null) {
                continue;
            }
            checked++;
            int[] real = parseCode(dto.getRealHm());
            if (real == null) {
                continue;
            }
            boolean allHit = true;
            for (int pos = 0; pos < 3; pos++) {
                boolean hit = false;
                for (String d : parts[pos].split(",")) {
                    if (d.trim().length() == 1 && d.trim().charAt(0) - '0' == real[pos]) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    allHit = false;
                    if (!stopPos[pos]) {
                        s.posMissStreak[pos]++;
                    }
                } else {
                    stopPos[pos] = true;
                }
            }
            if (!stopFull) {
                if (allHit) {
                    stopFull = true;
                } else {
                    s.fullMissStreak++;
                }
            }
            int[] recentFreq = recentAnyFreq(digits, 15);
            for (int pos = 0; pos < 3; pos++) {
                if (recentFreq[real[pos]] <= 1) {
                    coldAppear++;
                    break;
                }
            }
            if (stopFull && stopPos[0] && stopPos[1] && stopPos[2]) {
                break;
            }
        }
        if (checked > 0) {
            s.coldAppearRate = (double) coldAppear / checked;
        }
        return s;
    }

    private static void fillShapeFromDigits(DiffStats s, int[][] digits) {
        if (digits == null || digits.length < 5) {
            return;
        }
        int n = digits.length;
        int from = Math.max(0, n - 10);
        for (int i = from; i < n; i++) {
            int a = digits[i][0], b = digits[i][1], c = digits[i][2];
            if (a == b && b == c) {
                continue;
            }
            if (isPairSet(a, b, c)) {
                s.recentPairActual++;
            } else {
                s.recentZu6Actual++;
            }
        }
    }

    private static int[] lightweightScore(int[][] digits, int pos, int window) {
        int n = digits.length;
        int[] freq = new int[10];
        int from = Math.max(0, n - window);
        for (int i = from; i < n; i++) {
            freq[digits[i][pos]]++;
        }
        int last = digits[n - 1][pos];
        int[] omit = new int[10];
        Arrays.fill(omit, n);
        for (int i = n - 1; i >= 0; i--) {
            int d = digits[i][pos];
            if (omit[d] == n) {
                omit[d] = n - 1 - i;
            }
        }
        int half = Math.max(5, window / 2);
        int[] freqHalf = new int[10];
        int fromHalf = Math.max(0, n - half);
        for (int i = fromHalf; i < n; i++) {
            freqHalf[digits[i][pos]]++;
        }
        int[] score = new int[10];
        for (int d = 0; d < 10; d++) {
            int s = freq[d] * 3 + freqHalf[d] * 4;
            if (omit[d] >= 2 && omit[d] <= Math.max(4, window / 2)) {
                s += 3;
            }
            if (d == last) {
                s += 1;
            }
            if (d == neighbor(last, -1) || d == neighbor(last, 1)) {
                s += 2;
            }
            score[d] = s;
        }
        return score;
    }

    private static int[] recentAnyFreq(int[][] digits, int window) {
        int[] f = new int[10];
        if (digits == null || digits.length == 0) {
            return f;
        }
        int n = digits.length;
        int from = Math.max(0, n - window);
        for (int i = from; i < n; i++) {
            f[digits[i][0]]++;
            f[digits[i][1]]++;
            f[digits[i][2]]++;
        }
        return f;
    }

    private static int rankOf(int[] score, int digit) {
        int better = 0;
        for (int d = 0; d < 10; d++) {
            if (score[d] > score[digit] || (score[d] == score[digit] && d < digit)) {
                better++;
            }
        }
        return better + 1;
    }

    private static boolean containsBet(String pred, String actual) {
        if (isBlank(pred) || isBlank(actual)) {
            return false;
        }
        String a = pad3(actual);
        for (String p : pred.split(",")) {
            if (pad3(p.trim()).equals(a)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsGroup(String pred, String actual) {
        if (isBlank(pred) || isBlank(actual) || actual.trim().length() < 3) {
            return false;
        }
        String key = sortedKey(pad3(actual));
        for (String p : pred.split(",")) {
            String t = pad3(p.trim());
            if (t.length() == 3 && sortedKey(t).equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static String sortedKey(String code) {
        char[] c = code.toCharArray();
        Arrays.sort(c);
        return new String(c);
    }

    private static boolean isPairSet(int a, int b, int c) {
        return (a == b && b != c) || (a == c && a != b) || (b == c && a != b);
    }

    private static int neighbor(int d, int delta) {
        return (d + delta + 10) % 10;
    }

    private static int[] parseCode(String code) {
        if (code == null) {
            return null;
        }
        String c = pad3(code.trim());
        if (c.length() != 3) {
            return null;
        }
        try {
            return new int[]{c.charAt(0) - '0', c.charAt(1) - '0', c.charAt(2) - '0'};
        } catch (Exception e) {
            return null;
        }
    }

    private static String pad3(String s) {
        if (s == null) {
            return "000";
        }
        String t = s.trim();
        while (t.length() < 3) {
            t = "0" + t;
        }
        return t.length() > 3 ? t.substring(t.length() - 3) : t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static final class DiffStats {
        double neighborMissRate;
        double coldAppearRate;
        double hotMissRate;
        double posCoverMissRate;
        int missGroupStreak;
        int missZxStreak;
        int fullMissStreak;
        int recentPairActual;
        int recentZu6Actual;
        int pairMissWhenActualPair;
        final int[] posMissStreak = new int[3];
        boolean lastZxHit;
        boolean lastGroupHit;
    }
}
