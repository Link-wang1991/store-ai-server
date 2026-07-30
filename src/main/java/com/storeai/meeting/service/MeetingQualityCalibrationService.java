package com.storeai.meeting.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动评分不是“自证正确”的指标。本服务只汇总店长已经人工复核过的真实会谈，
 * 展示样本量、自动/人工均值、绝对偏差及分段一致率，避免用未标注数据伪造准确率。
 */
@Service
@RequiredArgsConstructor
public class MeetingQualityCalibrationService {

    private static final int[] BANDS = {0, 25, 50, 75, 100};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public Map<String, Object> summary(String storeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT ma.id, ma.meeting_id, ma.quality_score, ma.quality_review_score,
                   ma.quality_review_reason_codes, ma.quality_review_note, ma.quality_reviewed_at,
                   m.customer_name, m.employee_name
            FROM meeting_analysis ma
            JOIN meetings m ON m.id = ma.meeting_id AND m.store_id = ma.store_id
            WHERE ma.store_id = ? AND ma.quality_review_status = 'reviewed'
              AND ma.quality_score IS NOT NULL AND ma.quality_review_score IS NOT NULL
            ORDER BY ma.quality_reviewed_at DESC
            LIMIT 200
            """, storeId);

        int total = rows.size();
        double autoTotal = 0D;
        double manualTotal = 0D;
        double absoluteGapTotal = 0D;
        int sameBand = 0;
        Map<String, Integer> automaticBands = emptyBands();
        Map<String, Integer> reviewBands = emptyBands();
        Map<String, Integer> reasonCounts = new LinkedHashMap<>();
        List<Map<String, Object>> recent = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            int automatic = number(row.get("quality_score"));
            int manual = number(row.get("quality_review_score"));
            autoTotal += automatic;
            manualTotal += manual;
            absoluteGapTotal += Math.abs(automatic - manual);
            automaticBands.compute(String.valueOf(nearestBand(automatic)), (ignored, value) -> value == null ? 1 : value + 1);
            reviewBands.compute(String.valueOf(nearestBand(manual)), (ignored, value) -> value == null ? 1 : value + 1);
            if (nearestBand(automatic) == nearestBand(manual)) sameBand++;
            for (String reason : parseReasons(row.get("quality_review_reason_codes"))) {
                reasonCounts.compute(reason, (ignored, value) -> value == null ? 1 : value + 1);
            }
            if (recent.size() < 20) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("meeting_id", row.get("meeting_id"));
                item.put("customer_name", row.get("customer_name"));
                item.put("employee_name", row.get("employee_name"));
                item.put("automatic_score", automatic);
                item.put("review_score", manual);
                item.put("gap", Math.abs(automatic - manual));
                item.put("reason_codes", parseReasons(row.get("quality_review_reason_codes")));
                item.put("note", row.get("quality_review_note"));
                item.put("reviewed_at", row.get("quality_reviewed_at"));
                recent.add(item);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sample_size", total);
        result.put("automatic_average", total == 0 ? null : round(autoTotal / total));
        result.put("manual_average", total == 0 ? null : round(manualTotal / total));
        result.put("mean_absolute_gap", total == 0 ? null : round(absoluteGapTotal / total));
        result.put("same_band_rate", total == 0 ? null : round((sameBand * 100D) / total));
        result.put("automatic_bands", automaticBands);
        result.put("manual_bands", reviewBands);
        result.put("reason_counts", reasonCounts);
        result.put("recent_reviews", recent);
        result.put("interpretation", interpretation(total, total == 0 ? 0D : absoluteGapTotal / total, total == 0 ? 0D : (sameBand * 100D) / total));
        result.put("method", "仅统计已保存店长人工复核、且自动评分与人工评分都存在的会谈；分段一致=两者落在同一 0/25/50/75/100 档。样本不足时不下结论。");
        return result;
    }

    private Map<String, Integer> emptyBands() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int band : BANDS) result.put(String.valueOf(band), 0);
        return result;
    }

    private List<String> parseReasons(Object raw) {
        if (raw == null) return List.of();
        try {
            return objectMapper.readValue(String.valueOf(raw), new TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return new BigDecimal(String.valueOf(value)).intValue(); }
        catch (Exception ignored) { return 0; }
    }

    private int nearestBand(int value) {
        int winner = BANDS[0];
        for (int band : BANDS) if (Math.abs(value - band) < Math.abs(value - winner)) winner = band;
        return winner;
    }

    private double round(double value) { return Math.round(value * 10D) / 10D; }

    private String interpretation(int sample, double meanGap, double agreement) {
        if (sample < 10) return "当前人工复核样本少于 10 场，仅用于发现问题，不应据此调整评分规则。";
        if (meanGap <= 10 && agreement >= 70) return "当前自动评分与人工判断基本一致；仍应按月补充跨场景样本。";
        if (meanGap <= 20) return "存在可校准偏差；优先查看偏差大的会谈与复核原因，再调整提示词或量表，不直接改总分。";
        return "自动评分与人工判断偏差较大；请暂停把分数用于比较，先检查转写质量、评分证据和各维量表。";
    }
}
