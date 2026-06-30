package com.storeai.task.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storeai.task.entity.Task;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskRepository extends BaseMapper<Task> {
}
