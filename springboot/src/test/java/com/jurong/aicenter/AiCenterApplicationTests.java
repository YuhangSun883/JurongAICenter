package com.jurong.aicenter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring 启动测试 - 验证骨架能编译 + ApplicationContext 能起来
 *
 * Phase 2 完成标志：跑 mvn test 这个能过
 */
@SpringBootTest
@ActiveProfiles("dev")
class AiCenterApplicationTests {

    @Test
    void contextLoads() {
        // 空方法，验证 Spring 能装配所有 bean
    }
}