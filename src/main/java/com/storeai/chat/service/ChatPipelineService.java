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
import com.storeai.knowledge.service.SystemPlaybookService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final SystemPlaybookService systemPlaybookService;
    private final CurrentUser cur;
    private final AiAdapter aiAdapter;
    private final CustomerService customerService;
    private final CustomerTimelineService customerTimelineService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final List<String> FEEDBACK_TYPES = List.of("已接受", "已预约", "仍有顾虑", "信息有误", "需要升级");

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

        // 3. 门店标准答案优先匹配。它不再短路整条回答链路：AI 教练仍要把门店口径、
        //    客户上下文和系统销售方法论放在一起判断，且门店口径优先级最高。
        String standardAnswer = findStandardAnswer(normalizedQuestion);

        // 4. 双来源检索：门店知识库是本店事实与口径；系统方法论是通用销售专业能力。
        //    两者分别保存和展示，避免把后者误呈现为本店制度。
        var chunks = knowledgeService.search(normalizedQuestion, 5);
        var playbooks = systemPlaybookService.search(normalizedQuestion, cur.role(), 3);
        var chunkTexts = new ArrayList<String>();
        for (var c : chunks) {
            chunkTexts.add("【" + c.documentTitle() + "】" + c.content());
        }
        if (standardAnswer != null) {
            chunkTexts.add(0, "【门店标准口径】" + standardAnswer);
        }
        var playbookTexts = playbooks.stream().map(item -> "《" + item.title() + "》"
            + "（模块：" + item.category() + "；来源：" + item.source() + "）\n" + item.content()).toList();
        boolean hasContext = !chunkTexts.isEmpty() || !playbooks.isEmpty();

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
                false // 对话页只输出待员工确认的下一步建议，不在无确认时自动写入业务数据。
            ));
            String context = buildCustomerProfile(customerId) + buildConversationHistory(sessionId);
            var user = PromptBuilder.buildUser(new PromptBuilder.UserPromptOpts(
                normalizedQuestion, chunkTexts, playbookTexts, context, ""
            ));
            String aiAnswer = aiAdapter.call(system, user, null);
            if (aiAnswer != null) {
                answer = aiAnswer;
            } else {
                answer = buildFallbackAnswer(standardAnswer, customerId, chunks, playbooks, normalizedQuestion);
            }
        } else {
            answer = buildFallbackAnswer(standardAnswer, customerId, chunks, playbooks, normalizedQuestion);
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
            answer, chunks, playbooks, bannedHit);
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
                    "WHERE store_id = ? AND customer_id = ? AND (status IS NULL OR status = 'confirmed') ORDER BY created_at DESC LIMIT 12",
                cur.storeId(), customerId);
            if (!memories.isEmpty()) {
                profile.append("近期客户记忆：\n");
                for (var memory : memories) {
                    profile.append("- ").append(memory.get("key")).append("：")
                        .append(memory.get("value")).append("（可信度：")
                        .append(memory.get("confidence")).append("）\n");
                }
            }
            var meetings = jdbc.queryForList("""
                SELECT ma.summary, ma.explicit_needs, ma.decision_barriers, ma.followup_goal, m.ended_at
                FROM meeting_analysis ma
                JOIN meetings m ON m.id = ma.meeting_id
                WHERE ma.store_id = ? AND m.customer_id = ?
                ORDER BY ma.created_at DESC
                LIMIT 5
                """, cur.storeId(), customerId);
            if (!meetings.isEmpty()) {
                profile.append("最近会谈复盘：\n");
                for (var meeting : meetings) {
                    profile.append("- 摘要：").append(meeting.getOrDefault("summary", "未记录"))
                        .append("；需求：").append(meeting.getOrDefault("explicit_needs", "未记录"))
                        .append("；阻碍：").append(meeting.getOrDefault("decision_barriers", "未记录"))
                        .append("；下一步：").append(meeting.getOrDefault("followup_goal", "未记录"))
                        .append("\n");
                }
            }
            var openTasks = jdbc.queryForList("""
                SELECT title, content, type, status, due_at
                FROM tasks
                WHERE store_id = ? AND customer_id = ?
                  AND status IN ('todo', 'doing')
                  AND (? = 1 OR assigned_to = ?)
                ORDER BY due_at ASC, created_at DESC
                LIMIT 8
                """, cur.storeId(), customerId, cur.isAdmin() ? 1 : 0, cur.employeeId());
            if (!openTasks.isEmpty()) {
                profile.append("待执行事项（避免重复承诺）：\n");
                for (var task : openTasks) {
                    profile.append("- ").append(shortText(task.get("title"), 120))
                        .append("；状态：").append(task.getOrDefault("status", "todo"))
                        .append("；截止：").append(task.getOrDefault("due_at", "未设"))
                        .append("；内容：").append(shortText(task.get("content"), 180)).append("\n");
                }
            }
            var interactions = jdbc.queryForList("""
                SELECT type, content, created_at FROM interactions
                WHERE store_id = ? AND customer_id = ?
                ORDER BY created_at DESC LIMIT 8
                """, cur.storeId(), customerId);
            if (!interactions.isEmpty()) {
                profile.append("最近客户互动：\n");
                for (var interaction : interactions) {
                    profile.append("- ").append(interaction.getOrDefault("created_at", ""))
                        .append(" · ").append(interaction.getOrDefault("type", "互动"))
                        .append("：").append(shortText(interaction.get("content"), 180)).append("\n");
                }
            }
            return profile.toString();
        } catch (Exception e) {
            log.warn("读取客户上下文失败，继续按通用模式回答: customerId={}", customerId);
            return "";
        }
    }

    /** 将当前会话最近的问答明确传入模型，避免每轮都像第一次提问。 */
    private String buildConversationHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "";
        try {
            var rows = jdbc.queryForList("""
                SELECT content, ai_response FROM chat_messages
                WHERE session_id = ? AND store_id = ?
                ORDER BY created_at DESC
                LIMIT 6
                """, sessionId, cur.storeId());
            if (rows.isEmpty()) return "";
            var history = new StringBuilder("\n最近对话（延续上下文，不要重复追问已确认内容）：\n");
            for (int i = rows.size() - 1; i >= 0; i--) {
                var row = rows.get(i);
                history.append("- 员工：").append(shortText(row.get("content"), 320)).append("\n")
                    .append("  教练：").append(shortText(row.get("ai_response"), 520)).append("\n");
            }
            return history.toString();
        } catch (Exception e) {
            log.warn("读取会话上下文失败: session={}", sessionId);
            return "";
        }
    }

    private String shortText(Object value, int max) {
        String text = value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 保存员工对一条 AI 建议的采纳/异议结果；该结果是可追溯业务数据，不存浏览器临时状态。 */
    public FeedbackResult recordFeedback(String messageId, String feedbackType, String comment) {
        if (messageId == null || messageId.isBlank()) throw BizException.badRequest("缺少回答标识");
        if (!FEEDBACK_TYPES.contains(feedbackType)) throw BizException.badRequest("不支持的反馈类型");
        ChatMessage message = messageRepo.selectById(messageId);
        if (message == null || !cur.storeId().equals(message.getStoreId()) || !cur.employeeId().equals(message.getEmployeeId())) {
            throw BizException.notFound("AI 回答");
        }
        boolean helpful = "已接受".equals(feedbackType) || "已预约".equals(feedbackType);
        String note = comment == null ? "" : comment.trim();
        if (note.length() > 1_000) note = note.substring(0, 1_000);
        jdbc.update("""
            INSERT INTO ai_feedback (id, store_id, employee_id, message_id, customer_id, feedback_type, is_helpful, comment, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            ON DUPLICATE KEY UPDATE feedback_type = VALUES(feedback_type), is_helpful = VALUES(is_helpful),
                comment = VALUES(comment), customer_id = VALUES(customer_id), updated_at = NOW()
            """, UUID.randomUUID().toString().replace("-", ""), cur.storeId(), cur.employeeId(), messageId,
            message.getCustomerId(), feedbackType, helpful ? 1 : 0, note);
        if (message.getCustomerId() != null && !message.getCustomerId().isBlank()) {
            customerTimelineService.addInteraction(message.getCustomerId(), "ai_coach_feedback",
                "员工对 AI 教练建议的反馈：" + feedbackType + (note.isBlank() ? "" : "；补充：" + note));
        }
        // 负向反馈不是只留一条浏览器提示，而是进入门店的知识缺口审核池。
        // 使用 message_id 去重，因此员工修改同一条反馈时只更新反馈本身，不会反复堆积待审项。
        if (!helpful) recordKnowledgeGapFromFeedback(message, feedbackType, note);
        return new FeedbackResult(feedbackType, helpful);
    }

    private void recordKnowledgeGapFromFeedback(ChatMessage message, String feedbackType, String note) {
        try {
            Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_gaps
                WHERE store_id = ? AND source_type = 'ai_feedback' AND source_id = ?
                """, Integer.class, cur.storeId(), message.getId());
            if (exists != null && exists > 0) return;
            String question = "AI 教练回答待复核【" + feedbackType + "】："
                + shortText(message.getContent(), 300)
                + (note.isBlank() ? "" : "；员工反馈：" + note);
            jdbc.update("""
                INSERT INTO knowledge_gaps (id, store_id, employee_id, question, status, source_type, source_id, created_at)
                VALUES (?, ?, ?, ?, 'pending', 'ai_feedback', ?, NOW())
                """, UUID.randomUUID().toString().replace("-", ""), cur.storeId(), cur.employeeId(), question, message.getId());
        } catch (Exception e) {
            // 反馈本身已经持久化，知识缺口的补充动作失败不能让员工误以为反馈没有记录。
            log.warn("AI 反馈未能写入知识缺口审核池: message={}, reason={}", message.getId(), e.getMessage());
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
                                    List<SystemPlaybookService.PlaybookReference> playbooks,
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
        try {
            msg.setRetrievedChunks(mapper.writeValueAsString(chunks.stream()
                // documentId 与 chunkId 同时保存：前者用于让员工回到原资料核验，
                // 后者用于定位命中的具体片段。旧记录缺少 documentId 时仍可兼容读取。
                .map(c -> new RetrievedInfo(c.id(), c.documentId(), c.documentTitle(), c.content().substring(0, Math.min(180, c.content().length()))))
                .toList()));
        } catch (Exception ignored) { }
        try {
            msg.setMethodologySources(mapper.writeValueAsString(playbooks.stream()
                .map(item -> new MethodologyInfo(item.id(), item.scenarioKey(), item.title(), item.category(), item.source()))
                .toList()));
        } catch (Exception ignored) { }
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
            .map(c -> new RetrievedInfo(c.id(), c.documentId(), c.documentTitle(), c.content().substring(0, Math.min(100, c.content().length()))))
            .toList();
        List<MethodologyInfo> methodology = playbooks.stream()
            .map(item -> new MethodologyInfo(item.id(), item.scenarioKey(), item.title(), item.category(), item.source()))
            .toList();

        return new AnswerResult(
            sessionId, msg.getId(), answer, category,
            riskLevel, answerType, retrieved, methodology, bannedHit
        );
    }

    /** 模型服务临时不可用时仍展示清楚的双来源依据，避免降级成没有说明的通用答案。 */
    private String buildFallbackAnswer(String standardAnswer, String customerId,
                                       List<KnowledgeRetrieveService.RetrievedChunk> chunks,
                                       List<SystemPlaybookService.PlaybookReference> playbooks,
                                       String question) {
        if (standardAnswer != null) return buildCustomerStandardAnswer(standardAnswer, customerId)
            + methodologyReminder(playbooks);
        if (!chunks.isEmpty()) return buildKnowledgeAnswer(chunks, question) + methodologyReminder(playbooks);
        if (!playbooks.isEmpty()) return buildMethodologyAnswer(playbooks);
        return buildGeneralAnswer(question);
    }

    private String methodologyReminder(List<SystemPlaybookService.PlaybookReference> playbooks) {
        if (playbooks == null || playbooks.isEmpty()) return "";
        String names = playbooks.stream().map(SystemPlaybookService.PlaybookReference::title)
            .filter(name -> name != null && !name.isBlank()).limit(2).reduce((a, b) -> a + "、" + b).orElse("");
        return "\n\n**销售方法参考**：本次同时参考了系统销售方法论" + (names.isBlank() ? "。" : "《" + names + "》。")
            + "它只用于沟通和决策策略，不替代本店价格、服务或合规口径。";
    }

    private String buildMethodologyAnswer(List<SystemPlaybookService.PlaybookReference> playbooks) {
        var sb = new StringBuilder("（当前未命中本店专属资料，以下为系统销售方法论建议；价格、活动和服务细则请以门店确认口径为准）\n\n");
        for (int i = 0; i < Math.min(2, playbooks.size()); i++) {
            var item = playbooks.get(i);
            String content = item.content().replaceAll("\\s+", " ").trim();
            sb.append("**方法").append(i + 1).append("：《").append(item.title()).append("》**\n")
                .append(content.substring(0, Math.min(220, content.length())))
                .append("\n");
        }
        sb.append("\n**下一步动作**：先用开放问题确认客户真正顾虑，再按门店已确认口径给出选择。\n")
            .append("**是否需要升级**：如涉及价格让步、效果承诺、投诉或安全风险，需升级店长。\n");
        return sb.toString();
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
        List<MethodologyInfo> methodology,
        List<String> bannedHit
    ) {}

    /** 门店资料引用快照。documentId 是可信追溯主键，chunkId 是本次命中的具体片段。 */
    public record RetrievedInfo(String chunkId, String documentId, String documentTitle, String snippet) {}

    public record MethodologyInfo(String id, String scenarioKey, String title, String module, String source) {}

    public record FeedbackResult(String feedbackType, boolean helpful) {}
}
