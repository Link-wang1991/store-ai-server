package com.storeai.meeting.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.ai.AiAdapter;
import com.storeai.common.exception.BizException;
import com.storeai.common.net.DirectProxySelector;
import com.storeai.customer.service.CustomerTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingAnalysisService {

    private final JdbcTemplate jdbc;
    private final AiAdapter aiAdapter;
    private final ComplianceScanner complianceScanner;
    private final CustomerTimelineService customerTimelineService;

    /** 高质量会谈阈值：达到则自动沉淀优质经验 + 生成训练任务 */
    private static final int DISTILL_THRESHOLD = 75;
    /** 量化评分权重（需求挖掘/成交推进/合规表现/服务体验） */
    private static final double[] SCORE_WEIGHTS = {0.25, 0.30, 0.20, 0.25};

    @Value("${ai.qwen.api-key:}")
    private String dashscopeKey;

    /** 单段文本超过该字符数则走分段分析（约 9000 中文字） */
    private static final int SINGLE_LIMIT = 9000;
    /** 每个分段的最大字符数（按 segment 边界切，不截断句子） */
    private static final int CHUNK_LIMIT = 6000;

    private static final String DS_BASE = "https://dashscope.aliyuncs.com/api/v1";
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .proxy(DirectProxySelector.INSTANCE)
            .build();
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * 推进会谈状态机：transcribing → analyzing → done。
     * 幂等：重复调用不会重复插入。
     */
    public Map<String, Object> process(String meetingId) {
        var row = jdbc.queryForMap("SELECT * FROM meetings WHERE id = ?", meetingId);
        String status = (String) row.get("status");
        if ("done".equals(status) || "failed".equals(status)) {
            return Map.of("status", status);
        }

        try {
            if ("queued".equals(status) || "submitting".equals(status)) {
                return Map.of("status", status);
            }
            if ("transcribing".equals(status)) {
                return handleTranscribing(row);
            }
            if ("analyzing".equals(status)) {
                return handleAnalyzing(row);
            }
        } catch (Exception e) {
            log.error("处理会谈失败: meeting={}, error={}", meetingId, e.getMessage());
            jdbc.update("UPDATE meetings SET status = 'failed' WHERE id = ?", meetingId);
            return Map.of("status", "failed", "error", e.getMessage());
        }
        return Map.of("status", status);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleTranscribing(Map<String, Object> row) throws Exception {
        String id = (String) row.get("id");
        String asrTaskId = (String) row.get("asr_task_id");

        if (asrTaskId == null) {
            jdbc.update("UPDATE meetings SET status = 'queued', transcript_status = 'pending', updated_at = NOW() WHERE id = ?", id);
            return Map.of("status", "queued");
        }

        // 轮询 DashScope
        var req = HttpRequest.newBuilder()
                .uri(URI.create(DS_BASE + "/tasks/" + asrTaskId))
                .header("Authorization", "Bearer " + dashscopeKey)
                .GET().build();
        var res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            log.warn("DashScope 查询失败: task={}, status={}", asrTaskId, res.statusCode());
            return Map.of("status", "transcribing");
        }

        Map<String, Object> data = jsonMapper.readValue(res.body(), Map.class);
        String taskStatus = (String) ((Map<String, Object>) data.get("output")).get("task_status");

        if ("PENDING".equals(taskStatus) || "RUNNING".equals(taskStatus)) {
            return Map.of("status", "transcribing");
        }

        if (!"SUCCEEDED".equals(taskStatus)) {
            // 转写失败
            String error = data.get("output") != null ? data.get("output").toString() : taskStatus;
            String reason;
            if (error.contains("NO_VALID_FRAGMENT")) {
                reason = "未识别到有效语音。可能原因：①录音太短（建议10秒以上）②没人说话或音量太小③环境太嘈杂。建议重录时靠近麦克风、保持环境安静。";
            } else if (error.contains("AudioDurationExceed")) {
                reason = "录音文件超长，语音转写仅支持最长6小时的音频。";
            } else if (error.contains("InvalidFile") || error.contains("Unsupported")) {
                reason = "录音格式不支持，请使用常见的音频格式（MP4/AAC/WebM/MP3）。";
            } else if (error.contains("file_url") || error.contains("download")) {
                reason = "语音识别服务无法获取录音文件，可能是网络问题，请重试。";
            } else {
                reason = "语音转写失败（" + taskStatus + "），请重新提交转写。";
            }
            jdbc.update("UPDATE meetings SET status = 'failed', transcript_status = 'failed', fail_reason = ? WHERE id = ?", reason, id);
            return Map.of("status", "failed", "error", reason);
        }

        // 解析转写结果
        var results = (List<Map<String, Object>>) ((Map<String, Object>) data.get("output")).get("results");
        List<Map<String, Object>> segments = new ArrayList<>();

        for (var r : results) {
            String url = (String) r.get("transcription_url");
            if (url == null) continue;
            try {
                var trReq = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
                var trRes = httpClient.send(trReq, HttpResponse.BodyHandlers.ofString());
                Map<String, Object> trData = jsonMapper.readValue(trRes.body(), Map.class);
                var transcripts = (List<Map<String, Object>>) trData.get("transcripts");
                if (transcripts == null) continue;
                for (var t : transcripts) {
                    var sentences = (List<Map<String, Object>>) t.get("sentences");
                    if (sentences == null) continue;
                    for (var s : sentences) {
                        var seg = new HashMap<String, Object>();
                        seg.put("speaker", "speaker_" + s.getOrDefault("speaker_id", 0));
                        seg.put("start", ((Number) s.getOrDefault("begin_time", 0)).doubleValue() / 1000);
                        seg.put("end", ((Number) s.getOrDefault("end_time", 0)).doubleValue() / 1000);
                        seg.put("text", s.getOrDefault("text", ""));
                        segments.add(seg);
                    }
                }
            } catch (Exception e) {
                log.warn("跳过转写结果文件: {}", e.getMessage());
            }
        }

        if (segments.isEmpty()) {
            jdbc.update("UPDATE meetings SET status = 'failed', transcript_status = 'failed' WHERE id = ?", id);
            return Map.of("status", "failed", "error", "未识别到有效语音（录音可能太短、太嘈杂或无人说话）");
        }

        // 保存转写
        String storeId = (String) row.get("store_id");
        for (int i = 0; i < segments.size(); i++) {
            var seg = segments.get(i);
            jdbc.update(
                "INSERT INTO meeting_transcripts (id, meeting_id, store_id, speaker, content, start_time, end_time, seq, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString().replace("-", ""),
                id, storeId,
                seg.get("speaker"), seg.get("text"),
                seg.get("start"), seg.get("end"),
                i,
                OffsetDateTime.now().toString(), OffsetDateTime.now().toString()
            );
        }

        jdbc.update("UPDATE meetings SET transcript_status = 'done', status = 'analyzing' WHERE id = ?", id);
        return Map.of("status", "analyzing");
    }

    private Map<String, Object> handleAnalyzing(Map<String, Object> row) throws Exception {
        String id = (String) row.get("id");
        String storeId = (String) row.get("store_id");

        // 幂等：已有分析结果则直接 done
        var existing = jdbc.queryForList(
            "SELECT id FROM meeting_analysis WHERE meeting_id = ? AND store_id = ? LIMIT 1", id, storeId);
        if (!existing.isEmpty()) {
            jdbc.update("UPDATE meetings SET status = 'done', analysis_status = 'done' WHERE id = ?", id);
            return Map.of("status", "done");
        }

        // 读取转写
        var transcripts = jdbc.queryForList(
            "SELECT * FROM meeting_transcripts WHERE meeting_id = ? AND store_id = ? ORDER BY seq ASC", id, storeId);
        if (transcripts.isEmpty()) {
            throw new BizException("没有转写内容可分析");
        }

        // ③ 说话人角色映射：speaker_x → 员工 / 客户
        Map<String, String> roleLabel = mapSpeakers(transcripts);

        // 构建带角色标注的逐句文本
        List<String> lines = new ArrayList<>();
        for (var t : transcripts) {
            String sp = (String) t.get("speaker");
            String content = (String) t.get("content");
            lines.add("[" + roleLabel.getOrDefault(sp, sp) + "] " + content);
        }

        String scene = (String) row.get("scene");
        String roleHint = "说话人已按角色标注：[员工] 为门店销售顾问，[客户] 为到店顾客。分析时请据此区分双方立场。";

        // ① + ② 结构化输出 + 分段分析
        Map<String, Object> analysis = analyze(lines, scene, roleHint);
        if (analysis == null) analysis = emptyReport();

        // ④ 合规风险硬规则扫描（在 AI 文本基础上交叉验证）
        String rawText = buildRawText(transcripts);
        var hits = complianceScanner.scan(rawText);
        if (!hits.isEmpty()) {
            mergeCompliance(analysis, hits);
        }

        // ⑤ 量化评分（维度分 → 加权总分）
        int qualityScore = computeQualityScore(analysis);
        analysis.put("quality_score", qualityScore);

        // 提取 JSON 文本用于落库
        String jsonStr = jsonMapper.writeValueAsString(analysis);

        // 保存分析结果
        String analysisId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
            "INSERT INTO meeting_analysis (id, meeting_id, store_id, report, summary, explicit_needs, implicit_needs, emotional_needs, decision_barriers, employee_did_well, employee_to_improve, missed_opportunities, compliance_risks, compliance_hits, followup_goal, suggested_script, need_manager_involved, need_digging_score, deal_advancing_score, compliance_score, service_score, quality_score, distilled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            analysisId, id, storeId,
            jsonStr,
            safeStr(analysis.get("summary")),
            safeStr(analysis.get("explicit_needs")),
            safeStr(analysis.get("implicit_needs")),
            safeStr(analysis.get("emotional_needs")),
            safeStr(analysis.get("decision_barriers")),
            safeStr(analysis.get("employee_did_well")),
            safeStr(analysis.get("employee_to_improve")),
            safeStr(analysis.get("missed_opportunities")),
            safeStr(analysis.get("compliance_risks")),
            safeStr(analysis.get("compliance_hits")),
            safeStr(analysis.get("followup_goal")),
            safeStr(analysis.get("suggested_script")),
            toIntFlag(analysis.get("need_manager_involved")),
            toIntScore(analysis.get("need_digging_score")),
            toIntScore(analysis.get("deal_advancing_score")),
            toIntScore(analysis.get("compliance_score")),
            toIntScore(analysis.get("service_score")),
            qualityScore,
            0,
            OffsetDateTime.now().toString(), OffsetDateTime.now().toString()
        );

        jdbc.update("UPDATE meetings SET status = 'done', analysis_status = 'done', quality_score = ? WHERE id = ?", qualityScore, id);

        // ⑥ 完整闭环：经验沉淀 + 跟进任务 + 合规整改 + 店长通知 + 低分告警 + 客户记忆 + 知识缺口
        closeLoop(id, storeId, analysisId, row, analysis, qualityScore);

        // 记录客户互动时间线
        if (row.get("customer_id") != null) {
            customerTimelineService.addInteraction((String) row.get("customer_id"), "meeting_analysis",
                "会谈分析完成，质量分：" + qualityScore + "，摘要：" + safeStr(analysis.get("summary")));
        }

        return Map.of("status", "done");
    }

    // ===================== 分析核心 =====================

    /**
     * 对外分析入口：文本较短直接单次分析；过长则分段提取要素 + 汇总合并。
     */
    private Map<String, Object> analyze(List<String> lines, String scene, String roleHint) {
        String full = String.join("\n", lines);
        if (full.length() <= SINGLE_LIMIT) {
            Map<String, Object> r = doAnalysisCall(full, scene, roleHint, false);
            if (r != null) return r;
            // 调用失败兜底：返回空报告，避免状态卡死
            return emptyReport();
        }

        // ② 分段分析：按 segment 边界切分，每段独立提取要素，最后合并
        List<List<String>> chunks = splitByBoundary(lines, CHUNK_LIMIT);
        List<Map<String, Object>> partials = new ArrayList<>();
        for (var chunk : chunks) {
            Map<String, Object> part = doAnalysisCall(String.join("\n", chunk), scene, roleHint, true);
            if (part != null) partials.add(part);
        }
        if (partials.isEmpty()) return emptyReport();

        Map<String, Object> merged = mergePartials(partials, scene, roleHint);
        return merged != null ? merged : emptyReport();
    }

    /**
     * 单次分析调用（结构化 JSON 输出）。
     * @param isPartial true=分段提取（允许信息不完整），false=完整分析
     */
    private Map<String, Object> doAnalysisCall(String text, String scene, String roleHint, boolean isPartial) {
        String system = buildAnalysisSystem();
        String user = String.format(
            "会谈场景：%s\n%s\n\n转写内容：\n%s\n\n%s",
            scene,
            roleHint,
            text,
            isPartial
                ? "以上仅为会谈的其中一段（可能不完整）。请就这一段内容尽可能提取以下字段；没有相关信息的字段给空字符串或空数组。必须输出 JSON。"
                : "请基于完整会谈内容，输出包含以下所有字段的 JSON 分析报告："
                  + "\nsummary, explicit_needs, implicit_needs, emotional_needs, decision_barriers, "
                  + "employee_did_well, employee_to_improve, missed_opportunities, service_experience_risk, "
                  + "compliance_risks, followup_goal, suggested_followup_at, suggested_script, need_manager_involved, "
                  + "need_digging_score, deal_advancing_score, compliance_score, service_score"
        );

        String aiResult = aiAdapter.callJson(system, user);
        if (aiResult == null) return null;

        // ① 结构化解析：response_format 已保证合法 JSON，仍保留截取兜底
        String jsonStr = extractJson(aiResult);
        try {
            Map<String, Object> m = jsonMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
            if (m != null && !m.isEmpty()) return m;
        } catch (Exception e) {
            log.warn("分析 JSON 解析失败，原样保存: {}", aiResult);
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("raw", aiResult);
        return fallback;
    }

    /**
     * 合并多段提取结果，产出最终综合报告。
     */
    private Map<String, Object> mergePartials(List<Map<String, Object>> partials, String scene, String roleHint) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < partials.size(); i++) {
            sb.append("=== 第 ").append(i + 1).append(" 段提取结果 ===\n");
            try {
                sb.append(jsonMapper.writeValueAsString(partials.get(i)));
            } catch (Exception e) {
                sb.append(partials.get(i).toString());
            }
            sb.append("\n");
        }

        String system = buildAnalysisSystem();
        String user = String.format(
            "会谈场景：%s\n%s\n\n下面是一段会谈被切分后、各段分别提取出的分析要素。请将这些要素去重、合并、归纳，"
            + "产出一份完整的综合报告，必须输出 JSON，字段与单次分析完全一致：\n"
            + "summary, explicit_needs, implicit_needs, emotional_needs, decision_barriers, "
            + "employee_did_well, employee_to_improve, missed_opportunities, service_experience_risk, "
            + "compliance_risks, followup_goal, suggested_followup_at, suggested_script, need_manager_involved, "
            + "need_digging_score, deal_advancing_score, compliance_score, service_score\n\n"
            + "各段要素如下：\n%s",
            scene, roleHint, sb
        );

        String aiResult = aiAdapter.callJson(system, user);
        if (aiResult == null) return null;
        String jsonStr = extractJson(aiResult);
        try {
            Map<String, Object> m = jsonMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
            if (m != null && !m.isEmpty()) return m;
        } catch (Exception e) {
            log.warn("合并 JSON 解析失败: {}", aiResult);
        }
        return null;
    }

    // ===================== 说话人角色映射（③） =====================

    /**
     * 启发式判定说话人角色：提问比例更高者判为员工（销售），其余为客户。
     * 平局时首位发言者为员工。单人对话统一标为客户。
     */
    private Map<String, String> mapSpeakers(List<Map<String, Object>> transcripts) {
        Map<String, String> label = new HashMap<>();
        if (transcripts.isEmpty()) return label;

        LinkedHashMap<String, Integer> qCount = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> sCount = new LinkedHashMap<>();
        String firstSpeaker = null;
        for (var t : transcripts) {
            String sp = (String) t.get("speaker");
            if (firstSpeaker == null) firstSpeaker = sp;
            sCount.merge(sp, 1, Integer::sum);
            qCount.merge(sp, countQuestions((String) t.get("content")), Integer::sum);
        }

        List<String> speakers = new ArrayList<>(sCount.keySet());
        if (speakers.size() == 1) {
            label.put(speakers.get(0), "客户");
            return label;
        }
        if (speakers.size() >= 2) {
            String a = speakers.get(0), b = speakers.get(1);
            double ra = ratio(qCount, sCount, a);
            double rb = ratio(qCount, sCount, b);
            String employee, customer;
            if (ra > rb) { employee = a; customer = b; }
            else if (rb > ra) { employee = b; customer = a; }
            else { employee = firstSpeaker; customer = (firstSpeaker.equals(a) ? b : a); }
            label.put(employee, "员工");
            label.put(customer, "客户");
        }
        return label;
    }

    private double ratio(Map<String, Integer> q, Map<String, Integer> s, String sp) {
        int sc = s.getOrDefault(sp, 0);
        if (sc == 0) return 0;
        return (double) q.getOrDefault(sp, 0) / sc;
    }

    private int countQuestions(String text) {
        if (text == null) return 0;
        int n = 0;
        n += text.replaceAll("[^?？]", "").length();
        for (String w : new String[]{"吗","呢","怎么","什么","多少","为何","为什么","哪","几","可否","是否","行不行","对不对"}) {
            int idx = 0;
            while ((idx = text.indexOf(w, idx)) != -1) { n++; idx += w.length(); }
        }
        return n;
    }

    // ===================== 工具方法 =====================

    private String buildAnalysisSystem() {
        return "你是一个资深的门店销售咨询分析专家，擅长从销售会谈转写中提炼可落地的洞察。"
            + "请基于转写内容，对以下每个字段给出准确、具体、有依据的分析（不要空泛套话）：\n"
            + "summary：一句话概括本次会谈核心结论与走向。\n"
            + "explicit_needs：客户明确说出的需求（如想买什么、预算、规格偏好），用数组列点。\n"
            + "implicit_needs：客户未明说但可合理推断的需求或顾虑。\n"
            + "emotional_needs：客户情绪层面的诉求（如被重视、怕被坑、想省心）。\n"
            + "decision_barriers：阻碍客户成交的具体原因，列点。\n"
            + "employee_did_well：员工表现好的地方，列点。\n"
            + "employee_to_improve：员工可改进的地方，列点（要具体可操作）。\n"
            + "missed_opportunities：错失的销售/跟进机会，列点。\n"
            + "service_experience_risk：影响客户体验的风险点。\n"
            + "compliance_risks：合规或话术风险（如过度承诺、虚假宣传、贬低竞品），无则空数组。\n"
            + "followup_goal：下一步跟进目标（一句话）。\n"
            + "suggested_followup_at：建议跟进时间（如\"3天内\"\"下周\"）。\n"
            + "suggested_script：推荐的跟进话术或下次沟通要点。\n"
            + "need_manager_involved：布尔值，是否需店长介入（存在重大风险或高价值客户时为 true）。\n"
            + "need_digging_score：需求挖掘能力评分（0-100），员工是否充分探询、澄清并确认客户真实需求。\n"
            + "deal_advancing_score：成交推进评分（0-100），会谈是否围绕成交有效推进、临门一脚是否到位。\n"
            + "compliance_score：合规表现评分（0-100），话术是否合规、有无夸大/绝对化/疗效承诺。\n"
            + "service_score：服务体验评分（0-100），客户是否被尊重、沟通是否顺畅舒适。";
    }

    /** 从模型返回中截取 JSON 片段（兜底用，正常情况下 response_format 已保证纯 JSON） */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private Map<String, Object> emptyReport() {
        Map<String, Object> m = new HashMap<>();
        m.put("summary", "");
        m.put("raw", "AI 分析未返回有效结果");
        return m;
    }

    private int toIntFlag(Object val) {
        if (val == null) return 0;
        if (val instanceof Boolean b) return b ? 1 : 0;
        if (val instanceof Number n) return n.intValue() != 0 ? 1 : 0;
        if (val instanceof String s) {
            String ls = s.trim().toLowerCase();
            return ls.equals("true") || ls.equals("是") || ls.equals("1") ? 1 : 0;
        }
        return 0;
    }

    /** 安全提取字符串：null→""，对象→JSON，字符串→原值 */
    private String safeStr(Object val) {
        if (val == null) return "";
        if (val instanceof String s) return s;
        try { return jsonMapper.writeValueAsString(val); }
        catch (Exception e) { return val.toString(); }
    }

    /** 按 segment 边界把行列表切成每段不超过 maxChars 的块（最后一块可能略超） */
    private List<List<String>> splitByBoundary(List<String> lines, int maxChars) {
        List<List<String>> chunks = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int curLen = 0;
        for (String line : lines) {
            if (!cur.isEmpty() && curLen + line.length() > maxChars) {
                chunks.add(cur);
                cur = new ArrayList<>();
                curLen = 0;
            }
            cur.add(line);
            curLen += line.length();
        }
        if (!cur.isEmpty()) chunks.add(cur);
        return chunks;
    }

    // ===================== ④ 合规硬规则合并 =====================

    /** 拼接转写纯文本（不含角色标注），用于词表扫描 */
    private String buildRawText(List<Map<String, Object>> transcripts) {
        StringBuilder sb = new StringBuilder();
        for (var t : transcripts) {
            String c = (String) t.get("content");
            if (c != null && !c.isBlank()) sb.append(c).append("\n");
        }
        return sb.toString();
    }

    /**
     * 把词表命中合并进 AI 的合规风险字段：
     * - 命中即追加【命中合规词表】明细；
     * - 任一 L3/L4 命中 → 强制 need_manager_involved=true（硬规则优先于模型判断）。
     */
    private void mergeCompliance(Map<String, Object> analysis, List<ComplianceScanner.ComplianceHit> hits) {
        StringBuilder sb = new StringBuilder();
        List<Map<String, Object>> hitList = new ArrayList<>();
        boolean forceManager = false;
        for (var h : hits) {
            sb.append("· ").append(h.getWord()).append("（").append(h.getLevelName()).append("·").append(h.getCategory()).append("）：")
              .append(h.getContext()).append("\n");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("word", h.getWord());
            m.put("level", h.getLevel());
            m.put("level_name", h.getLevelName());
            m.put("category", h.getCategory());
            m.put("context", h.getContext());
            hitList.add(m);
            if (h.getLevel() >= 3) forceManager = true;
        }
        String hard = sb.toString().trim();
        String existing = safeStr(analysis.get("compliance_risks"));
        analysis.put("compliance_risks",
            existing.isBlank() ? "【命中合规词表】\n" + hard : existing + "\n\n【命中合规词表】\n" + hard);
        analysis.put("compliance_hits", hitList);
        if (forceManager) analysis.put("need_manager_involved", true);
    }

    // ===================== ⑤ 量化评分 =====================

    /** 取维度分并归一化到 0-100，再按权重算加权总分 */
    private int computeQualityScore(Map<String, Object> analysis) {
        String[] keys = {"need_digging_score", "deal_advancing_score", "compliance_score", "service_score"};
        double sum = 0;
        for (int i = 0; i < keys.length; i++) {
            int s = clampScore(analysis.get(keys[i]));
            analysis.put(keys[i], s);
            sum += s * SCORE_WEIGHTS[i];
        }
        return (int) Math.round(sum);
    }

    private int clampScore(Object v) {
        if (v == null) return 60;
        int n;
        if (v instanceof Number num) n = num.intValue();
        else {
            try { n = Integer.parseInt(String.valueOf(v).replaceAll("[^0-9]", "")); }
            catch (Exception e) { return 60; }
        }
        if (n < 0) n = 0;
        if (n > 100) n = 100;
        return n;
    }

    private int toIntScore(Object v) {
        return clampScore(v);
    }

    // ===================== ⑥ 完整闭环 =====================

    /** 低分会谈告警阈值 */
    private static final int LOW_SCORE_THRESHOLD = 50;

    /**
     * 分析完成后执行完整闭环：
     * - 高分（≥75）：沉淀经验 + 训练任务 + 跟进任务 + 更新客户跟进时间
     * - 合规 L3/L4：生成整改任务 + 通知店长
     * - 店长介入：通知店长
     * - 低分（<50）：强制培训 + 告警
     * - 客户记忆：写入 memory_items
     * - 知识缺口：分析失败时记录
     * 幂等：用 distilled 标志 + source_id 去重，异常不影响主流程。
     */
    private void closeLoop(String meetingId, String storeId, String analysisId,
                           Map<String, Object> row, Map<String, Object> analysis, int qualityScore) {
        try {
            String customerId = (String) row.get("customer_id");
            String employeeId = (String) row.get("employee_id");
            String scene = (String) row.get("scene");
            String customerName = (String) row.get("customer_name");
            String summary = safeStr(analysis.get("summary"));

            // A. 高分会谈：经验沉淀 + 跟进任务
            if (qualityScore >= DISTILL_THRESHOLD) {
                distillExperience(meetingId, storeId, scene, customerName, employeeId, analysis);
                createFollowupTask(storeId, employeeId, customerId, analysis);
                updateCustomerFollowupAt(storeId, customerId, analysis);
            }

            // B. 合规整改：L3/L4 命中生成整改任务
            createComplianceFixTasks(storeId, employeeId, customerName, analysis);

            // C. 店长介入通知
            if (Boolean.TRUE.equals(analysis.get("need_manager_involved"))
                    || toIntFlag(analysis.get("need_manager_involved")) == 1) {
                notifyManager(storeId, employeeId, summary, analysis);
            }

            // D. 低分会谈告警
            if (qualityScore < LOW_SCORE_THRESHOLD) {
                handleLowScore(storeId, employeeId, customerName, summary, analysis);
            }

            // E. 客户记忆写入
            if (customerId != null && !customerId.isBlank()) {
                writeCustomerMemory(storeId, customerId, employeeId, analysisId, analysis, qualityScore);
            }

            // F. 知识缺口记录
            if (summary.isBlank() || analysis.containsKey("raw")) {
                recordKnowledgeGap(storeId, employeeId, scene);
            }

            jdbc.update("UPDATE meeting_analysis SET distilled = 1 WHERE meeting_id = ?", meetingId);
            log.info("闭环完成: meeting={}, quality={}", meetingId, qualityScore);
        } catch (Exception e) {
            log.warn("闭环执行异常（不影响主流程）: {}", e.getMessage());
        }
    }

    // ---------- A. 高分经验沉淀（改为审核任务） ----------

    private void distillExperience(String meetingId, String storeId, String scene,
                                   String customerName, String employeeId, Map<String, Object> analysis) {
        String script = safeStr(analysis.get("suggested_script"));
        String didWell = safeStr(analysis.get("employee_did_well"));
        String summary = safeStr(analysis.get("summary"));

        // 优质话术先提交审核任务，不直接入库
        if ((!script.isBlank() || !didWell.isBlank()) && employeeId != null) {
            String managerId = findManagerId(storeId);
            if (managerId != null) {
                String content = "会谈 ID：" + meetingId + "\n场景：" + scene + "\n摘要：" + summary + "\n\n"
                    + (script.isBlank() ? "" : "【建议话术】\n" + script + "\n\n")
                    + (didWell.isBlank() ? "" : "【值得复制的做法】\n" + didWell)
                    + "\n\n请审核：脱敏、验证结果后决定是否沉淀为门店经验。";
                createTask(storeId, "审核会谈经验：" + scene, content,
                    "experience_review", managerId, employeeId, null);
            }
        }

        // 改进项仍直接生成训练任务
        String improve = safeStr(analysis.get("employee_to_improve"));
        if (!improve.isBlank() && employeeId != null) {
            createTask(storeId, "会谈改进项 · " + (customerName == null ? "客户" : customerName),
                improve, "training", employeeId, employeeId, null);
        }
    }

    // ---------- A. 跟进任务 + 客户跟进时间 ----------

    private void createFollowupTask(String storeId, String employeeId, String customerId, Map<String, Object> analysis) {
        String goal = safeStr(analysis.get("followup_goal"));
        if (goal.isBlank() || employeeId == null) return;

        String script = safeStr(analysis.get("suggested_script"));
        String followupAt = safeStr(analysis.get("suggested_followup_at"));
        OffsetDateTime dueAt = parseFollowupAt(followupAt);

        String content = "跟进目标：" + goal;
        if (!script.isBlank()) content += "\n建议话术：" + script;
        if (!followupAt.isBlank()) content += "\n建议时间：" + followupAt;

        createTask(storeId, "跟进：" + goal, content, "followup", employeeId, employeeId, dueAt);
    }

    private void updateCustomerFollowupAt(String storeId, String customerId, Map<String, Object> analysis) {
        if (customerId == null || customerId.isBlank()) return;
        String followupAt = safeStr(analysis.get("suggested_followup_at"));
        OffsetDateTime dueAt = parseFollowupAt(followupAt);
        if (dueAt == null) return;
        try {
            jdbc.update("UPDATE customers SET next_follow_at = ?, updated_at = NOW() WHERE id = ? AND store_id = ?",
                dueAt.toString(), customerId, storeId);
        } catch (Exception e) {
            log.debug("更新客户跟进时间失败: {}", e.getMessage());
        }
    }

    // ---------- B. 合规整改任务 ----------

    private void createComplianceFixTasks(String storeId, String employeeId, String customerName, Map<String, Object> analysis) {
        Object hitsObj = analysis.get("compliance_hits");
        if (!(hitsObj instanceof List<?> hits) || hits.isEmpty() || employeeId == null) return;

        for (Object h : hits) {
            if (!(h instanceof Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> hit = (Map<String, Object>) raw;
            Object levelObj = hit.get("level");
            int level = levelObj instanceof Number n ? n.intValue() : 0;
            if (level < 3) continue;

            String word = String.valueOf(hit.getOrDefault("word", ""));
            String categoryName = String.valueOf(hit.getOrDefault("category", ""));
            String ctx = String.valueOf(hit.getOrDefault("context", ""));

            String content = "违规词：" + word + "\n等级：" + ComplianceScanner.levelName(level)
                + "\n分类：" + categoryName + "\n上下文：" + ctx
                + "\n客户：" + (customerName == null ? "" : customerName);

            createTask(storeId, "合规整改：" + word, content, "compliance_fix", employeeId, employeeId, null);
        }
    }

    // ---------- C. 店长介入通知 ----------

    private void notifyManager(String storeId, String employeeId, String summary, Map<String, Object> analysis) {
        String complianceRisks = safeStr(analysis.get("compliance_risks"));
        String question = "【会谈风险】" + (summary.isBlank() ? "会谈存在需店长关注的风险" : summary);
        String suggestion = complianceRisks.isBlank() ? "请查看会谈分析报告并介入处理" : complianceRisks;

        int maxLevel = getMaxComplianceLevel(analysis);
        String riskLevel = maxLevel >= 4 ? "L4" : maxLevel >= 3 ? "L3" : "L2";

        createPendingQuestion(storeId, employeeId, question, suggestion, "会谈风险", riskLevel);
    }

    // ---------- D. 低分会谈告警 ----------

    private void handleLowScore(String storeId, String employeeId, String customerName,
                                String summary, Map<String, Object> analysis) {
        String improve = safeStr(analysis.get("employee_to_improve"));
        if (!improve.isBlank() && employeeId != null) {
            createTask(storeId, "低分会谈改进 · " + (customerName == null ? "" : customerName),
                improve, "training", employeeId, employeeId, null);
        }

        String question = "【低分会谈】" + (summary.isBlank() ? "会谈质量分低于50，需关注" : summary);
        createPendingQuestion(storeId, employeeId, question,
            "建议安排一对一辅导或旁听优秀员工会谈", "会谈质量", "L2");
    }

    // ---------- E. 客户记忆写入 ----------

    private void writeCustomerMemory(String storeId, String customerId, String employeeId,
                                     String analysisId, Map<String, Object> analysis, int qualityScore) {
        Integer existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM memory_items WHERE source_type = 'meeting_analysis' AND source_id = ?",
            Integer.class, analysisId);
        if (existing != null && existing > 0) return;

        String confidence = confidenceLevel(qualityScore);
        String now = OffsetDateTime.now().toString();

        String needs = mergeNeeds(analysis);
        if (!needs.isBlank()) {
            insertMemoryItem(storeId, customerId, employeeId, "customer", "needs", needs, confidence, "meeting_analysis", analysisId, now);
            if ("low".equals(confidence)) createMemoryConfirmTask(storeId, customerId, employeeId, "needs", needs, analysisId);
        }

        String concerns = safeStr(analysis.get("decision_barriers"));
        if (!concerns.isBlank()) {
            insertMemoryItem(storeId, customerId, employeeId, "customer", "concerns", concerns, confidence, "meeting_analysis", analysisId, now);
            if ("low".equals(confidence)) createMemoryConfirmTask(storeId, customerId, employeeId, "concerns", concerns, analysisId);
        }

        String emotional = safeStr(analysis.get("emotional_needs"));
        if (!emotional.isBlank()) {
            insertMemoryItem(storeId, customerId, employeeId, "customer", "emotional_needs", emotional, confidence, "meeting_analysis", analysisId, now);
            if ("low".equals(confidence)) createMemoryConfirmTask(storeId, customerId, employeeId, "emotional_needs", emotional, analysisId);
        }
    }

    private void createMemoryConfirmTask(String storeId, String customerId, String employeeId,
                                         String key, String value, String analysisId) {
        createTask(storeId, "确认客户记忆：" + key,
            "会谈分析识别出以下客户记忆，可信度较低，请确认或修正。\n\n"
                + "类型：" + key + "\n"
                + "内容：" + value + "\n"
                + "来源会谈：" + analysisId + "\n\n"
                + "如确认无误可忽略；如需修正请通过客户详情更新。",
            "memory_confirm", employeeId, employeeId, null);
    }

    private void insertMemoryItem(String storeId, String customerId, String employeeId,
                                  String scope, String key, String value, String confidence,
                                  String sourceType, String sourceId, String now) {
        jdbc.update(
            "INSERT INTO memory_items (id, store_id, customer_id, employee_id, scope, `key`, value, confidence, source_type, source_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID().toString().replace("-", ""), storeId, customerId, employeeId,
            scope, key, value, confidence, sourceType, sourceId, now);
    }

    // ---------- F. 知识缺口记录 ----------

    private void recordKnowledgeGap(String storeId, String employeeId, String scene) {
        String gapId = UUID.randomUUID().toString().replace("-", "");
        String question = (scene == null ? "未知场景" : scene) + "会谈分析信息不足，需补充相关培训资料";
        jdbc.update(
            "INSERT INTO knowledge_gaps (id, store_id, employee_id, question, status, created_at) VALUES (?, ?, ?, ?, 'pending', ?)",
            gapId, storeId, employeeId, question, OffsetDateTime.now().toString());

        // 自动生成分配给会谈员工的确认任务
        if (employeeId != null) {
            createTask(storeId, "补充知识缺口：" + scene,
                "知识缺口 ID：" + gapId + "\n场景：" + scene + "\n问题：" + question + "\n请补充答案并决定是否入库。",
                "knowledge_review", employeeId, employeeId, null);
        }
    }

    // ---------- 闭环辅助方法 ----------

    /** 查找门店负责人：先找 owner，再找 manager，最后取任意员工 */
    private String findManagerId(String storeId) {
        try {
            return jdbc.queryForObject(
                "SELECT id FROM employees WHERE store_id = ? AND role = 'owner' AND status = 'active' LIMIT 1",
                String.class, storeId);
        } catch (Exception ignored) {}
        try {
            return jdbc.queryForObject(
                "SELECT id FROM employees WHERE store_id = ? AND role = 'manager' AND status = 'active' LIMIT 1",
                String.class, storeId);
        } catch (Exception ignored) {}
        try {
            return jdbc.queryForObject(
                "SELECT id FROM employees WHERE store_id = ? AND status = 'active' LIMIT 1",
                String.class, storeId);
        } catch (Exception ignored) {}
        return null;
    }

    private void createTask(String storeId, String title, String content, String type,
                            String assignedTo, String createdBy, OffsetDateTime dueAt) {
        jdbc.update(
            "INSERT INTO tasks (id, store_id, title, content, type, status, assigned_to, created_by, due_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'todo', ?, ?, ?, ?, ?)",
            UUID.randomUUID().toString().replace("-", ""), storeId,
            title.length() > 200 ? title.substring(0, 200) : title,
            content, type, assignedTo, createdBy,
            dueAt == null ? null : dueAt.toString(),
            OffsetDateTime.now().toString(), OffsetDateTime.now().toString());
    }

    private void createPendingQuestion(String storeId, String employeeId, String question,
                                       String aiSuggestion, String category, String riskLevel) {
        jdbc.update(
            "INSERT INTO pending_questions (id, store_id, employee_id, question, ai_suggestion, status, category, risk_level, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?)",
            UUID.randomUUID().toString().replace("-", ""), storeId, employeeId,
            question, aiSuggestion, category, riskLevel,
            OffsetDateTime.now().toString(), OffsetDateTime.now().toString());
    }

    private int getMaxComplianceLevel(Map<String, Object> analysis) {
        Object hitsObj = analysis.get("compliance_hits");
        if (!(hitsObj instanceof List<?> hits)) return 0;
        int max = 0;
        for (Object h : hits) {
            if (h instanceof Map<?, ?> hit) {
                Object lv = hit.get("level");
                if (lv instanceof Number n) max = Math.max(max, n.intValue());
            }
        }
        return max;
    }

    /** 将中文时间描述解析为 OffsetDateTime（简易实现，覆盖常见表达） */
    private OffsetDateTime parseFollowupAt(String text) {
        if (text == null || text.isBlank()) return null;
        OffsetDateTime now = OffsetDateTime.now();
        if (text.contains("今天")) return now;
        if (text.contains("明天")) return now.plusDays(1);
        if (text.contains("3天") || text.contains("三天天")) return now.plusDays(3);
        if (text.contains("一周") || text.contains("7天")) return now.plusDays(7);
        if (text.contains("两周") || text.contains("14天")) return now.plusDays(14);
        if (text.contains("下周")) {
            int dayOfWeek = now.getDayOfWeek().getValue();
            return now.plusDays(8 - dayOfWeek);
        }
        if (text.contains("本月") || text.contains("月底")) {
            return now.withDayOfMonth(now.toLocalDate().lengthOfMonth());
        }
        // 提取数字天数
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*天").matcher(text);
        if (m.find()) {
            try { return now.plusDays(Long.parseLong(m.group(1))); } catch (Exception ignored) {}
        }
        return now.plusDays(3);
    }

    private String confidenceLevel(int qualityScore) {
        if (qualityScore >= 75) return "high";
        if (qualityScore >= 50) return "medium";
        return "low";
    }

    private String mergeNeeds(Map<String, Object> analysis) {
        String explicit = safeStr(analysis.get("explicit_needs"));
        String implicit = safeStr(analysis.get("implicit_needs"));
        if (explicit.isBlank() && implicit.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        if (!explicit.isBlank()) sb.append(explicit);
        if (!implicit.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append(implicit);
        }
        return sb.toString();
    }
}
