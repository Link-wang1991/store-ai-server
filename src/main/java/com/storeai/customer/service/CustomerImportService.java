package com.storeai.customer.service;

import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import com.storeai.customer.entity.Customer;
import com.storeai.customer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 客户批量导入：支持 CSV（UTF-8，表头：姓名,手机号,性别,年龄,阶段,客户池,标签,备注）。
 * 管理端先下载模板（含表头示例），按列填写后上传导入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerImportService {

    private static final String[] HEADERS = {"姓名", "手机号", "性别", "年龄", "阶段", "客户池", "标签", "备注"};

    private final CustomerRepository customerRepo;
    private final CurrentUser cur;

    /** 生成导入模板 CSV（UTF-8 带 BOM，兼容 Excel）。 */
    public byte[] buildTemplate() {
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append(String.join(",", HEADERS)).append("\n");
        sb.append("张三,13800138000,男,30,new,today,意向客户,首次到店咨询\n");
        sb.append("李四,13900139000,女,,intent,new_deal,高意向,关注价格\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 解析 CSV 并批量导入。返回 {total, success, failed, message}。 */
    @Transactional
    public Map<String, Object> importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("请选择要导入的文件");
        }
        List<String> lines;
        try {
            String raw = new String(file.getBytes(), StandardCharsets.UTF_8);
            // 去除 BOM
            if (raw.startsWith("\uFEFF")) raw = raw.substring(1);
            lines = new java.util.ArrayList<>(List.of(raw.split("\\r?\\n")));
        } catch (Exception e) {
            throw BizException.badRequest("文件读取失败，请上传 UTF-8 编码的 CSV");
        }
        if (lines.isEmpty()) throw BizException.badRequest("文件内容为空");

        // 跳过表头行
        int start = 0;
        if (!lines.isEmpty() && lines.get(0).contains("姓名")) start = 1;

        int total = 0, success = 0;
        List<String> failed = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isBlank()) continue;
            total++;
            try {
                List<String> cols = parseLine(line);
                String name = cols.size() > 0 ? cols.get(0).trim() : "";
                if (name.isBlank()) throw new IllegalArgumentException("姓名为空");
                Customer c = new Customer();
                c.setStoreId(cur.storeId());
                c.setName(name);
                if (cols.size() > 1) c.setPhone(blankToNull(cols.get(1)));
                if (cols.size() > 2) c.setGender(blankToNull(cols.get(2)));
                if (cols.size() > 3) c.setAge(parseAge(cols.get(3)));
                if (cols.size() > 4) c.setStage(blankToNull(cols.get(4)));
                if (cols.size() > 5) c.setPool(blankToNull(cols.get(5)));
                if (cols.size() > 6) c.setTags(blankToNull(cols.get(6)));
                if (cols.size() > 7) c.setConcerns(blankToNull(cols.get(7)));
                if (c.getStage() == null) c.setStage("new");
                if (c.getPool() == null) c.setPool("new");
                c.setTotalVisits(0);
                c.setCreatedAt(OffsetDateTime.now());
                c.setUpdatedAt(OffsetDateTime.now());
                customerRepo.insert(c);
                success++;
            } catch (Exception e) {
                failed.add("第 " + (i + 1) + " 行：" + e.getMessage());
            }
        }
        return Map.of("total", total, "success", success, "failed", failed);
    }

    private List<String> parseLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    inQuote = !inQuote;
                }
            } else if (ch == ',' && !inQuote) {
                result.add(field.toString());
                field.setLength(0);
            } else {
                field.append(ch);
            }
        }
        result.add(field.toString());
        return result;
    }

    private Integer parseAge(String s) {
        if (s == null || s.trim().isBlank()) return null;
        try {
            int a = Integer.parseInt(s.trim());
            return (a >= 0 && a <= 120) ? a : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isBlank() ? null : v;
    }
}
