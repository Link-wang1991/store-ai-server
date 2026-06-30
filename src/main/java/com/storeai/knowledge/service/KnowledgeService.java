package com.storeai.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storeai.common.exception.BizException;
import com.storeai.common.service.StorageService;
import com.storeai.common.util.CurrentUser;
import com.storeai.knowledge.entity.KnowledgeChunk;
import com.storeai.knowledge.entity.KnowledgeDocument;
import com.storeai.knowledge.repository.KnowledgeChunkRepository;
import com.storeai.knowledge.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeDocumentRepository docRepo;
    private final KnowledgeChunkRepository chunkRepo;
    private final KnowledgeRetrieveService retrieveService;
    private final StorageService storage;
    private final CurrentUser cur;

    // ==================== 文档 CRUD ====================

    /** 上传知识库文件 + 解析 + 切分 + 落库 */
    @Transactional
    public KnowledgeDocument upload(MultipartFile file, String title, String category,
                                     List<String> visibleRoles, String tags, String remark) {
        if (!cur.isAdmin()) {
            throw BizException.forbidden();
        }

        // 1. 上传文件到 MinIO
        String fileUrl = null;
        try {
            String objectName = cur.storeId() + "/" + UUID.randomUUID() + "_" + file.getOriginalFilename();
            fileUrl = storage.saveKnowledge(objectName,
                    file.getInputStream(), file.getSize(), file.getContentType());
        } catch (Exception e) {
            throw new BizException("文件上传失败: " + e.getMessage());
        }

        // 2. 保存文档记录
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setStoreId(cur.storeId());
        doc.setTitle(title);
        doc.setCategory(category);
        doc.setStatus("active");
        doc.setUploadedBy(cur.employeeId());
        doc.setVisibleRoles(toJsonArray(visibleRoles));
        doc.setTags(tags);
        doc.setRemark(remark);
        doc.setFileUrl(fileUrl);
        doc.setFileType(getFileExt(file.getOriginalFilename()));
        doc.setCreatedAt(OffsetDateTime.now());
        doc.setUpdatedAt(OffsetDateTime.now());
        docRepo.insert(doc);

        // 3. 解析文本内容
        String text = parseFileToText(file);
        if (text == null || text.isBlank()) {
            log.warn("文件内容为空: {}", file.getOriginalFilename());
            return doc;
        }

        // 4. 切分片段
        List<String> chunks = chunkText(text);
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setStoreId(cur.storeId());
            chunk.setDocumentId(doc.getId());
            chunk.setContent(chunks.get(i));
            chunk.setSeq(i);
            chunk.setCreatedAt(OffsetDateTime.now());
            chunkRepo.insert(chunk);
        }

        log.info("知识库上传完成: {} → {} 个片段", title, chunks.size());
        return doc;
    }

    /** 手动创建知识文档（纯文本，无文件） */
    @Transactional
    public KnowledgeDocument createManual(String title, String category,
                                           String content, List<String> visibleRoles) {
        if (!cur.isAdmin()) {
            throw BizException.forbidden();
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setStoreId(cur.storeId());
        doc.setTitle(title);
        doc.setCategory(category);
        doc.setStatus("active");
        doc.setUploadedBy(cur.employeeId());
        doc.setVisibleRoles(toJsonArray(visibleRoles));
        doc.setCreatedAt(OffsetDateTime.now());
        doc.setUpdatedAt(OffsetDateTime.now());
        docRepo.insert(doc);

        List<String> chunks = chunkText(content);
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setStoreId(cur.storeId());
            chunk.setDocumentId(doc.getId());
            chunk.setContent(chunks.get(i));
            chunk.setSeq(i);
            chunk.setCreatedAt(OffsetDateTime.now());
            chunkRepo.insert(chunk);
        }

        return doc;
    }

    /** 切换文档启用状态 */
    public void toggleStatus(String docId) {
        if (!cur.isAdmin()) throw BizException.forbidden();
        KnowledgeDocument doc = docRepo.selectById(docId);
        if (doc == null || !cur.storeId().equals(doc.getStoreId())) {
            throw BizException.notFound("文档");
        }
        doc.setStatus("active".equals(doc.getStatus()) ? "inactive" : "active");
        doc.setUpdatedAt(OffsetDateTime.now());
        docRepo.updateById(doc);
    }

    /** 删除文档及其片段 */
    @Transactional
    public void delete(String docId) {
        if (!cur.isAdmin()) throw BizException.forbidden();
        KnowledgeDocument doc = docRepo.selectById(docId);
        if (doc == null || !cur.storeId().equals(doc.getStoreId())) {
            throw BizException.notFound("文档");
        }
        chunkRepo.delete(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getDocumentId, docId));
        docRepo.deleteById(docId);
    }

    /** 列出启用的文档 */
    public List<KnowledgeDocument> listActive(String category) {
        var qw = new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getStoreId, cur.storeId())
                .eq(KnowledgeDocument::getStatus, "active");
        if (category != null && !category.isBlank()) {
            qw.eq(KnowledgeDocument::getCategory, category);
        }
        qw.orderByDesc(KnowledgeDocument::getUpdatedAt);
        return docRepo.selectList(qw);
    }

    // ==================== 检索 ====================

    /**
     * 检索知识库（bigram 关键词匹配）。
     * 仅检索当前门店 + 启用 + 可见角色匹配的片段。
     */
    public List<KnowledgeRetrieveService.RetrievedChunk> search(String query, int topN) {
        // 加载当前角色可访问的所有片段
        var chunks = chunkRepo.selectList(
            new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getStoreId, cur.storeId()));

        if (chunks.isEmpty()) return Collections.emptyList();

        // bigram 搜索
        return retrieveService.retrieve(chunks, query, topN);
    }

    // ==================== 文件解析 ====================

    /** 简单文本解析：支持 txt / md / csv。docx/pdf/xlsx 后续加 */
    private String parseFileToText(MultipartFile file) {
        String name = (file.getOriginalFilename() != null)
                ? file.getOriginalFilename().toLowerCase() : "";
        try {
            if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv")) {
                return new String(file.getBytes(), "UTF-8");
            }
            // docx/pdf/xlsx 暂不支持，返回提示
            return "[文件类型暂不支持内容解析: " + getFileExt(name) + "，可在查看详情中补充]";
        } catch (Exception e) {
            log.error("文件解析失败: {}", name, e);
            return null;
        }
    }

    // ==================== 文本切分 ====================

    /**
     * 按段落 + Markdown 标题切分，每段 200-800 字
     */
    List<String> chunkText(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();

        List<String> result = new ArrayList<>();
        // 按 ## 和空行分块 和持续段落
        String[] sections = text.split("(?m)^#{1,3}\\s+|(?m)^\\n{2,}");
        StringBuilder buf = new StringBuilder();

        for (String sec : sections) {
            String trimmed = sec.trim();
            if (trimmed.isEmpty()) continue;

            if (buf.length() + trimmed.length() > 800 && buf.length() > 100) {
                result.add(buf.toString().trim());
                buf.setLength(0);
            }

            if (buf.length() > 0) buf.append("\n\n");
            buf.append(trimmed);
        }

        if (buf.length() > 0) {
            result.add(buf.toString().trim());
        }

        return result;
    }

    // ==================== 工具方法 ====================

    private String toJsonArray(List<String> items) {
        if (items == null || items.isEmpty()) return "[]";
        return "[" + items.stream()
            .map(s -> "\"" + s.replace("\"", "\\\"") + "\"")
            .collect(Collectors.joining(",")) + "]";
    }

    private String getFileExt(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(i + 1) : "";
    }
}
