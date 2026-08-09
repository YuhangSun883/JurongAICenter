package com.jurong.aicenter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 开启 @Async 异步支持。
 *
 * 用法：在另一个 Bean（不能是当前类）的方法上加 @Async，Spring 会用独立线程池执行。
 * 主要给 CanvasAsyncExecutor 用。
 *
 * captionExecutor —— 视频抽帧 caption 并行任务专用线程池。
 *   - corePoolSize=2, maxPoolSize=3(配合 Semaphore(2) 限流,9 帧分批打)
 *   - 队列满时由调用方线程执行(CallerRunsPolicy),避免任务被丢弃
 *   - 线程名前缀 "caption-" 方便排查日志
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
}