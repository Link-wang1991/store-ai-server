package com.storeai.auth.service;

import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import com.storeai.auth.entity.Employee;
import com.storeai.auth.entity.User;
import com.storeai.auth.repository.EmployeeRepository;
import com.storeai.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 员工批量导入：CSV（UTF-8，表头：姓名,登录邮箱,手机号,岗位,初始密码）。
 * 岗位仅支持店长/咨询师/美容师/前台/运营（与 EmployeeAccountService.create 一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeImportService {

    private static final Set<String> ASSIGNABLE_ROLES = Set.of(
        "manager", "consultant", "beautician", "receptionist", "operator");
    private static final String[] HEADERS = {"姓名", "登录邮箱", "手机号", "岗位", "初始密码"};

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUser cur;

    /** 生成员工导入模板 CSV。 */
    public byte[] buildTemplate() {
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append(String.join(",", HEADERS)).append("\n");
        sb.append("王店长,wang@store.com,13700137000,店长,123456\n");
        sb.append("李咨询师,li@store.com,13600136000,咨询师,123456\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 解析 CSV 并批量创建员工账号。返回 {total, success, failed}。 */
    @Transactional
    public Map<String, Object> importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) throw BizException.badRequest("请选择要导入的文件");
        List<String> lines;
        try {
            String raw = new String(file.getBytes(), StandardCharsets.UTF_8);
            if (raw.startsWith("\uFEFF")) raw = raw.substring(1);
            lines = new ArrayList<>(List.of(raw.split("\\r?\\n")));
        } catch (Exception e) {
            throw BizException.badRequest("文件读取失败，请上传 UTF-8 编码的 CSV");
        }
        if (lines.isEmpty()) throw BizException.badRequest("文件内容为空");
        int start = !lines.isEmpty() && lines.get(0).contains("姓名") ? 1 : 0;

        int total = 0, success = 0;
        List<String> failed = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isBlank()) continue;
            total++;
            try {
                List<String> cols = parseLine(line);
                String name = cols.size() > 0 ? cols.get(0).trim() : "";
                String email = cols.size() > 1 ? cols.get(1).trim().toLowerCase() : "";
                String phone = cols.size() > 2 ? cols.get(2).trim() : "";
                String roleLabel = cols.size() > 3 ? cols.get(3).trim() : "";
                String password = cols.size() > 4 ? cols.get(4).trim() : "";
                if (name.isBlank()) throw new IllegalArgumentException("姓名为空");
                if (email.isBlank()) throw new IllegalArgumentException("登录邮箱为空");
                if (!email.contains("@")) throw new IllegalArgumentException("邮箱格式不正确");
                if (password.length() < 6) throw new IllegalArgumentException("初始密码至少 6 位");
                String role = roleToCode(roleLabel);
                if (role == null) throw new IllegalArgumentException("岗位不支持：" + roleLabel);

                if (userRepository.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                    .eq(User::getEmail, email)) != null) {
                    throw new IllegalArgumentException("邮箱已被使用：" + email);
                }

                OffsetDateTime now = OffsetDateTime.now();
                User user = new User();
                user.setEmail(email);
                user.setName(name);
                user.setPasswordHash(passwordEncoder.encode(password));
                user.setCreatedAt(now);
                userRepository.insert(user);

                Employee employee = new Employee();
                employee.setStoreId(cur.storeId());
                employee.setUserId(user.getId());
                employee.setName(name);
                employee.setPhone(phone.isBlank() ? null : phone);
                employee.setRole(role);
                employee.setStatus("active");
                employee.setDataScope("manager".equals(role) ? "store" : "self");
                employee.setCreatedAt(now);
                employee.setUpdatedAt(now);
                employeeRepository.insert(employee);
                success++;
            } catch (Exception e) {
                failed.add("第 " + (i + 1) + " 行：" + e.getMessage());
            }
        }
        return Map.of("total", total, "success", success, "failed", failed);
    }

    private String roleToCode(String label) {
        switch (label) {
            case "店长": return "manager";
            case "咨询师": return "consultant";
            case "美容师": return "beautician";
            case "前台": return "receptionist";
            case "运营": return "operator";
            default: return null;
        }
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
}
