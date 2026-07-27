package com.storeai.knowledge.service;

import com.storeai.common.service.StorageService;
import com.storeai.common.util.CurrentUser;
import com.storeai.knowledge.entity.KnowledgeChunk;
import com.storeai.knowledge.entity.KnowledgeDocument;
import com.storeai.knowledge.repository.KnowledgeChunkRepository;
import com.storeai.knowledge.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 防回归：没有 MinIO 的本机启动也必须能完成知识文件入库。 */
class KnowledgeServiceLocalStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadFallsBackToLocalOriginalAndStillCreatesSearchableDocument() throws Exception {
        var docRepo = mock(KnowledgeDocumentRepository.class);
        var chunkRepo = mock(KnowledgeChunkRepository.class);
        var retrieve = mock(KnowledgeRetrieveService.class);
        var embedding = mock(EmbeddingService.class);
        var storage = mock(StorageService.class);
        var currentUser = mock(CurrentUser.class);

        when(currentUser.isAdmin()).thenReturn(true);
        when(currentUser.storeId()).thenReturn("store-test");
        when(currentUser.employeeId()).thenReturn("employee-test");
        when(embedding.isConfigured()).thenReturn(false);
        doAnswer(invocation -> {
            invocation.<KnowledgeDocument>getArgument(0).setId("knowledge-test");
            return 1;
        }).when(docRepo).insert(any(KnowledgeDocument.class));

        var service = new KnowledgeService(docRepo, chunkRepo, retrieve, embedding, storage, currentUser);
        ReflectionTestUtils.setField(service, "storageProvider", "local");
        ReflectionTestUtils.setField(service, "knowledgeLocalPath", tempDir.toString());

        var file = new MockMultipartFile(
            "file", "门店话术.md", "text/markdown", "# 成交话术\n先确认客户需求，再说明方案。".getBytes());
        KnowledgeDocument document = service.upload(
            file, "成交话术", "销售", List.of("owner", "consultant"), "话术", "测试", "active");

        assertNotNull(document.getFileUrl());
        assertTrue(document.getFileUrl().startsWith("local://store-test/"));
        Path saved = tempDir.resolve(document.getFileUrl().substring("local://".length()));
        assertTrue(Files.isRegularFile(saved));
        assertTrue(Files.readString(saved).contains("确认客户需求"));
        verifyNoInteractions(storage);
        verify(chunkRepo, atLeastOnce()).insert(any(KnowledgeChunk.class));
    }
}
