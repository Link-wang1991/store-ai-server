package com.storeai.common.service;

import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.stereotype.Service;

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

    public ProxyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.schema = new TableSchema();
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
            params.add(cur.storeId());
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
            params.add(cur.storeId());
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
            safeData.put("store_id", cur.storeId());
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
        var params = keys.stream().map(safeData::get).collect(Collectors.toList());

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
        var params = keys.stream().map(safeData::get).collect(Collectors.toList());

        StringBuilder sql = new StringBuilder("UPDATE " + tbl + " SET " + sets + " WHERE id = ?");
        params.add(id);

        if (hasStoreId && cur != null) {
            sql.append(" AND store_id = ?");
            params.add(cur.storeId());
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
            params.add(cur.storeId());
        }

        int n = jdbc.update(sql.toString(), params.toArray());
        if (n == 0) throw BizException.notFound("记录");
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

    private Map<String, Object> redactSensitiveFields(String table, Map<String, Object> row) {
        if (!"users".equals(table)) return row;
        Map<String, Object> safe = new LinkedHashMap<>(row);
        safe.remove("password_hash");
        return safe;
    }
}
