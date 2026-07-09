package com.storeai.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 合规清洗 + 禁用词检查（原 TypeScript lib/ai/compliance.ts）
 * 回答生成后：检测禁用词 → 替换为【需规避表达】。
 * 仅对"对客话术/营销/医美承诺"场景替换；排班/通知/制度等内部回答不替换。
 */
public class ComplianceChecker {

    private static final List<String> DEFAULT_BANNED = List.of(
        "根治", "永久", "保证有效", "一次见效", "排毒",
        "包治", "无风险", "绝对安全", "一定有效",
        "彻底治愈", "百分百", "最好", "最佳", "国家级", "医院级"
    );

    /**
     * 检查并隐藏禁用词。返回清洗后的文本 + 命中的禁用词列表。
     */
    public static CheckResult check(String text, List<String> extraBanned, boolean isInternal) {
        if (text == null || text.isBlank()) {
            return new CheckResult(text, List.of());
        }
        if (isInternal) {
            return new CheckResult(text, List.of());
        }

        var allBanned = new ArrayList<>(DEFAULT_BANNED);
        if (extraBanned != null) {
            for (String w : extraBanned) {
                if (w != null && !w.isBlank() && !allBanned.contains(w)) {
                    allBanned.add(w);
                }
            }
        }

        var hits = new ArrayList<String>();
        var result = text;
        for (String word : allBanned) {
            if (result.contains(word)) {
                hits.add(word);
                result = result.replace(word, "【需规避表达】");
            }
        }

        if (!hits.isEmpty()) {
            result += "\n\n⚠️ 合规提醒：回答中检测到疑似违规/禁用表达，已自动隐藏。对客户表达时请改为客观、审慎描述。";
        }

        return new CheckResult(result, hits);
    }

    public record CheckResult(String text, List<String> hits) {}
}
