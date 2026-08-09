package com.jurong.aicenter.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Map;
import java.util.UUID;

/**
 * ComfyUI HTTP 客户端
 *
 * 接口契约：
 *   POST /prompt           提交工作流，返回 { prompt_id, number, node_errors }
 *   GET  /history/{id}     查任务历史
 *   GET  /view             下载产物
 *   WS   /ws?clientId=xxx  WebSocket 进度（暂不实现，先用轮询）
 *
 * Phase 4 - C 负责实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComfyUIClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${comfyui.base-url}")
    private String baseUrl;

    /** 提交工作流，返回 prompt_id；若 ComfyUI 拒绝（node_errors）或网络失败，抛 BusinessException */
    public String submit(JsonNode workflow) {
        Map<String, Object> body = Map.of(
            "prompt", workflow,
            "client_id", UUID.randomUUID().toString()
        );
        return webClientBuilder.baseUrl(baseUrl).build()
            .post()
            .uri("/prompt")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .map(this::extractPromptIdOrThrow)
            .onErrorMap(e -> {
                if (e instanceof BusinessException) return e;
                // 4xx 错误：抓 response body 以诊断为什么 ComfyUI 拒绝
                String detail = e.getMessage();
                if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
                    org.springframework.web.reactive.function.client.WebClientResponseException wcre =
                        (org.springframework.web.reactive.function.client.WebClientResponseException) e;
                    String respBody = wcre.getResponseBodyAsString();
                    log.error("ComfyUI /prompt 拒绝 ({}): body={}", wcre.getStatusCode(), respBody);
                    detail = String.format("%s | body: %s", wcre.getStatusCode(), respBody);
                } else {
                    log.error("ComfyUI /prompt failed: {}", e.getMessage());
                }
                return new BusinessException(ErrorCode.COMFYUI_REJECTED, detail);
            })
            .block();
    }

    /** 从 ComfyUI /prompt 响应里拿 prompt_id；没拿到或 node_errors 非空就抛 COMFYUI_REJECTED */
    private String extractPromptIdOrThrow(JsonNode response) {
        if (response == null) {
            throw new BusinessException(ErrorCode.COMFYUI_REJECTED, "ComfyUI 返回空响应");
        }
        JsonNode errors = response.get("node_errors");
        if (errors != null && !errors.isNull() && errors.size() > 0) {
            log.warn("ComfyUI node_errors: {}", errors);
            throw new BusinessException(ErrorCode.COMFYUI_REJECTED, errors.toString());
        }
        JsonNode promptIdNode = response.get("prompt_id");
        if (promptIdNode == null || promptIdNode.isNull() || promptIdNode.asText().isEmpty()) {
            throw new BusinessException(ErrorCode.COMFYUI_REJECTED, "ComfyUI 未返回 prompt_id");
        }
        return promptIdNode.asText();
    }

    /**
     * C5 - 查任务历史。ComfyUI 响应格式：{ "<prompt_id>": { "outputs": {...}, "status": {...}, "messages": [...] } }
     * 行为约定：
     *   - 404 (promptId 不在 history 里) → 返回 null（任务还在跑或 ComfyUI 还没记录）
     *   - 200 + 有效 entry → 返回整个 history 对象（调用方根据 promptId 取自己的 entry）
     *   - 其它网络错误 → 抛 BusinessException(COMFYUI_UNREACHABLE)
     */
    public JsonNode pollHistory(String promptId) {
        try {
            return webClientBuilder.baseUrl(baseUrl).build()
                .get()
                .uri("/history/{id}", promptId)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        } catch (WebClientResponseException.NotFound e) {
            log.debug("ComfyUI /history/{} → 404 (still running or not recorded)", promptId);
            return null;
        } catch (Exception e) {
            log.error("ComfyUI /history/{} failed: {}", promptId, e.getMessage());
            throw new BusinessException(ErrorCode.COMFYUI_UNREACHABLE, e.getMessage());
        }
    }

    /** 下载产物（图片/视频）到内存（小文件用，大文件用 downloadStream） */
    public byte[] download(String filename, String subfolder, String type) {
        String sub = subfolder == null ? "" : subfolder;
        String t = type == null ? "output" : type;
        return webClientBuilder.baseUrl(baseUrl).build()
            .get()
            .uri("/view?filename={f}&subfolder={s}&type={t}", filename, sub, t)
            .retrieve()
            .bodyToMono(byte[].class)
            .block();
    }

    /**
     * 流式下载产物（C3 轮询用：边下边传 MinIO，避免大视频塞满内存）。
     * 返回的 InputStream 在订阅管线关闭/出错时自动 EOF。
     */
    public InputStream downloadStream(String filename, String subfolder, String type) {
        String sub = subfolder == null ? "" : subfolder;
        String t = type == null ? "output" : type;

        PipedInputStream pis = new PipedInputStream(64 * 1024);
        PipedOutputStream pos;
        try {
            pos = new PipedOutputStream(pis);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "PipedOutputStream init failed: " + e.getMessage());
        }

        webClientBuilder.baseUrl(baseUrl).build()
            .get()
            .uri("/view?filename={f}&subfolder={s}&type={t}", filename, sub, t)
            .accept(MediaType.APPLICATION_OCTET_STREAM)
            .retrieve()
            .bodyToFlux(DataBuffer.class)
            .subscribe(
                buffer -> {
                    try {
                        int readable = buffer.readableByteCount();
                        byte[] bytes = new byte[readable];
                        buffer.read(bytes);
                        pos.write(bytes);
                        DataBufferUtils.release(buffer);  // 显式释放
                    } catch (IOException e) {
                        log.error("downloadStream write error: {}", e.getMessage());
                    }
                },
                error -> {
                    log.error("downloadStream failed: {}", error.getMessage());
                    try { pos.close(); } catch (IOException ignored) {}
                },
                () -> {
                    try { pos.close(); } catch (IOException ignored) {}
                }
            );
        return pis;
    }

    /** 健康检查 */
    public boolean isReachable() {
        try {
            webClientBuilder.baseUrl(baseUrl).build()
                .get()
                .uri("/system_stats")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
            return true;
        } catch (Exception e) {
            log.warn("ComfyUI {} unreachable: {}", baseUrl, e.getMessage());
            return false;
        }
    }

    /**
     * C9 - 取消当前正在执行的 prompt（POST /interrupt）。
     * ComfyUI 不会确认某个具体 prompt_id；只中断当前正在跑的那一个。
     * 失败不抛异常（取消是 best-effort 行为）。
     */
    public void interrupt() {
        try {
            webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/interrupt")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
            log.info("ComfyUI /interrupt sent");
        } catch (Exception e) {
            log.warn("ComfyUI /interrupt failed: {}", e.getMessage());
        }
    }

    /**
     * 图生图：把本地图片上传到 ComfyUI 的 input 文件夹，返回 ComfyUI 给的文件名。
     * ComfyUI 响应：{ "name": "xxx.png", "subfolder": "", "type": "input" }
     * 之后用返回的 name 在 workflow 的 LoadImage 节点引用。
     */
    public String uploadImage(byte[] data, String filename, String contentType) {
        LinkedMultiValueMap<String, HttpEntity<?>> parts = new LinkedMultiValueMap<>();

        ContentDisposition disposition = ContentDisposition.builder("form-data")
            .name("image")
            .filename(filename)
            .build();

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(
            contentType != null ? contentType : "application/octet-stream"));
        fileHeaders.setContentDisposition(disposition);

        HttpEntity<byte[]> filePart = new HttpEntity<>(data, fileHeaders);
        parts.add("image", filePart);

        JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
            .post()
            .uri("/upload/image")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorMap(e -> {
                // 关键:把 ComfyUI 响应 body 抠出来,否则 500 只能看到 "500 Internal Server Error"
                String detail = e.getMessage();
                if (e instanceof WebClientResponseException wcre) {
                    String respBody = wcre.getResponseBodyAsString();
                    log.error("ComfyUI /upload/image FAILED: status={}, body={}",
                              wcre.getStatusCode(), respBody);
                    detail = String.format("%s | body: %s | filename: %s | size: %d bytes",
                                           wcre.getStatusCode(),
                                           respBody == null ? "(empty)" : respBody,
                                           filename, data.length);
                } else {
                    log.error("ComfyUI /upload/image failed: filename={}, size={}, err={}",
                              filename, data.length, e.getMessage());
                }
                return new BusinessException(ErrorCode.COMFYUI_UNREACHABLE, detail);
            })
            .block();

        if (response == null || response.get("name") == null) {
            throw new BusinessException(ErrorCode.COMFYUI_REJECTED, "ComfyUI /upload/image 未返回 name");
        }
        String returnedName = response.get("name").asText();
        log.info("ComfyUI /upload/image: {} → {}", filename, returnedName);
        return returnedName;
    }
}