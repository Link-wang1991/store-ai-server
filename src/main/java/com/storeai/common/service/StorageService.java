package com.storeai.common.service;

import com.storeai.common.config.MinioConfig;
import com.storeai.common.exception.BizException;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 文件存储服务（当前适配 MinIO，可替换为 S3 / OSS 等）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final MinioClient minio;
    private final MinioConfig config;

    /** 保存知识库文件到公开桶 */
    public String saveKnowledge(String objectName, InputStream data, long size, String contentType) {
        try {
            ensureBucket(config.getBucketKnowledge());
            minio.putObject(PutObjectArgs.builder()
                    .bucket(config.getBucketKnowledge())
                    .object(objectName)
                    .stream(data, size, -1)
                    .contentType(contentType)
                    .build());
            return String.format("%s/%s/%s", config.getEndpoint(),
                    config.getBucketKnowledge(), objectName);
        } catch (Exception e) {
            log.error("文件上传失败", e);
            throw new BizException("文件上传失败: " + e.getMessage());
        }
    }

    /** 保存会谈录音到私有桶 */
    public String saveMeetingAudio(String objectName, InputStream data, long size) {
        try {
            ensureBucket(config.getBucketMeeting());
            minio.putObject(PutObjectArgs.builder()
                    .bucket(config.getBucketMeeting())
                    .object(objectName)
                    .stream(data, size, -1)
                    .contentType("audio/mpeg")
                    .build());
            return objectName;
        } catch (Exception e) {
            log.error("录音上传失败", e);
            throw new BizException("录音上传失败: " + e.getMessage());
        }
    }

    /** 生成预签名 URL（给 ASR 服务拉取私有文件） */
    public String presignedUrl(String objectName, int expiryHours) {
        try {
            return minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(config.getBucketMeeting())
                    .object(objectName)
                    .expiry(expiryHours, java.util.concurrent.TimeUnit.HOURS)
                    .method(io.minio.http.Method.GET)
                    .build());
        } catch (Exception e) {
            log.error("生成预签名 URL 失败", e);
            throw new BizException("文件访问失败");
        }
    }

    private void ensureBucket(String bucket) throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
