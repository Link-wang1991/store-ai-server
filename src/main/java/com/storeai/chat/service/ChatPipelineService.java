package com.storeai.chat.service;

import com.storeai.ai.AiAdapter;
import com.storeai.ai.ComplianceChecker;
import com.storeai.ai.PromptBuilder;
import com.storeai.ai.RiskClassifier;
import com.storeai.chat.entity.ChatMessage;
import com.storeai.chat.entity.ChatSession;
import com.storeai.chat.repository.ChatMessageRepository;
import com.storeai.chat.repository.ChatSessionRepository;
import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import com.storeai.customer.service.CustomerService;
import com.storeai.customer.service.CustomerTimelineService;
import com.storeai.knowledge.service.KnowledgeRetrieveService;
import com.storeai.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AI 问答核心管线（重构版）
 * 完整流程：分类+风险 → 检索 → 构建 Prompt → LLM 调用 → 合规检查 → 落库
 * 对应前端 lib/ai/pipeline.ts
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPipelineService {

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final KnowledgeService knowledgeService;
    private final CurrentUser cur;
    private final AiAdapter aiAdapter;
    private final CustomerService customerService;
    private final CustomerTimelineService customerTimelineService;
    private final JdbcTemplate jdbc;

    public AnswerResult answer(String question, String sessionId, String customerId) {
        String normalizedQuestion = question == null ? "" : question.trim();
        if (normalizedQuestion.isBlank()) {
            throw BizException.badRequest("问题不能为空");
        }

        String requestedCustomerId = normalizeCustomerId(customerId);

        // 1. 会话与客户上下文管理。客户只在用户明确选择或入口显式传入时关联，
        //    后续消息始终以会话已经绑定的 customer_id 为准，避免串到另一位客户。
        if (sessionId == null) {
            if (requestedCustomerId != null) {
                customerService.getById(requestedCustomerId);
            }
            var s = new ChatSession();
            s.setStoreId(cur.storeId());
            s.setEmployeeId(cur.employeeId());
            s.setRole(cur.role());
            s.setTitle(normalizedQuestion.length() > 20 ? normalizedQuestion.substring(0, 20) : normalizedQuestion);
            s.setCustomerId(requestedCustomerId);
            s.setCreatedAt(OffsetDateTime.now());
            s.setUpdatedAt(OffsetDateTime.now());
            sessionRepo.insert(s);
            sessionId = s.getId();
            customerId = requestedCustomerId;
        } else {
            var session = sessionRepo.selectById(sessionId);
            if (session == null) {
                throw BizException.notFound("会话");
            }
            if (!cur.storeId().equals(session.getStoreId()) || !cur.employeeId().equals(session.getEmployeeId())) {
                throw BizException.forbidden("无权继续此会话");
            }

            String boundCustomerId = normalizeCustomerId(session.getCustomerId());
            if (boundCustomerId != null) {
                if (requestedCustomerId != null && !boundCustomerId.equals(requestedCustomerId)) {
                    throw BizException.badRequest("该会话已关联其他客户，请新建对话后再切换客户");
                }
                customerService.getById(boundCustomerId);
                customerId = boundCustomerId;
            } else if (requestedCustomerId != null) {
                customerService.getById(requestedCustomerId);
                session.setCustomerId(requestedCustomerId);
                session.setUpdatedAt(OffsetDateTime.now());
                sessionRepo.updateById(session);
                customerId = requestedCustomerId;
            } else {
                customerId = null;
            }
        }

        // 2. 问题分类 + 风险初判
        var classification = RiskClassifier.classify(normalizedQuestion);
        var category = classification.category();
        var baseRisk = classification.baseRisk();

        // 3. 标准答案优先匹配。通用模式可直接返回；客户模式把标准口径与客户画像
        //    一同提供给模型，使话术仍能结合该客户的阶段与顾虑。
        String standardAnswer = findStandardAnswer(normalizedQuestion);
        if (standardAnswer != null && customerId == null) {
            return saveAnswer(normalizedQuestion, sessionId, customerId, category, "L1",
                "standard_answer", standardAnswer, List.of(), List.of());
        }

        // 4. 知识库检索（Bigram）
        var chunks = knowledgeService.search(normalizedQuestion, 5);
        var chunkTexts = new ArrayList<String>();
        for (var c : chunks) {
            chunkTexts.add("【" + c.documentId() + "】" + c.content());
        }
        if (standardAnswer != null) {
            chunkTexts.add(0, "【门店标准口径】" + standardAnswer);
        }
        boolean hasContext = !chunkTexts.isEmpty();

        // 5. 定级+回答类型
        String answerType;
        String riskLevel;
        if ("L4".equals(baseRisk)) {
            answerType = "risk";
            riskLevel = "L4";
        } else if ("L3".equals(baseRisk)) {
            answerType = "need_confirm";
            riskLevel = "L3";
        } else if (hasContext) {
            answerType = "knowledge";
            riskLevel = "L1";
        } else {
            answerType = "general";
            riskLevel = "L2";
        }

        // 6. 生成回答
        String answer;
        if ("risk".equals(answerType)) {
            answer = buildRiskAnswer();
        } else if (aiAdapter.isConfigured()) {
            var system = PromptBuilder.buildSystem(new PromptBuilder.SystemPromptOpts(
                "本店",  // storeName - CurrentUser 暂无此字段
                cur.role(),
                cur.role(),  // roleLabel - 暂无自定义角色名
                List.of(),
                customerId != null
            ));
            var user = PromptBuilder.buildUser(new PromptBuilder.UserPromptOpts(
                normalizedQuestion, chunkTexts, List.of(), buildCustomerProfile(customerId), ""
            ));
            String aiAnswer = aiAdapter.call(system, user, null);
            if (aiAnswer != null) {
                answer = aiAnswer;
            } else {
                answer = standardAnswer != null
                    ? buildCustomerStandardAnswer(standardAnswer, customerId)
                    : hasContext
                    ? buildKnowledgeAnswer(chunks, normalizedQuestion)
                    : buildGeneralAnswer(normalizedQuestion);
            }
        } else {
            answer = standardAnswer != null
                ? buildCustomerStandardAnswer(standardAnswer, customerId)
                : hasContext
                ? buildKnowledgeAnswer(chunks, normalizedQuestion)
                : buildGeneralAnswer(normalizedQuestion);
        }

        // 7. 合规检查（禁用词）
        boolean isInternal = normalizedQuestion.matches(".*(排班|上班|几点|班次|休息|通知|培训|制度).*");
        var checkResult = ComplianceChecker.check(answer, List.of(), isInternal);
        answer = checkResult.text();
        var bannedHit = checkResult.hits();

        // L3 提醒
        if ("need_confirm".equals(answerType) && !isInternal) {
            answer += "\n\n⚠️ 提醒：若涉及具体价格/折扣/退款/活动政策，最终以店长/老板确认为准。";
        }

        return saveAnswer(normalizedQuestion, sessionId, customerId, category, riskLevel, answerType,
            answer, chunks, bannedHit);
    }

    private String normalizeCustomerId(String customerId) {
        if (customerId == null || customerId.isBlank()) return null;
        return customerId.trim();
    }

    private String buildCustomerProfile(String customerId) {
        if (customerId == null) return "";

        try {
            var customer = jdbc.queryForMap(
                "SELECT name, stage, concerns, portrait, last_active_at, next_follow_at " +
                    "FROM customers WHERE id = ? AND store_id = ?",
                customerId, cur.storeId());
            var profile = new StringBuilder();
            profile.append("客户 ID：").append(customerId).append("\n")
                .append("姓名：").append(customer.getOrDefault("name", "")).append("\n")
                .append("当前阶段：").append(customer.getOrDefault("stage", "未记录")).append("\n")
                .append("已知顾虑：").append(customer.getOrDefault("concerns", "未记录")).append("\n")
                .append("最近活跃：").append(customer.getOrDefault("last_active_at", "未记录")).append("\n")
                .append("下次跟进：").append(customer.getOrDefault("next_follow_at", "未记录")).append("\n")
                .append("客户画像：").append(customer.getOrDefault("portrait", "未记录")).append("\n");

            var memories = jdbc.queryForList(
                "SELECT `key`, value, confidence FROM memory_items " +
                    "WHERE store_id = ? AND customer_id = ? ORDER BY created_at DESC LIMIT 6",
                cur.storeId(), customerId);
            if (!memories.isEmpty()) {
                profile.append("近期客户记忆：\n");
                for (var memory : memories) {
                    profile.append("- ").append(memory.get("key")).append("：")
                        .append(memory.get("value")).append("（可信度：")
                        .append(memory.get("confidence")).append("）\n");
                }
            }
            return profile.toString();
        } catch (Exception e) {
            log.warn("读取客户上下文失败，继续按通用模式回答: customerId={}", customerId);
            return "";
        }
    }

    private String buildCustomerStandardAnswer(String standardAnswer, String customerId) {
        if (customerId == null) return standardAnswer;
        try {
            var customer = jdbc.queryForMap(
                "SELECT name, stage, concerns FROM customers WHERE id = ? AND store_id = ?",
                customerId, cur.storeId());
            String name = String.valueOf(customer.getOrDefault("name", "该客户"));
            String stage = String.valueOf(customer.getOrDefault("stage", "当前阶段未记录"));
            String concerns = String.valueOf(customer.getOrDefault("concerns", "暂无明确顾虑"));
            return "（已针对客户「" + name + "」生成：当前阶段「" + stage + "」，重点关注「" + concerns + "」）\n\n"
                + standardAnswer
                + "\n\n**跟进提醒**：请结合该客户当前阶段和顾虑确认下一步，再发送具体话术。";
        } catch (Exception e) {
            log.warn("读取客户标准答案上下文失败: customerId={}", customerId);
            return standardAnswer;
        }
    }

    private String findStandardAnswer(String question) {
        try {
            return jdbc.queryForObject(
                """
                    SELECT answer FROM standard_answers
                    WHERE store_id = ?
                      AND (? LIKE CONCAT('%', question, '%') OR question LIKE CONCAT('%', ?, '%'))
                    ORDER BY CHAR_LENGTH(question) DESC
                    LIMIT 1
                    """,
                String.class, cur.storeId(), question, question);
        } catch (Exception e) {
            return null;
        }
    }

    private AnswerResult saveAnswer(String question, String sessionId, String customerId,
                                    String category, String riskLevel, String answerType,
                                    String answer,
                                    List<KnowledgeRetrieveService.RetrievedChunk> chunks,
                                    List<String> bannedHit) {
        var msg = new ChatMessage();
        msg.setStoreId(cur.storeId());
        msg.setSessionId(sessionId);
        msg.setEmployeeId(cur.employeeId());
        msg.setRole("user");
        msg.setContent(question);
        msg.setAiResponse(answer);
        msg.setQuestionCategory(category);
        msg.setAnswerType(answerType);
        msg.setRiskLevel(riskLevel);
        msg.setCustomerId(customerId);
        msg.setCreatedAt(OffsetDateTime.now());
        messageRepo.insert(msg);

        var session = sessionRepo.selectById(sessionId);
        if (session != null) {
            session.setUpdatedAt(OffsetDateTime.now());
            sessionRepo.updateById(session);
        }

        if (customerId != null) {
            customerTimelineService.addInteraction(customerId, "chat_message",
                "员工咨询 AI：" + question);
        }

        List<RetrievedInfo> retrieved = chunks.stream()
            .map(c -> new RetrievedInfo(c.id(), c.content().substring(0, Math.min(100, c.content().length()))))
            .toList();

        return new AnswerResult(
            sessionId, msg.getId(), answer, category,
            riskLevel, answerType, retrieved, bannedHit
        );
    }

    // --- 回答模板 ---

    private String buildRiskAnswer() {
        return """
            ⚠️ 这是高风险问题，已自动升级。

            **结论**：不做医疗判断、不承诺效果。
            **原因**：涉及皮肤/健康异常或法律风险，超出员工可处理范围。
            **建议话术**：「您的情况我非常重视，我马上请我们负责人来跟您对接处理。」
            **下一步动作**：① 安抚情绪 ② 立即升级给店长/老板 ③ 如有身体不适引导就医。
            **是否需要升级**：是 —— 立即升级。
            """;
    }

    private String buildGeneralAnswer(String question) {
        return String.format("""
            （当前知识库没有明确标准，以下是通用建议）

            **结论**：可以按通用思路处理，但建议尽快补充门店标准口径。
            **原因**：知识库暂无「%s」的明确资料。
            **建议话术**：「这个我先帮您了解清楚，给您一个最准确的答复。」
            **下一步动作**：了解客户真实顾虑，给出明确的下一步。
            **是否需要升级**：否（如涉及价格或风险则需升级）。
            """, question);
    }

    private String buildKnowledgeAnswer(List<KnowledgeRetrieveService.RetrievedChunk> chunks,
                                         String question) {
        var sb = new StringBuilder();
        sb.append("基于门店知识库资料回答：\n\n");
        for (int i = 0; i < Math.min(chunks.size(), 2); i++) {
            var c = chunks.get(i).content().replaceAll("\\s+", " ");
            var snippet = c.substring(0, Math.min(140, c.length()));
            sb.append("资料").append(i + 1).append("：").append(snippet).append("…\n");
        }
        sb.append("\n**建议话术**：按上面门店资料的口径表达，结合客户实际灵活调整。\n");
        sb.append("**是否需要升级**：否。\n");
        return sb.toString();
    }

    // --- DTOs ---

    public record AnswerResult(
        String sessionId,
        String messageId,
        String answer,
        String category,
        String riskLevel,
        String answerType,
        List<RetrievedInfo> retrieved,
        List<String> bannedHit
    ) {}

    public record RetrievedInfo(String chunkId, String snippet) {}
}
