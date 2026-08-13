package com.jurong.aicenter.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/**
 * 聚融素材库代理（按《聚融中转站接口手册 v2.1》§9）
 * 调用 :8090/v1/assets 上传图片到 aicoming 拿 asset://aic_xxx。
 *
 * <p>背景：用户上传的图片如果要用于"图生图 / 图生视频"，必须先入库 aicoming
 * 拿到 {@code asset://aic_xxx} 引用形式（绕过 PrivacyInformation 审核）。
 * 直接传公网 URL 含真人图会被拒收。
 *
 * <p>Spring Boot 后端作为客户端代理（避免前端跨域调 :8090）。
 */
@Slf4j
@Component
public class AssetsProxyClient {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${assets-proxy.base-url}")
    private String baseUrl;

    @Value("${assets-proxy.upload-path:/v1/assets}")
    private String uploadPath;

    @Value("${assets-proxy.query-path:/v1/assets}")
    private String queryPath;

    public AssetsProxyClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    /**
     * 上传图片到 aicoming 素材库，拿到 asset_url (asset://aic_xxx)
     *
     * @param imageBytes 图片二进制
     * @param filename   文件名（含扩展名，如 "cat.png"）
     * @param mimeType   MIME 类型，如 image/png
     * @return 上传响应 JSON，包含 asset_id / asset_url / status / thumbnail_url 等
     */
    public JsonNode uploadImage(byte[] imageBytes, String filename, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("imageBytes 不能为空");
        }
        if (filename == null || filename.isBlank()) {
            filename = "upload.png";
        }
        final String finalFilename = filename;
        String useMime = (mimeType != null && !mimeType.isBlank()) ? mimeType : "image/png";
        MediaType mediaType = MediaType.parseMediaType(useMime);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() { return finalFilename; }
        }, mediaType);
        // 文档 §9.2：type 字段必填，image / video / audio 三选一
        builder.part("type", "image");

        log.info("[ASSET-UPLOAD] → POST {}{}: filename={}, mime={}, size={}B",
            baseUrl, uploadPath, finalFilename, useMime, imageBytes.length);

        try {
            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri(uploadPath)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(120))  // 上传 + 审核最多 2 分钟
                .onErrorMap(WebClientResponseException.class, e -> {
                    String body = e.getResponseBodyAsString();
                    log.error("[ASSET-UPLOAD] ← HTTP {}: body={}", e.getStatusCode(), body);
                    return new RuntimeException("素材上传失败: HTTP " + e.getStatusCode()
                        + " body=" + body);
                })
                .block();

            if (response == null) {
                throw new RuntimeException("素材上传返回为空");
            }
            log.info("[ASSET-UPLOAD] ← 响应: code={}, message={}, data={}",
                response.path("code").asText("?"),
                response.path("message").asText("?"),
                response.path("data"));

            // 文档 §9.2 响应：{code: 0, data: {id, asset_url, status, ...}, message: "ok"}
            JsonNode codeNode = response.get("code");
            if (codeNode != null && codeNode.asInt(-1) != 0) {
                String msg = response.path("message").asText("上传失败");
                throw new RuntimeException("素材上传被拒: " + msg);
            }
            JsonNode data = response.get("data");
            if (data == null || data.isMissingNode() || data.isNull()) {
                throw new RuntimeException("素材上传响应缺少 data 字段: " + response);
            }
            return response;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[ASSET-UPLOAD] ← 异常: {}", e.getMessage(), e);
            throw new RuntimeException("素材上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询单个素材状态（用于轮询 status=processing → active）。
     *
     * @param assetId 形如 aic_xxx
     * @return 响应 JSON（data 字段含 status / asset_url 等）
     */
    public JsonNode queryAsset(String assetId) {
        if (assetId == null || assetId.isBlank()) {
            throw new IllegalArgumentException("assetId 不能为空");
        }
        String url = queryPath + "/" + assetId;
        log.info("[ASSET-QUERY] → GET {}{}: assetId={}", baseUrl, url, assetId);
        try {
            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .get()
                .uri(url)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorMap(WebClientResponseException.class, e -> {
                    log.error("[ASSET-QUERY] ← HTTP {}: body={}", e.getStatusCode(), e.getResponseBodyAsString());
                    return new RuntimeException("查询素材失败: HTTP " + e.getStatusCode());
                })
                .block();
            if (response == null) {
                throw new RuntimeException("查询素材返回为空");
            }
            JsonNode data = response.get("data");
            String status = data == null ? "?" : data.path("status").asText("?");
            log.info("[ASSET-QUERY] ← status={}", status);
            return response;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("查询素材失败: " + e.getMessage(), e);
        }
    }

    /**
     * 上传 + 轮询直到 status=active 或 failed（按文档 §9.2，上传后要等入库完成）
     *
     * @param imageBytes 图片二进制
     * @param filename   文件名
     * @param mimeType   MIME 类型
     * @return 最终 data 节点（status=active 时必含 asset_url）
     */
    public JsonNode uploadImageAndWaitActive(byte[] imageBytes, String filename, String mimeType) {
        JsonNode uploadResp = uploadImage(imageBytes, filename, mimeType);
        JsonNode data = uploadResp.get("data");
        if (data == null || data.isMissingNode() || data.isNull()) {
            throw new RuntimeException("素材上传响应缺少 data");
        }

        String initialStatus = data.path("status").asText("");
        if ("active".equalsIgnoreCase(initialStatus)) {
            log.info("[ASSET-UPLOAD] 一次就绪: status=active");
            return uploadResp;
        }
        if ("failed".equalsIgnoreCase(initialStatus)) {
            String reason = data.path("fail_reason").asText("(no reason)");
            throw new RuntimeException("素材入库失败: " + reason);
        }

        // status=processing 时轮询（实测 ~1-10 秒，文档 §9.2）
        String assetId = data.path("id").asText("");
        if (assetId.isBlank()) {
            throw new RuntimeException("素材上传响应缺少 id 字段");
        }
        long start = System.currentTimeMillis();
        long timeoutMs = 60_000;  // 最多等 60 秒
        int intervalMs = 2_000;
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("轮询被中断");
            }
            JsonNode queryResp = queryAsset(assetId);
            JsonNode qData = queryResp.get("data");
            if (qData == null) continue;
            String status = qData.path("status").asText("");
            if ("active".equalsIgnoreCase(status)) {
                log.info("[ASSET-UPLOAD] 轮询就绪: assetId={}, waited={}ms",
                    assetId, System.currentTimeMillis() - start);
                // 把 data 替换成最新的，保留外层 code/message
                return queryResp;
            }
            if ("failed".equalsIgnoreCase(status)) {
                String reason = qData.path("fail_reason").asText("(no reason)");
                throw new RuntimeException("素材入库失败: " + reason);
            }
            // processing 继续等
        }
        throw new RuntimeException("素材入库超时（60s）: assetId=" + assetId);
    }
}