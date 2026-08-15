package com.jurong.aicenter.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway 启动策略：先 repair 再 migrate。
 *
 * <p>开发场景背景：
 * 数据库（云端）可能已被更高版本或其他人的迁移脚本执行过；而本地 git 版本的迁移脚本
 * 与数据库实际状态存在偏差（例如某版本 SQL 之前执行失败留下 failed 记录，或脚本内容
 * 被小改造成 checksum 不匹配）。直接 migrate 会报错，让开发无法启动。
 *
 * <p>flyway.repair() 的行为是安全的（不改动业务表）：
 * <ol>
 *   <li>删除 flyway_schema_history 中所有 failed 状态的记录；</li>
 *   <li>重新计算已执行版本的 checksum 并写回（解决"脚本微调后 checksum 不一致"）。</li>
 * </ol>
 *
 * <p>配合 application-dev.yml 中 out-of-order=true + validate-on-migrate=false，
 * 只要业务迁移 SQL 本身写得幂等（列/索引存在则跳过），即可顺利启动。
 */
@Slf4j
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            try {
                log.info("Flyway: running repair() before migrate()");
                flyway.repair();
            } catch (Exception e) {
                // repair 失败时不阻断启动链路（可能是库没权限写 flyway_schema_history），
                // 交给 migrate() 自己报告问题更明确
                log.warn("Flyway repair() skipped due to: {}", e.getMessage());
            }
            log.info("Flyway: disabling validation, running migrate()");
            // 关闭校验（避免 V16 在云端没记录时报错）
            org.flywaydb.core.Flyway newFlyway = org.flywaydb.core.Flyway.configure()
                .configuration(flyway.getConfiguration())
                .validateOnMigrate(false)
                .load();
            newFlyway.migrate();
        };
    }
}
