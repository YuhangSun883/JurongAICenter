package com.jurong.aicenter.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurong.aicenter.client.AssetsProxyClient;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 素材库代理 Controller（按《聚融中转站接口手册 v2.1》§9）
 * 把 Spring Boot 当作客户端，调 :8090/v1/assets 转发到 aicoming 素材库。
 *
 * <p>前端用这里作为统一入口，避开跨域调 :8090 的麻烦。
 *
 * <p>响应字段：
 * <pre>
 * {
 *   "code": 0,
 *   "data": { id, asset_url, name, type, status, ... },
 *   "message": "ok"
 * }
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetsProxyController {

    private final AssetsProxyClient assetsProxyClient;

    /**
     * 上传图片到 aicoming 素材库，拿到 asset_url (asset://aic_xxx)。
     *
     * <p>前端在「AI 生成图片」「AI 生成视频」引用本地图片前，先调这个端点。
     */
    @PostMapping("/upload")
    public Map<String, Object> uploadImage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam("file") MultipartFile file) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "file 不能为空");
        }
        log.info("[ASSET-CTRL] 上传请求: userId={}, filename={}, size={}B, contentType={}",
            principal.id(), file.getOriginalFilename(), file.getSize(), file.getContentType());

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("[ASSET-CTRL] 读取文件失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INVALID_PARAM, "读取文件失败: " + e.getMessage());
        }

        JsonNode resp;
        try {
            resp = assetsProxyClient.uploadImageAndWaitActive(
                bytes, file.getOriginalFilename(), file.getContentType());
        } catch (Exception e) {
            log.error("[ASSET-CTRL] 上传到 aicoming 失败: userId={}, err={}",
                principal.id(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "素材上传失败: " + e.getMessage());
        }

        // 把 :8090 响应包成统一格式 {code, data, message}
        Map<String, Object> out = new HashMap<>();
        out.put("code", resp.path("code").asInt(0));
        out.put("data", resp.path("data"));
        out.put("message", resp.path("message").asText("ok"));
        return out;
    }
}