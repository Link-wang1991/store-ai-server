package com.storeai.customer.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.customer.entity.Customer;
import com.storeai.customer.service.CustomerBriefService;
import com.storeai.customer.service.CustomerCheckinService;
import com.storeai.customer.service.CustomerIdentificationService;
import com.storeai.customer.service.CustomerService;
import com.storeai.customer.service.CustomerTimelineService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "客户管理")
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    private final CustomerBriefService customerBriefService;
    private final CustomerIdentificationService customerIdentificationService;
    private final CustomerCheckinService customerCheckinService;
    private final CustomerTimelineService customerTimelineService;

    @GetMapping
    public ApiResponse<List<Customer>> list() {
        return ApiResponse.ok(customerService.listByScope());
    }

    @GetMapping("/identify")
    public ApiResponse<List<Map<String, Object>>> identify(@RequestParam String keyword) {
        return ApiResponse.ok(customerIdentificationService.identify(keyword));
    }

    @GetMapping("/{id}/brief")
    public ApiResponse<Map<String, Object>> brief(@PathVariable String id) {
        return ApiResponse.ok(customerBriefService.generateBrief(id));
    }

    @PostMapping("/{id}/checkin")
    public ApiResponse<Map<String, Object>> checkin(@PathVariable String id,
                                                     @RequestBody(required = false) CheckinRequest req) {
        return ApiResponse.ok(customerCheckinService.checkin(id, req == null ? null : req.note()));
    }

    @GetMapping("/{id}/timeline")
    public ApiResponse<List<Map<String, Object>>> timeline(@PathVariable String id) {
        return ApiResponse.ok(customerTimelineService.getTimeline(id));
    }

    public record CheckinRequest(String note) {}

    @GetMapping("/{id}")
    public ApiResponse<Customer> detail(@PathVariable String id) {
        return ApiResponse.ok(customerService.getById(id));
    }

    @PostMapping("/{id}/update")
    public ApiResponse<Customer> update(@PathVariable String id,
                                         @RequestBody Customer customer) {
        return ApiResponse.ok(customerService.update(id, customer));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        customerService.delete(id);
        return ApiResponse.ok();
    }
}
