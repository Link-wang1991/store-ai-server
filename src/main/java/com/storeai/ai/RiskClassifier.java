package com.storeai.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 问题分类 + 风险等级判定（原 TypeScript lib/ai/risk-classifier.ts）
 * 规则化关键词判断：稳定、可解释、零成本。
 */
public class RiskClassifier {

    private static final List<String> ALWAYS_L4 = List.of(
        "投诉", "维权", "曝光", "起诉", "律师", "法律", "赔偿", "纠纷",
        "病历", "处方", "晕厥", "休克", "呼吸困难", "剧痛", "化脓", "溃烂", "烫伤", "灼伤"
    );

    private static final List<String> SYMPTOM_WORDS = List.of(
        "过敏", "红肿", "泛红", "起泡", "水泡", "破皮", "发炎", "感染",
        "色沉", "色素沉着", "留疤", "疤痕", "术后", "不适", "心慌", "确诊", "诊断"
    );

    private static final List<String> OCCURRED_CTX = List.of(
        "已经", "出现", "做完", "刚做", "术后第", "现在", "昨天做", "今天做",
        "红了", "肿了", "破了", "起泡了", "发炎了", "过敏了", "感染了", "留疤了", "反应"
    );

    private static final List<String> L3_KEYWORDS = List.of(
        "退款", "退钱", "退卡", "便宜", "优惠", "打折", "降价", "再送",
        "能不能少", "抹零", "活动叠加", "叠加", "一起用", "会员价",
        "排班", "调班", "请假", "承诺", "保证", "包", "免费送", "额外赠送"
    );

    private static final Map<String, List<String>> CATEGORY_RULES = Map.ofEntries(
        Map.entry("医美健康异常", List.of("过敏", "红肿", "泛红", "灼伤", "感染", "术后", "不适", "皮肤异常", "发炎")),
        Map.entry("客诉处理", List.of("投诉", "不满意", "差评", "维权", "退款", "纠纷")),
        Map.entry("活动政策", List.of("活动", "优惠", "团购", "套餐", "促销", "叠加", "会员")),
        Map.entry("销售话术", List.of("怎么回", "话术", "嫌贵", "考虑一下", "成交", "逼单", "不回微信", "对比别家", "异议")),
        Map.entry("客户跟进", List.of("跟进", "回访", "唤醒", "老客", "复购", "邀约")),
        Map.entry("护理流程", List.of("流程", "sop", "服务前", "服务后", "操作", "手法", "护理", "注意事项")),
        Map.entry("项目介绍", List.of("项目", "介绍", "功效", "适合", "原理", "几次", "疗程")),
        Map.entry("运营文案", List.of("朋友圈", "小红书", "文案", "标题", "海报", "种草", "笔记")),
        Map.entry("合规表达", List.of("违规", "禁用词", "能不能说", "合规", "敏感词")),
        Map.entry("员工管理", List.of("排班", "考核", "培训", "晨会", "提成", "绩效")),
        Map.entry("经营数据", List.of("业绩", "营业额", "数据", "报表", "客流", "转化率"))
    );

    public static Classification classify(String question) {
        if (question == null) question = "";
        String q = question.toLowerCase();

        var always = filter(ALWAYS_L4, q);
        var symptom = filter(SYMPTOM_WORDS, q);
        boolean occurred = OCCURRED_CTX.stream().anyMatch(k -> q.contains(k));
        var l3 = filter(L3_KEYWORDS, q);

        String baseRisk = "L1";
        List<String> matchedKeywords = new ArrayList<>();

        if (!always.isEmpty()) {
            baseRisk = "L4";
            matchedKeywords = always;
        } else if (!symptom.isEmpty() && occurred) {
            baseRisk = "L4";
            matchedKeywords = symptom;
        } else if (!l3.isEmpty()) {
            baseRisk = "L3";
            matchedKeywords = l3;
        }

        String category = "其他问题";
        for (var entry : CATEGORY_RULES.entrySet()) {
            for (String kw : entry.getValue()) {
                if (q.contains(kw.toLowerCase())) {
                    category = entry.getKey();
                    break;
                }
            }
            if (!"其他问题".equals(category)) break;
        }

        return new Classification(category, baseRisk, matchedKeywords);
    }

    public static List<String> findBannedWords(String text, List<String> bannedWords) {
        if (text == null || bannedWords == null) return List.of();
        return bannedWords.stream()
            .filter(w -> w != null && !w.isEmpty() && text.contains(w))
            .toList();
    }

    private static List<String> filter(List<String> keywords, String lowerQ) {
        return keywords.stream().filter(k -> lowerQ.contains(k.toLowerCase())).toList();
    }

    public record Classification(String category, String baseRisk, List<String> matchedRiskKeywords) {}
}
