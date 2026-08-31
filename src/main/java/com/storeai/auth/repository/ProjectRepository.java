package com.storeai.auth.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storeai.auth.entity.Project;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectRepository extends BaseMapper<Project> {
}
