package com.jurong.aicenter.service;

import java.io.InputStream;
import java.util.List;

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
     * 2026-08-13 新增:MinIO 兜底轮询 - 按前缀列对象
     *
     * @param prefix 路径前缀
     * @param recursive 是否递归子目录
     * @return 该前缀下的所有对象 key 列表(按 lastModified 倒序,最新在前)
     */
    List<String> listObjectsByPrefix(String prefix, boolean recursive);

    /**
     * 2026-08-14 新增:获取 MinIO 对象的输入流(给 MediaController 流式读取用)。
     * 调用方负责关闭 InputStream。
     *
     * @param objectKey MinIO 对象 key
     * @return InputStream
     */
    java.io.InputStream getFileStream(String objectKey);

    /**
     * uploadAiMedia 的返回体
     */
    record UploadResult(String objectKey, String url) {}
}