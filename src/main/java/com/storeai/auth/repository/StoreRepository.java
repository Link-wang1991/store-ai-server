package com.storeai.auth.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storeai.auth.entity.Store;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StoreRepository extends BaseMapper<Store> {
}
