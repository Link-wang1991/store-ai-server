package com.storeai.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storeai.auth.dto.LoginRequest;
import com.storeai.auth.dto.LoginResponse;
import com.storeai.auth.dto.SendCodeRequest;
import com.storeai.auth.dto.SendCodeResponse;
import com.storeai.auth.dto.PhoneLoginRequest;
import com.storeai.auth.dto.WxBindRequest;
import com.storeai.auth.dto.WxLoginRequest;
import com.storeai.auth.dto.WxLoginResult;
import com.storeai.auth.entity.Employee;
import com.storeai.auth.entity.Store;
import com.storeai.auth.entity.User;
import com.storeai.auth.repository.EmployeeRepository;
import com.storeai.auth.repository.StoreRepository;
import com.storeai.auth.repository.UserRepository;
import com.storeai.auth.security.JwtUtil;
import com.storeai.auth.security.UserDetailsImpl;
import com.storeai.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final long LOCAL_PREVIEW_TTL_MILLIS = 4 * 60 * 60 * 1000L;
    private static final long SMS_CODE_TTL_MILLIS = 5 * 60 * 1000L;
    private static final long SMS_RESEND_INTERVAL_MILLIS = 60 * 1000L;
    private static final int SMS_MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * wx.login 的 code 只能被微信 code2session 消费一次，但同一会话内 Taro.login() 可能返回同一个 code。
     * 这里用内存缓存 code→openid，保证重复调用直接复用，避免第二次报 "code been used"。
     * code 短期有效（几分钟），内存缓存足够；多实例部署可换 Redis。
     */
    private final ConcurrentHashMap<String, String> wxCode2openidCache = new ConcurrentHashMap<>();

    private static final Map<String, String> ROLE_LABELS = Map.of(
        "owner", "老板",
        "admin", "管理员",
        "manager", "店长",
        "consultant", "咨询师",
        "beautician", "美容师",
        "receptionist", "前台",
        "operator", "运营"
    );

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final StoreRepository storeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final JdbcTemplate jdbc;
    private final SmsService smsService;

    @Value("${app.local-preview-login.enabled:false}")
    private boolean localPreviewLoginEnabled;

    @Value("${app.local-preview-login.owner-email:owner@demo.com}")
    private String localPreviewOwnerEmail;

    /** mock 模式下 send-code 会在响应里回传验证码，方便联调；生产/真实短信不下发。 */
    @Value("${app.sms.mode:mock}")
    private String smsMode;

    @Value("${wx.appid:}")
    private String wxAppid;
    @Value("${wx.secret:}")
    private String wxSecret;

    public LoginResponse login(LoginRequest req) {
        String phone = req.getPhone() == null ? null : req.getPhone().trim();
        String email = req.getEmail() == null ? null : req.getEmail().trim().toLowerCase();

        User user;
        if (phone != null && !phone.isBlank()) {
            user = userRepository.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        } else if (email != null && !email.isBlank()) {
            user = userRepository.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        } else {
            throw BizException.badRequest("请输入手机号或邮箱");
        }
        if (user == null || user.getPasswordHash() == null) {
            throw BizException.badRequest("账号或密码错误");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw BizException.badRequest("账号或密码错误");
        }
        return loginResponseFor(user);
    }

    /** 手机号 + 验证码登录。账号必须由超级管理员预录入，不开放自助注册。 */
    @Transactional
    public LoginResponse loginByPhone(PhoneLoginRequest req) {
        String phone = req.getPhone().trim();
        String code = req.getCode() == null ? "" : req.getCode().trim();
        verifyCode(phone, "login", code);

        User user = userRepository.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user == null) {
            throw BizException.badRequest("该手机号尚未开通账号，请联系门店管理员");
        }
        return loginResponseFor(user);
    }

    /** 发送验证码：限频、存库，并交给 SmsService 下发（mock 模式回传 devCode）。 */
    @Transactional
    public SendCodeResponse sendCode(SendCodeRequest req) {
        String phone = req.getPhone().trim();
        String type = (req.getType() == null || req.getType().isBlank()) ? "login" : req.getType();

        // 重发限频：距上次发送不足间隔则拒绝
        CodeRow last = latestCode(phone, type);
        if (last != null && last.createdAt != null) {
            long elapsed = System.currentTimeMillis() - last.createdAt.getTime();
            if (elapsed < SMS_RESEND_INTERVAL_MILLIS) {
                long remain = (SMS_RESEND_INTERVAL_MILLIS - elapsed) / 1000;
                return new SendCodeResponse(false, null, (int) Math.max(remain, 1));
            }
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
            "INSERT INTO sms_verification_codes (id, phone, code, type, expires_at, attempts, used, created_at) VALUES (?, ?, ?, ?, ?, 0, 0, NOW())",
            id, phone, code, type,
            java.sql.Timestamp.from(java.time.Instant.now().plusMillis(SMS_CODE_TTL_MILLIS))
        );

        smsService.sendCode(phone, code, type);

        if ("mock".equals(smsMode)) {
            return new SendCodeResponse(true, code, (int) (SMS_RESEND_INTERVAL_MILLIS / 1000));
        }
        return new SendCodeResponse(true, null, (int) (SMS_RESEND_INTERVAL_MILLIS / 1000));
    }

    private void verifyCode(String phone, String type, String inputCode) {
        if (inputCode.isBlank()) throw BizException.badRequest("请填写验证码");
        CodeRow row = latestCode(phone, type);
        if (row == null) throw BizException.badRequest("请先获取验证码");
        if (row.used) throw BizException.badRequest("验证码已使用，请重新获取");
        if (row.expiresAt != null && row.expiresAt.before(java.sql.Timestamp.from(java.time.Instant.now()))) {
            throw BizException.badRequest("验证码已过期，请重新获取");
        }
        if (row.attempts >= SMS_MAX_ATTEMPTS) throw BizException.badRequest("尝试次数过多，请重新获取验证码");
        if (!inputCode.equals(row.code)) {
            jdbc.update("UPDATE sms_verification_codes SET attempts = attempts + 1 WHERE id = ?", row.id);
            throw BizException.badRequest("验证码不正确");
        }
        jdbc.update("UPDATE sms_verification_codes SET used = 1 WHERE id = ?", row.id);
    }

    /** 取某手机号最新一条验证码记录（用 RowMapper 显式取 Timestamp，规避驱动返回 LocalDateTime 的类型差异）。 */
    private CodeRow latestCode(String phone, String type) {
        String sql = "SELECT id, code, expires_at, attempts, used, created_at FROM sms_verification_codes WHERE phone = ? AND type = ? ORDER BY created_at DESC LIMIT 1";
        List<CodeRow> rows = jdbc.query(sql, (rs, i) -> {
            CodeRow r = new CodeRow();
            r.id = rs.getString("id");
            r.code = rs.getString("code");
            r.expiresAt = rs.getTimestamp("expires_at");
            r.createdAt = rs.getTimestamp("created_at");
            r.attempts = rs.getInt("attempts");
            r.used = rs.getBoolean("used");
            return r;
        }, phone, type);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static class CodeRow {
        String id;
        String code;
        java.sql.Timestamp expiresAt;
        java.sql.Timestamp createdAt;
        int attempts;
        boolean used;
    }

    private LoginResponse loginResponseFor(User user) {
        // 查找关联员工
        var employee = employeeRepository.selectOne(
            new LambdaQueryWrapper<Employee>().apply("user_id = {0}", user.getId()));
        if (employee == null) {
            // 平台超级管理员不绑定真实门店员工档案，这里给出更清晰的引导。
            throw BizException.badRequest("该手机号是平台管理员账号，未关联门店员工档案，无法在小程序端登录，请使用门店员工账号（或联系管理员录入）");
        }
        if (!"active".equals(employee.getStatus())) {
            throw BizException.badRequest("该账号已停用，请联系门店管理员");
        }

        // 查找门店
        var store = storeRepository.selectById(employee.getStoreId());
        String storeName = store != null ? store.getName() : "";

        // 生成 JWT（含 name / storeName）
        var details = UserDetailsImpl.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .password(user.getPasswordHash())
                .storeId(employee.getStoreId())
                .employeeId(employee.getId())
                .role(employee.getRole())
                .roleLabel(roleLabel(employee.getRole()))
                .build();

        String token = jwtUtil.generateToken(details, Map.of(
                "storeId", employee.getStoreId(),
                "employeeId", employee.getId(),
                "role", employee.getRole(),
                "name", user.getName() != null ? user.getName() : "",
                "storeName", storeName
        ));

        return new LoginResponse(token,
                user.getId(), employee.getId(),
                employee.getStoreId(), employee.getRole(),
                roleLabel(employee.getRole()), storeName,
                user.getName() != null ? user.getName() : "");
    }

    /**
     * 本机验收专用：在 local profile 中由配置明确打开，列出同一门店的真实账号角色。
     * 不返回邮箱、密码或用户 ID，避免把账号信息当作免密接口的返回内容。
     */
    public List<LocalPreviewAccount> listLocalPreviewAccounts(HttpServletRequest request) {
        LocalPreviewStore previewStore = requireLocalPreviewStore(request);
        audit(previewStore.storeId(), null, "accounts_listed", request);
        return employeeRepository.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getStoreId, previewStore.storeId())
                .eq(Employee::getStatus, "active")
                .isNotNull(Employee::getUserId)
                .orderByAsc(Employee::getCreatedAt))
            .stream()
            .map(employee -> new LocalPreviewAccount(
                employee.getId(),
                employee.getName() == null || employee.getName().isBlank() ? "未命名员工" : employee.getName(),
                employee.getRole(), roleLabel(employee.getRole()), entryFor(employee.getRole())
            ))
            .toList();
    }

    /**
     * 本机验收专用的短时角色令牌。只能进入配置 owner 所在门店的已启用员工，
     * 且仅在 local profile 明确开启时可调用，不校验也不会读取员工密码。
     */
    public LoginResponse localPreviewLogin(String employeeId, HttpServletRequest request) {
        if (employeeId == null || employeeId.isBlank()) throw BizException.badRequest("请选择要体验的角色");
        LocalPreviewStore previewStore = requireLocalPreviewStore(request);
        Employee employee = employeeRepository.selectById(employeeId);
        if (employee == null || !previewStore.storeId().equals(employee.getStoreId())
                || !"active".equals(employee.getStatus()) || employee.getUserId() == null) {
            audit(previewStore.storeId(), employeeId, "login_rejected", request);
            throw BizException.notFound("可体验的员工账号");
        }
        User user = userRepository.selectById(employee.getUserId());
        if (user == null) throw BizException.notFound("可体验的员工账号");
        Store store = storeRepository.selectById(employee.getStoreId());
        String storeName = store == null || store.getName() == null ? "" : store.getName();
        String name = user.getName() == null || user.getName().isBlank() ? employee.getName() : user.getName();

        UserDetailsImpl details = UserDetailsImpl.builder()
            .userId(user.getId())
            .email(user.getEmail())
            .password(user.getPasswordHash())
            .storeId(employee.getStoreId())
            .employeeId(employee.getId())
            .role(employee.getRole())
            .roleLabel(roleLabel(employee.getRole()))
            .build();
        String token = jwtUtil.generateToken(details, Map.of(
            "storeId", employee.getStoreId(),
            "employeeId", employee.getId(),
            "role", employee.getRole(),
            "name", name == null ? "" : name,
            "storeName", storeName,
            "localPreview", true
        ), LOCAL_PREVIEW_TTL_MILLIS);
        audit(previewStore.storeId(), employee.getId(), "login_issued", request);
        return new LoginResponse(token, user.getId(), employee.getId(), employee.getStoreId(),
            employee.getRole(), roleLabel(employee.getRole()), storeName, name == null ? "" : name);
    }

    private static String roleLabel(String role) {
        return ROLE_LABELS.getOrDefault(role, role == null ? "" : role);
    }

    private LocalPreviewStore requireLocalPreviewStore(HttpServletRequest request) {
        if (!localPreviewLoginEnabled) throw BizException.notFound("本机角色体验");
        if (!isLoopbackRequest(request)) {
            audit(null, null, "remote_rejected", request);
            throw BizException.notFound("本机角色体验");
        }
        User owner = userRepository.selectOne(new LambdaQueryWrapper<User>()
            .eq(User::getEmail, localPreviewOwnerEmail.trim().toLowerCase()));
        if (owner == null) throw BizException.badRequest("未找到本机体验的老板账号，请先使用正常登录");
        Employee employee = employeeRepository.selectOne(new LambdaQueryWrapper<Employee>()
            .eq(Employee::getUserId, owner.getId())
            .eq(Employee::getStatus, "active"));
        if (employee == null) throw BizException.badRequest("本机体验老板账号未启用");
        return new LocalPreviewStore(employee.getStoreId());
    }

    /**
     * 后端的免密接口只接受由本机 Next 同源代理转发的 loopback 请求。手机可访问
     * Next 页面，但不能绕过它直接调用 8080；公网/直连地址即便误开配置也会被拒绝。
     */
    private boolean isLoopbackRequest(HttpServletRequest request) {
        if (request == null) return false;
        try { return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress(); }
        catch (Exception ignored) { return false; }
    }

    private void audit(String storeId, String targetEmployeeId, String action, HttpServletRequest request) {
        try {
            jdbc.update("""
                INSERT INTO role_preview_audit
                (id, store_id, target_employee_id, action, request_ip, request_origin, user_agent, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                """, java.util.UUID.randomUUID().toString().replace("-", ""), storeId, targetEmployeeId, action,
                request == null ? null : request.getRemoteAddr(),
                request == null ? null : trim(request.getHeader("X-Store-AI-Preview-Origin"), 255),
                request == null ? null : trim(request.getHeader("User-Agent"), 500));
        } catch (Exception ignored) {
            // 审计失败不得导致正式登录或其他业务受影响；本机体验本身仍由上方边界保护。
        }
    }

    private String trim(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String entryFor(String role) {
        return "owner".equals(role) || "manager".equals(role) || "admin".equals(role) ? "/admin" : "/home";
    }

    // ============================================================
    // 微信一键登录 / 绑定
    // ============================================================
    public WxLoginResult wxLogin(WxLoginRequest req) {
        String openid = code2Session(req.code());
        User user = userRepository.selectOne(new LambdaQueryWrapper<User>().eq(User::getWxOpenid, openid));
        if (user == null) {
            return new WxLoginResult(true, null, null, null, null, null, null, null, null);
        }
        LoginResponse lr = loginResponseFor(user);
        return new WxLoginResult(false, lr.getToken(), lr.getUserId(), lr.getEmployeeId(),
                lr.getStoreId(), lr.getRole(), lr.getRoleLabel(), lr.getStoreName(), lr.getName());
    }

    @Transactional
    public LoginResponse wxBindPhone(WxBindRequest req) {
        String openid = code2Session(req.code());
        verifyCode(req.phone() == null ? "" : req.phone().trim(), "login",
                req.smsCode() == null ? "" : req.smsCode().trim());
        User user = userRepository.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, req.phone().trim()));
        if (user == null) {
            throw BizException.badRequest("该手机号尚未开通账号，请联系门店管理员");
        }
        // 同一 openid 已绑定其他手机号账号：一个微信只能绑定一个账号，避免串号。
        User openidUser = userRepository.selectOne(new LambdaQueryWrapper<User>().eq(User::getWxOpenid, openid));
        if (openidUser != null && !openidUser.getId().equals(user.getId())) {
            throw BizException.badRequest("该微信已绑定其他手机号，请直接使用原手机号对应的微信登录");
        }
        // 该手机号已绑定过其他微信：拒绝再次绑定，避免不同微信都能登进同一账号、且 openid 无法回写。
        String boundOpenid = user.getWxOpenid();
        if (boundOpenid != null && !boundOpenid.isBlank() && !boundOpenid.equals(openid)) {
            throw BizException.badRequest("该手机号已绑定其他微信账号，请使用原微信登录");
        }
        if (boundOpenid == null || boundOpenid.isBlank()) {
            user.setWxOpenid(openid);
            userRepository.updateById(user);
        }
        return loginResponseFor(user);
    }

    /** 用 wx.login 的 code 换取微信 openid（code2session）。 */
    private String code2Session(String code) {
        if (code == null || code.isBlank()) throw BizException.badRequest("微信登录码为空");
        // 命中缓存则直接复用，避免同一 code 二次消费时报 "code been used"
        String cached = wxCode2openidCache.get(code);
        if (cached != null) return cached;
        if (wxAppid == null || wxAppid.isBlank() || wxSecret == null || wxSecret.isBlank()) {
            throw BizException.badRequest("微信登录未配置 appid/secret");
        }
        String url = String.format(
                "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                wxAppid, wxSecret, code);
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
            if (node.has("errcode") && node.get("errcode").asInt() != 0) {
                throw BizException.badRequest("微信登录失败: " + node.get("errmsg").asText());
            }
            String openid = node.get("openid").asText();
            if (openid == null || openid.isBlank()) {
                throw BizException.badRequest("微信登录未返回 openid");
            }
            // 缓存 code→openid，供同一会话内重复调用复用；code 有效期短，不主动清理也无碍
            wxCode2openidCache.put(code, openid);
            return openid;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.badRequest("微信登录校验失败: " + e.getMessage());
        }
    }

    public record LocalPreviewAccount(String employeeId, String name, String role, String roleLabel, String entry) {}
    private record LocalPreviewStore(String storeId) {}
}
