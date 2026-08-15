package com.jurong.aicenter.service.impl;

import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.service.StorageService;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.http.Method;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * 2026-08-13 新增:MinIO 兜底轮询 - 按前缀列对象,找视频/图片。
     *
     * <p>场景:NewAPI 中转站元数据被清理(task_not_exist),但上游 aicoming 已把视频文件同步到 MinIO。
     * 此时不再依赖 NewAPI poll,直接从 MinIO 拿视频。</p>
     *
     * <p>返回该前缀下所有对象 key 列表(按时间倒序,最新在前),调用方按业务规则筛(取最新的 mp4)。</p>
     *
     * @param prefix 路径前缀,如 "i2v-result/" 或 "i2v-result/task_xxx/"
     * @param recursive 是否递归子目录
     * @return 对象 key 列表(按 lastModified 倒序)
     */
    public List<String> listObjectsByPrefix(String prefix, boolean recursive) {
        // 返回 Map<key, lastModifiedInstant>,调用方按 lastModified 排序或筛选
        List<Object[]> items = new ArrayList<>();  // [key, lastModifiedMillis]
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .recursive(recursive)
                    .build());
            for (Result<Item> r : results) {
                try {
                    Item item = r.get();
                    if (item.isDir()) continue;
                    // 跳过 size=0 的删除标记对象(etag 包含 delete marker 标记)
                    String etag = item.etag();
                    if (etag != null && etag.isEmpty()) continue;
                    long lastModMs = 0L;
                    try {
                        // Item.lastModified() 返回 ZonedDateTime(java.time),取 Instant 排序
                        lastModMs = item.lastModified().toInstant().toEpochMilli();
                    } catch (Exception ignore) {}
                    items.add(new Object[]{item.objectName(), lastModMs});
                } catch (Exception innerEx) {
                    log.warn("[MinIO] 解析对象失败: prefix={}, err={}", prefix, innerEx.getMessage());
                }
            }
            // 按 lastModified 倒序(latest first)
            items.sort((a, b) -> Long.compare((Long) b[1], (Long) a[1]));
            List<String> keys = new ArrayList<>();
            for (Object[] o : items) keys.add((String) o[0]);
            log.info("[MinIO] 列对象完成: prefix={}, count={}, keys={}", prefix, keys.size(), keys);
            return keys;
        } catch (Exception e) {
            log.error("[MinIO] listObjectsByPrefix 失败: prefix={}, err={}", prefix, e.getMessage());
            return new ArrayList<>();
        }
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