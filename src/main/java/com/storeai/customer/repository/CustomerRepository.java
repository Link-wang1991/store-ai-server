package com.storeai.customer.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storeai.customer.entity.Customer;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CustomerRepository extends BaseMapper<Customer> {
}
