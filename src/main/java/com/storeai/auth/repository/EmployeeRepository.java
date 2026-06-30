package com.storeai.auth.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storeai.auth.entity.Employee;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EmployeeRepository extends BaseMapper<Employee> {
}
