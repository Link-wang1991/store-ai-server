package com.storeai.auth.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storeai.auth.entity.Employee;
import com.storeai.auth.entity.User;
import com.storeai.auth.repository.UserRepository;
import com.storeai.auth.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;

    @Override
    public UserDetailsImpl loadUserByUsername(String userId) throws UsernameNotFoundException {
        var user = userRepository.selectOne(
            new LambdaQueryWrapper<User>().apply("id = {0}", userId));
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在: " + userId);
        }

        var employee = employeeRepository.selectOne(
            new LambdaQueryWrapper<Employee>().apply("user_id = {0}", user.getId()));
        if (employee == null) {
            throw new UsernameNotFoundException("未找到关联的员工信息");
        }

        return UserDetailsImpl.builder()
                .userId(userId)
                .email(user.getEmail())
                .password(user.getPasswordHash())
                .storeId(employee.getStoreId())
                .employeeId(employee.getId())
                .role(employee.getRole())
                .roleLabel(employee.getRole())  // 可由门店自定义
                .build();
    }
}
