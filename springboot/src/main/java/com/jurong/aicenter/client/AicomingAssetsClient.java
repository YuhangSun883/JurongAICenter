package com.jurong.aicenter.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/**
 * aicoming-video-proxy 资产 CRUD 客户端
 *
 * <p>严格按 Assets-API 参考手册实现：
 * <ul>
 *   <li>§4.1.1 POST /v1/assets (multipart) — 上传图片，返回 asset_url</li>
 *   <li>§4.2     GET  /v1/assets/{id}       — 查询单个 / 轮询到 active</li>
 *   <li>§4.4     DELETE /v1/assets/{id}     — 删除素材</li>
 * </ul>
 *
 * <p>关键约束（手册 §2）：
 * <ul>
 *   <li>资产 CRUD <b>必须直连 proxy 8080</b>，走 NewAPI 会 404（channel #4 白名单拒绝 /v1/assets）</li>
 *   <li>视频生成（POST /v1/videos）走 NewAPI 3000，<b>不走这里</b></li>
 *   <li>客户端只认 NEWAPI_TOKEN（手册 §1），用 ${aicoming.proxy.token} 注入（= ${newapi.token}）</li>
 * </ul>
 *
 * <p>响应外层是 NewAPI 风格：{@code {code:0, data:{...}, message:"ok"}}。
 * 列表端点 data 是数组，total/limit/offset 跟 data 同级（手册 §3.2 / §4.3）。
 * 本客户端只用到 POST / GET 单个 / DELETE，不需要列表。
 */
@Slf4j
@Component
public class AicomingAssetsClient {

    private final WebClient.Builder webClientBuilder;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${newapi.base-url}")
    private String baseUrl;

    @Value("${newapi.token}")
    private String token;

    public AicomingAssetsClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
        this.restTemplate = new RestTemplate();
        // 添加请求拦截器：记录实际发送的 HTTP 请求头（调试 multipart 400 问题）
        this.restTemplate.getInterceptors().add((request, body, execution) -> {
            log.info("[ASSET-UP-REQ] {} {}", request.getMethod(), request.getURI());
            request.getHeaders().forEach((k, v) -> {
                // 不记录 Authorization 的完整值
                String val = "Authorization".equalsIgnoreCase(k) ? v.get(0).substring(0, 20) + "..." : v.toString();
                log.info("[ASSET-UP-REQ] Header: {} = {}", k, val);
            });
            log.info("[ASSET-UP-REQ] Body size: {} bytes, first 200: {}",
                body.length,
                new String(body, 0, Math.min(200, body.length)).replaceAll("\r\n", "\\r\\n"));
            return execution.execute(request, body);
        });
    }

    /**
     * §4.1.1 multipart 上传图片素材。
     *
     * <p>响应立即返回 {@code status="processing"}，<b>不能立即用于视频生成</b>，
     * 必须再调 {@link #pollUntilActive} 等到 {@code status="active"}。
     *
     * @param fileBytes    图片二进制（jpg/png/webp/gif）
     * @param filename     文件名（aicoming 用来识别格式）
     * @param contentType  MIME 类型，如 image/png
     * @param name         资产名（可空，缺省为空字符串）
     * @return 上传响应里的 {@code data} 节点（含 id / asset_url / status 等）
     */
    public JsonNode uploadAssetByMultipart(byte[] fileBytes, String filename,
                                           String contentType, String name) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new BusinessException(ErrorCode.ASSET_UPLOAD_FAILED, "图片字节为空");
        }
        final String fname = (filename != null && !filename.isBlank()) ? filename : "upload.png";
        final String mime = (contentType != null && !contentType.isBlank()) ? contentType : "image/png";

        log.info("[ASSET-UP] → POST {}/v1/assets (multipart via RestTemplate): filename={}, contentType={}, size={}B, name={}",
            baseUrl, fname, mime, fileBytes.length, name);

        // 用 RestTemplate 发送 multipart 请求（而非 WebClient）
        // 原因：Reactor Netty (WebClient) 默认用 Transfer-Encoding: chunked，
        // aicoming-proxy 的 nginx 拒绝 chunked multipart 请求返回 400。
        // RestTemplate 会设置 Content-Length，与 curl 行为一致。
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        if (name != null && !name.isBlank()) {
            body.add("name", name);
        }
        body.add("file", new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() { return fname; }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Authorization", "Bearer " + token);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> respEntity = restTemplate.postForEntity(
                baseUrl + "/v1/assets", requestEntity, String.class);

            String respStr = respEntity.getBody();
            log.info("[ASSET-UP] ← HTTP {}: {}", respEntity.getStatusCode(), truncateForLog(respStr, 2000));

            JsonNode resp = objectMapper.readTree(respStr);
            JsonNode data = unwrapData(resp, Op.UPLOAD);
            log.info("[ASSET-UP] ← 上传成功: id={}, asset_url={}, status={}, name={}",
                data.path("id").asText(),
                data.path("asset_url").asText(),
                data.path("status").asText(),
                data.path("name").asText());
            return data;
        } catch (BusinessException e) {
            throw e;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            String respBody = e.getResponseBodyAsString();
            log.error("[ASSET-UP] ← HTTP {}: body={}", e.getStatusCode(), respBody);
            throw new BusinessException(ErrorCode.ASSET_UPLOAD_FAILED,
                e.getStatusCode() + " | " + respBody);
        } catch (Exception e) {
            log.error("[ASSET-UP] ← 异常: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.ASSET_UPLOAD_FAILED, e.getMessage());
        }
    }

    /**
     * §4.2 查询单个素材（轮询用）。
     *
     * @param assetId 形如 aic_xxx
     * @return 响应里的 {@code data} 节点（含 id / status / lines 等）；不存在返回 null
     */
    public JsonNode getAsset(String assetId) {
        log.debug("[ASSET-GET] → GET {}/v1/assets/{}", baseUrl, assetId);
        try {
            JsonNode resp = webClientBuilder.baseUrl(baseUrl).build()
                .get()
                .uri("/v1/assets/{id}", assetId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .block();
            log.debug("[ASSET-GET] ← 响应: {}", truncateForLog(resp == null ? "null" : resp.toString(), 1000));
            return unwrapData(resp, Op.QUERY);
        } catch (WebClientResponseException.NotFound e) {
            log.warn("[ASSET-GET] ← 404 asset 不存在: {}", assetId);
            return null;
        } catch (WebClientResponseException e) {
            log.error("[ASSET-GET] ← HTTP {}: body={}", assetId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.ASSET_NOT_ACTIVE,
                "查询素材失败: " + e.getStatusCode());
        } catch (BusinessException e) {
            // unwrapData 抛出来的 ASSET_NOT_ACTIVE 直接传
            throw e;
        } catch (Exception e) {
            log.error("[ASSET-GET] ← 异常: assetId={}, err={}", assetId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.ASSET_NOT_ACTIVE, e.getMessage());
        }
    }

    /**
     * §4.2 轮询直到素材就绪。
     *
     * <p>状态机（手册 §4.2）：
     * <pre>
     *   processing ──► active    (可用，返回 data)
     *             └────► failed   (抛 ASSET_NOT_ACTIVE)
     * </pre>
     *
     * <p>建议参数：{@code maxWaitSec=90, intervalSec=3}（手册 §4.2 末尾推荐）。
     * 绝大多数资产 6-9 秒内 active。
     *
     * @param assetId     形如 aic_xxx
     * @param maxWaitSec  最大等待秒数
     * @param intervalSec 轮询间隔秒数
     * @return status=active 时的 data 节点
     */
    public JsonNode pollUntilActive(String assetId, int maxWaitSec, int intervalSec) {
        log.info("[ASSET-POLL] → 开始轮询 asset: id={}, maxWaitSec={}, intervalSec={}",
            assetId, maxWaitSec, intervalSec);
        long start = System.currentTimeMillis();
        long deadline = start + maxWaitSec * 1000L;
        String lastStatus = "";
        int pollCount = 0;

        while (System.currentTimeMillis() < deadline) {
            pollCount++;
            JsonNode data = getAsset(assetId);
            if (data == null) {
                log.error("[ASSET-POLL] ← asset 不存在: {}", assetId);
                throw new BusinessException(ErrorCode.ASSET_NOT_ACTIVE,
                    "素材不存在: " + assetId);
            }
            String status = data.path("status").asText("unknown");
            if (!status.equals(lastStatus)) {
                log.info("[ASSET-POLL] ← 第{}次轮询状态变化: id={}, status={} (elapsedMs={})",
                    pollCount, assetId, status, System.currentTimeMillis() - start);
                lastStatus = status;
            }
            if ("active".equalsIgnoreCase(status)) {
                log.info("[ASSET-POLL] ← asset 已就绪: id={}, 轮询{}次, 耗时{}ms",
                    assetId, pollCount, System.currentTimeMillis() - start);
                return data;
            }
            if ("failed".equalsIgnoreCase(status)) {
                log.error("[ASSET-POLL] ← asset 处理失败: id={}, data={}", assetId, data);
                throw new BusinessException(ErrorCode.ASSET_NOT_ACTIVE,
                    "素材处理失败: " + data.toString());
            }
            try {
                Thread.sleep(intervalSec * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.ASSET_NOT_ACTIVE, "轮询被中断");
            }
        }
        log.error("[ASSET-POLL] ← 轮询超时: id={}, 轮询{}次, 耗时{}ms",
            assetId, pollCount, System.currentTimeMillis() - start);
        throw new BusinessException(ErrorCode.ASSET_NOT_ACTIVE,
            "素材 " + assetId + " 在 " + maxWaitSec + "s 内未就绪");
    }

    /**
     * §4.4 删除素材（永久删除，best-effort）。
     *
     * <p>手册 §4.4 警告：删除是永久的，资产本身 + 所有引用关系全清。
     * 已在视频生成里被引用的 asset 仍能跑完已有任务，但新建任务会失败。
     * 所以本方法只在视频任务完成后调用，且失败不抛异常（只 log warn）。
     */
    public boolean deleteAsset(String assetId) {
        log.info("[ASSET-DEL] → DELETE {}/v1/assets/{}", baseUrl, assetId);
        try {
            JsonNode resp = webClientBuilder.baseUrl(baseUrl).build()
                .delete()
                .uri("/v1/assets/{id}", assetId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .block();
            log.info("[ASSET-DEL] ← 响应: {}", truncateForLog(resp == null ? "null" : resp.toString(), 1000));
            JsonNode data = unwrapData(resp, Op.DELETE);
            boolean deleted = data.path("deleted").asBoolean(false);
            log.info("[ASSET-DEL] ← 删除完成: id={}, deleted={}", assetId, deleted);
            return deleted;
        } catch (Exception e) {
            // 清理是 best-effort，失败不影响主流程
            log.warn("[ASSET-DEL] ← 清理失败 (best-effort): id={}, err={}", assetId, e.getMessage());
            return false;
        }
    }

    /** unwrapData 的操作类型 — 决定用哪个错误码 */
    private enum Op { UPLOAD, QUERY, DELETE }

    /**
     * 解包 NewAPI 风格响应：{@code {code:0, data:{...}, message:"ok"}}。
     * 按操作类型选择正确的错误码（之前全部用 ASSET_UPLOAD_FAILED 会误导排查）。
     */
    private JsonNode unwrapData(JsonNode resp, Op op) {
        if (resp == null) {
            throw new BusinessException(
                op == Op.UPLOAD ? ErrorCode.ASSET_UPLOAD_FAILED :
                op == Op.DELETE ? ErrorCode.ASSET_DELETE_FAILED :
                ErrorCode.ASSET_NOT_ACTIVE,
                "Aicoming proxy " + op.name().toLowerCase() + " 返回空响应");
        }
        int code = resp.path("code").asInt(-1);
        if (code != 0) {
            String msg = resp.path("error").path("message").asText(
                resp.path("message").asText("unknown"));
            ErrorCode ec = op == Op.UPLOAD ? ErrorCode.ASSET_UPLOAD_FAILED :
                           op == Op.DELETE ? ErrorCode.ASSET_DELETE_FAILED :
                           ErrorCode.ASSET_NOT_ACTIVE;
            throw new BusinessException(ec,
                "Aicoming proxy " + op.name().toLowerCase()
                    + " 失败 code=" + code + " msg=" + msg);
        }
        JsonNode data = resp.get("data");
        if (data == null || data.isNull()) {
            ErrorCode ec = op == Op.UPLOAD ? ErrorCode.ASSET_UPLOAD_FAILED :
                           op == Op.DELETE ? ErrorCode.ASSET_DELETE_FAILED :
                           ErrorCode.ASSET_NOT_ACTIVE;
            throw new BusinessException(ec,
                "Aicoming proxy " + op.name().toLowerCase()
                    + " 响应无 data 字段: " + resp);
        }
        return data;
    }

    /** 把超长 JSON 字符串截断到指定长度，方便日志查看 */
    private String truncateForLog(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...(truncated, totalLen=" + s.length() + ")";
    }
}
