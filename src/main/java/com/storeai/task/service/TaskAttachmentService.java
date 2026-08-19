package com.storeai.task.service;

import com.storeai.common.exception.BizException;
import com.storeai.common.service.StorageService;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务证据附件：员工执行任务时上传完成凭证/证据。
 * 文件二进制保存在受控存储（MinIO 私有桶或本地目录），数据库只记录元数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskAttachmentService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;
    private final StorageService storage;

    @Value("${storage.provider:local}")
    private String storageProvider;

    @Value("${storage.task-attachment-local-path:./uploads/task-attachments}")
    private String localPath;

    private static final long MAX_BYTES = 20L * 1024 * 1024; // 20MB

    /** 上传附件到指定任务。返回附件记录。 */
    @Transactional
    public Map<String, Object> upload(String taskId, MultipartFile file) {
        requireTaskOwner(taskId);
        if (file == null || file.isEmpty()) throw BizException.badRequest("没有附件文件");
        if (file.getSize() > MAX_BYTES) throw BizException.badRequest("附件超过 20MB 限制");

        String originalName = sanitizeFileName(file.getOriginalFilename());
        String key = cur.storeId() + "/" + UUID.randomUUID() + "_" + originalName;
        String fileUrl = saveFile(file, key);
        if (fileUrl == null) throw BizException.badRequest("附件保存失败");

        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
            INSERT INTO task_attachments (id, store_id, task_id, uploader_id, original_name, file_url, mime_type, size_bytes, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, cur.storeId(), taskId, cur.employeeId(), originalName, fileUrl,
            file.getContentType(), file.getSize(), OffsetDateTime.now().toString());

        jdbc.update("""
            UPDATE tasks SET has_attachments = 1, updated_at = NOW() WHERE id = ? AND store_id = ?
            """, taskId, cur.storeId());

        return Map.of("id", id, "task_id", taskId, "original_name", originalName,
            "size_bytes", file.getSize(), "mime_type", file.getContentType() == null ? "" : file.getContentType());
    }

    /** 列出任务附件元数据。 */
    public List<Map<String, Object>> list(String taskId) {
        requireTaskOwner(taskId);
        return jdbc.queryForList("""
            SELECT id, task_id, uploader_id, original_name, mime_type, size_bytes, created_at
            FROM task_attachments WHERE store_id = ? AND task_id = ? ORDER BY created_at DESC
            """, cur.storeId(), taskId);
    }

    /** 附件原始文件名（用于下载时设置 Content-Disposition）。 */
    public String originalName(String taskId, String attachmentId) {
        Map<String, Object> row = findAttachment(taskId, attachmentId);
        Object name = row.get("original_name");
        return name == null ? "attachment" : String.valueOf(name);
    }

    /** 受控读取附件本地文件（local provider）。 */
    public Path openLocal(String taskId, String attachmentId) {
        Map<String, Object> row = findAttachment(taskId, attachmentId);
        String fileUrl = (String) row.get("file_url");
        if (fileUrl == null || !fileUrl.startsWith("local://")) throw BizException.notFound("本地附件");
        Path base = Path.of(localPath).toAbsolutePath().normalize();
        Path target = base.resolve(fileUrl.substring("local://".length())).normalize();
        if (!target.startsWith(base) || !Files.isRegularFile(target)) throw BizException.notFound("附件");
        return target;
    }

    /** 受控读取附件 MinIO 流（minio provider）。 */
    public InputStream openMinio(String taskId, String attachmentId) {
        Map<String, Object> row = findAttachment(taskId, attachmentId);
        String fileUrl = (String) row.get("file_url");
        if (fileUrl == null || fileUrl.startsWith("local://")) throw BizException.notFound("云端附件");
        return storage.openMeetingAudio(fileUrl);
    }

    public boolean isLocalAttachment(String taskId, String attachmentId) {
        String fileUrl = (String) findAttachment(taskId, attachmentId).get("file_url");
        return fileUrl != null && fileUrl.startsWith("local://");
    }

    /** 删除附件：删记录 + 尝试删文件。仅负责人或管理员。 */
    @Transactional
    public void delete(String taskId, String attachmentId) {
        requireTaskOwner(taskId);
        Map<String, Object> row = findAttachment(taskId, attachmentId);
        int n = jdbc.update("""
            DELETE FROM task_attachments WHERE id = ? AND task_id = ? AND store_id = ?
            """, attachmentId, taskId, cur.storeId());
        if (n == 0) throw BizException.notFound("附件");
        // 删除后若该任务已无附件，重置标记
        Integer remain = jdbc.queryForObject(
            "SELECT COUNT(*) FROM task_attachments WHERE task_id = ? AND store_id = ?",
            Integer.class, taskId, cur.storeId());
        if (remain != null && remain == 0) {
            jdbc.update("UPDATE tasks SET has_attachments = 0, updated_at = NOW() WHERE id = ? AND store_id = ?",
                taskId, cur.storeId());
        }
        // 删除文件（best-effort，不因文件删除失败而回滚记录删除）
        try {
            String fileUrl = (String) row.get("file_url");
            if (fileUrl != null && fileUrl.startsWith("local://")) {
                Path base = Path.of(localPath).toAbsolutePath().normalize();
                Path target = base.resolve(fileUrl.substring("local://".length())).normalize();
                Files.deleteIfExists(target);
            }
        } catch (Exception e) {
            log.warn("删除附件文件失败: {}", e.getMessage());
        }
    }

    private Map<String, Object> findAttachment(String taskId, String attachmentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT * FROM task_attachments WHERE id = ? AND task_id = ? AND store_id = ?
            """, attachmentId, taskId, cur.storeId());
        if (rows.isEmpty()) throw BizException.notFound("附件");
        return rows.get(0);
    }

    /** 校验当前用户是该任务负责人或管理员。 */
    private void requireTaskOwner(String taskId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM tasks WHERE id = ? AND store_id = ?
            """, Integer.class, taskId, cur.storeId());
        if (count == null || count == 0) throw BizException.notFound("任务");
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT assigned_to FROM tasks WHERE id = ? AND store_id = ?", taskId, cur.storeId());
        String assignedTo = rows.isEmpty() ? null : (String) rows.get(0).get("assigned_to");
        if (!cur.isAdmin() && !cur.employeeId().equals(assignedTo)) {
            throw BizException.forbidden("只有任务负责人可以操作附件");
        }
    }

    private String saveFile(MultipartFile file, String key) {
        if ("minio".equalsIgnoreCase(storageProvider)) {
            try (InputStream input = file.getInputStream()) {
                return storage.saveMeetingAudio(key, input, file.getSize());
            } catch (Exception e) {
                log.warn("MinIO 附件存储不可用，改为本地保存: {}", e.getMessage());
            }
        }
        try (InputStream input = file.getInputStream()) {
            Path base = Path.of(localPath).toAbsolutePath().normalize();
            Path target = base.resolve(key).normalize();
            if (!target.startsWith(base)) throw new IllegalStateException("非法文件路径");
            Files.createDirectories(target.getParent());
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            return "local://" + key;
        } catch (Exception e) {
            log.warn("本地附件保存失败: {}", e.getMessage());
            return null;
        }
    }

    private String sanitizeFileName(String name) {
        String safe = name == null ? "attachment" : Path.of(name).getFileName().toString();
        safe = safe.replaceAll("[^a-zA-Z0-9._\\-一-龥]", "_");
        return safe.isBlank() ? "attachment" : safe;
    }
}
