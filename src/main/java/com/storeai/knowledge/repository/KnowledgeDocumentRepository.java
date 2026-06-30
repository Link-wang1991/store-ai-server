package com.storeai.knowledge.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storeai.knowledge.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeDocumentRepository extends BaseMapper<KnowledgeDocument> {
}
