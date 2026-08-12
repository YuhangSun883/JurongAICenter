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
 * Bucket 结构：
 *   - AI 生成产物 / 收藏图片：media/{userId}/{yyyy-MM}/{uuid}.{ext}
 *   - 用户自行上传素材：media/{userId}/{yyyy-MM}/{uuid}.{ext}
 *   （两类统一用 media/ 路径，与"我的资产"目录保持一致）
 */
public interface StorageService {

    /**
     * 上传文件到 MinIO（AI 生成产物路径）
     * 路径规则：media/{userId}/{yyyy-MM}/{uuid}.{ext}
     *
     * @return UploadResult 包含 objectKey 和访问 URL
     */
    UploadResult uploadAiMedia(Long userId, String ext, InputStream input, String contentType);

    /**
     * 上传文件到 MinIO（兼容旧接口，内部仍走 media/ 路径）
     */
    String uploadFile(Long userId, Long jobId, String filename,
                      InputStream input, String contentType);

    /**
     * 自定义 objectKey 上传
     */
    String uploadObject(String objectKey, InputStream input, String contentType);

    /**
     * 删除文件
     */
    void deleteFile(String objectKey);

    String getPresignedUrl(String objectKey, int expiryHours);

    /**
     * 从 MinIO 读取文件流（用于后端代理/流式转发给前端，避免跨域）
     */
    InputStream getFileStream(String objectKey);

    /**
     * uploadAiMedia 的返回体
     */
    record UploadResult(String objectKey, String url) {}
}