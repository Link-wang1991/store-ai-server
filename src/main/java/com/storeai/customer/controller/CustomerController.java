package com.storeai.customer.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.customer.entity.Customer;
import com.storeai.customer.service.CustomerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "客户管理")
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping
    public ApiResponse<List<Customer>> list() {
        return ApiResponse.ok(customerService.listByScope());
    }

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
