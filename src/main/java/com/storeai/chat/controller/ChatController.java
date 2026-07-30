package com.storeai.chat.controller;

import com.storeai.chat.service.ChatHistoryService;
import com.storeai.chat.service.AiActionProposalService;
import com.storeai.chat.service.ChatPipelineService;
import com.storeai.chat.service.ChatPipelineService.AnswerResult;
import com.storeai.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "AI 对话")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatPipelineService pipeline;
    private final ChatHistoryService historyService;
    private final AiActionProposalService actionProposalService;

    @PostMapping
    public ApiResponse<AnswerResult> chat(
            @RequestBody ChatRequest req,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String clientRequestId) {
        return ApiResponse.ok(pipeline.answer(
                req.question(), req.sessionId(), req.customerId(), clientRequestId));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<ChatHistoryService.SessionItem>> sessions() {
        return ApiResponse.ok(historyService.listSessions());
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<ChatHistoryService.ChatMessageItem>> messages(
            @PathVariable String sessionId) {
        return ApiResponse.ok(historyService.listMessages(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable String sessionId) {
        historyService.deleteSession(sessionId);
        return ApiResponse.ok();
    }

    @PostMapping("/messages/{messageId}/feedback")
    public ApiResponse<ChatPipelineService.FeedbackResult> feedback(
            @PathVariable String messageId, @RequestBody FeedbackRequest req) {
        return ApiResponse.ok(pipeline.recordFeedback(messageId, req.feedbackType(), req.comment()));
    }

    /** 将文字建议保存为待确认的业务提案；不会自动创建任务。 */
    @PostMapping("/messages/{messageId}/action-proposals")
    public ApiResponse<AiActionProposalService.ActionProposal> createActionProposal(
            @PathVariable String messageId) {
        return ApiResponse.ok(actionProposalService.create(messageId));
    }

    /** 员工在确认前可修订动作、负责人、优先级和截止时间。 */
    @PatchMapping("/action-proposals/{proposalId}")
    public ApiResponse<AiActionProposalService.ActionProposal> updateActionProposal(
            @PathVariable String proposalId, @RequestBody UpdateActionProposalRequest req) {
        return ApiResponse.ok(actionProposalService.update(
            proposalId, req.title(), req.content(), req.assignedTo(), req.priority(), req.dueAt()));
    }

    /** 管理者可选择同门店员工；普通员工只能选择自己。 */
    @GetMapping("/action-proposals/assignees")
    public ApiResponse<List<AiActionProposalService.EmployeeOption>> actionProposalAssignees() {
        return ApiResponse.ok(actionProposalService.listAssignableEmployees());
    }

    /** 员工确认后才真正创建跟进待办。 */
    @PostMapping("/action-proposals/{proposalId}/apply")
    public ApiResponse<AiActionProposalService.ActionProposal> applyActionProposal(
            @PathVariable String proposalId) {
        return ApiResponse.ok(actionProposalService.apply(proposalId));
    }

    @PostMapping("/action-proposals/{proposalId}/reject")
    public ApiResponse<AiActionProposalService.ActionProposal> rejectActionProposal(
            @PathVariable String proposalId) {
        return ApiResponse.ok(actionProposalService.reject(proposalId));
    }

    public record ChatRequest(String question, String sessionId, String customerId) {}
    public record FeedbackRequest(String feedbackType, String comment) {}
    public record UpdateActionProposalRequest(String title, String content, String assignedTo, String priority, String dueAt) {}
}
