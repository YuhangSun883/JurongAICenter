package com.jurong.aicenter.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jurong.aicenter.entity.RevokedToken;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RevokedTokenRepository extends BaseMapper<RevokedToken> {

    /** 检查 jti 是否已被撤销 */
    @Select("SELECT COUNT(*) FROM revoked_tokens WHERE jti = #{jti}")
    long countByJti(@Param("jti") String jti);

    /** 清理已过期的撤销记录（定时任务调用） */
    @Delete("DELETE FROM revoked_tokens WHERE expires_at < NOW()")
    int deleteExpired();
}