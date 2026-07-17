package com.storeai.coach.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.ai.AiAdapter;
import com.storeai.agent.AgentExecutor;
import com.storeai.common.util.CurrentUser;
import com.storeai.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务中实时 AI 教练辅助。
 * 员工随时提问，AI 返回：话术、追问、动作、风险、详细分析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachAssistService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;
    private final AiAdapter aiAdapter;
    private final KnowledgeService knowledgeService;
    private final AgentExecutor agentExecutor;
    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> assist(String question, String customerId, String meetingId) {
        // 1. 上下文收集
        String customerProfile = buildCustomerProfile(customerId);
        String meetingContext = buildMeetingContext(meetingId);
        List<String> chunks = retrieveKnowledge(question);

        // 2. 构造 prompt
        String system = buildSystem();
        String user = buildUserPrompt(question, customerProfile, meetingContext, chunks);

        // 3. 调用 LLM
        String aiResult = aiAdapter.call(system, user, null);
        if (aiResult == null) {
            return fallbackResult(question);
        }

        // 4. 解析结构化输出
        Map<String, Object> result = parseResult(aiResult);

        // 5. 执行 Agent 行动
        var actions = agentExecutor.parseActions(aiResult);
        var actionResults = agentExecutor.execute(actions, customerId);
        result.put("actions", actionResults);

        return result;
    }

    private String buildSystem() {
        return "你是门店 AI 教练，员工在服务客户时向你求助。"
            + "请按以下结构回答，让员工能直接照做：\n\n"
            + "【可以直接说的话】1-2 句能直接对客户说的话\n"
            + "【接下来要问的问题】1-3 个关键追问\n"
            + "【下一步动作】具体可执行动作\n"
            + "【风险提醒】合规、体验或升级风险，无则写\"无\"\n\n"
            + "然后再另起一行输出 ===ANALYSIS===， followed by：\n"
            + "1) 客户判断\n"
            + "2) 沟通策略\n"
            + "3) 是否需要升级\n\n"
            + "如果确有必要创建任务、建议跟进或升级店长，可在回答最后按以下 JSON 格式输出行动：\n"
            + "【AGENT_ACTION】{\"type\":\"create_task\",\"reason\":\"原因\",\"payload\":{\"title\":\"任务标题\"}}【/AGENT_ACTION】";
    }

    private String buildUserPrompt(String question, String customerProfile,
                                   String meetingContext, List<String> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 员工问题 ===\n").append(question).append("\n\n");
        if (!customerProfile.isBlank()) {
            sb.append("=== 客户背景 ===\n").append(customerProfile).append("\n\n");
        }
        if (!meetingContext.isBlank()) {
            sb.append("=== 当前会谈上下文 ===\n").append(meetingContext).append("\n\n");
        }
        if (!chunks.isEmpty()) {
            sb.append("=== 门店知识库 ===\n");
            for (int i = 0; i < chunks.size(); i++) {
                sb.append("资料").append(i + 1).append("：").append(chunks.get(i)).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildCustomerProfile(String customerId) {
        if (customerId == null || customerId.isBlank()) return "";
        try {
            Map<String, Object> c = jdbc.queryForMap(
                "SELECT name, stage, pool, tags, concerns, portrait, ai_suggestion FROM customers WHERE id = ? AND store_id = ?",
                customerId, cur.storeId());
            StringBuilder sb = new StringBuilder();
            sb.append("姓名：").append(c.getOrDefault("name", "")).append("\n");
            sb.append("阶段：").append(c.getOrDefault("stage", "")).append("\n");
            sb.append("标签：").append(c.getOrDefault("tags", "")).append("\n");
            sb.append("顾虑：").append(c.getOrDefault("concerns", "")).append("\n");
            sb.append("画像：").append(c.getOrDefault("portrait", "")).append("\n");

            List<Map<String, Object>> memories = jdbc.queryForList(
                "SELECT `key`, value, confidence FROM memory_items " +
                "WHERE customer_id = ? AND store_id = ? ORDER BY created_at DESC LIMIT 10",
                customerId, cur.storeId());
            if (!memories.isEmpty()) {
                sb.append("\n最近记忆：\n");
                for (var m : memories) {
                    sb.append("- [").append(m.get("key")).append("] ").append(m.get("value")).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildMeetingContext(String meetingId) {
        if (meetingId == null || meetingId.isBlank()) return "";
        try {
            Map<String, Object> m = jdbc.queryForMap(
                "SELECT scene, summary, explicit_needs, implicit_needs, emotional_needs, " +
                "decision_barriers, followup_goal, suggested_script " +
                "FROM meeting_analysis WHERE meeting_id = ? AND store_id = ?",
                meetingId, cur.storeId());
            StringBuilder sb = new StringBuilder();
            sb.append("场景：").append(m.getOrDefault("scene", "")).append("\n");
            sb.append("摘要：").append(m.getOrDefault("summary", "")).append("\n");
            sb.append("需求：").append(m.getOrDefault("explicit_needs", "")).append("\n");
            sb.append("障碍：").append(m.getOrDefault("decision_barriers", "")).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private List<String> retrieveKnowledge(String question) {
        try {
            var chunks = knowledgeService.search(question, 3);
            List<String> result = new ArrayList<>();
            for (var c : chunks) result.add(c.content());
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> parseResult(String aiResult) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("script", "");
        result.put("nextQuestion", "");
        result.put("nextAction", "");
        result.put("riskReminder", "");
        result.put("analysis", "");

        // 按 ===ANALYSIS=== 拆分
        String clean = agentExecutor.stripActions(aiResult);
        int split = clean.indexOf("===ANALYSIS===");
        String front = split >= 0 ? clean.substring(0, split) : clean;
        String back = split >= 0 ? clean.substring(split + "===ANALYSIS===".length()) : "";

        result.put("analysis", back.trim());

        // 解析前四部分
        String[] lines = front.split("\n");
        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();
        Map<String, String> keyMap = Map.of(
            "可以直接说的话", "script",
            "接下来要问的问题", "nextQuestion",
            "下一步动作", "nextAction",
            "风险提醒", "riskReminder"
        );

        for (String line : lines) {
            String trimmed = line.trim();
            boolean matched = false;
            for (var e : keyMap.entrySet()) {
                if (trimmed.startsWith(e.getKey()) || trimmed.startsWith("【" + e.getKey() + "】")) {
                    if (currentKey != null) {
                        result.put(currentKey, currentValue.toString().trim());
                    }
                    currentKey = e.getValue();
                    currentValue = new StringBuilder();
                    int idx = trimmed.indexOf("】");
                    if (idx >= 0) {
                        String after = trimmed.substring(idx + 1).trim();
                        if (after.startsWith(":")) after = after.substring(1).trim();
                        if (!after.isBlank()) currentValue.append(after);
                    }
                    matched = true;
                    break;
                }
            }
            if (!matched && currentKey != null && !trimmed.isBlank()) {
                if (!currentValue.isEmpty()) currentValue.append("\n");
                currentValue.append(trimmed);
            }
        }
        if (currentKey != null) {
            result.put(currentKey, currentValue.toString().trim());
        }

        return result;
    }

    private Map<String, Object> fallbackResult(String question) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("script", "这个我先帮您确认一下，给您最准确的答复。");
        m.put("nextQuestion", "您最在意的是哪一点？");
        m.put("nextAction", "了解客户真实顾虑，必要时升级店长。");
        m.put("riskReminder", "涉及价格/折扣/退款等对外承诺需店长确认。");
        m.put("analysis", "当前 AI 服务未配置，无法生成详细分析。");
        m.put("actions", List.of());
        return m;
    }
}
