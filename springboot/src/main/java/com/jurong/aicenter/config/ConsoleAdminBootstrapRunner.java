package com.jurong.aicenter.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jurong.aicenter.entity.ConsoleAdmin;
import com.jurong.aicenter.repository.ConsoleAdminRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class ConsoleAdminBootstrapRunner implements ApplicationRunner {

    private final ConsoleAdminRepository consoleAdminRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${console.bootstrap.email:}")
    private String email;

    @Value("${console.bootstrap.password:}")
    private String password;

    @Value("${console.bootstrap.display-name:超级管理员}")
    private String displayName;

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            return;
        }
        String normalizedEmail = email.trim().toLowerCase();
        Long existing = consoleAdminRepository.selectCount(
            new LambdaQueryWrapper<ConsoleAdmin>().eq(ConsoleAdmin::getEmail, normalizedEmail)
        );
        if (existing != null && existing > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        ConsoleAdmin admin = new ConsoleAdmin();
        admin.setEmail(normalizedEmail);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setDisplayName(StringUtils.hasText(displayName) ? displayName.trim() : "超级管理员");
        admin.setRole("ADMIN");
        admin.setDisabled(0);
        admin.setDeleted(0);
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);
        consoleAdminRepository.insert(admin);
    }
}
