package com.storeai.common.util;

import com.storeai.auth.security.UserDetailsImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 获取当前登录用户上下文信息。
 * 所有 Controller / Service 通过此类获取 storeId、employeeId、role，无需层层传参。
 */
@Component
public class CurrentUser {

    private UserDetailsImpl get() {
        return (UserDetailsImpl) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
    }

    public String userId() {
        return get().getUserId();
    }

    public String storeId() {
        return get().getStoreId();
    }

    public String employeeId() {
        return get().getEmployeeId();
    }

    public String role() {
        return get().getRole();
    }

    public String email() {
        return get().getEmail();
    }

    public boolean isAdmin() {
        String r = role();
        return "owner".equals(r) || "manager".equals(r) || "admin".equals(r);
    }

    public boolean isOwner() {
        return "owner".equals(role());
    }
}
