package com.storeai.knowledge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 系统级销售专业知识（方法论）的读取与检索。
 *
 * 它与每家门店上传的资料严格分层：系统方法论可以帮助判断客户心理、提问策略和
 * 销售推进方式，但永远不能覆盖门店自己配置的价格、服务、活动和合规口径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemPlaybookService {

    private static final int MAX_QUERY_CHARS = 8_000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final KnowledgeRetrieveService retrieveService;

    /**
     * 应用启动时幂等导入内置方法论。资源来自项目中保留的原始 68 条销售专业资料，
     * 使用 scenario_key 做唯一键；重启不会重复创建，也不会写入门店资料表。
     */
    @PostConstruct
    public void seedBuiltInPlaybooks() {
        try (InputStream input = new ClassPathResource("system-playbooks.json").getInputStream()) {
            List<PlaybookSeed> seeds = mapper.readValue(input, new TypeReference<List<PlaybookSeed>>() {});
            int written = 0;
            for (PlaybookSeed seed : seeds) {
                if (blank(seed.scenarioKey()) || blank(seed.title())) continue;
                String id = "system_" + seed.scenarioKey();
                String content = composeContent(seed);
                String tags = mapper.writeValueAsString(Map.of(
                    "module", value(seed.module()),
                    "scene", value(seed.scene()),
                    "stages", safeList(seed.applicableStages())
                ));
                jdbc.update("""
                    INSERT INTO playbooks
                        (id, scenario_key, category, title, content, tags, source, applicable_roles, applicable_stages, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', NOW(), NOW())
                    ON DUPLICATE KEY UPDATE
                        category = VALUES(category), title = VALUES(title), content = VALUES(content), tags = VALUES(tags),
                        source = VALUES(source), applicable_roles = VALUES(applicable_roles),
                        applicable_stages = VALUES(applicable_stages), status = 'active', updated_at = NOW()
                    """,
                    id, seed.scenarioKey(), value(seed.module()), seed.title(), content, tags, value(seed.source()),
                    mapper.writeValueAsString(safeList(seed.applicableRoles())),
                    mapper.writeValueAsString(safeList(seed.applicableStages())));
                written++;
            }
            log.info("系统销售方法论已就绪: {} 条", written);
        } catch (Exception e) {
            // 这是增强能力。即便历史库尚未升级，也不能让整个后端无法启动。
            log.warn("系统销售方法论初始化暂不可用，将在下次启动自动重试: {}", e.getMessage());
        }
    }

    /** 仅按关键词确定性检索，不依赖外部向量接口，避免网络波动阻塞会谈和 AI 教练。 */
    public List<PlaybookReference> search(String query, String role, int topN) {
        if (blank(query)) return List.of();
        try {
            List<PlaybookReference> candidates = jdbc.query("""
                    SELECT id, scenario_key, category, title, content, source, applicable_roles, applicable_stages
                    FROM playbooks
                    WHERE COALESCE(status, 'active') = 'active'
                    """, (rs, ignored) -> new PlaybookReference(
                    rs.getString("id"), rs.getString("scenario_key"), rs.getString("category"),
                    rs.getString("title"), rs.getString("content"), rs.getString("source"),
                    readList(rs.getObject("applicable_roles")), readList(rs.getObject("applicable_stages")), 0D));
            if (candidates.isEmpty()) return List.of();

            String retrievalQuery = enrichQueryForCommonObjections(clip(query, MAX_QUERY_CHARS));
            List<String> tokens = new ArrayList<>(new LinkedHashSet<>(retrieveService.tokenize(retrievalQuery)));
            if (tokens.isEmpty()) return List.of();

            return candidates.stream()
                .filter(item -> isRoleVisible(item.applicableRoles(), role))
                .map(item -> item.withScore(retrieveService.scoreChunk(tokens, searchable(item))))
                .filter(item -> item.score() > 0D)
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(Math.max(1, Math.min(topN, 4)))
                .toList();
        } catch (Exception e) {
            log.warn("系统销售方法论检索失败，继续按门店资料处理: {}", e.getMessage());
            return List.of();
        }
    }

    /** 给模型的完整可读上下文；每一条都带来源，以便模型能正确解释判断依据。 */
    public String promptContext(List<PlaybookReference> references, int contentLimit) {
        if (references == null || references.isEmpty()) return "系统销售方法论检索结果：本次未命中直接相关的方法论。";
        StringBuilder builder = new StringBuilder("系统销售方法论检索结果（通用方法，不得替代门店价格、活动、服务和合规口径）：\n");
        int index = 1;
        for (PlaybookReference item : references) {
            builder.append(index++).append(". 《").append(value(item.title())).append("》")
                .append("（模块：").append(value(item.category())).append("；来源：")
                .append(value(item.source())).append("）\n")
                .append(clip(item.content(), contentLimit)).append("\n\n");
        }
        return builder.toString().trim();
    }

    public String basis(List<PlaybookReference> references) {
        if (references == null || references.isEmpty()) {
            return "本次未命中直接相关的系统销售方法论；判断仅以会谈事实和门店资料为依据。";
        }
        return "本次同时参考了系统销售方法论：" + references.stream()
            .map(item -> "《" + value(item.title()) + "》")
            .collect(Collectors.joining("、"))
            + "。它用于解释客户决策与沟通策略，不替代本店的价格、服务和合规规则。";
    }

    private String searchable(PlaybookReference item) {
        return String.join("\n", value(item.scenarioKey()), value(item.category()), value(item.title()),
            value(item.content()), value(item.source()));
    }

    /**
     * 业务用户常用“太贵”“怕痛”等口语，而知识标题可能写成“嫌贵”“安全顾虑”。
     * 只补充稳定的业务同义词，不调用外部模型；这样离线/弱网时检索结果仍可解释、可复现。
     */
    private String enrichQueryForCommonObjections(String query) {
        String lower = value(query).toLowerCase();
        StringBuilder enriched = new StringBuilder(query);
        if (containsAny(lower, "贵", "价格", "便宜", "折扣", "预算", "优惠")) enriched.append(" 嫌贵 价格异议 价值 预算");
        if (containsAny(lower, "痛", "过敏", "风险", "副作用", "安全", "恢复")) enriched.append(" 安全顾虑 风险边界 体验 术后护理");
        if (containsAny(lower, "考虑", "比较", "再看看", "犹豫", "想想")) enriched.append(" 犹豫 比较 决策障碍 跟进");
        if (containsAny(lower, "老客", "复购", "回访", "流失", "没来")) enriched.append(" 老客复购 回访 唤醒 信任");
        if (containsAny(lower, "投诉", "不满", "退款", "客诉")) enriched.append(" 客诉 异议 安抚 升级");
        return enriched.toString();
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private boolean isRoleVisible(List<String> applicableRoles, String role) {
        if (applicableRoles == null || applicableRoles.isEmpty()) return true;
        String normalized = value(role).toLowerCase();
        // 管理者需要看到全局经营/辅导方法论；一线员工只取与本岗位相符的知识。
        if (Set.of("owner", "manager", "admin").contains(normalized)) return true;
        return applicableRoles.stream().map(this::value).map(String::toLowerCase)
            .anyMatch(item -> Objects.equals(item, normalized) || "all".equals(item));
    }

    private List<String> readList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof Collection<?> items) {
            return items.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        try {
            return mapper.readValue(String.valueOf(raw), new TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String composeContent(PlaybookSeed seed) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("适用场景", seed.scene());
        fields.put("客户心理", seed.customerPsychology());
        fields.put("常见误区", seed.commonMistakes());
        fields.put("推荐策略", seed.strategy());
        fields.put("可用表达", seed.scripts());
        fields.put("建议追问", seed.followUpQuestions());
        fields.put("下一步动作", seed.nextAction());
        fields.put("风险边界", seed.riskNote());
        return fields.entrySet().stream().filter(entry -> !blank(entry.getValue()))
            .map(entry -> entry.getKey() + "：" + entry.getValue()).collect(Collectors.joining("\n"));
    }

    private List<String> safeList(List<String> values) { return values == null ? List.of() : values; }
    private String value(String text) { return text == null ? "" : text.trim(); }
    private boolean blank(String text) { return text == null || text.isBlank(); }
    private String clip(String text, int max) {
        String normalized = value(text);
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }

    private record PlaybookSeed(
        String scenario_key, String module, String title, String scene, String customer_psychology,
        String common_mistakes, String strategy, String scripts, String follow_up_questions,
        String next_action, String risk_note, String source, List<String> applicable_roles,
        List<String> applicable_stages
    ) {
        String scenarioKey() { return scenario_key; }
        String customerPsychology() { return customer_psychology; }
        String commonMistakes() { return common_mistakes; }
        String followUpQuestions() { return follow_up_questions; }
        String nextAction() { return next_action; }
        String riskNote() { return risk_note; }
        List<String> applicableRoles() { return applicable_roles; }
        List<String> applicableStages() { return applicable_stages; }
    }

    public record PlaybookReference(
        String id, String scenarioKey, String category, String title, String content, String source,
        List<String> applicableRoles, List<String> applicableStages, double score
    ) {
        PlaybookReference withScore(double nextScore) {
            return new PlaybookReference(id, scenarioKey, category, title, content, source,
                applicableRoles, applicableStages, nextScore);
        }
    }
}
