package com.storeai.notification.service;

import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 门店通知公告管理：创建、列表、停用。
 * 面向员工展示的通知；管理入口由老板/店长创建与维护。
 */
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;

    /** 门店可见公告（当前角色在 visible_roles 内或为空）。 */
    public List<Map<String, Object>> list() {
        String storeId = cur.storeId();
        String role = cur.role();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT a.*, e.name AS created_by_name
            FROM announcements a
            LEFT JOIN employees e ON e.id = a.created_by
            WHERE a.store_id = ? AND a.status = 'active'
            ORDER BY a.created_at DESC
            """, storeId);
        // 前端展示用：按 visible_roles 过滤（空视为全员可见）
        return rows.stream()
            .filter(row -> rolesVisible(row.get("visible_roles"), role))
            .toList();
    }

    /** 管理角色创建公告。 */
    public Map<String, Object> create(CreateInput input) {
        if (!cur.isAdmin()) throw BizException.forbidden("仅老板、店长或管理员可发布通知");
        String title = input.title() == null ? "" : input.title().trim();
        String content = input.content() == null ? "" : input.content().trim();
        if (title.isBlank()) throw BizException.badRequest("请填写通知标题");
        if (content.isBlank()) throw BizException.badRequest("请填写通知内容");
        String id = UUID.randomUUID().toString().replace("-", "");
        String now = java.time.OffsetDateTime.now().toString();
        jdbc.update("""
            INSERT INTO announcements
              (id, store_id, title, content, type, priority, visible_roles, status, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'active', ?, ?)
            """, id, cur.storeId(), title, content,
            input.type() == null ? "notice" : input.type(),
            input.priority() == null ? "normal" : input.priority(),
            toJsonArray(input.visibleRoles()),
            cur.employeeId(), now);
        return jdbc.queryForMap("SELECT * FROM announcements WHERE id = ?", id);
    }

    /** 管理角色停用公告。 */
    public void deactivate(String id) {
        if (!cur.isAdmin()) throw BizException.forbidden();
        Integer cnt = jdbc.queryForObject(
            "SELECT COUNT(*) FROM announcements WHERE id = ? AND store_id = ?",
            Integer.class, id, cur.storeId());
        if (cnt == null || cnt == 0) throw BizException.notFound("通知");
        jdbc.update("UPDATE announcements SET status = 'inactive' WHERE id = ?", id);
    }

    private boolean rolesVisible(Object raw, String role) {
        if (raw == null) return true;
        String json = String.valueOf(raw).trim();
        if (json.isBlank() || "[]".equals(json) || "null".equalsIgnoreCase(json)) return true;
        return json.contains("\"" + role + "\"");
    }

    private String toJsonArray(List<String> roles) {
        if (roles == null || roles.isEmpty()) return null;
        return "[" + roles.stream().map(r -> "\"" + r + "\"").collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    public record CreateInput(String title, String content, String type, String priority, List<String> visibleRoles) {}
}
