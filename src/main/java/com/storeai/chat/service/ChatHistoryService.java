package com.storeai.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.chat.entity.ChatMessage;
import com.storeai.chat.entity.ChatSession;
import com.storeai.chat.repository.ChatMessageRepository;
import com.storeai.chat.repository.ChatSessionRepository;
import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final CurrentUser cur;
    private final JdbcTemplate jdbc;
    private final AiActionProposalService actionProposalService;
    private final ObjectMapper mapper;

    public List<SessionItem> listSessions() {
        var wrapper = new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getStoreId, cur.storeId())
                .eq(ChatSession::getEmployeeId, cur.employeeId())
                .orderByDesc(ChatSession::getUpdatedAt);
        return sessionRepo.selectList(wrapper).stream()
                .map(s -> new SessionItem(s.getId(), s.getTitle(), s.getCustomerId()))
                .toList();
    }

    public List<ChatMessageItem> listMessages(String sessionId) {
        var session = sessionRepo.selectById(sessionId);
        if (session == null) {
            throw BizException.badRequest("会话不存在");
        }
        if (!cur.storeId().equals(session.getStoreId())) {
            throw BizException.notFound("会话");
        }
        if (!cur.employeeId().equals(session.getEmployeeId()) && !cur.isAdmin()) {
            throw BizException.forbidden("无权查看该会话");
        }
        var wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt);
        var rows = messageRepo.selectList(wrapper);
        var result = new ArrayList<ChatMessageItem>();
        for (var row : rows) {
            // 一条数据库记录包含用户问题和 AI 回答，拆成两条消息返回
            result.add(new ChatMessageItem(
                row.getId() + "_u",
                "user",
                row.getContent(),
                null,
                null,
                null,
                null,
                null,
                null));
            result.add(new ChatMessageItem(
                    row.getId(),
                    "ai",
                    row.getAiResponse(),
                    row.getRiskLevel(),
                    row.getAnswerType(),
                    feedbackType(row.getId()),
                    readRetrieved(row.getRetrievedChunks()),
                    readMethodology(row.getMethodologySources()),
                    actionProposalService.findByMessageForCurrentEmployee(row.getId())));
        }
        return result;
    }

    public void deleteSession(String sessionId) {
        var session = sessionRepo.selectById(sessionId);
        if (session == null) {
            throw BizException.badRequest("会话不存在");
        }
        if (!cur.storeId().equals(session.getStoreId())) {
            throw BizException.notFound("会话");
        }
        if (!cur.employeeId().equals(session.getEmployeeId()) && !cur.isAdmin()) {
            throw BizException.forbidden("无权删除该会话");
        }
        sessionRepo.deleteById(sessionId);
    }

    private String feedbackType(String messageId) {
        try {
            return jdbc.queryForObject("""
                SELECT feedback_type FROM ai_feedback
                WHERE message_id = ? AND employee_id = ? AND store_id = ?
                LIMIT 1
                """, String.class, messageId, cur.employeeId(), cur.storeId());
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<ChatPipelineService.RetrievedInfo> readRetrieved(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return mapper.readValue(raw, new TypeReference<List<ChatPipelineService.RetrievedInfo>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<ChatPipelineService.MethodologyInfo> readMethodology(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return mapper.readValue(raw, new TypeReference<List<ChatPipelineService.MethodologyInfo>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public record SessionItem(String id, String title, String customerId) {}

    public record ChatMessageItem(
            String id,
            String role,
            String text,
            String riskLevel,
            String answerType,
            String feedbackType,
            List<ChatPipelineService.RetrievedInfo> retrieved,
            List<ChatPipelineService.MethodologyInfo> methodology,
            AiActionProposalService.ActionProposal actionProposal
    ) {}
}
