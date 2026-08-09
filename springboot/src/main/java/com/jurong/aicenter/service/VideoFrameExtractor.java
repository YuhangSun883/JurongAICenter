package com.jurong.aicenter.service;

import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 视频抽帧服务 —— 直接调本地 ffmpeg(系统 PATH 中需有 ffmpeg)。
 *
 * 设计要点:
 *   - 输入 URL(MinIO 公开地址),ffmpeg 支持直接拉
 *   - 输出按 "fps=N,scale=768:-1" 重采样,VLM 不需要 4K,768 够清晰且省 token
 *   - 失败兜底:exit code 非 0 / 超时 / 0 帧 都抛 BusinessException
 */
@Slf4j
@Component
public class VideoFrameExtractor {

    /** 单次抽帧最长等 120 秒(1 分钟视频 ≈ 几秒;封顶避免极端长视频卡线程池) */
    private static final long FFMPEG_TIMEOUT_SEC = 120;

    /**
     * @param videoUrl 视频 URL(MinIO 公网 / 任何 ffmpeg 支持的协议)
     * @param outDir   输出目录(不存在会自动创建)
     * @param fps      抽帧频率(1 = 每秒 1 帧)
     * @return 帧元数据列表,按帧号升序
     */
    public List<FrameMeta> extractFrames(String videoUrl, Path outDir, double fps) {
        try {
            Files.createDirectories(outDir);
            // 文件名必须 4 位补零(frame_0001.jpg / frame_0002.jpg ...)
            String pattern = outDir.resolve("frame_%04d.jpg").toString();

            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", videoUrl,
                "-vf", "fps=" + fps + ",scale=768:-1",
                "-q:v", "2",
                pattern
            ).redirectErrorStream(true);

            log.info("[ffmpeg] starting: fps={}, out={}", fps, pattern);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), "UTF-8");
            boolean finished = p.waitFor(FFMPEG_TIMEOUT_SEC, TimeUnit.SECONDS);

            if (!finished) {
                p.destroyForcibly();
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "ffmpeg 抽帧超时(" + FFMPEG_TIMEOUT_SEC + "s)");
            }
            if (p.exitValue() != 0) {
                String tail = output.length() > 800
                    ? "..." + output.substring(output.length() - 800) : output;
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "ffmpeg 抽帧失败(exit=" + p.exitValue() + "): " + tail);
            }

            // 列举 frame_*.jpg
            try (Stream<Path> stream = Files.list(outDir)) {
                List<FrameMeta> frames = stream
                    .filter(p_ -> p_.getFileName().toString().matches("frame_\\d{4}\\.jpg"))
                    .sorted()
                    .map(p_ -> {
                        String name = p_.getFileName().toString();
                        int idx = Integer.parseInt(name.replaceAll("\\D", "")) - 1;
                        return new FrameMeta(idx, idx / fps, p_);
                    })
                    .toList();
                if (frames.isEmpty()) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "ffmpeg 完成但未生成帧文件(URL 可能不可访问): " + videoUrl);
                }
                log.info("[ffmpeg] 完成: {} 帧, 目录={}", frames.size(), outDir);
                return frames;
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("[ffmpeg] 调用异常", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "ffmpeg 调用异常: " + e.getMessage());
        }
    }

    /**
     * 抽取视频的音频轨道到 wav 文件(给 Whisper ASR 用)。
     * 输出 16kHz 单声道 PCM,wav 格式(Whisper 推荐输入)。
     *
     * 失败兜底:任何异常 / exit 非 0 / 超时 → 返回 null(不抛错,让上层决定是否跳过)
     * 因为 ASR 是增强功能,失败不应该影响主流程的 caption
     *
     * @param videoUrl 视频 URL(同 extractFrames)
     * @param outFile  输出 wav 文件路径
     * @return outFile 成功;null 失败
     */
    public Path extractAudio(String videoUrl, Path outFile) {
        try {
            Files.createDirectories(outFile.getParent());
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", videoUrl,
                "-vn",                          // 不要视频流
                "-acodec", "pcm_s16le",         // WAV PCM 16-bit
                "-ar", "16000",                 // 16kHz(Whisper 推荐)
                "-ac", "1",                     // 单声道
                outFile.toString()
            ).redirectErrorStream(true);

            log.info("[ffmpeg-audio] starting: out={}", outFile);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), "UTF-8");
            boolean finished = p.waitFor(FFMPEG_TIMEOUT_SEC, TimeUnit.SECONDS);

            if (!finished) {
                p.destroyForcibly();
                log.warn("[ffmpeg-audio] 超时,放弃 ASR");
                return null;
            }
            if (p.exitValue() != 0) {
                log.warn("[ffmpeg-audio] exit={}, 跳过 ASR", p.exitValue());
                return null;
            }
            log.info("[ffmpeg-audio] 完成: {}", outFile);
            return outFile;
        } catch (Exception e) {
            log.warn("[ffmpeg-audio] 异常: {},跳过 ASR", e.getMessage());
            return null;
        }
    }

        /** 单帧元数据:index 从 0 起,timestampSeconds 是该帧在视频中的秒数 */
    public record FrameMeta(int index, double timestampSeconds, Path path) {}
}