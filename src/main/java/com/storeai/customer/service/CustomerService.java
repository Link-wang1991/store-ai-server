package com.storeai.customer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import com.storeai.customer.entity.Customer;
import com.storeai.customer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepo;
    private final CurrentUser cur;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final CustomerTimelineService customerTimelineService;

    /** 返回全店客户（会谈页面需按分配人拆分显示） */
    public List<Customer> listByScope() {
        var qw = new LambdaQueryWrapper<Customer>()
                .eq(Customer::getStoreId, cur.storeId());
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
    @Transactional
    public Customer update(String id, Customer update) {
        Customer c = getById(id);
        String oldName = c.getName();
        String oldStage = c.getStage();
        if (update.getName() != null) c.setName(update.getName());
        if (update.getPhone() != null) c.setPhone(update.getPhone());
        if (update.getGender() != null) c.setGender(update.getGender());
        if (update.getAge() != null) c.setAge(update.getAge());
        if (update.getStage() != null) c.setStage(update.getStage());
        if (update.getAssignedTo() != null) c.setAssignedTo(update.getAssignedTo());
        c.setUpdatedAt(OffsetDateTime.now());
        customerRepo.updateById(c);

        if (update.getStage() != null && !update.getStage().equals(oldStage)) {
            customerTimelineService.addInteraction(id, "stage_update",
                "客户阶段更新：" + oldStage + " → " + update.getStage());
        }

        // 客户改名时同步更新相关会谈记录的 customer_name
        if (update.getName() != null && !update.getName().equals(oldName)) {
            jdbc.update("UPDATE meetings SET customer_name = ? WHERE customer_id = ? AND store_id = ?",
                update.getName(), id, cur.storeId());
        }
        return c;
    }

    public void delete(String id) {
        Customer c = getById(id);
        customerRepo.deleteById(id);
    }
}
