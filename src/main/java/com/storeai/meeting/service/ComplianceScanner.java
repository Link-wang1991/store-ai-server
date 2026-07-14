package com.storeai.meeting.service;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 合规风险硬规则扫描器。
 * 在 AI 分析之前/之后对转写文本做词表预扫描，命中即强制标记风险等级，
 * 与模型判断交叉验证：模型判“无风险”但词表命中 L3/L4 时，以硬规则为准。
 *
 * 等级：L1 轻度提示 / L2 中度 / L3 重度 / L4 红线（严重违规）
 * 场景：医美美容门店对客表达合规边界（《广告法》《医疗广告管理办法》）。
 */
@Component
public class ComplianceScanner {

    /** 禁用词 → 风险等级（1-4）与分类 */
    private static final List<BannedTerm> TERMS = List.of(
        // L4 红线：疗效/结果承诺、虚假保证
        t("保证有效", 4, "疗效承诺"),
        t("一次见效", 4, "疗效承诺"),
        t("立刻见效", 4, "疗效承诺"),
        t("根治", 4, "疗效承诺"),
        t("包治", 4, "疗效承诺"),
        t("包好", 4, "疗效承诺"),
        t("彻底治愈", 4, "疗效承诺"),
        t("无效退款", 4, "疗效承诺"),
        t("无效全退", 4, "疗效承诺"),
        t("100%有效", 4, "疗效承诺"),
        t("百分百有效", 4, "疗效承诺"),
        t("永不反弹", 4, "疗效承诺"),
        t("绝对安全无副作用", 4, "疗效承诺"),
        t("签约治疗", 4, "疗效承诺"),
        t(" guaranteed", 4, "疗效承诺"),

        // L3 重度：绝对化用语（违反广告法）
        t("最好", 3, "绝对化用语"),
        t("最佳", 3, "绝对化用语"),
        t("第一", 3, "绝对化用语"),
        t("顶级", 3, "绝对化用语"),
        t("最权威", 3, "绝对化用语"),
        t("最专业", 3, "绝对化用语"),
        t("唯一", 3, "绝对化用语"),
        t("国家级", 3, "绝对化用语"),
        t("王牌", 3, "绝对化用语"),
        t("史上最强", 3, "绝对化用语"),

        // L2 中度：夸大诱导、制造焦虑
        t("明星都在用", 2, "夸大诱导"),
        t("专家推荐", 2, "夸大诱导"),
        t("最后一天", 2, "虚假促销"),
        t("限时免费", 2, "虚假促销"),
        t("错过不再有", 2, "虚假促销"),
        t("不买就亏", 2, "制造焦虑"),
        t("再不买就没了", 2, "制造焦虑"),
        t("低价引流", 2, "虚假促销"),

        // L1 轻度：不严谨的口头承诺
        t("应该没问题", 1, "不严谨承诺"),
        t("基本没问题", 1, "不严谨承诺"),
        t("大概能", 1, "不严谨承诺"),
        t("大概率有效", 1, "不严谨承诺"),
        t("肯定能", 1, "不严谨承诺"),
        t("放心吧", 1, "不严谨承诺")
    );

    private static BannedTerm t(String word, int level, String category) {
        return new BannedTerm(word, level, category);
    }

    public static String levelName(int level) {
        return switch (level) {
            case 4 -> "红线";
            case 3 -> "重度";
            case 2 -> "中度";
            default -> "轻度";
        };
    }

    /**
     * 扫描文本，返回命中的合规风险（按等级降序）。
     * 同一词多次出现只记一次，避免刷屏。
     */
    public List<ComplianceHit> scan(String text) {
        if (text == null || text.isBlank()) return List.of();
        Set<String> seen = new HashSet<>();
        List<ComplianceHit> hits = new ArrayList<>();
        for (BannedTerm term : TERMS) {
            int idx = text.indexOf(term.getWord());
            if (idx >= 0 && seen.add(term.getWord())) {
                int s = Math.max(0, idx - 15);
                int e = Math.min(text.length(), idx + term.getWord().length() + 15);
                String ctx = text.substring(s, e).replace("\n", " ");
                hits.add(new ComplianceHit(term.getWord(), term.getLevel(), levelName(term.getLevel()), term.getCategory(), ctx));
            }
        }
        hits.sort((a, b) -> Integer.compare(b.getLevel(), a.getLevel()));
        return hits;
    }

    /** 是否存在达到指定等级及以上的命中 */
    public boolean hasAtLeast(String text, int minLevel) {
        return TERMS.stream().anyMatch(t -> t.getLevel() >= minLevel && text != null && text.contains(t.getWord()));
    }

    @Getter
    public static class ComplianceHit {
        private final String word;
        private final int level;
        private final String levelName;
        private final String category;
        private final String context;

        public ComplianceHit(String word, int level, String levelName, String category, String context) {
            this.word = word;
            this.level = level;
            this.levelName = levelName;
            this.category = category;
            this.context = context;
        }
    }

    /** 不可变词表项 */
    @Getter
    public static class BannedTerm {
        private final String word;
        private final int level;
        private final String category;

        public BannedTerm(String word, int level, String category) {
            this.word = word;
            this.level = level;
            this.category = category;
        }
    }
}
