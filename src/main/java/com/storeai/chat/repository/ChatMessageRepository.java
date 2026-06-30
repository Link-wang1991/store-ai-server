package com.storeai.chat.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storeai.chat.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageRepository extends BaseMapper<ChatMessage> {
}
