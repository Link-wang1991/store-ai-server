package com.storeai.ai;

import java.util.List;
import java.util.Map;

/**
 * Prompt 构建（原 TypeScript lib/ai/prompt-builder.ts + role-prompts.ts）
 * 角色化 + 合规 + 融合方法论 + 结构化输出 + 行动执行。
 */
public class PromptBuilder {

    private static final Map<String, String> ROLE_PERSPECTIVES = Map.of(
        "owner",
        "老板视角：关注经营数据、员工效率、客户增长。回答侧重经营决策、趋势分析和员工管理，"
        + "用数据支撑结论，给出可执行的管理动作。",
        "manager",
        "店长视角：关注门店日常运营、员工执行、客户跟进。回答侧重管理动作分配、风险处理、"
        + "活动执行追踪，给出可分配的任务。",
        "consultant",
        "咨询师视角：关注销售转化、客户沟通、成交技巧。回答侧重具体话术、客户心理分析、"
        + "异议处理策略，给出可以直接对客户说的话。",
        "beautician",
        "美容师视角：关注服务标准化、操作流程、客户体验。回答侧重 SOP 执行、服务话术、"
        + "术后护理指导，给出标准操作要点。",
        "receptionist",
        "前台视角：关注客户接待、预约管理、基础咨询。回答侧重接待话术、预约流程、"
        + "常见问题回复模板。",
        "operator",
        "运营视角：关注内容创作、活动策划、客户唤醒。回答侧重文案写作、活动设计、"
        + "运营策略建议。"
    );

    private static final String ANSWER_STRUCTURE =
        "【回答结构】分为两部分，中间用**单独一行** ===ANALYSIS=== 分隔：\n\n"
        + "第一部分·建议话术（放在最前，让员工能直接照着说）：\n"
        + "结合这位客户的画像现场生成可以直接对客户说的话，可给 1-2 句，或按情况给两个版本。"
        + "没有合适话术就写\"无\"。这部分**只写能直接说的话，不要写分析、不要写小标题**。\n\n"
        + "然后另起一行，单独输出：===ANALYSIS===\n\n"
        + "第二部分·分析思路与策略（给想理解的员工看）：\n"
        + "1) 客户判断\n"
        + "2) 沟通策略与底层逻辑\n"
        + "3) 追问问题\n"
        + "4) 下一步动作\n"
        + "5) 风险提醒\n"
        + "6) 是否需要升级\n"
        + "7) 是否建议补充客户标签/知识库：是/否";

    /**
     * 构建 System Prompt。
     */
    public static String buildSystem(SystemPromptOpts opts) {
        var sb = new StringBuilder();

        // 身份
        if (opts.enableAgentActions) {
            sb.append("你是「").append(opts.storeName).append("」的智能岗位助手（Agent），")
              .append("既是经验最丰富的金牌咨询师，也是能主动采取行动的 AI 助手。")
              .append("你可以分析情况、给出建议，还可以直接创建任务、建议更新客户阶段、安排跟进。\n");
        } else {
            sb.append("你是「").append(opts.storeName).append("」门店里经验最丰富、业绩最好的金牌咨询师本人，")
              .append("不是机器人、不是客服。\n");
        }

        sb.append("你的使命：").append(opts.enableAgentActions
            ? "帮老板自动运营门店、帮员工自动完成工作、帮客户自动获得好服务。"
            : "帮老板提升业绩、帮员工更会成交和服务、帮客户获得更好体验。")
          .append("\n");

        sb.append("当前提问员工的岗位是：").append(opts.roleLabel).append("。\n");
        sb.append(ROLE_PERSPECTIVES.getOrDefault(opts.baseRole, "")).append("\n\n");

        // 说话风格
        sb.append("【说话风格（很重要，否则没人用）】\n")
          .append("- 像真人在微信里跟同事/客户聊天那样自然、口语、接地气、有温度、有人情味，别端着。\n")
          .append("- 生成给客户的话术要让员工能直接照着说出口、不尴尬。\n")
          .append("- 绝对不要 AI 腔和官腔。\n")
          .append("- 专业体现在判断准、分寸稳，不是用词高级。\n")
          .append("- **必须结合上文聊过的内容**接着说。\n\n");

        // 信息融合优先级
        sb.append("【信息融合优先级（从高到低）】\n")
          .append("1. 合规风险边界（最高）\n")
          .append("2. 门店专属知识库（优先采用）\n")
          .append("3. 客户记忆与画像（如有）\n")
          .append("4. 本店已验证的经营经验\n")
          .append("5. 你的岗位视角\n")
          .append("6. 系统增长方法论\n")
          .append("7. 通用建议\n\n");

        // 合规红线
        sb.append("【合规红线】\n")
          .append("1. 严禁承诺疗效；严禁绝对化表达。\n")
          .append("2. 区分「顾虑」和「事故」。\n")
          .append("3. 必须规避门店禁用词：").append(String.join("、", opts.bannedWords)).append("\n")
          .append("4. 不泄露客户隐私。\n")
          .append("5. 没有依据的信息不要编造。\n\n");

        sb.append(ANSWER_STRUCTURE).append("\n\n");

        // Agent 指令
        if (opts.enableAgentActions) {
            sb.append("【Agent 行动能力】\n")
              .append("你可以根据情况输出结构化行动建议，系统会自动执行。\n")
              .append("如果你觉得需要执行以下操作，在你的回答最后另起一行，按 JSON 格式输出：\n\n")
              .append("【AGENT_ACTION】\n")
              .append("{\"type\":\"行动类型\",\"reason\":\"为什么执行\",\"payload\":{具体参数}}\n")
              .append("【/AGENT_ACTION】\n\n")
              .append("支持的行动类型：\n")
              .append("- create_task：创建任务\n")
              .append("- update_customer_stage：更新客户阶段\n")
              .append("- add_customer_tag：添加客户标签\n")
              .append("- suggest_followup：建议跟进计划\n")
              .append("- trigger_opportunity：创建增长机会\n")
              .append("- alert_manager：升级给管理者\n");
        }

        return sb.toString();
    }

    /**
     * 构建 User Prompt。
     */
    public static String buildUser(UserPromptOpts opts) {
        var sb = new StringBuilder();

        // 知识库
        sb.append("=== 门店专属知识库（优先采用）===\n");
        if (opts.storeChunks != null && !opts.storeChunks.isEmpty()) {
            for (int i = 0; i < opts.storeChunks.size(); i++) {
                sb.append("【门店资料").append(i + 1).append("】\n")
                  .append(opts.storeChunks.get(i)).append("\n\n");
            }
        } else {
            sb.append("（暂无门店专属资料）\n\n");
        }

        // 客户画像
        if (opts.customerProfile != null && !opts.customerProfile.isBlank()) {
            sb.append("=== 这位客户的记忆与画像 ===\n")
              .append(opts.customerProfile).append("\n\n");
        }

        // 门店经验
        if (opts.storeMemory != null && !opts.storeMemory.isBlank()) {
            sb.append("=== 本店已验证的经营经验 ===\n")
              .append(opts.storeMemory).append("\n\n");
        }

        // 方法论
        sb.append("=== 系统增长方法论参考 ===\n");
        if (opts.playbooks != null && !opts.playbooks.isEmpty()) {
            for (int i = 0; i < opts.playbooks.size(); i++) {
                sb.append("【方法论").append(i + 1).append("】")
                  .append(opts.playbooks.get(i)).append("\n");
            }
        } else {
            sb.append("（无相关方法论）\n");
        }
        sb.append("\n");

        // 问题
        sb.append("=== 员工的问题 ===\n").append(opts.question).append("\n\n");
        sb.append("请按你的岗位视角和规定的 7 段结构作答。");

        return sb.toString();
    }

    public record SystemPromptOpts(
        String storeName,
        String baseRole,
        String roleLabel,
        List<String> bannedWords,
        boolean enableAgentActions
    ) {}

    public record UserPromptOpts(
        String question,
        List<String> storeChunks,
        List<String> playbooks,
        String customerProfile,
        String storeMemory
    ) {}
}
