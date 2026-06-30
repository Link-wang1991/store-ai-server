package com.storeai.customer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import com.storeai.customer.entity.Customer;
import com.storeai.customer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepo;
    private final CurrentUser cur;

    /** 根据权限范围列出客户 */
    public List<Customer> listByScope() {
        var qw = new LambdaQueryWrapper<Customer>()
                .eq(Customer::getStoreId, cur.storeId());
        if (!cur.isAdmin()) {
            // 普通员工只看自己负责的
            qw.eq(Customer::getAssignedTo, cur.employeeId());
        }
        qw.orderByDesc(Customer::getUpdatedAt);
        return customerRepo.selectList(qw);
    }

    /** 客户详情 */
    public Customer getById(String id) {
        Customer c = customerRepo.selectById(id);
        if (c == null || !cur.storeId().equals(c.getStoreId())) {
            throw BizException.notFound("客户");
        }
        if (!cur.isAdmin() && !cur.employeeId().equals(c.getAssignedTo())) {
            throw BizException.forbidden();
        }
        return c;
    }

    /** 更新客户基础信息 */
    public Customer update(String id, Customer update) {
        Customer c = getById(id);
        if (update.getName() != null) c.setName(update.getName());
        if (update.getPhone() != null) c.setPhone(update.getPhone());
        if (update.getGender() != null) c.setGender(update.getGender());
        if (update.getAge() != null) c.setAge(update.getAge());
        if (update.getStage() != null) c.setStage(update.getStage());
        if (update.getAssignedTo() != null) c.setAssignedTo(update.getAssignedTo());
        c.setUpdatedAt(OffsetDateTime.now());
        customerRepo.updateById(c);
        return c;
    }
}
