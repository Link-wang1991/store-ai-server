package com.storeai.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storeai.chat.entity.ChatMessage;
import com.storeai.chat.entity.ChatSession;
import com.storeai.chat.repository.ChatMessageRepository;
import com.storeai.chat.repository.ChatSessionRepository;
import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final CurrentUser cur;

    public List<SessionItem> listSessions() {
        var wrapper = new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getEmployeeId, cur.employeeId())
                .orderByDesc(ChatSession::getUpdatedAt);
        return sessionRepo.selectList(wrapper).stream()
                .map(s -> new SessionItem(s.getId(), s.getTitle()))
                .toList();
    }

    public List<ChatMessageItem> listMessages(String sessionId) {
        var session = sessionRepo.selectById(sessionId);
        if (session == null) {
            throw BizException.badRequest("会话不存在");
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
                    null));
            result.add(new ChatMessageItem(
                    row.getId() + "_a",
                    "ai",
                    row.getAiResponse(),
                    row.getRiskLevel(),
                    row.getAnswerType()));
        }
        return result;
    }

    public void deleteSession(String sessionId) {
        var session = sessionRepo.selectById(sessionId);
        if (session == null) {
            throw BizException.badRequest("会话不存在");
        }
        if (!cur.employeeId().equals(session.getEmployeeId()) && !cur.isAdmin()) {
            throw BizException.forbidden("无权删除该会话");
        }
        sessionRepo.deleteById(sessionId);
    }

    public record SessionItem(String id, String title) {}

    public record ChatMessageItem(
            String id,
            String role,
            String text,
            String riskLevel,
            String answerType
    ) {}
}
