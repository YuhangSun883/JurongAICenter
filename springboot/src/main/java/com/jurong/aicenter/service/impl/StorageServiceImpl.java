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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageServiceImpl implements StorageService {

    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    @Override
    public UploadResult uploadAiMedia(Long userId, String ext, InputStream input, String contentType) {
        String ym = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String suffix = (ext != null && !ext.isBlank()) ? ext : "bin";
        if (suffix.startsWith(".")) {
            suffix = suffix.substring(1);
        }
        String objectKey = String.format("media/%d/%s/%s.%s",
            userId, ym, UUID.randomUUID().toString().replace("-", ""), suffix);
        try {
            minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .stream(input, -1, 10 * 1024 * 1024)
                .contentType(contentType)
                .build());
            String url = getPresignedUrl(objectKey, 24);
            return new UploadResult(objectKey, url);
        } catch (Exception e) {
            log.error("MinIO uploadAiMedia failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Upload failed: " + e.getMessage());
        }
    }

    @Override
    public String uploadFile(Long userId, Long jobId, String filename,
                             InputStream input, String contentType) {
        // 保持旧接口签名，但存储路径统一改成 media/{userId}/{yyyy-MM}/{uuid}.{ext}
        String ext = extractExt(filename);
        UploadResult r = uploadAiMedia(userId, ext, input, contentType);
        log.info("uploadFile (userId={}, jobId={}, filename={}) → {}", userId, jobId, filename, r.objectKey());
        return r.url();
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

    @Override
    public InputStream getFileStream(String objectKey) {
        try {
            return minioClient.getObject(
                io.minio.GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build()
            );
        } catch (Exception e) {
            log.error("Get file stream failed: objectKey={}, error={}", objectKey, e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to read file: " + e.getMessage());
        }
    }

    public io.minio.MinioClient getMinioClient() {
        return minioClient;
    }

    public String getBucket() {
        return bucket;
    }

    private String extractExt(String filename) {
        if (filename == null) return "bin";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "bin";
        return filename.substring(dot + 1).toLowerCase();
    }
}