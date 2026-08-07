package com.jurong.aicenter.service.impl;

import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.service.StorageService;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 实现 - Phase 4 C 负责完整实现
 *
 * 当前是骨架代码。完整功能待 C 实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageServiceImpl implements StorageService {

    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    @Override
    public String uploadFile(Long userId, Long jobId, String filename,
                             InputStream input, String contentType) {
        try {
            String objectKey = String.format("ai-platform/%d/%d/%s", userId, jobId, filename);
            minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .stream(input, -1, 10 * 1024 * 1024)
                .contentType(contentType)
                .build());
            return getPresignedUrl(objectKey, 24);
        } catch (Exception e) {
            log.error("MinIO upload failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Upload failed: " + e.getMessage());
        }
    }

    @Override
    public String uploadObject(String objectKey, InputStream input, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .stream(input, -1, 10 * 1024 * 1024)
                .contentType(contentType)
                .build());
            return getPresignedUrl(objectKey, 24);
        } catch (Exception e) {
            log.error("MinIO uploadObject failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Upload failed: " + e.getMessage());
        }
    }

    @Override
    public void deleteFile(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build());
        } catch (Exception e) {
            log.warn("MinIO delete failed: {}", e.getMessage());
        }
    }

    @Override
    public String getPresignedUrl(String objectKey, int expiryHours) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(objectKey)
                .expiry(expiryHours, TimeUnit.HOURS)
                .build());
        } catch (Exception e) {
            log.error("Get presigned URL failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Get presigned URL failed: " + e.getMessage());
        }
    }
}