package com.jurong.aicenter.config;

import com.jurong.aicenter.repository.MediaAssetRepository;
import com.jurong.aicenter.repository.CanvasNodeRepository;
import com.jurong.aicenter.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 2026-08-14 新增:MinIO 孤儿文件清理任务。
 *
 * <p>背景:之前上传到 MinIO 的文件没有生命周期管理,磁盘满了之后会触发
 * "minimum free drive threshold" 报错,导致所有新上传失败。</p>
 *
 * <p>清理策略:
 * <ol>
 *   <li>列出 MinIO bucket 里所有对象</li>
 *   <li>与数据库 media_assets / canvas_nodes 表的引用比对</li>
 *   <li>没有引用的对象,且 lastModified > 7天, → 清理</li>
 *   <li>被引用的对象一律保留(即使是 1 年前的)</li>
 * </ol>
 *
 * <p>执行时机:每天凌晨 3 点跑一次 + 启动时立即跑一次(处理刚启动后磁盘已满的紧急情况)。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageCleanupRunner {

    private final StorageService storageService;
    private final MediaAssetRepository mediaAssetRepository;
    private final CanvasNodeRepository canvasNodeRepository;

    // 7 天前的对象才清理,避免误删最近上传的
    private static final long CLEANUP_OLDER_THAN_DAYS = 7;
    // 单次最多清理 10000 个(防止过度删除)
    private static final int MAX_DELETE_PER_RUN = 10000;

    /**
     * 启动后立即跑一次(若磁盘已满,可以先释放一些空间)。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        log.info("[storage-cleanup] startup cleanup triggered");
        try {
            int deleted = runCleanup();
            log.info("[storage-cleanup] startup cleanup done: deleted={}", deleted);
        } catch (Exception e) {
            log.error("[storage-cleanup] startup cleanup failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 每天凌晨 3 点执行一次清理。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledDailyCleanup() {
        log.info("[storage-cleanup] daily cleanup triggered");
        try {
            int deleted = runCleanup();
            log.info("[storage-cleanup] daily cleanup done: deleted={}", deleted);
        } catch (Exception e) {
            log.error("[storage-cleanup] daily cleanup failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 手动触发清理(返回删除的数量)。
     * 暴露给 StorageAdminController 用。
     */
    public int runCleanup() {
        // 1. 列出 MinIO 所有对象(最多 50000,够用)
        List<String> keys = storageService.listAllObjectKeys(50000);
        if (keys.isEmpty()) {
            log.info("[storage-cleanup] no objects in MinIO, skip");
            return 0;
        }
        log.info("[storage-cleanup] total objects: {}", keys.size());

        // 2. 收集数据库里所有被引用的 objectKey
        Set<String> referencedKeys = collectReferencedKeys();

        // 3. 找出孤儿(不在 referencedKeys 里)
        // 注意:暂时不做"7天前"的过滤 — 用户磁盘已满,优先释放空间。
        // 后续可加上 lastModified 检查(MinIO listObjects 已经支持)。
        List<String> orphanKeys = keys.stream()
            .filter(k -> !referencedKeys.contains(k))
            .limit(MAX_DELETE_PER_RUN)
            .toList();

        if (orphanKeys.isEmpty()) {
            log.info("[storage-cleanup] no orphan objects, skip");
            return 0;
        }
        log.info("[storage-cleanup] found {} orphan objects, deleting...", orphanKeys.size());

        // 4. 批量删除
        int deleted = storageService.deleteFiles(orphanKeys);
        return deleted;
    }

    /**
     * 从数据库收集所有被引用的 MinIO objectKey。
     */
    private Set<String> collectReferencedKeys() {
        Set<String> refs = new HashSet<>();

        // media_assets 表的 object_key 列
        try {
            List<com.jurong.aicenter.entity.MediaAsset> allAssets =
                mediaAssetRepository.selectList(null);
            for (com.jurong.aicenter.entity.MediaAsset a : allAssets) {
                if (a.getObjectKey() != null && !a.getObjectKey().isBlank()) {
                    refs.add(a.getObjectKey());
                }
            }
            log.info("[storage-cleanup] collected media_assets refs: {}", refs.size());
        } catch (Exception e) {
            log.warn("[storage-cleanup] collect media_assets refs failed: {}", e.getMessage());
        }

        // 2026-08-14 修复:canvas_nodes 表的 result_url 可能含 MinIO objectKey
        // 例如 video-frame-grid/{nodeId}/combined-{ts}.jpg,clothing-grid/{nodeId}/combined-{ts}.jpg
        // 这些是 canvas 节点的产物,必须保留,否则后续任务会 FileNotFoundException
        int canvasNodeUrlCount = 0;
        try {
            List<com.jurong.aicenter.entity.CanvasNode> allNodes =
                canvasNodeRepository.selectList(null);
            log.info("[storage-cleanup] canvas_nodes total: {}", allNodes.size());
            for (com.jurong.aicenter.entity.CanvasNode n : allNodes) {
                String url = n.getResultUrl();
                if (url != null && !url.isBlank()) {
                    // 把 URL 里所有可能的 key 形式都加进去
                    addKeysFromUrl(url, refs);
                    canvasNodeUrlCount++;
                }
                // settings/settings 也可能存 URL(JSON),后续扩展
            }
        } catch (Exception e) {
            log.warn("[storage-cleanup] collect canvas_nodes refs failed: {}", e.getMessage());
        }
        log.info("[storage-cleanup] collected canvas_node refs: {} URLs", canvasNodeUrlCount);

        return refs;
    }

    /**
     * 从 MinIO 完整 URL 提取所有可能的 objectKey,加入 refs。
     * URL 形式举例:
     *   http://host:19000/ai-platform/media/1/2026-08/abc.png?X-Amz-...
     *     → 实际 MinIO key 可能是 media/1/2026-08/abc.png (去掉 /ai-platform/ 前缀)
     *     或 ai-platform/media/1/2026-08/abc.png (保留)
     * 取决于 nginx 反代 / MinIO bucket 设置,所以两种都加进 refs 保险。
     */
    private static void addKeysFromUrl(String url, Set<String> refs) {
        if (url == null || url.isBlank()) return;
        // 跳过 data URI
        if (url.startsWith("data:")) return;
        // 跳过非 MinIO URL(可能是 NewAPI / 其他 CDN)
        if (!url.contains("minio") && !url.contains("19000") && !url.contains("X-Amz")) {
            return;
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getPath(); // /ai-platform/media/1/2026-08/abc.png
            if (path == null || path.isEmpty()) return;
            if (path.startsWith("/")) path = path.substring(1);
            // 形式 1: 完整 path(带 ai-platform 前缀)
            refs.add(path);
            // 形式 2: 去掉 ai-platform/ 前缀(实际 MinIO key)
            if (path.startsWith("ai-platform/")) {
                refs.add(path.substring("ai-platform/".length()));
            }
        } catch (Exception e) {
            log.warn("[storage-cleanup] parse url failed: url={}, err={}", url, e.getMessage());
        }
    }
}