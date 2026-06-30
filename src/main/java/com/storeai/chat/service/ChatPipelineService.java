package com.storeai.chat.service;

import com.storeai.chat.entity.ChatMessage;
import com.storeai.chat.entity.ChatSession;
import com.storeai.chat.repository.ChatMessageRepository;
import com.storeai.chat.repository.ChatSessionRepository;
import com.storeai.common.util.CurrentUser;
import com.storeai.knowledge.service.KnowledgeRetrieveService;
import com.storeai.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * AI 问答核心服务（骨架）。
 * 当前仅实现 bigram 检索 + 结构化模板回答，后续接入真实 LLM。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPipelineService {

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final KnowledgeService knowledgeService;
    private final CurrentUser cur;

    private static final List<String> HIGH_RISK_KEYWORDS = List.of(
        "过敏", "红肿", "溃烂", "感染", "医疗", "手术", "处方", "激素",
        "投诉", "举报", "工商", "退款", "假货", "骗子"
    );

    /**
     * 执行问答全流程：
     * 1. 创建/获取会话
     * 2. 风险评估
     * 3. 知识库检索
     * 4. 生成回答
     */
    public QuestionResult answer(String question, String sessionId, String customerId) {
        // 1. 会话管理
        if (sessionId == null) {
            ChatSession s = new ChatSession();
            s.setStoreId(cur.storeId());
            s.setEmployeeId(cur.employeeId());
            s.setRole(cur.role());
            s.setTitle(question.length() > 20 ? question.substring(0, 20) : question);
            s.setCustomerId(customerId);
            s.setCreatedAt(OffsetDateTime.now());
            s.setUpdatedAt(OffsetDateTime.now());
            sessionRepo.insert(s);
            sessionId = s.getId();
        }

        // 2. 风险评估
        String riskLevel = assessRisk(question);

        // 3. 知识库检索
        List<KnowledgeRetrieveService.RetrievedChunk> chunks =
            knowledgeService.search(question, 5);

        // 4. 生成回答
        String answerType;
        String answer;

        if ("L4".equals(riskLevel)) {
            answerType = "risk";
            answer = buildRiskAnswer();
        } else if (chunks.isEmpty()) {
            answerType = "general";
            answer = buildGeneralAnswer(question);
        } else {
            answerType = "knowledge";
            answer = buildKnowledgeAnswer(chunks, question);
        }

        // 5. 保存消息
        ChatMessage msg = new ChatMessage();
        msg.setStoreId(cur.storeId());
        msg.setSessionId(sessionId);
        msg.setEmployeeId(cur.employeeId());
        msg.setRole("user");
        msg.setContent(question);
        msg.setAnswerType(answerType);
        msg.setRiskLevel(riskLevel);
        msg.setCustomerId(customerId);
        msg.setCreatedAt(OffsetDateTime.now());
        messageRepo.insert(msg);

        return new QuestionResult(sessionId, answer, answerType, riskLevel,
            msg.getId(), chunks.stream().map(c ->
                new RetrievedInfo(c.id(), c.content().substring(0,
                    Math.min(100, c.content().length())))).toList());
    }

    // --- 风险评估 ---
    private String assessRisk(String question) {
        String lower = question.toLowerCase();
        for (String kw : HIGH_RISK_KEYWORDS) {
            if (lower.contains(kw)) return "L4";
        }
        if (Stream.of("价格", "多少钱", "退款", "打折", "优惠", "活动叠加")
                .anyMatch(lower::contains)) {
            return "L2";
        }
        return "L1";
    }

    // --- 回答模板 ---
    private String buildRiskAnswer() {
        return """
            ⚠️ 这是高风险问题，我不能直接判断或给出处理结论。

            **结论**：不做医疗判断、不承诺效果。
            **原因**：涉及皮肤/健康异常或法律风险，超出员工可处理范围。
            **建议话术**：「您的情况我非常重视，我马上请我们负责人来跟您对接处理。」
            **下一步行动**：① 安抚情绪 ② 立即升级给店长/老板 ③ 如有身体不适引导就医。
            **是否需要升级**：是 —— 立即升级。
            **是否建议补充知识库**：是 —— 建议沉淀此类异常的标准处理流程。
            """;
    }

    private String buildGeneralAnswer(String question) {
        return String.format("""
            （当前知识库没有明确标准，以下是通用建议）

            **结论**：可以按通用思路处理，但建议尽快补充门店标准口径。
            **原因**：知识库暂无「%s」的明确资料。
            **建议话术**：「这个我先帮您了解清楚，给您一个最准确的答复。」
            **下一步行动**：了解客户真实顾虑，给出明确的下一步。
            **是否需要升级**：否（如涉及价格或风险则需升级）。
            **是否建议补充知识库**：是 —— 建议老板把该场景标准话术补进知识库。
            """, question);
    }

    private String buildKnowledgeAnswer(List<KnowledgeRetrieveService.RetrievedChunk> chunks,
                                         String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("基于门店知识库资料回答：\n\n");
        for (int i = 0; i < Math.min(chunks.size(), 2); i++) {
            String snippet = chunks.get(i).content().replaceAll("\\s+", " ")
                .substring(0, Math.min(140, chunks.get(i).content().length()));
            sb.append("资料").append(i + 1).append("：").append(snippet).append("…\n");
        }
        sb.append("\n**建议话术**：按上面门店资料的口径表达，结合客户实际灵活调整。\n");
        sb.append("**是否需要升级**：否。\n");
        return sb.toString();
    }

    // --- 结果 DTO ---
    public record QuestionResult(
        String sessionId,
        String answer,
        String answerType,
        String riskLevel,
        String messageId,
        List<RetrievedInfo> retrieved
    ) {}

    public record RetrievedInfo(String chunkId, String snippet) {}
}
