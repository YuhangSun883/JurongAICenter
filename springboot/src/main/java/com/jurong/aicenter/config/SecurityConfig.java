package com.jurong.aicenter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS 配置源 — 显式放行前端开发地址。
     *
     * <p>生产环境应改为具体域名，不要保留 {@code http://localhost:*}。</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(List.of(
                "http://localhost:3000",
                "http://localhost:3001",
                "http://localhost:3889",       // 2026-08-14:sendStream 绕过 dev server 直连,需要这个 origin
                "http://127.0.0.1:3000",
                "http://127.0.0.1:3001",
                "http://127.0.0.1:3889"        // 同上
        ));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 2026-08-14 修复:Spring Security 默认对 ASYNC dispatch 也会跑 security 检查
                //   - SSE 端点返回 SseEmitter,Tomcat 后续 async dispatch 写 stream 时会重新过 filter chain
                //   - async dispatch 不带原始 Authorization header,JWT filter 拿不到 token,SecurityContext 是空的
                //   - 表现:async dispatch 抛 AccessDeniedException,堆栈里能看到 AsyncContextImpl
                //   - 修复:让 ASYNC dispatch 跳过 security,原始请求已经在第一个 dispatch 认证过了
                // 2026-08-14 追加:ERROR dispatch 同样需要放行
                //   - 原因:emitter.send 客户端断开 → completeWithError(e) → Tomcat 触发 ERROR dispatch
                //   - 这次 ERROR dispatch 同样没 SecurityContext,AuthorizationFilter 抛 AccessDeniedException
                //   - 而此时 SSE 响应已经 committed(Spring 写过 data: 帧),ExceptionTranslationFilter 写 403 失败
                //   - 表现:浏览器显示 ERR_INCOMPLETE_CHUNKED_ENCODING / 一直"正在思考"
                //   - 修复:ERROR dispatch 也走 permitAll
                .dispatcherTypeMatchers(
                        jakarta.servlet.DispatcherType.ASYNC,
                        jakarta.servlet.DispatcherType.ERROR
                ).permitAll()
                .requestMatchers(
                    "/api/auth/register",
                    "/api/auth/login",
                    "/api/auth/refresh",
                    "/api/auth/logout",
                    "/api/health",
                    "/actuator/health",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html"
                ).permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // 安全异常处理：未认证返回 401（而非默认 403），让前端能触发 silent refresh
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    Map<String, Object> body = new HashMap<>();
                    body.put("code", ErrorCode.UNAUTHORIZED.getCode());
                    body.put("message", "请先登录");
                    body.put("data", null);
                    response.getWriter().write(new ObjectMapper().writeValueAsString(body));
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    Map<String, Object> body = new HashMap<>();
                    body.put("code", ErrorCode.FORBIDDEN.getCode());
                    body.put("message", "无权限访问");
                    body.put("data", null);
                    response.getWriter().write(new ObjectMapper().writeValueAsString(body));
                })
            );
        return http.build();
    }
}