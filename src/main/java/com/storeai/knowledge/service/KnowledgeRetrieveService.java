package com.storeai.knowledge.service;

import com.storeai.knowledge.entity.KnowledgeChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 知识库检索 — 中文 bigram 关键词匹配。
 * 对应现有 TS 版 lib/knowledge/retrieve.ts 的降级逻辑。
 * 知识库量小（几十篇）时够用，后续需要语义搜索再加向量检索。
 */
@Slf4j
@Service
public class KnowledgeRetrieveService {

    // 停用词
    private static final Set<String> STOPWORDS = Set.of(
        "怎么", "什么", "如何", "可以", "我们", "你们", "他们", "这个", "那个",
        "一下", "帮我", "请问", "需要", "应该", "就是", "的话"
    );

    private static final Pattern ZH_PATTERN = Pattern.compile("[一-龥]+");
    private static final Pattern EN_PATTERN = Pattern.compile("[a-z0-9]{2,}");

    /** bigram 分词 */
    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        String cleaned = text.toLowerCase();
        // 英文/数字连续串
        var enMatcher = EN_PATTERN.matcher(cleaned);
        while (enMatcher.find()) {
            tokens.add(enMatcher.group());
        }
        // 中文 bigram
        var zhMatcher = ZH_PATTERN.matcher(cleaned);
        while (zhMatcher.find()) {
            String seg = zhMatcher.group();
            if (seg.length() == 1) {
                tokens.add(seg);
            } else {
                for (int i = 0; i < seg.length() - 1; i++) {
                    tokens.add(seg.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }

    /** 计算片段得分 */
    public double scoreChunk(List<String> queryTokens, String content) {
        if (queryTokens.isEmpty()) return 0;
        String lower = content.toLowerCase();
        double score = 0;
        Set<String> seen = new HashSet<>();
        for (String t : queryTokens) {
            if (STOPWORDS.contains(t)) continue;
            if (lower.contains(t)) {
                score += seen.contains(t) ? 0.2 : 1;
                seen.add(t);
            }
        }
        return score;
    }

    /**
     * 检索 topN 个相关片段（仅 bigram，无向量）
     */
    public List<RetrievedChunk> retrieve(List<KnowledgeChunk> candidates,
                                          String query, int topN) {
        List<String> queryTokens = new ArrayList<>(new LinkedHashSet<>(tokenize(query)));
        if (queryTokens.isEmpty()) return Collections.emptyList();

        return candidates.stream()
            .map(c -> new RetrievedChunk(
                c.getId(), c.getDocumentId(),
                c.getContent(), scoreChunk(queryTokens, c.getContent())))
            .filter(c -> c.score > 0)
            .sorted((a, b) -> Double.compare(b.score, a.score))
            .limit(Math.min(topN, 5))
            .toList();
    }

    public record RetrievedChunk(String id, String documentId,
                                  String content, double score) {}
}
