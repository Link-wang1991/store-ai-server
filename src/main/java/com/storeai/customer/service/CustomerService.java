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

    /** 管理角色可看全店，其他员工只看到自己负责的客户。 */
    public List<Customer> listByScope() {
        var qw = new LambdaQueryWrapper<Customer>()
                .eq(Customer::getStoreId, cur.storeId());
        if (!cur.isAdmin()) {
            // 被分配了正式跟进任务的员工，必须能看到对应客户，否则首页任务卡会
            // 显示客户却无法打开档案或带上下文进入 AI 教练。只放开仍在处理的任务。
            List<String> taskCustomerIds = jdbc.queryForList("""
                SELECT DISTINCT customer_id FROM tasks
                WHERE store_id = ? AND assigned_to = ? AND customer_id IS NOT NULL
                  AND status IN ('todo', 'doing', 'overdue')
                """, String.class, cur.storeId(), cur.employeeId());
            if (taskCustomerIds.isEmpty()) {
                qw.eq(Customer::getAssignedTo, cur.employeeId());
            } else {
                qw.and(scope -> scope.eq(Customer::getAssignedTo, cur.employeeId())
                    .or().in(Customer::getId, taskCustomerIds));
            }
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
        if (!cur.isAdmin() && !cur.employeeId().equals(c.getAssignedTo()) && !hasOpenAssignedTask(id)) {
            throw BizException.forbidden();
        }
        return c;
    }

    /** 更新客户基础信息 */
    @Transactional
    public Customer update(String id, Customer update) {
        Customer c = getById(id);
        requireOwnerForWrite(c);
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
        requireOwnerForWrite(c);
        customerRepo.deleteById(id);
    }

    private void requireOwnerForWrite(Customer customer) {
        if (!cur.isAdmin() && !cur.employeeId().equals(customer.getAssignedTo())) {
            throw BizException.forbidden("任务负责人可查看客户，但只有客户负责人可以修改或删除档案");
        }
    }

    private boolean hasOpenAssignedTask(String customerId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM tasks
            WHERE store_id = ? AND customer_id = ? AND assigned_to = ?
              AND status IN ('todo', 'doing', 'overdue')
            """, Integer.class, cur.storeId(), customerId, cur.employeeId());
        return count != null && count > 0;
    }

}
