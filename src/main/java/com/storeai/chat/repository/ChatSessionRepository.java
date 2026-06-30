package com.storeai.chat.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storeai.chat.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionRepository extends BaseMapper<ChatSession> {
}
