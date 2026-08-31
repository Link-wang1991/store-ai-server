package com.storeai.common.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 通用数据代理服务 —— 动态 SQL 执行。
 * JdbcTemplate 直接执行安全受限的 SQL，避免为每张表写单独 Mapper。
 */
@Service
public class ProxyService {

    private final JdbcTemplate jdbc;
    private final TableSchema schema;
    private final ObjectMapper objectMapper;

    public ProxyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.schema = new TableSchema();
        this.objectMapper = objectMapper;
    }

    // ================================================================
    // 列表查询
    // ================================================================
    public List<Map<String, Object>> query(String table, String select, String id,
                                           int limit, int offset, String order, String dir,
                                           Map<String, String> filters, CurrentUser cur) {
        String tbl = schema.validateTable(table);
        List<String> fields = parseSelect(select, tbl);
        boolean hasStoreId = schema.hasStoreId(tbl);

        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(String.join(", ", fields));
        sql.append(" FROM ").append(tbl);
        sql.append(" WHERE 1=1");

        List<Object> params = new ArrayList<>();

        // 自动注入 store_id
        if (hasStoreId && cur != null) {
            sql.append(" AND store_id = ?");
            params.add(effectiveStoreId(cur));
        }
        if ("users".equals(tbl) && cur != null) {
            sql.append(" AND id = ?");
            params.add(cur.userId());
        }
        if ("stores".equals(tbl) && cur != null) {
            sql.append(" AND id = ?");
            params.add(cur.storeId());
        }

        // 按 id 查询
        if (id != null && !id.isEmpty()) {
            sql.append(" AND id = ?");
            params.add(id);
        }

        // 自定义 filter：field=op.value（如前端的 eq.xxx/neq.yyy）
        if (filters != null) {
            for (var entry : filters.entrySet()) {
                String field = entry.getKey();
                String val = entry.getValue();
                if (!schema.isValidColumn(table, field)) continue;
                int dot = val.indexOf('.');
                if (dot < 0) continue;
                String op = val.substring(0, dot);
                String value = val.substring(dot + 1);
                switch (op) {
                    case "eq" -> { sql.append(" AND ").append(field).append(" = ?"); params.add(value); }
                    case "neq" -> { sql.append(" AND ").append(field).append(" != ?"); params.add(value); }
                    case "like" -> { sql.append(" AND ").append(field).append(" LIKE ?"); params.add("%" + value + "%"); }
                    case "gte" -> { sql.append(" AND ").append(field).append(" >= ?"); params.add(value); }
                    case "lte" -> { sql.append(" AND ").append(field).append(" <= ?"); params.add(value); }
                }
            }
        }

        // 排序
        if (order != null && !order.isEmpty() && schema.isValidColumn(tbl, order)) {
            String d = "desc".equalsIgnoreCase(dir) ? "DESC" : "ASC";
            sql.append(" ORDER BY ").append(order).append(" ").append(d);
        }

        // 分页（防止任意大查询占满连接池）
        sql.append(" LIMIT ? OFFSET ?");
        params.add(Math.max(1, Math.min(limit, 500)));
        params.add(Math.max(0, offset));

        return jdbc.query(sql.toString(), params.toArray(), new ColumnMapRowMapper())
                .stream().map(row -> redactSensitiveFields(tbl, row)).toList();
    }

    // ================================================================
    // 按 ID 查询单条
    // ================================================================
    public Map<String, Object> getById(String table, String id, CurrentUser cur) {
        String tbl = schema.validateTable(table);
        boolean hasStoreId = schema.hasStoreId(tbl);
        if (cur != null && "users".equals(tbl) && !cur.userId().equals(id)) return null;
        if (cur != null && "stores".equals(tbl) && !cur.storeId().equals(id)) return null;

        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(tbl).append(" WHERE id = ?");
        List<Object> params = new ArrayList<>();
        params.add(id);

        if (hasStoreId && cur != null) {
            sql.append(" AND store_id = ?");
            params.add(effectiveStoreId(cur));
        }

        var rows = jdbc.query(sql.toString(), params.toArray(), new ColumnMapRowMapper());
        return rows.isEmpty() ? null : redactSensitiveFields(tbl, rows.get(0));
    }

    // ================================================================
    // 插入
    // ================================================================
    public Map<String, Object> insert(String table, Map<String, Object> data, CurrentUser cur) {
        String tbl = schema.validateTable(table);
        boolean hasStoreId = schema.hasStoreId(tbl);
        if ("users".equals(tbl)) throw BizException.forbidden("用户由认证接口管理");
        Map<String, Object> safeData = writableData(tbl, data, true);

        // 调用方不能伪造所属门店。
        if (hasStoreId && cur != null) {
            safeData.put("store_id", effectiveStoreId(cur));
        }

        // 生成 id（若无）
        if (!safeData.containsKey("id")) {
            safeData.put("id", UUID.randomUUID().toString().replace("-", ""));
        }

        // 时间戳
        String now = new java.sql.Timestamp(System.currentTimeMillis()).toString();
        if (!safeData.containsKey("created_at")) safeData.put("created_at", now);
        if (schema.hasUpdatedAt(tbl) && !safeData.containsKey("updated_at")) safeData.put("updated_at", now);

        // 构建 INSERT
        var keys = new ArrayList<>(safeData.keySet());
        String cols = String.join(", ", keys);
        String vals = keys.stream().map(k -> "?").collect(Collectors.joining(", "));
        var params = keys.stream().map(key -> writeParameter(tbl, key, safeData.get(key))).collect(Collectors.toList());

        jdbc.update("INSERT INTO " + tbl + " (" + cols + ") VALUES (" + vals + ")", params.toArray());

        // 返回插入的行
        Map<String, Object> result = new HashMap<>(safeData);
        return result;
    }

    // ================================================================
    // 更新
    // ================================================================
    public void update(String table, String id, Map<String, Object> data, CurrentUser cur) {
        String tbl = schema.validateTable(table);
        boolean hasStoreId = schema.hasStoreId(tbl);
        if ("users".equals(tbl)) throw BizException.forbidden("用户由认证接口管理");
        if ("stores".equals(tbl) && cur != null && !cur.storeId().equals(id)) throw BizException.forbidden();
        Map<String, Object> safeData = writableData(tbl, data, false);
        if (safeData.isEmpty()) throw BizException.badRequest("没有可更新的字段");

        // 时间戳
        if (schema.hasUpdatedAt(tbl)) {
            safeData.put("updated_at", new java.sql.Timestamp(System.currentTimeMillis()).toString());
        }

        var keys = new ArrayList<>(safeData.keySet());
        String sets = keys.stream().map(k -> k + " = ?").collect(Collectors.joining(", "));
        var params = keys.stream().map(key -> writeParameter(tbl, key, safeData.get(key))).collect(Collectors.toList());

        StringBuilder sql = new StringBuilder("UPDATE " + tbl + " SET " + sets + " WHERE id = ?");
        params.add(id);

        if (hasStoreId && cur != null) {
            sql.append(" AND store_id = ?");
            params.add(effectiveStoreId(cur));
        }

        int n = jdbc.update(sql.toString(), params.toArray());
        if (n == 0) throw BizException.notFound("记录");
    }

    // ================================================================
    // 删除
    // ================================================================
    public void delete(String table, String id, CurrentUser cur) {
        String tbl = schema.validateTable(table);
        boolean hasStoreId = schema.hasStoreId(tbl);
        if ("users".equals(tbl) || "stores".equals(tbl)) throw BizException.forbidden("该记录不允许通过数据代理删除");

        StringBuilder sql = new StringBuilder("DELETE FROM " + tbl + " WHERE id = ?");
        List<Object> params = new ArrayList<>();
        params.add(id);

        if (hasStoreId && cur != null) {
            sql.append(" AND store_id = ?");
            params.add(effectiveStoreId(cur));
        }

        int n = jdbc.update(sql.toString(), params.toArray());
        if (n == 0) throw BizException.notFound("记录");
    }

    // ================================================================
    // 门店切换（X-Store-Id）
    // ================================================================
    private static final String STORE_SWITCH_HEADER = "X-Store-Id";

    /**
     * 解析本次请求实际生效的门店 ID。
     * 默认使用登录门店；若请求头带 X-Store-Id 且当前用户有权访问该门店
     * （超管任意，普通用户须在该门店有在职 Employee 记录），则使用目标门店。
     */
    private String effectiveStoreId(CurrentUser cur) {
        if (cur == null) return null;
        String target = currentRequestStoreId();
        if (target == null || target.isBlank() || target.equals(cur.storeId())) {
            return cur.storeId();
        }
        if (cur.isSuperAdmin()) {
            return target;
        }
        if (canAccessStore(cur.userId(), target)) {
            return target;
        }
        return cur.storeId();
    }

    private String currentRequestStoreId() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            String v = sra.getRequest().getHeader(STORE_SWITCH_HEADER);
            return v == null ? null : v.trim();
        }
        return null;
    }

    private boolean canAccessStore(String userId, String storeId) {
        if (userId == null || storeId == null) return false;
        try {
            Integer n = jdbc.queryForObject(
                "SELECT 1 FROM employees WHERE user_id = ? AND store_id = ? AND status = 'active' LIMIT 1",
                Integer.class, userId, storeId);
            return n != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================
    // 辅助
    // ================================================================
    private List<String> parseSelect(String select, String table) {
        if ("*".equals(select.trim())) return List.of("*");
        List<String> fields = Arrays.stream(select.split(","))
                .map(String::trim)
                .filter(f -> schema.isValidColumn(table, f))
                .collect(Collectors.toList());
        if (fields.isEmpty()) throw BizException.badRequest("没有可查询的字段");
        return fields;
    }

    private Map<String, Object> writableData(String table, Map<String, Object> data, boolean inserting) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (data == null) return safe;
        data.forEach((key, value) -> {
            if (!schema.isValidColumn(table, key)) return;
            if (!inserting && ("id".equals(key) || "store_id".equals(key) || "created_at".equals(key))) return;
            safe.put(key, value);
        });
        return safe;
    }

    /**
     * 浏览器提交的 JSON 数组/对象会被 Jackson 还原为 List/Map。若把它们直接交给
     * MySQL JDBC，驱动会按 binary 对象绑定，写入 JSON 列时便会报
     * “Cannot create a JSON value from a string with CHARACTER SET 'binary'”。
     *
     * 统一序列化为 JSON 文本并显式按 VARCHAR 绑定，既适用于 JSON 列，也让 TEXT
     * 类型的 tags 等字段获得稳定、可读的存储格式。
     */
    private Object writeParameter(String table, String column, Object value) {
        if (value == null) return null;
        if (value instanceof Collection<?> || value instanceof Map<?, ?> || value.getClass().isArray()) {
            try {
                return new SqlParameterValue(Types.VARCHAR, objectMapper.writeValueAsString(value));
            } catch (JsonProcessingException e) {
                throw BizException.badRequest("字段 " + column + " 无法转换为 JSON");
            }
        }
        // 已经是 JSON 文本的值也明确按字符集写入，避免 MySQL 把 PreparedStatement
        // 未指定类型的参数推断为 binary。
        if (isJsonColumn(table, column) && value instanceof String text) {
            return new SqlParameterValue(Types.VARCHAR, text);
        }
        // 浏览器和 Next Server Action 会自然产生 ISO 8601 字符串
        // （2026-07-27T03:57:00.251Z）。MySQL DATETIME 不接受该文本格式，
        // 必须作为 TIMESTAMP 参数传入；否则客户导入会在第一个 AI 建议时间处失败。
        if (isDateTimeColumn(column) && value instanceof String text && text.contains("T")) {
            Timestamp timestamp = parseIsoTimestamp(text);
            if (timestamp != null) return new SqlParameterValue(Types.TIMESTAMP, timestamp);
        }
        return value;
    }

    private boolean isDateTimeColumn(String column) {
        return "created_at".equals(column) || "updated_at".equals(column) || column.endsWith("_at");
    }

    private Timestamp parseIsoTimestamp(String text) {
        try {
            return Timestamp.from(Instant.parse(text));
        } catch (DateTimeParseException ignored) {
            // 带 +08:00 等 offset 的 ISO 字符串。
            try {
                return Timestamp.from(OffsetDateTime.parse(text).toInstant());
            } catch (DateTimeParseException ignoredAgain) {
                // 兼容没有时区、但仍使用 T 的本地 ISO 字符串。
                try {
                    return Timestamp.valueOf(LocalDateTime.parse(text));
                } catch (DateTimeParseException ignoredLocal) {
                    return null;
                }
            }
        }
    }

    private boolean isJsonColumn(String table, String column) {
        return switch (table) {
            case "roles" -> "permissions".equals(column);
            case "knowledge_documents" -> "visible_roles".equals(column);
            case "chat_messages" -> "retrieved_chunks".equals(column);
            case "customers" -> "portrait".equals(column);
            case "announcements" -> "visible_roles".equals(column) || "target_employees".equals(column);
            case "reports" -> "content".equals(column);
            case "meeting_analysis" -> "report".equals(column) || "analysis_json".equals(column);
            case "role_permissions" -> "actions".equals(column);
            case "activities" -> "tags".equals(column);
            default -> false;
        };
    }

    private Map<String, Object> redactSensitiveFields(String table, Map<String, Object> row) {
        if (!"users".equals(table)) return row;
        Map<String, Object> safe = new LinkedHashMap<>(row);
        safe.remove("password_hash");
        return safe;
    }
}
