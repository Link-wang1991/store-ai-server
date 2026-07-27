package com.storeai.common.service;

import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 门店自定义配置的专用读写服务。
 *
 * 配置项是业务字典，不应再通过通用 Proxy CRUD 写入：后者既容易吞掉数据库错误，
 * 也不能保证「整类配置」替换时的排序和门店范围一致。
 */
@Service
@RequiredArgsConstructor
public class StoreConfigService {

    private static final Set<String> EDITABLE_CATEGORIES = Set.of(
        "role", "duty", "workbench", "knowledge", "pool", "stage", "alert",
        "followup", "scene", "tag", "project_cat", "sop_cat", "script_cat"
    );

    private final JdbcTemplate jdbc;
    private final CurrentUser cur;

    /** 所有已登录用户都可读取本店显示字典；写入由 replaceCategory 严格限制为管理角色。 */
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
            SELECT id, store_id, category, code, display_name, enabled, visible_to_staff, sort_order, created_at
            FROM store_config
            WHERE store_id = ?
            ORDER BY category ASC, sort_order ASC, created_at ASC
            """, cur.storeId());
    }

    /**
     * 原子地替换一类配置。前端每一次操作都传回当前整类列表，因此服务端统一重建
     * 排序，避免新增、改名、上下移动分别走不同的半成品写入链路。
     */
    @Transactional
    public List<Map<String, Object>> replaceCategory(String category, List<ConfigItem> requestedItems) {
        if (!cur.isAdmin()) throw BizException.forbidden("仅老板、店长或管理员可修改自定义配置");

        String normalizedCategory = normalizeCategory(category);
        List<ConfigItem> items = requestedItems == null ? List.of() : requestedItems;
        List<ConfigItem> normalizedItems = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();

        for (ConfigItem item : items) {
            String code = item == null ? "" : trim(item.code());
            String displayName = item == null ? "" : trim(item.displayName());
            if (!code.matches("[A-Za-z0-9_-]{1,120}")) {
                throw BizException.badRequest("分类 code 格式不正确");
            }
            if (displayName.isBlank() || displayName.length() > 200) {
                throw BizException.badRequest("分类名称不能为空且不能超过 200 个字符");
            }
            if (!seenCodes.add(code)) {
                throw BizException.badRequest("同一分类中不能有重复 code");
            }
            normalizedItems.add(new ConfigItem(code, displayName,
                item.enabled() == null || item.enabled(),
                item.visibleToStaff() == null || item.visibleToStaff()));
        }

        jdbc.update("DELETE FROM store_config WHERE store_id = ? AND category = ?", cur.storeId(), normalizedCategory);
        for (int index = 0; index < normalizedItems.size(); index++) {
            ConfigItem item = normalizedItems.get(index);
            jdbc.update("""
                INSERT INTO store_config
                  (id, store_id, category, code, display_name, enabled, visible_to_staff, sort_order, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                """,
                UUID.randomUUID().toString().replace("-", ""),
                cur.storeId(), normalizedCategory, item.code(), item.displayName(),
                item.enabled(), item.visibleToStaff(), index);
        }

        return jdbc.queryForList("""
            SELECT id, store_id, category, code, display_name, enabled, visible_to_staff, sort_order, created_at
            FROM store_config
            WHERE store_id = ? AND category = ?
            ORDER BY sort_order ASC, created_at ASC
            """, cur.storeId(), normalizedCategory);
    }

    private String normalizeCategory(String category) {
        String normalized = trim(category);
        if (!EDITABLE_CATEGORIES.contains(normalized)) {
            throw BizException.badRequest("不支持修改该配置分类");
        }
        return normalized;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record ConfigItem(String code, String displayName, Boolean enabled, Boolean visibleToStaff) {}
}
