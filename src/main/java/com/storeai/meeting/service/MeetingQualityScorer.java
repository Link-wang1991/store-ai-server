package com.storeai.meeting.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会谈质量评分的确定性规则层。
 *
 * <p>模型只负责根据逐句转写给出维度判断和证据；本类负责统一量表、加权公式以及
 * 合规风险否决。这样不会再把模型缺失的分数伪装成 60 分，也不会让严重合规风险被
 * 其他维度的高分抵消。</p>
 */
@Component
public class MeetingQualityScorer {

    public static final int DISTILL_THRESHOLD = 75;
    public static final int LOW_SCORE_THRESHOLD = 50;

    private static final int[] SCORE_BANDS = {0, 25, 50, 75, 100};
    private static final Pattern PURE_SCORE = Pattern.compile("^\\s*(\\d{1,3})(?:\\s*分)?\\s*$");

    private static final List<Dimension> DIMENSIONS = List.of(
        new Dimension(
            "need_digging_score", "需求挖掘", 25, "need_digging_evidence",
            "0=未识别需求；25=只做基础提问；50=确认需求但缺少关键决策事实；75=确认需求并核实至少两项决策事实；100=需求、痛点、预算、时点、决策关系和优先级均有转写依据且已复核。"
        ),
        new Dimension(
            "deal_advancing_score", "成交推进", 30, "deal_advancing_evidence",
            "0=没有下一步；25=仅介绍或回答；50=提出匹配方案但未处理顾虑或达成行动；75=依据客户顾虑推进并约定具体下一步；100=在不施压、不夸大前提下，明确约定时间、负责人、行动与成功标准。"
        ),
        new Dimension(
            "compliance_score", "合规表现", 20, "compliance_evidence",
            "0=L4 红线；25=L3 重度风险；50=存在 L2 风险或关键边界不清；75=仅有轻微提醒且总体合规；100=无风险表达，并清楚处理边界、预期和不确定性。"
        ),
        new Dimension(
            "service_score", "服务体验", 25, "service_evidence",
            "0=忽视或贬低客户；25=有回应但缺少共情或清晰说明；50=基本礼貌但未建立预期；75=兼顾共情、清晰和服务预期；100=主动建立信任，确认客户感受并给出持续支持安排。"
        )
    );

    /** 对新的模型报告计算评分，并把可展示的评分元数据写回报告。 */
    public Result evaluate(Map<String, Object> analysis) {
        List<DimensionResult> dimensions = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        double weighted = 0;

        for (Dimension dimension : DIMENSIONS) {
            Integer score = parseAndNormalize(analysis.get(dimension.scoreKey()));
            if (score == null) {
                missing.add(dimension.label());
            } else {
                weighted += score * dimension.weightPercent() / 100.0;
            }
            dimensions.add(new DimensionResult(dimension, score));
        }

        Result result;
        Integer baseScore = missing.isEmpty() ? (int) Math.round(weighted) : null;
        int hardRiskLevel = hardRiskLevel(analysis);
        if (hardRiskLevel >= 4) {
            result = new Result(
                0, baseScore, "redline",
                "命中 L4 合规红线：不以其他表现抵消风险，质量分记为 0，禁止进入经验沉淀。"
                    + (missing.isEmpty() ? "" : "评分维度同时不完整：" + String.join("、", missing) + "。"),
                missing, dimensions
            );
        } else if (hardRiskLevel >= 3) {
            result = new Result(
                baseScore == null ? LOW_SCORE_THRESHOLD - 1 : Math.min(baseScore, LOW_SCORE_THRESHOLD - 1),
                baseScore, "serious_risk",
                "命中 L3 严重合规风险：总分最高为 49，禁止进入经验沉淀，并触发店长介入与辅导。"
                    + (missing.isEmpty() ? "" : "评分维度同时不完整：" + String.join("、", missing) + "。"),
                missing, dimensions
            );
        } else if (!missing.isEmpty()) {
            result = new Result(
                null, null, "incomplete",
                "评分依据不完整：缺少" + String.join("、", missing) + "维度的有效评分。本次不生成质量分，也不会进入经验沉淀或低分辅导。",
                missing, dimensions
            );
        } else {
            String message = baseScore >= DISTILL_THRESHOLD
                ? "达到经验候选线；仍须店长或老板审核后，才会进入正式知识库。"
                : baseScore < LOW_SCORE_THRESHOLD
                    ? "低于辅导线，系统会创建改进任务；该分数不是员工绩效结论。"
                    : "处于改进区间；请结合各维证据安排下一次练习。";
            result = new Result(baseScore, baseScore, "scored", message, List.of(), dimensions);
        }

        applyTo(analysis, result);
        return result;
    }

    /**
     * 重试闭环时复用原报告的规则结果。没有新版状态的历史会谈保留原分数，避免在
     * 未重新分析的情况下静默改写其业务动作；页面会明确标识为历史评分。
     */
    public Result fromStoredReport(Map<String, Object> report, Integer persistedScore) {
        String status = value(report.get("quality_score_status"));
        if ("scored".equals(status) || "incomplete".equals(status)
            || "serious_risk".equals(status) || "redline".equals(status)) {
            return evaluate(report);
        }
        Integer score = persistedScore == null ? parseAndNormalize(report.get("quality_score")) : persistedScore;
        return new Result(
            score, score, "legacy",
            "这是旧版评分，缺少本次评分量表和维度证据；重新分析后将按新版规则计算。",
            List.of(), legacyDimensions(report)
        );
    }

    public static String rubricForPrompt() {
        StringBuilder prompt = new StringBuilder(
            "评分仅评估本次会谈执行质量，不是成交概率或员工绩效。四项分数只能填写 0、25、50、75、100 之一；"
                + "每一项都必须给出对应 evidence 字段，写明逐句转写中的事实。没有足够依据时该分数和 evidence 都留空，不得猜测。\n"
        );
        for (Dimension dimension : DIMENSIONS) {
            prompt.append(dimension.scoreKey()).append("（").append(dimension.label()).append("，权重 ")
                .append(dimension.weightPercent()).append("%）：").append(dimension.rubric()).append("\n")
                .append(dimension.evidenceKey()).append("：支持该分数的转写事实；没有依据则空字符串。\n");
        }
        return prompt.toString();
    }

    private void applyTo(Map<String, Object> analysis, Result result) {
        for (DimensionResult dimension : result.dimensions()) {
            analysis.put(dimension.definition().scoreKey(), dimension.score());
        }
        analysis.put("quality_score", result.score());
        analysis.put("quality_score_base", result.baseScore());
        analysis.put("quality_score_status", result.status());
        analysis.put("quality_score_message", result.message());
        analysis.put("quality_score_formula", "需求挖掘25% + 成交推进30% + 合规表现20% + 服务体验25%");
        analysis.put("quality_score_missing_dimensions", result.missingDimensions());
        analysis.put("quality_score_distill_eligible", result.canDistill());

        List<Map<String, Object>> rubric = new ArrayList<>();
        for (Dimension definition : DIMENSIONS) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", definition.scoreKey());
            item.put("label", definition.label());
            item.put("weight_percent", definition.weightPercent());
            item.put("rubric", definition.rubric());
            rubric.add(item);
        }
        analysis.put("quality_score_rubric", rubric);
    }

    private List<DimensionResult> legacyDimensions(Map<String, Object> report) {
        List<DimensionResult> result = new ArrayList<>();
        for (Dimension dimension : DIMENSIONS) {
            result.add(new DimensionResult(dimension, parseAndNormalize(report.get(dimension.scoreKey()))));
        }
        return result;
    }

    private int hardRiskLevel(Map<String, Object> analysis) {
        Object raw = analysis.get("hard_compliance_level");
        Integer parsed;
        if (raw instanceof Number number) {
            parsed = number.intValue();
        } else {
            parsed = parseInteger(raw);
        }
        return parsed == null ? 0 : Math.max(0, Math.min(4, parsed));
    }

    private Integer parseAndNormalize(Object value) {
        Integer raw;
        if (value instanceof Number number) {
            raw = (int) Math.round(number.doubleValue());
        } else {
            raw = parseInteger(value);
        }
        if (raw == null || raw < 0 || raw > 100) return null;
        int nearest = SCORE_BANDS[0];
        int distance = Math.abs(raw - nearest);
        for (int band : SCORE_BANDS) {
            int candidateDistance = Math.abs(raw - band);
            if (candidateDistance < distance) {
                nearest = band;
                distance = candidateDistance;
            }
        }
        return nearest;
    }

    private Integer parseInteger(Object value) {
        if (value == null) return null;
        Matcher matcher = PURE_SCORE.matcher(String.valueOf(value));
        if (!matcher.matches()) return null;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String value(Object input) {
        return input == null ? "" : String.valueOf(input).trim();
    }

    public record Result(
        Integer score,
        Integer baseScore,
        String status,
        String message,
        List<String> missingDimensions,
        List<DimensionResult> dimensions
    ) {
        public boolean canDistill() {
            return "scored".equals(status) && score != null && score >= DISTILL_THRESHOLD;
        }

        public boolean needsCoaching() {
            return score != null && score < LOW_SCORE_THRESHOLD;
        }

        public String displayScore() {
            return score == null ? "评估不完整" : String.valueOf(score);
        }
    }

    public record Dimension(String scoreKey, String label, int weightPercent, String evidenceKey, String rubric) { }

    public record DimensionResult(Dimension definition, Integer score) { }
}
