package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 已撤销的 refresh token 记录。
 *
 * <p>登出 / 改密 / 管理员强制下线时写入；refresh 时校验 jti 是否在表内。</p>
 *
 * <p>access token 由于是无状态的，撤销它需要黑名单机制；这里只撤销 refresh，
 * 配合 access token 2h 短过期时间，业务上等效"登出后 2h 内 access 仍可用，
 * 但无法 refresh 续命，自然过期后必须重新登录"。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("revoked_tokens")
public class RevokedToken {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** refresh token 的 jti（JWT 唯一标识） */
    private String jti;

    /** 关联用户 id（审计用） */
    private Long userId;

    /** 撤销时间 */
    private LocalDateTime revokedAt;

    /** refresh token 本身的过期时间（用于定时清理过期记录） */
    private LocalDateTime expiresAt;
}