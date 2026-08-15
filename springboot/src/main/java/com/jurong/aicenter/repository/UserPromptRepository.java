package com.jurong.aicenter.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jurong.aicenter.entity.UserPrompt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserPromptRepository extends BaseMapper<UserPrompt> {

    /**
     * 查询用户的所有提示词，按使用次数降序排列
     */
    @Select("SELECT * FROM user_prompts WHERE email = #{email} ORDER BY use_count DESC, created_at DESC")
    List<UserPrompt> findByEmailOrderByUseCountDesc(@Param("email") String email);
}
