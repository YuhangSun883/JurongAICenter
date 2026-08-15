package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.canvas.*;
import com.jurong.aicenter.entity.Canvas;
import com.jurong.aicenter.entity.CanvasNode;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface CanvasService {

    // ============= 画布（容器） =============

    /** 我的创作列表（分页，按 updated_at DESC） */
    List<CanvasListItem> listCanvases(Long userId, int page, int pageSize);

    /** 新建画布 */
    Canvas createCanvas(Long userId, CreateCanvasRequest req);

    /** 拿画布实体（已鉴权） */
    Canvas getCanvas(Long userId, String canvasId);

    /** 拿画布完整快照（画布 + 所有节点 + 所有连线） */
    CanvasDetail getCanvasDetail(Long userId, String canvasId);

    /** 改画布名字 */
    Canvas updateCanvas(Long userId, String canvasId, UpdateCanvasRequest req);

    /**
     * 删画布（级联删节点 + 任务）。
     * 同时把节点的上下游连接里指向已删节点的引用清掉（避免悬空引用）。
     */
    void deleteCanvas(Long userId, String canvasId);

    // ============= 节点 =============

    /**
     * 创建节点。
     *
     * @param userId    当前用户
     * @param canvasId  目标画布（NULL → 自动用/建用户默认画布）
     * @param req       节点内容
     */
    CanvasNode createNode(Long userId, String canvasId, CreateCanvasNodeRequest req);

    /** 修改节点元数据 */
    CanvasNode updateNode(Long userId, String nodeId, UpdateCanvasNodeRequest req);

    /** 获取单个节点（已鉴权） */
    CanvasNode getNodeEntity(Long userId, String nodeId);

    /** 删除节点（含级联删除任务） */
    void deleteNode(Long userId, String nodeId);

    /**
     * 本地上传：上传文件到 MinIO + media_assets，**同时**建一个对应类型的画布节点。
     *
     * <p>节点 type 由 mime/扩展名自动判断：
     * <ul>
     *   <li>image/* → "image"</li>
     *   <li>video/* → "video"</li>
     *   <li>audio/* → "audio"</li>
     * </ul>
     *
     * <p>文件大小限制由 MediaService.uploadAsset 强制（图片 20M / 视频 200M / 音频 50M）。
     * canvasId 为 NULL 时落到用户默认画布。
     *
     * @param userId      当前用户
     * @param canvasId    目标画布（NULL → 默认画布）
     * @param file        文件
     * @param title       节点标题（可空，默认用文件名）
     * @param positionX   画布位置 X（可空，默认 0）
     * @param positionY   画布位置 Y（可空，默认 0）
     * @return 创建好的画布节点（type=image|video|audio，status=success，assetId 已填）
     */
    CanvasNode uploadAndCreateNode(Long userId, String canvasId, MultipartFile file,
                                    String title, Integer positionX, Integer positionY);

    /**
     * 异步生成：返回 pending 状态的任务快照，**真实 AI 在后台线程跑**
     */
    GenerateCanvasNodeResponse generate(Long userId, String nodeId, GenerateCanvasNodeRequest req);

    /**
     * 视频抽帧描述：ffmpeg 抽帧 + VL 模型 caption + 拼装成口播文案模板，写回节点 content。
     * 立刻返回 pending 状态，前端轮询 /tasks/{taskId} 看结果。
     *
     * @param userId 当前用户
     * @param nodeId 视频节点 ID（节点必须 type=video 且 resultUrl 非空）
     * @param fps    抽帧频率（默认 1，取值 (0, 10]）
     * @param mode   "script" 只建文本节点；"frames" 只建帧节点；"both" 都建（默认）
     */
    GenerateCanvasNodeResponse extractAndCaption(Long userId, String nodeId, double fps, String mode);

    /** 任务状态查询（前端轮询用） */
    GenerateCanvasNodeResponse getTaskStatus(Long userId, String taskId);

    /**
     * 2026-08-09 新增:换装(clothing transfer)
     * 从视频节点抽帧,每帧 + 3 张衣服参考图 → NewAPI /v1/images/edits,
     * 生成 N 张"只换了衣服、主体/背景全不变"的图 + 1 张拼图。
     *
     * @param userId          当前用户
     * @param targetNodeId    结果落点节点(也即源节点,直接在节点上点换装)
     * @param clothingNodeIds 衣服 image 节点的 id,顺序为[正面,背面,模特上身]
     * @return 立刻返回 pending 状态,前端轮询 /tasks/{taskId}
     */
    GenerateCanvasNodeResponse transferClothing(Long userId, String targetNodeId,
                                                List<String> clothingNodeIds);

    /**
     * 2026-08-10 v5:换装(重载版) — 结果落在 target 节点,source 节点只读(不覆盖原图)。
     * 从 generateNode 自动路由时调用:右侧激活节点是 target,上游抽帧图节点是 source。
     *
     * @param userId            当前用户
     * @param targetNodeId      结果落点节点(右侧激活节点,写回 resultUrl)
     * @param sourceNodeId      源节点(抽帧图/视频节点,只读,resultUrl 保持不变)
     * @param clothingNodeIds   衣服 image 节点的 id 列表
     * @return 立刻返回 pending 状态,前端轮询 /tasks/{taskId}
     */
    GenerateCanvasNodeResponse transferClothing(Long userId, String targetNodeId,
                                                String sourceNodeId, List<String> clothingNodeIds);

    /**
     * 2026-08-11 新增:5 参数重载,支持 userInstruction(用户的自然语言转换描述,如"换人脸+换沐浴露")
     *
     * @param userInstruction   用户的转换要求,会作为强约束拼到 NewAPI prompt 末尾。可空。
     */
    GenerateCanvasNodeResponse transferClothing(Long userId, String targetNodeId,
                                                String sourceNodeId, List<String> clothingNodeIds,
                                                String userInstruction);

    /**
     * 2026-08-10 新增:画布视频生成(从多源输入生成新视频)
     * 1. 从 videoGenNode 的 upstreamIds 拿脚本拆解文本 + 换装总图
     * 2. 合并 prompt(用户优先)
     * 3. 调 VideoGenerationService(走 NewAPI 主路径)
     * 4. 轮询 → 完成 → 建新视频节点
     * 立刻返回 pending,前端轮询 /tasks/{taskId}。
     *
     * @param userId        当前用户
     * @param videoGenNodeId 视频生成节点 ID(节点 type=video-generation,upstreamIds 含 text + image)
     * @param duration      视频时长(秒),默认 9(跟原视频一致)
     * @param resolution    视频分辨率,如 "720P",默认 "720P"
     * @return pending 状态的任务,前端轮询看结果
     */
    GenerateCanvasNodeResponse generateVideoFromCanvas(Long userId, String videoGenNodeId,
                                                       int duration, String resolution);
}