package com.jurong.aicenter.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final Duration accessExpiry;
    private final Duration refreshExpiry;
    private final String issuer;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiry:2h}") Duration accessExpiry,
            @Value("${jwt.refresh-token-expiry:7d}") Duration refreshExpiry,
            @Value("${jwt.issuer:jurong-aicenter}") String issuer) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpiry = accessExpiry;
        this.refreshExpiry = refreshExpiry;
        this.issuer = issuer;
    }

    public String generateAccessToken(Long userId, String email, String role) {
        return generate(userId, email, role, "access", accessExpiry);
    }

    public String generateRefreshToken(Long userId, String email, String role) {
        return generate(userId, email, role, "refresh", refreshExpiry);
    }

    // 兼容旧调用：role 缺失时按 USER 处理
    public String generateAccessToken(Long userId, String email) {
        return generateAccessToken(userId, email, "USER");
    }

    public String generateRefreshToken(Long userId, String email) {
        return generateRefreshToken(userId, email, "USER");
    }

    private String generate(Long userId, String email, String role, String type, Duration expiry) {
        Instant now = Instant.now();
        return Jwts.builder()
            // jti() 让 JJWT 自动生成 UUID（refresh token 撤销时用作唯一标识）
            .id(java.util.UUID.randomUUID().toString())
            .subject(String.valueOf(userId))
            .issuer(issuer)
            .claim("email", email)
            .claim("role", role == null ? "USER" : role)
            .claim("type", type)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(expiry)))
            .signWith(key)
            .compact();
    }

    /** 从 token 中提取 jti（refresh 撤销用） */
    public String getJti(String token) {
        return parse(token).getId();
    }

    /** 从 token 中提取过期时间（refresh 撤销时记录，便于清理） */
    public Instant getExpiration(String token) {
        return parse(token).getExpiration().toInstant();
    }

    /** 从 token 中提取 userId（refresh 撤销时记录审计用） */
    public Long getUserIdFromToken(String token) {
        return Long.valueOf(parse(token).getSubject());
    }

    // 兼容旧签名（不传 role = 视为 USER）
    private String generate(Long userId, String email, String type, Duration expiry) {
        return generate(userId, email, "USER", type, expiry);
    }

    public Claims parse(String token) {
        try {
            return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        } catch (JwtException e) {
            log.warn("Invalid JWT: {}", e.getMessage());
            throw e;
        }
    }

    public Long getUserId(String token) {
        return Long.valueOf(parse(token).getSubject());
    }
}