package com.storeai.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.knowledge.entity.KnowledgeChunk;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class KnowledgeRetrieveService {

    private final EmbeddingService embeddingService;
    private final ObjectMapper mapper = new ObjectMapper();

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
     * 混合检索：有可用向量时优先语义相似度，始终保留关键词分数作为降级与可解释依据。
     * 旧数据或向量服务不可用时自动退回原有 bigram，不影响门店继续使用。
     */
    public List<RetrievedChunk> retrieve(List<KnowledgeChunk> candidates,
                                          String query, int topN) {
        List<String> queryTokens = new ArrayList<>(new LinkedHashSet<>(tokenize(query)));
        float[] queryEmbedding = embeddingService.isConfigured() ? embeddingService.embed(query) : null;
        if (queryTokens.isEmpty() && queryEmbedding == null) return Collections.emptyList();

        return candidates.stream()
            .map(c -> {
                double lexical = scoreChunk(queryTokens, c.getContent());
                float[] documentEmbedding = parseEmbedding(c.getEmbedding());
                double semantic = cosine(queryEmbedding, documentEmbedding);
                double score = combineScore(lexical, semantic, queryEmbedding != null && documentEmbedding != null);
                return new RetrievedChunk(c.getId(), c.getDocumentId(), null, c.getContent(), score);
            })
            .filter(c -> c.score > 0)
            .sorted((a, b) -> Double.compare(b.score, a.score))
            .limit(Math.max(1, Math.min(topN, 5)))
            .toList();
    }

    /**
     * 不依赖外部向量服务的确定性检索。会谈后台任务使用它，避免网络波动把
     * 录音分析卡在向量请求上；互动式 AI 教练仍继续走上面的混合语义检索。
     */
    public List<RetrievedChunk> retrieveKeywordOnly(List<KnowledgeChunk> candidates,
                                                     String query, int topN) {
        List<String> queryTokens = new ArrayList<>(new LinkedHashSet<>(tokenize(query)));
        if (queryTokens.isEmpty()) return Collections.emptyList();
        return candidates.stream()
            .map(c -> new RetrievedChunk(c.getId(), c.getDocumentId(), null, c.getContent(),
                scoreChunk(queryTokens, c.getContent())))
            .filter(c -> c.score > 0)
            .sorted((a, b) -> Double.compare(b.score, a.score))
            .limit(Math.max(1, Math.min(topN, 5)))
            .toList();
    }

    private double combineScore(double lexical, double semantic, boolean hasSemantic) {
        double lexicalNormalized = lexical <= 0 ? 0 : Math.min(1D, lexical / 4D);
        if (!hasSemantic) return lexical;
        // 余弦低于 0.12 且没有关键词命中时，视为无关，不能因为 [-1,1] 映射被误召回。
        if (semantic < 0.12D && lexicalNormalized == 0D) return 0D;
        // cosine 的 [-1, 1] 先映射到 [0, 1]；弱关键词仍可辅助排序。
        double semanticNormalized = Math.max(0D, (semantic + 1D) / 2D);
        return semanticNormalized * 0.72D + lexicalNormalized * 0.28D;
    }

    private float[] parseEmbedding(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return mapper.readValue(raw, float[].class); }
        catch (Exception ignored) { return null; }
    }

    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) return -1D;
        double dot = 0D, leftNorm = 0D, rightNorm = 0D;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0D || rightNorm == 0D) return -1D;
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    /** documentTitle 由 KnowledgeService 在完成权限过滤后补齐。 */
    public record RetrievedChunk(String id, String documentId, String documentTitle,
                                  String content, double score) {}
}
