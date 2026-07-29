package com.storeai.meeting.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MeetingQualityScorerTest {

    private final MeetingQualityScorer scorer = new MeetingQualityScorer();

    @Test
    void normalizesToPublishedBandsAndUsesVisibleFormula() {
        Map<String, Object> report = scores(84, 62, 90, 48);

        MeetingQualityScorer.Result result = scorer.evaluate(report);

        assertEquals("scored", result.status());
        assertEquals(66, result.score()); // 75×25% + 50×30% + 100×20% + 50×25%
        assertEquals(75, report.get("need_digging_score"));
        assertEquals(50, report.get("deal_advancing_score"));
        assertEquals(100, report.get("compliance_score"));
        assertEquals(50, report.get("service_score"));
        assertFalse(result.canDistill());
        assertEquals(Boolean.FALSE, report.get("quality_score_distill_eligible"));
    }

    @Test
    void missingDimensionDoesNotInventSixtyPoints() {
        Map<String, Object> report = scores(75, 75, 75, 75);
        report.remove("service_score");

        MeetingQualityScorer.Result result = scorer.evaluate(report);

        assertEquals("incomplete", result.status());
        assertNull(result.score());
        assertEquals(List.of("服务体验"), result.missingDimensions());
        assertNull(report.get("service_score"));
        assertFalse(result.canDistill());
        assertFalse(result.needsCoaching());
    }

    @Test
    void l3RiskCapsScoreAndBlocksDistillation() {
        Map<String, Object> report = scores(100, 100, 100, 100);
        report.put("hard_compliance_level", 3);

        MeetingQualityScorer.Result result = scorer.evaluate(report);

        assertEquals("serious_risk", result.status());
        assertEquals(49, result.score());
        assertEquals(100, result.baseScore());
        assertFalse(result.canDistill());
        assertTrue(result.needsCoaching());
    }

    @Test
    void l4RiskSetsScoreToZeroAndBlocksDistillation() {
        Map<String, Object> report = scores(100, 100, 100, 100);
        report.put("hard_compliance_level", 4);

        MeetingQualityScorer.Result result = scorer.evaluate(report);

        assertEquals("redline", result.status());
        assertEquals(0, result.score());
        assertEquals(100, result.baseScore());
        assertFalse(result.canDistill());
        assertTrue(result.needsCoaching());
    }

    @Test
    void hardRiskStillWinsWhenAiDidNotReturnAllScores() {
        Map<String, Object> report = scores(100, 100, 100, 100);
        report.remove("service_score");
        report.put("hard_compliance_level", 4);

        MeetingQualityScorer.Result result = scorer.evaluate(report);

        assertEquals("redline", result.status());
        assertEquals(0, result.score());
        assertEquals(List.of("服务体验"), result.missingDimensions());
        assertFalse(result.canDistill());
        assertTrue(result.needsCoaching());
    }

    private Map<String, Object> scores(int need, int deal, int compliance, int service) {
        Map<String, Object> report = new HashMap<>();
        report.put("need_digging_score", need);
        report.put("deal_advancing_score", deal);
        report.put("compliance_score", compliance);
        report.put("service_score", service);
        return report;
    }
}
