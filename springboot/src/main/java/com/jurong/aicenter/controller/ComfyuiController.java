package com.jurong.aicenter.controller;

import com.jurong.aicenter.client.ComfyUIClient;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * ComfyUI 辅助端点 - 图生图前置：把本地图片上传到 ComfyUI 的 input 文件夹。
 *
 * 用法：
 *   1. POST /api/comfyui/upload (multipart) → { filename, originalName }
 *   2. 创建 workflow，LoadImage 节点的 image 字段填返回的 filename
 *   3. POST /api/generate 提交
 */
@RestController
@RequestMapping("/api/comfyui")
@RequiredArgsConstructor
public class ComfyuiController {

    private final ComfyUIClient comfyUIClient;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> uploadImage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam("image") MultipartFile image) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (image.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "image 文件为空");
        }
        try {
            String filename = comfyUIClient.uploadImage(
                image.getBytes(),
                image.getOriginalFilename(),
                image.getContentType()
            );
            return Map.of(
                "filename", filename,
                "originalName", image.getOriginalFilename() == null ? "" : image.getOriginalFilename()
            );
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败: " + e.getMessage());
        }
    }
}
