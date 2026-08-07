package com.jurong.aicenter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 开启 @Async 异步支持。
 *
 * 用法：在另一个 Bean（不能是当前类）的方法上加 @Async，Spring 会用独立线程池执行。
 * 主要给 CanvasAsyncExecutor 用。
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}