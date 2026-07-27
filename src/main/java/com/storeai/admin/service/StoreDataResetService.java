package com.storeai.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 演示环境切换到真实数据时的安全清理服务。
 *
 * 只处理当前门店的经营数据，始终保留账号、员工、角色、门店配置、知识库和原始文件。
 * 清理操作会在删除前强制生成一份本地 JSON 备份，避免不可逆的误操作。
 */
@Service
@RequiredArgsConstructor
public class StoreDataResetService {

    public static final String CONFIRMATION_PHRASE = "清空本店经营数据";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CurrentUser cur;

    private enum Scope { STORE, MEETING }

    private record Dataset(String table, String label, Scope scope) {}

    // 顺序同时用于备份展示和清理；清理时先删除依赖会议/客户的明细表。
    private static final List<Dataset> DATASETS = List.of(
        new Dataset("meeting_access_logs", "会谈访问记录", Scope.MEETING),
        new Dataset("audio_files", "录音文件记录", Scope.MEETING),
        new Dataset("meeting_consents", "会谈知情同意", Scope.MEETING),
        new Dataset("meeting_analysis", "会谈分析", Scope.MEETING),
        new Dataset("meeting_transcripts", "会谈原始转写", Scope.MEETING),
        new Dataset("meetings", "会谈", Scope.STORE),
        new Dataset("interactions", "客户互动", Scope.STORE),
        new Dataset("memory_items", "客户记忆", Scope.STORE),
        new Dataset("opportunities", "经营机会", Scope.STORE),
        new Dataset("customer_feedback", "客户反馈", Scope.STORE),
        new Dataset("consultation_records", "咨询记录", Scope.STORE),
        new Dataset("followups", "跟进动作", Scope.STORE),
        new Dataset("customers", "客户档案", Scope.STORE),
        new Dataset("risk_logs", "风险记录", Scope.STORE),
        new Dataset("chat_messages", "AI 对话消息", Scope.STORE),
        new Dataset("chat_sessions", "AI 对话会话", Scope.STORE),
        new Dataset("pending_questions", "待确认问题", Scope.STORE),
        new Dataset("knowledge_gaps", "知识缺口", Scope.STORE),
        new Dataset("tasks", "任务", Scope.STORE),
        new Dataset("reports", "经营报告", Scope.STORE),
        new Dataset("activities", "门店活动", Scope.STORE)
    );

    public record Preview(Map<String, Integer> counts, int totalRows, String confirmationPhrase,
                          List<String> preservedData, String backupLocation) {}

    public record Backup(String fileName, String createdAt, int totalRows, Map<String, Integer> counts,
                         String backupLocation) {}

    public record ClearResult(Map<String, Integer> removed, int totalRows, Backup backup,
                              List<String> preservedData) {}

    public Preview preview() {
        requireOwner();
        Map<String, Integer> counts = countDatasets();
        return new Preview(
            counts,
            total(counts),
            CONFIRMATION_PHRASE,
            preservedData(),
            backupLocation()
        );
    }

    /** 仅生成备份，不删除任何数据。 */
    public Backup backup() {
        requireOwner();
        return writeBackup(countDatasets());
    }

    /**
     * 先备份再清理，整个数据库删除部分放在同一事务中。
     * 即使调用方跳过了“手动备份”，此处也不会在没有新备份的前提下删除数据。
     */
    @Transactional
    public ClearResult clear(String confirmation) {
        requireOwner();
        if (!CONFIRMATION_PHRASE.equals(confirmation == null ? "" : confirmation.trim())) {
            throw BizException.badRequest("请完整输入“" + CONFIRMATION_PHRASE + "”后再执行");
        }

        Map<String, Integer> countsBefore = countDatasets();
        Backup backup = writeBackup(countsBefore);
        Map<String, Integer> removed = new LinkedHashMap<>();
        for (Dataset dataset : DATASETS) {
            if (!isQueryable(dataset)) continue;
            int deleted = deleteDataset(dataset);
            if (deleted > 0) removed.put(dataset.label(), deleted);
        }
        return new ClearResult(removed, total(removed), backup, preservedData());
    }

    private void requireOwner() {
        if (!cur.isOwner()) throw BizException.forbidden("仅老板可执行数据备份或清理");
    }

    private Map<String, Integer> countDatasets() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Dataset dataset : DATASETS) {
            if (!isQueryable(dataset)) continue;
            int count = countDataset(dataset);
            if (count > 0) counts.put(dataset.label(), count);
        }
        return counts;
    }

    private int countDataset(Dataset dataset) {
        String sql = dataset.scope() == Scope.STORE
            ? "SELECT COUNT(*) FROM " + dataset.table() + " WHERE store_id = ?"
            : "SELECT COUNT(*) FROM " + dataset.table() + " d JOIN meetings m ON d.meeting_id = m.id WHERE m.store_id = ?";
        Integer count = jdbc.queryForObject(sql, Integer.class, cur.storeId());
        return count == null ? 0 : count;
    }

    private int deleteDataset(Dataset dataset) {
        String sql = dataset.scope() == Scope.STORE
            ? "DELETE FROM " + dataset.table() + " WHERE store_id = ?"
            : "DELETE d FROM " + dataset.table() + " d JOIN meetings m ON d.meeting_id = m.id WHERE m.store_id = ?";
        return jdbc.update(sql, cur.storeId());
    }

    private boolean isQueryable(Dataset dataset) {
        if (!tableExists(dataset.table())) return false;
        return dataset.scope() == Scope.STORE
            ? columnExists(dataset.table(), "store_id")
            : tableExists("meetings") && columnExists(dataset.table(), "meeting_id");
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
            Integer.class,
            table
        );
        return count != null && count > 0;
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
            Integer.class,
            table,
            column
        );
        return count != null && count > 0;
    }

    private Backup writeBackup(Map<String, Integer> counts) {
        try {
            Path directory = Path.of(System.getProperty("user.home"), "Documents", "门店AI助手备份");
            Files.createDirectories(directory);
            String stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String safeStoreId = cur.storeId().replaceAll("[^a-zA-Z0-9_-]", "_");
            String fileName = "store-ai-" + safeStoreId + "-" + stamp + "-" + UUID.randomUUID().toString().substring(0, 8) + ".json";
            Path file = directory.resolve(fileName).normalize();
            if (!file.startsWith(directory)) throw new IllegalStateException("备份路径异常");

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("format", "store-ai-business-data-backup-v1");
            snapshot.put("createdAt", OffsetDateTime.now().toString());
            snapshot.put("storeId", cur.storeId());
            snapshot.put("createdByEmployeeId", cur.employeeId());
            snapshot.put("counts", counts);
            Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
            for (Dataset dataset : DATASETS) {
                if (!isQueryable(dataset)) continue;
                data.put(dataset.table(), readDataset(dataset));
            }
            snapshot.put("data", data);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), snapshot);

            return new Backup(fileName, OffsetDateTime.now().toString(), total(counts), counts, backupLocation());
        } catch (Exception e) {
            throw new BizException("创建本地备份失败，已取消清理：" + e.getMessage());
        }
    }

    private List<Map<String, Object>> readDataset(Dataset dataset) {
        String sql = dataset.scope() == Scope.STORE
            ? "SELECT * FROM " + dataset.table() + " WHERE store_id = ?"
            : "SELECT d.* FROM " + dataset.table() + " d JOIN meetings m ON d.meeting_id = m.id WHERE m.store_id = ?";
        return jdbc.queryForList(sql, cur.storeId());
    }

    private int total(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private List<String> preservedData() {
        return List.of("账号与员工", "角色与权限", "门店自定义配置", "知识库文档与原始文件", "方法论库", "原始录音对象文件");
    }

    private String backupLocation() {
        return "本机“文稿/门店AI助手备份”文件夹";
    }
}
