package com.jurong.aicenter.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.dto.canvas.NodeConnection;
import com.jurong.aicenter.entity.Canvas;
import com.jurong.aicenter.entity.CanvasNode;
import com.jurong.aicenter.repository.CanvasNodeRepository;
import com.jurong.aicenter.repository.CanvasRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 画布模块启动 Backfill。
 *
 * <p>在 Spring Boot 启动后跑一次（CommandLineRunner），处理两件历史遗留：
 * <ol>
 *   <li><b>孤儿节点归位</b>：V11 之前的 canvas_nodes 没有 canvas_id，启动后给
 *       每个有孤儿节点的用户建一张 "默认画布" 并把所有孤儿挪进去</li>
 *   <li><b>连线格式升级</b>：把旧的 {@code List<String>} 格式（"uuid" 数组）
 *       转成新的 {@code List<NodeConnection>} 格式（[{port, nodeId}]）</li>
 * </ol>
 *
 * <p>幂等：每次启动都会跑，没数据时几毫秒就退出。
 * 跑完会 log 处理的节点数 / 转换的连线数。
 */
@Slf4j
@Component
@Order(100)  // 晚于 Flyway / MyBatis 初始化
@RequiredArgsConstructor
public class CanvasBackfillRunner implements CommandLineRunner {

    private final CanvasNodeRepository nodeRepository;
    private final CanvasRepository canvasRepository;
    private final ObjectMapper objectMapper;

    private static final String DEFAULT_CANVAS_NAME = "默认画布";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(String... args) {
        log.info("CanvasBackfillRunner: starting...");
        long t0 = System.currentTimeMillis();

        int assignedNodes = backfillOrphanNodes();
        int convertedConnections = upgradeConnectionFormat();

        long ms = System.currentTimeMillis() - t0;
        log.info("CanvasBackfillRunner: done in {}ms (assignedNodes={}, convertedConnections={})",
            ms, assignedNodes, convertedConnections);
    }

    /**
     * 把所有 canvas_id IS NULL 的节点归到对应用户的默认画布。
     */
    private int backfillOrphanNodes() {
        List<CanvasNode> orphans = nodeRepository.selectList(
            new LambdaQueryWrapper<CanvasNode>().isNull(CanvasNode::getCanvasId)
        );
        if (orphans.isEmpty()) {
            return 0;
        }
        log.info("CanvasBackfillRunner: found {} orphan nodes", orphans.size());

        // 按 userId 分组
        Map<Long, List<CanvasNode>> byUser = orphans.stream()
            .collect(Collectors.groupingBy(CanvasNode::getUserId));

        int assigned = 0;
        for (Map.Entry<Long, List<CanvasNode>> entry : byUser.entrySet()) {
            Long userId = entry.getKey();
            List<CanvasNode> userOrphans = entry.getValue();

            // 拿/建用户的默认画布
            Canvas defaultCanvas = getOrCreateDefaultCanvas(userId);

            // 把这批孤儿节点的 canvas_id 改成默认画布
            for (CanvasNode n : userOrphans) {
                n.setCanvasId(defaultCanvas.getId());
                n.setUpdatedAt(LocalDateTime.now());
                nodeRepository.updateById(n);
                assigned++;
            }
            log.info("CanvasBackfillRunner: userId={} → canvas={}, assigned {} orphan nodes",
                userId, defaultCanvas.getId(), userOrphans.size());
        }
        return assigned;
    }

    /**
     * 把所有节点里旧的 {@code List<String>} 连线 JSON 转成新的 {@code List<NodeConnection>}。
     * 已经是新格式的跳过（通过 try parse 判断）。
     */
    private int upgradeConnectionFormat() {
        List<CanvasNode> all = nodeRepository.selectList(null);
        if (all.isEmpty()) return 0;

        int converted = 0;
        for (CanvasNode n : all) {
            boolean upsChanged = tryConvertField(n, n.getUpstreamIds(), true);
            boolean downsChanged = tryConvertField(n, n.getDownstreamIds(), false);
            if (upsChanged || downsChanged) {
                nodeRepository.updateById(n);
                converted++;
            }
        }
        return converted;
    }

    /**
     * 尝试把单个 JSON 字段从旧 List&lt;String&gt; 转成新 List&lt;NodeConnection&gt;。
     * @return true 表示字段被改了（需要 update）
     */
    private boolean tryConvertField(CanvasNode n, String json, boolean isUpstream) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return false;
        }

        // 先按新格式试解析
        try {
            List<NodeConnection> newFormat = objectMapper.readValue(json,
                new TypeReference<List<NodeConnection>>() {});
            if (newFormat != null) {
                // 已经是新格式（或空数组），不动
                return false;
            }
        } catch (Exception ignore) {
            // fall through to old format
        }

        // 旧格式：List<String>
        try {
            List<String> oldIds = objectMapper.readValue(json,
                new TypeReference<List<String>>() {});
            if (oldIds == null || oldIds.isEmpty()) {
                return false;
            }
            List<NodeConnection> upgraded = new ArrayList<>(oldIds.size());
            for (String id : oldIds) {
                upgraded.add(new NodeConnection("default", id));
            }
            String newJson = objectMapper.writeValueAsString(upgraded);
            if (isUpstream) {
                n.setUpstreamIds(newJson);
            } else {
                n.setDownstreamIds(newJson);
            }
            log.debug("CanvasBackfillRunner: nodeId={} {} upgraded ({} → {} connections)",
                n.getId(), isUpstream ? "upstream" : "downstream", oldIds.size(), upgraded.size());
            return true;
        } catch (Exception e) {
            log.warn("CanvasBackfillRunner: failed to convert nodeId={} field={}: {}",
                n.getId(), isUpstream ? "upstream" : "downstream", e.getMessage());
            return false;
        }
    }

    private Canvas getOrCreateDefaultCanvas(Long userId) {
        Canvas existing = canvasRepository.selectOne(
            new LambdaQueryWrapper<Canvas>()
                .eq(Canvas::getUserId, userId)
                .eq(Canvas::getName, DEFAULT_CANVAS_NAME)
                .last("LIMIT 1")
        );
        if (existing != null) return existing;

        Canvas c = new Canvas();
        c.setUserId(userId);
        c.setName(DEFAULT_CANVAS_NAME);
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        canvasRepository.insert(c);
        return c;
    }
}