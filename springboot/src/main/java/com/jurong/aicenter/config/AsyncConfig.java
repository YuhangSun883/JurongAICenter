package com.jurong.aicenter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 开启 @Async 异步支持 + @Scheduled 多线程调度。
 *
 * 用法：在另一个 Bean（不能是当前类）的方法上加 @Async，Spring 会用独立线程池执行。
 * 主要给 CanvasAsyncExecutor 用。
 *
 * captionExecutor —— 视频抽帧 caption 并行任务专用线程池。
 *   - corePoolSize=2, maxPoolSize=3(配合 Semaphore(2) 限流,9 帧分批打)
 *   - 队列满时由调用方线程执行(CallerRunsPolicy),避免任务被丢弃
 *   - 线程名前缀 "caption-" 方便排查日志
 *
 * taskScheduler —— 2026-08-10 新增:@Scheduled 多线程调度器。
 *   - Spring Boot 默认只有 1 个调度线程,导致两个 @Scheduled(pollRunningJobs + pollRunningVideoJobs)
 *     互相阻塞 — pollRunningVideoJobs 第一次跑完后 fixedDelay=2000 计数器才开始算,
 *     而 pollRunningJobs 在前面占着唯一的线程,后者一直得不到执行。
 *   - 修复:改为 4 个线程池,两个 @Scheduled 可以并行,fixedDelay 也能严格生效。
 *   - 线程名前缀 "scheduling-" 跟默认行为一致,日志好辨认。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "captionExecutor")
    public Executor captionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(9);
        executor.setThreadNamePrefix("caption-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 自定义 @Scheduled 调度器,多线程池避免两个 @Scheduled 互相阻塞。
     * 2026-08-10 新增:之前 1 个线程导致 pollRunningVideoJobs 在 pollRunningJobs 期间无法执行,
     * 视频生成任务提交 5 秒后就因"超时"被 CanvasVideoGenService.failTask 误判 FAILED。
     */
    @Bean(name = "taskScheduler")
    @Primary
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }
}