package com.jurong.aicenter.dto.canvas;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 节点连线（多端口版本）。
 *
 * <p>取代旧的 {@code List<String>} 格式，每条连线带：
 * <ul>
 *   <li>{@code port} — 输入/输出端口名（自由字符串）</li>
 *   <li>{@code nodeId} — 连接的节点 UUID</li>
 * </ul>
 *
 * <p>同一个端口可以连多条（fan-out / fan-in），例如：
 * <pre>
 * upstreamIds = [
 *   {"port": "video", "nodeId": "uuid-1"},
 *   {"port": "text",  "nodeId": "uuid-2"}
 * ]
 * </pre>
 *
 * <p>存储：序列化进 {@code canvas_nodes.upstream_ids} / {@code .downstream_ids}
 * 作为 JSON 字符串。运行期解析为 {@code List<NodeConnection>}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodeConnection {

    /** 端口名（如 "video" / "text" / "frames" / "clothing"），前端和节点定义约定 */
    private String port;

    /** 连接的节点 ID（UUID） */
    private String nodeId;

    public NodeConnection(String nodeId) {
        this.port = "default";
        this.nodeId = nodeId;
    }
}