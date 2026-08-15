package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.productimage.CreateProductImageTaskRequest;
import com.jurong.aicenter.dto.productimage.ProductImageAnalysisResponse;
import com.jurong.aicenter.dto.productimage.ProductImageOptions;
import com.jurong.aicenter.dto.productimage.ProductImageTaskResponse;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.ProductImageSetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商详套图控制器（「一张图」生成一套商品详情图）
 * <p>
 * 功能参考 da-ai.cc/toolbox/ecommerce-image-set：
 * 前端页面 tools/product-image 预留的 6 个接口，按 frontend/src/types/product-image.ts 约定实现。
 */
@Slf4j
@RestController
@RequestMapping("/api/product-image")
@RequiredArgsConstructor
public class ProductImageController {

    private final ProductImageSetService productImageSetService;

    /** 可选模型档位（标准版 / 高级版） */
    @GetMapping("/models")
    public List<ProductImageOptions.ModelOption> listModels() {
        return ProductImageOptions.MODELS;
    }

    /** 分辨率选项（1K / 2K / 4K） */
    @GetMapping("/resolutions")
    public List<ProductImageOptions.ResolutionOption> listResolutions() {
        return ProductImageOptions.RESOLUTIONS;
    }

    /** 输出格式选项（PNG / JPEG） */
    @GetMapping("/formats")
    public List<ProductImageOptions.FormatOption> listFormats() {
        return ProductImageOptions.FORMATS;
    }

    /** 参考示例（中间轮播） */
    @GetMapping("/examples")
    public List<ProductImageOptions.ExampleItem> listExamples() {
        return ProductImageOptions.EXAMPLES;
    }

    /** 套图类型名称（分析卡片的类型下拉选项：主视图 / 卖点图 …） */
    @GetMapping("/roles")
    public List<String> listRoles() {
        return productImageSetService.listRoleNames();
    }

    /**
     * 提交套图生成任务。
     * 后端立即返回任务（status=running），内部异步逐张调用图生图链路，前端轮询任务状态。
     */
    @PostMapping("/tasks")
    public ProductImageTaskResponse createTask(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateProductImageTaskRequest request) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        log.info("商详套图任务提交: userId={}, lang={}, count={}, modelKey={}, resolution={}, assetIds={}",
            principal.id(), request.getLang(), request.getCount(), request.getModelKey(),
            request.getResolution(),
            request.getAssetIds() == null ? 0 : request.getAssetIds().size());
        return productImageSetService.createTask(principal.id(), request);
    }

    /** 查询任务状态（前端轮询） */
    @GetMapping("/tasks/{taskId}")
    public ProductImageTaskResponse getTask(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String taskId) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        return productImageSetService.getTask(taskId);
    }

    /**
     * 提交商品详解分析任务（「分析结果」标签页）。
     * 多模态 LLM 结合商品图逐张输出商详图设计分析，文案语言 = 用户选择的语种；前端轮询状态。
     */
    @PostMapping("/analysis")
    public ProductImageAnalysisResponse createAnalysis(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateProductImageTaskRequest request) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        log.info("商详套图分析提交: userId={}, lang={}, count={}, assetIds={}",
            principal.id(), request.getLang(), request.getCount(),
            request.getAssetIds() == null ? 0 : request.getAssetIds().size());
        return productImageSetService.createAnalysis(principal.id(), request);
    }

    /** 查询分析任务状态（前端轮询） */
    @GetMapping("/analysis/{taskId}")
    public ProductImageAnalysisResponse getAnalysis(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String taskId) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        return productImageSetService.getAnalysis(taskId);
    }
}
