package com.storeai.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 门店切换请求头：PC 管理后台在已选门店上下文下，用此头声明"当前要看的门店"。 */
    private static final String STORE_SWITCH_HEADER = "X-Store-Id";

    private final JwtUtil jwtUtil;
    private final UserDetailsServiceImpl userDetailsService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!jwtUtil.validateToken(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        String username = jwtUtil.extractUsername(token);
        var userDetails = userDetailsService.loadUserByUsername(username);

        // 门店切换：在"登录门店"基础上，根据用户权限解析"本次请求实际生效的门店"。
        // 超管可切任意门店；普通用户仅能切到自己在职(active)的门店；越权时回落登录门店。
        // 解析结果直接写回 principal 的 storeId，使下游所有 cur.storeId() 自动遵循切换。
        String effectiveStoreId = resolveEffectiveStoreId(request, userDetails);
        if (effectiveStoreId != null && !effectiveStoreId.equals(userDetails.getStoreId())) {
            userDetails = userDetails.toBuilder().storeId(effectiveStoreId).build();
        }

        var auth = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(auth);
        filterChain.doFilter(request, response);
    }

    private String resolveEffectiveStoreId(HttpServletRequest request, UserDetailsImpl userDetails) {
        String loginStoreId = userDetails.getStoreId();
        String target = request.getHeader(STORE_SWITCH_HEADER);
        if (target == null || target.isBlank() || target.equals(loginStoreId)) {
            return loginStoreId;
        }
        boolean superAdmin = "super_admin".equals(userDetails.getRole());
        if (superAdmin) {
            return target;
        }
        // 普通用户：仅当在目标门店有在职员工档案时才允许切换
        try {
            Integer n = jdbcTemplate.queryForObject(
                "SELECT 1 FROM employees WHERE user_id = ? AND store_id = ? AND status = 'active' LIMIT 1",
                Integer.class, userDetails.getUserId(), target);
            if (n != null) return target;
        } catch (Exception ignored) {
            // 查询失败时回落登录门店
        }
        return loginStoreId;
    }

    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
