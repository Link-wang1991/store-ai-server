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
import org.springframework.beans.factory.annotation.Value;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private final EmbeddingService embeddingService;
    private final StorageService storage;
    private final CurrentUser cur;

    /** 默认 local；配置 minio 时优先云端，故障后仍会退回本机受控目录。 */
    @Value("${storage.provider:local}")
    private String storageProvider;

    @Value("${storage.knowledge-local-path:./uploads/knowledge}")
    private String knowledgeLocalPath;

    /** 防止把超大的原始附件一次性塞入事务、提示词或向量服务。 */
    private static final int MAX_EXTRACTED_TEXT = 1_500_000;

    // ==================== 文档 CRUD ====================

    /** 上传知识库文件 + 解析 + 切分 + 落库 */
    @Transactional
    public KnowledgeDocument upload(MultipartFile file, String title, String category,
                                     List<String> visibleRoles, String tags, String remark, String status) {
        if (!cur.isAdmin()) {
            throw BizException.forbidden();
        }

        // 1. 先解析，无法检索的附件不创建“看似上传成功”的空知识记录。
        String text = parseFileToText(file);
        if (text == null || text.isBlank()) {
            throw BizException.badRequest("未从文件中识别到可用文本，请检查文件是否为空或已加密");
        }

        // 2. 保存原件。没有运行 MinIO 的本机环境，不能阻断知识正文入库与检索。
        String fileUrl = saveOriginalFile(file);

        // 3. 保存文档记录
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setStoreId(cur.storeId());
        doc.setTitle(title);
        doc.setCategory(category);
        doc.setStatus(normalizeStatus(status));
        doc.setUploadedBy(cur.employeeId());
        doc.setVisibleRoles(toJsonArray(visibleRoles));
        doc.setTags(tags);
        doc.setRemark(remark);
        doc.setFileUrl(fileUrl);
        doc.setFileType(getFileExt(file.getOriginalFilename()));
        applyInitialLifecycle(doc, status);
        docRepo.insert(doc);

        // 4. 切分、向量化并落库。向量服务不可用时保留关键词检索，不阻断上传。
        List<String> chunks = saveChunks(doc, chunkText(text));

        log.info("知识库上传完成: {} → {} 个片段", title, chunks.size());
        return doc;
    }

    /** 手动创建知识文档（纯文本，无文件） */
    @Transactional
    public KnowledgeDocument createManual(String title, String category,
                                           String content, List<String> visibleRoles) {
        return createManual(title, category, content, visibleRoles, null, null, "active");
    }

    /** 手动创建的完整参数版本，供上传页面保留标签、备注和草稿状态。 */
    @Transactional
    public KnowledgeDocument createManual(String title, String category,
                                           String content, List<String> visibleRoles,
                                           String tags, String remark, String status) {
        if (!cur.isAdmin()) {
            throw BizException.forbidden();
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setStoreId(cur.storeId());
        doc.setTitle(title);
        doc.setCategory(category);
        doc.setStatus(normalizeStatus(status));
        doc.setUploadedBy(cur.employeeId());
        doc.setVisibleRoles(toJsonArray(visibleRoles));
        doc.setTags(tags);
        doc.setRemark(remark);
        applyInitialLifecycle(doc, status);
        docRepo.insert(doc);

        List<String> chunks = saveChunks(doc, chunkText(content));

        return doc;
    }

    private String normalizeStatus(String status) {
        // 页面历史上使用 disabled，后端实体统一使用 inactive；兼容两种调用。
        return "disabled".equalsIgnoreCase(status) || "inactive".equalsIgnoreCase(status)
            ? "inactive"
            : "active";
    }

    /** 新资料默认可用，但会生成明确的复核日期；历史资料保留为 approved，避免升级时突然消失。 */
    private void applyInitialLifecycle(KnowledgeDocument doc, String status) {
        OffsetDateTime now = OffsetDateTime.now();
        String normalized = normalizeStatus(status);
        doc.setStatus(normalized);
        doc.setReviewStatus("active".equals(normalized) ? "approved" : "draft");
        doc.setEffectiveAt(now);
        doc.setReviewDueAt(now.plusDays(180));
        doc.setVersionLabel("v1");
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
    }

    /**
     * 云端 MinIO 可用时继续保存在云端；本机一键启动未配置/未启动 MinIO 时，
     * 保存在受控的本地目录中。两种失败均不影响已完成解析的文字进入知识库。
     */
    private String saveOriginalFile(MultipartFile file) {
        String key = cur.storeId() + "/" + UUID.randomUUID() + "_" + sanitizeFileName(file.getOriginalFilename());
        if ("minio".equalsIgnoreCase(storageProvider)) {
            try (InputStream input = file.getInputStream()) {
                return storage.saveKnowledge(key, input, file.getSize(), file.getContentType());
            } catch (Exception e) {
                log.warn("MinIO 原件存储不可用，改为本地保存: {}", e.getMessage());
            }
        }

        try (InputStream input = file.getInputStream()) {
            Path base = Path.of(knowledgeLocalPath).toAbsolutePath().normalize();
            Path target = base.resolve(key).normalize();
            if (!target.startsWith(base)) throw new IllegalStateException("非法文件路径");
            Files.createDirectories(target.getParent());
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            // 数据库仅存相对 key，绝不把本机绝对路径暴露给浏览器。
            return "local://" + key;
        } catch (Exception e) {
            log.warn("知识原件未保存，但可检索文字会继续入库: {}", e.getMessage());
            return null;
        }
    }

    private String sanitizeFileName(String name) {
        String safe = name == null ? "upload" : Path.of(name).getFileName().toString();
        safe = safe.replaceAll("[^a-zA-Z0-9._\\-一-龥]", "_");
        return safe.isBlank() ? "upload" : safe;
    }

    /** 受控读取本机原件；Controller 负责以受登录保护的响应返回。 */
    public Path localOriginalFile(String docId) {
        KnowledgeDocument doc = docRepo.selectById(docId);
        if (doc == null || !cur.storeId().equals(doc.getStoreId())) throw BizException.notFound("资料");
        String fileUrl = doc.getFileUrl();
        if (fileUrl == null || !fileUrl.startsWith("local://")) throw BizException.notFound("本地原文件");
        Path base = Path.of(knowledgeLocalPath).toAbsolutePath().normalize();
        Path target = base.resolve(fileUrl.substring("local://".length())).normalize();
        if (!target.startsWith(base) || !Files.isRegularFile(target)) throw BizException.notFound("原文件");
        return target;
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
        return docRepo.selectList(qw).stream()
            .filter(this::isLifecycleEligible)
            .filter(this::isVisibleToCurrentRole)
            .toList();
    }

    /** 店长更新知识有效期与复核状态；退休资料从后续检索中移除，但原记录仍保留可审计。 */
    @Transactional
    public KnowledgeDocument updateLifecycle(String docId, String reviewStatus, String effectiveAt,
                                             String expiresAt, String reviewDueAt, String versionLabel, String reviewNote) {
        if (!cur.isAdmin()) throw BizException.forbidden();
        KnowledgeDocument doc = docRepo.selectById(docId);
        if (doc == null || !cur.storeId().equals(doc.getStoreId())) throw BizException.notFound("知识资料");
        String status = normalizeReviewStatus(reviewStatus);
        OffsetDateTime effective = parseLifecycleDate(effectiveAt, doc.getEffectiveAt());
        OffsetDateTime expires = parseLifecycleDate(expiresAt, doc.getExpiresAt());
        OffsetDateTime due = parseLifecycleDate(reviewDueAt, doc.getReviewDueAt());
        if (effective != null && expires != null && !expires.isAfter(effective)) {
            throw BizException.badRequest("失效日期必须晚于生效日期");
        }
        if (versionLabel != null && versionLabel.trim().length() > 64) throw BizException.badRequest("版本号不能超过 64 个字符");
        if (reviewNote != null && reviewNote.trim().length() > 2000) throw BizException.badRequest("复核说明不能超过 2000 字");
        doc.setReviewStatus(status);
        doc.setEffectiveAt(effective);
        doc.setExpiresAt(expires);
        doc.setReviewDueAt(due);
        doc.setVersionLabel(blankToNull(versionLabel));
        doc.setReviewNote(blankToNull(reviewNote));
        doc.setLastReviewedAt(OffsetDateTime.now());
        doc.setLastReviewedBy(cur.employeeId());
        doc.setStatus("retired".equals(status) ? "inactive" : doc.getStatus());
        doc.setUpdatedAt(OffsetDateTime.now());
        docRepo.updateById(doc);
        return doc;
    }

    // ==================== 检索 ====================

    /** 检索当前门店中已启用、且当前角色可见的知识片段。 */
    public List<KnowledgeRetrieveService.RetrievedChunk> search(String query, int topN) {
        return searchForStore(cur.storeId(), cur.role(), query, topN);
    }

    /**
     * 供会谈等后台任务按原会谈所属门店与员工角色检索。
     * 这类任务没有 HTTP 登录上下文，不能误用 CurrentUser；仍严格遵守资料启用状态、
     * 门店隔离和可见角色，避免把不该给该员工看的资料送入分析提示词。
     */
    public List<KnowledgeRetrieveService.RetrievedChunk> searchForStore(
            String storeId, String role, String query, int topN) {
        return searchForStore(storeId, role, query, topN, false);
    }

    /** 会谈后台的快速检索，不等待外部向量服务，确保录音分析能稳定完成。 */
    public List<KnowledgeRetrieveService.RetrievedChunk> searchForStoreKeywordOnly(
            String storeId, String role, String query, int topN) {
        return searchForStore(storeId, role, query, topN, true);
    }

    private List<KnowledgeRetrieveService.RetrievedChunk> searchForStore(
            String storeId, String role, String query, int topN, boolean keywordOnly) {
        if (storeId == null || storeId.isBlank() || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        var docs = docRepo.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
            .eq(KnowledgeDocument::getStoreId, storeId)
            .eq(KnowledgeDocument::getStatus, "active"))
            .stream()
            .filter(this::isLifecycleEligible)
            .filter(doc -> isVisibleToRole(doc, role))
            .toList();
        if (docs.isEmpty()) return Collections.emptyList();

        var documentIds = docs.stream().map(KnowledgeDocument::getId).toList();
        var titles = docs.stream().collect(Collectors.toMap(KnowledgeDocument::getId,
            d -> d.getTitle() == null || d.getTitle().isBlank() ? "未命名资料" : d.getTitle()));
        var chunks = chunkRepo.selectList(
            new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getStoreId, storeId)
                .in(KnowledgeChunk::getDocumentId, documentIds));

        if (chunks.isEmpty()) return Collections.emptyList();

        var retrieved = keywordOnly
            ? retrieveService.retrieveKeywordOnly(chunks, query, topN)
            : retrieveService.retrieve(chunks, query, topN);
        return retrieved.stream()
            .map(chunk -> new KnowledgeRetrieveService.RetrievedChunk(
                chunk.id(), chunk.documentId(), titles.getOrDefault(chunk.documentId(), "门店资料"),
                chunk.content(), chunk.score()))
            .toList();
    }

    /**
     * 为已有知识片段补建向量。仅管理员可操作；失败片段保留为空，下次可再次补建。
     * 用于从旧版本升级后，不要求用户把知识文件重新上传一遍。
     */
    @Transactional
    public Map<String, Object> reindexEmbeddings() {
        if (!cur.isAdmin()) throw BizException.forbidden();
        if (!embeddingService.isConfigured()) {
            throw BizException.badRequest("未配置向量服务，当前仍可使用关键词检索");
        }
        var chunks = chunkRepo.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
            .eq(KnowledgeChunk::getStoreId, cur.storeId())
            .and(q -> q.isNull(KnowledgeChunk::getEmbedding)
                .or().eq(KnowledgeChunk::getEmbeddingModel, "")));
        int indexed = 0;
        int failed = 0;
        for (KnowledgeChunk chunk : chunks) {
            if (applyEmbedding(chunk)) indexed++;
            else failed++;
        }
        return Map.of("total", chunks.size(), "indexed", indexed, "failed", failed,
            "model", embeddingService.model());
    }

    /** 空角色列表代表全员可见；格式异常时默认拒绝，避免资料意外越权。 */
    private boolean isVisibleToCurrentRole(KnowledgeDocument doc) {
        return isVisibleToRole(doc, cur.role());
    }

    private boolean isLifecycleEligible(KnowledgeDocument doc) {
        if (!"approved".equalsIgnoreCase(defaultedReviewStatus(doc.getReviewStatus()))) return false;
        OffsetDateTime now = OffsetDateTime.now();
        return (doc.getEffectiveAt() == null || !doc.getEffectiveAt().isAfter(now))
            && (doc.getExpiresAt() == null || doc.getExpiresAt().isAfter(now));
    }

    private String defaultedReviewStatus(String value) {
        return value == null || value.isBlank() ? "approved" : value.trim();
    }

    private String normalizeReviewStatus(String value) {
        String status = value == null || value.isBlank() ? "approved" : value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("draft", "approved", "needs_review", "retired").contains(status)) {
            throw BizException.badRequest("知识复核状态仅支持 draft、approved、needs_review、retired");
        }
        return status;
    }

    private OffsetDateTime parseLifecycleDate(String raw, OffsetDateTime fallback) {
        if (raw == null) return fallback;
        String value = raw.trim();
        if (value.isBlank()) return null;
        try { return OffsetDateTime.parse(value); }
        catch (Exception ignored) {
            try { return java.time.LocalDate.parse(value).atStartOfDay(java.time.ZoneId.systemDefault()).toOffsetDateTime(); }
            catch (Exception e) { throw BizException.badRequest("日期格式无效，请使用 YYYY-MM-DD"); }
        }
    }

    private String blankToNull(String value) {
        if (value == null || value.trim().isBlank()) return null;
        return value.trim();
    }

    private boolean isVisibleToRole(KnowledgeDocument doc, String role) {
        String visibleRoles = doc.getVisibleRoles();
        if (visibleRoles == null || visibleRoles.isBlank() || "[]".equals(visibleRoles.trim())) return true;
        if (role == null || role.isBlank()) return false;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"([^\\\"]+)\\\"").matcher(visibleRoles);
        boolean parsed = false;
        while (matcher.find()) {
            parsed = true;
            if (role.equals(matcher.group(1).trim())) return true;
        }
        if (!parsed && !visibleRoles.contains("[")) {
            for (String visibleRole : visibleRoles.split(",")) {
                if (role.equals(visibleRole.trim())) return true;
            }
        }
        return false;
    }

    // ==================== 文件解析 ====================

    /**
     * 提取用于检索的文本。支持常见的真实运营资料格式；不支持的格式明确失败，
     * 不再把“暂不支持”的提示文本写入知识库造成假命中。
     */
    private String parseFileToText(MultipartFile file) {
        String name = (file.getOriginalFilename() != null)
                ? file.getOriginalFilename().toLowerCase() : "";
        try {
            byte[] bytes = file.getBytes();
            if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv")) {
                return limitExtractedText(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            }
            if (name.endsWith(".pdf")) return parsePdf(bytes);
            if (name.endsWith(".docx")) return parseDocx(bytes);
            if (name.endsWith(".xlsx") || name.endsWith(".xls")) return parseSpreadsheet(bytes);
            if (name.endsWith(".pptx")) return parsePptx(bytes);
            throw BizException.badRequest("暂不支持 ." + getFileExt(name) + " 文件，请上传 txt、md、csv、pdf、docx、xls、xlsx 或 pptx");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("文件解析失败: {}", name, e);
            throw BizException.badRequest("文件解析失败，请确认文件未加密或损坏");
        }
    }

    private String parsePdf(byte[] bytes) throws Exception {
        try (PDDocument document = PDDocument.load(bytes)) {
            return limitExtractedText(new PDFTextStripper().getText(document));
        }
    }

    private String parseDocx(byte[] bytes) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            document.getParagraphs().forEach(p -> appendLine(text, p.getText()));
            document.getTables().forEach(table -> table.getRows().forEach(row -> {
                StringJoiner cells = new StringJoiner(" | ");
                row.getTableCells().forEach(cell -> cells.add(cell.getText().replaceAll("\\s+", " ").trim()));
                appendLine(text, cells.toString());
            }));
            return limitExtractedText(text.toString());
        }
    }

    private String parseSpreadsheet(byte[] bytes) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                appendLine(text, "# 工作表：" + sheet.getSheetName());
                for (Row row : sheet) {
                    StringJoiner cells = new StringJoiner(" | ");
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell).replaceAll("\\s+", " ").trim();
                        if (!value.isEmpty()) cells.add(value);
                    }
                    if (cells.length() > 0) appendLine(text, cells.toString());
                    if (text.length() > MAX_EXTRACTED_TEXT) return limitExtractedText(text.toString());
                }
            }
            return limitExtractedText(text.toString());
        }
    }

    private String parsePptx(byte[] bytes) throws Exception {
        try (XMLSlideShow presentation = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            presentation.getSlides().forEach(slide -> {
                appendLine(text, "# 幻灯片");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) appendLine(text, textShape.getText());
                }
            });
            return limitExtractedText(text.toString());
        }
    }

    private void appendLine(StringBuilder target, String value) {
        if (value == null || value.isBlank()) return;
        if (target.length() > 0) target.append('\n');
        target.append(value.trim());
    }

    private String limitExtractedText(String text) {
        if (text == null) return "";
        if (text.length() > MAX_EXTRACTED_TEXT) {
            log.warn("知识文件文本过长，已截取前 {} 字用于检索", MAX_EXTRACTED_TEXT);
            return text.substring(0, MAX_EXTRACTED_TEXT);
        }
        return text;
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
            if (trimmed.length() > 800 && buf.length() == 0) {
                for (int start = 0; start < trimmed.length(); start += 800) {
                    result.add(trimmed.substring(start, Math.min(start + 800, trimmed.length())).trim());
                }
            } else {
                if (buf.length() > 0) buf.append("\n\n");
                buf.append(trimmed);
            }
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

    private List<String> saveChunks(KnowledgeDocument doc, List<String> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setStoreId(cur.storeId());
            chunk.setDocumentId(doc.getId());
            chunk.setContent(chunks.get(i));
            chunk.setSeq(i);
            chunk.setCreatedAt(OffsetDateTime.now());
            applyEmbedding(chunk);
            chunkRepo.insert(chunk);
        }
        return chunks;
    }

    private boolean applyEmbedding(KnowledgeChunk chunk) {
        if (!embeddingService.isConfigured()) return false;
        try {
            float[] embedding = embeddingService.embed(chunk.getContent());
            if (embedding == null || embedding.length == 0) return false;
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < embedding.length; i++) {
                if (i > 0) json.append(',');
                json.append(embedding[i]);
            }
            json.append(']');
            chunk.setEmbedding(json.toString());
            chunk.setEmbeddingModel(embeddingService.model());
            return true;
        } catch (Exception e) {
            log.warn("知识片段向量化失败，将使用关键词检索: {}", e.getMessage());
            return false;
        }
    }
}
