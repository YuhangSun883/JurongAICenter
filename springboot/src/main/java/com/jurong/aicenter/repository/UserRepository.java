package com.jurong.aicenter.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jurong.aicenter.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserRepository extends BaseMapper<User> {

    /**
     * 示例：按邮箱精确查询用户。
     * 自定义 SQL（XML/注解）演示，BaseMapper 默认不提供按唯一字段查询的方法。
     */
    @Select("SELECT * FROM users WHERE email = #{email} AND deleted = 0 LIMIT 1")
    User findByEmail(@Param("email") String email);
}