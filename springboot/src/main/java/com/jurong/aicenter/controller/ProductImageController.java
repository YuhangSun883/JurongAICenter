package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.productimage.BatchDeleteProductImageTasksRequest;
import com.jurong.aicenter.dto.productimage.CreateProductImageTaskRequest;
import com.jurong.aicenter.dto.productimage.ProductImageAnalysisResponse;
import com.jurong.aicenter.dto.productimage.ProductImageOptions;
import com.jurong.aicenter.dto.productimage.ProductImageTaskResponse;
import com.jurong.aicenter.dto.productimage.RefineAnalysisItemRequest;
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
import java.util.Map;

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
     * 批量删除任务（与资产批量删除 POST /api/assets/batch-delete 同构）：
     * 直接删除数据库中的数据——任务生成的结果图资产记录与 MinIO 文件物理删除，不可恢复。
     */
    @PostMapping("/tasks/batch-delete")
    public Map<String, Object> batchDeleteTasks(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody BatchDeleteProductImageTasksRequest request) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        log.info("商详套图任务批量删除: userId={}, ids={}", principal.id(), request.getIds());
        int deleted = productImageSetService.batchDeleteTasks(principal.id(), request.getIds());
        return Map.of("deleted", deleted, "requested", request.getIds().size());
    }

    /** 查询当前用户的套图任务列表（内存任务态，新任务在前）：页面重新进入时恢复任务队列与生成结果 */
    @GetMapping("/tasks")
    public List<ProductImageTaskResponse> listTasks(@AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        return productImageSetService.listTasks(principal.id());
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

    /**
     * 单条分析文案重写（同步）：分析卡片修改「定位/比例」后点「重新生成」，
     * 多模态 LLM 按新定位与画布比例重写该条设计分析；前端拿到新文案后再提交图片生成。
     */
    @PostMapping("/analysis/refine")
    public ProductImageAnalysisResponse.AnalysisItem refineAnalysisItem(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody RefineAnalysisItemRequest request) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        log.info("商详套图单条分析重写: userId={}, role={}, ratio={}, lang={}",
            principal.id(), request.getRole(), request.getRatio(), request.getLang());
        return productImageSetService.refineAnalysisItem(principal.id(), request);
    }
}
