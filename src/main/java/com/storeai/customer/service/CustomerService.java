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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 客户的记忆项列表（含 id/status，供档案页确认/修正/拒绝） */
    public List<Map<String, Object>> getMemories(String customerId) {
        Customer c = getById(customerId);
        return jdbc.queryForList("""
            SELECT id, `key`, value, confidence, status, source_type, source_id,
                   created_at, updated_at, confirmed_at
            FROM memory_items
            WHERE customer_id = ? AND store_id = ?
            ORDER BY created_at DESC
            """, customerId, cur.storeId());
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

    /**
     * 客户合并：把 sourceId 客户的会谈、任务、记忆、互动时间线迁移到 targetId，
     * 补充目标客户缺失的基础信息，然后删除源客户。仅管理角色可操作。
     * 用于清理"新客户 xx"等临时占位档案，避免客户画像、AI 记忆和任务归属分散。
     */
    @Transactional
    public Map<String, Object> merge(String targetId, String sourceId) {
        if (targetId == null || targetId.isBlank() || sourceId == null || sourceId.isBlank()) {
            throw BizException.badRequest("请选择要保留的客户和待合并的客户");
        }
        if (targetId.equals(sourceId)) throw BizException.badRequest("不能合并到同一客户");

        Customer target = customerRepo.selectById(targetId);
        Customer source = customerRepo.selectById(sourceId);
        if (target == null || source == null
                || !cur.storeId().equals(target.getStoreId())
                || !cur.storeId().equals(source.getStoreId())) {
            throw BizException.notFound("客户");
        }
        // 权限：店长/老板可合并任意客户；普通员工仅可合并"自己负责的"待合并客户（source），
        // 用于把自己创建的临时录音占位客户绑定到正式客户，不能动其他员工的客户。
        boolean canMerge = cur.isAdmin() || cur.employeeId().equals(source.getAssignedTo());
        if (!canMerge) throw BizException.forbidden("只有店长/老板，或该客户负责人可以合并客户");

        // 1. 迁移关联数据：会谈、任务、互动时间线、记忆
        jdbc.update("UPDATE meetings SET customer_id = ? WHERE customer_id = ? AND store_id = ?",
            targetId, sourceId, cur.storeId());
        jdbc.update("UPDATE tasks SET customer_id = ? WHERE customer_id = ? AND store_id = ?",
            targetId, sourceId, cur.storeId());
        jdbc.update("UPDATE interactions SET customer_id = ? WHERE customer_id = ? AND store_id = ?",
            targetId, sourceId, cur.storeId());
        jdbc.update("UPDATE memory_items SET customer_id = ? WHERE customer_id = ? AND store_id = ?",
            targetId, sourceId, cur.storeId());

        // 2. 源客户改名时同步过 meetings.customer_name，迁移后统一为目标客户名
        jdbc.update("UPDATE meetings SET customer_name = ? WHERE customer_id = ? AND store_id = ?",
            target.getName(), targetId, cur.storeId());

        // 3. 补充目标客户缺失的基础信息（源客户有而目标没有的字段）
        if ((target.getPhone() == null || target.getPhone().isBlank()) && source.getPhone() != null) {
            target.setPhone(source.getPhone());
        }
        if ((target.getStage() == null || target.getStage().isBlank()) && source.getStage() != null) {
            target.setStage(source.getStage());
        }
        if ((target.getConcerns() == null || target.getConcerns().isBlank()) && source.getConcerns() != null) {
            target.setConcerns(source.getConcerns());
        }
        if ((target.getAiSuggestion() == null || target.getAiSuggestion().isBlank()) && source.getAiSuggestion() != null) {
            target.setAiSuggestion(source.getAiSuggestion());
        }
        if (target.getTotalVisits() == null || target.getTotalVisits() == 0) {
            target.setTotalVisits(source.getTotalVisits());
        }
        if (target.getLastVisitAt() == null && source.getLastVisitAt() != null) {
            target.setLastVisitAt(source.getLastVisitAt());
        }
        if (target.getLastActiveAt() == null && source.getLastActiveAt() != null) {
            target.setLastActiveAt(source.getLastActiveAt());
        }
        target.setUpdatedAt(OffsetDateTime.now());
        customerRepo.updateById(target);

        // 4. 写时间线
        customerTimelineService.addInteraction(targetId, "customer_merge",
            "合并客户：" + source.getName() + " 的资料已并入");

        // 5. 删除源客户
        customerRepo.deleteById(sourceId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customer_id", targetId);
        result.put("customer_name", target.getName());
        result.put("merged_from", source.getName());
        result.put("message", "已合并客户");
        return result;
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
