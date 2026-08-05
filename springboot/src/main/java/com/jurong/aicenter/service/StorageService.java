package com.jurong.aicenter.service;

import java.io.InputStream;

/**
 * 对象存储服务（MinIO / OSS）
 *
 * Phase 4 - C 负责实现
 *
 * 接口职责：
 *   - uploadFile: 把 ComfyUI 生成的产物上传到 MinIO，返回永久 URL
 *   - getPresignedUrl: 给前端可访问的 URL
 *
 * Bucket 结构建议：ai-platform/{user_id}/{job_id}/{filename}
 */
public interface StorageService {

    /**
     * 上传文件到 MinIO
     * @param userId  用户 ID（用于组织路径）
     * @param jobId   任务 ID（用于组织路径）
     * @param filename 原始文件名
     * @param input    文件输入流
     * @param contentType MIME type (image/png, video/mp4, ...)
     * @return 公开可访问的 URL
     */
    String uploadFile(Long userId, Long jobId, String filename,
                      InputStream input, String contentType);

    /**
     * 删除文件
     */
    void deleteFile(String objectKey);

    /**
     * C7 - 生成可访问的预签名 URL
     * @param objectKey  对象 key（如 "ai-platform/3/1/photo.png"）
     * @param expiryHours 过期时间（小时）
     * @return 24h 有效期内可访问的 URL
     */
    String getPresignedUrl(String objectKey, int expiryHours);
}