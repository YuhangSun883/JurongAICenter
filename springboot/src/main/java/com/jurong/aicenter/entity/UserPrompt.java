package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_prompts")
public class UserPrompt {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户邮箱（关联 users 表 email 字段） */
    private String email;

    /** 提示词内容 */
    private String prompt;

    /** 使用次数 */
    private Integer useCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
