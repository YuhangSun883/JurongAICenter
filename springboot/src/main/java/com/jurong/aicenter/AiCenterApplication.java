package com.jurong.aicenter;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan({"com.jurong.aicenter.repository", "com.jurong.aicenter.customer.repository"})
public class AiCenterApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCenterApplication.class, args);
    }
}