package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("billing_logs")
public class BillingLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long jobId;

    /** CONSUME / RECHARGE / REFUND / GRANT / EXPIRE */
    private String type;

    /** 正负 */
    private Integer creditsDelta;

    private Integer balanceAfter;

    private String description;

    private String paymentId;

    private LocalDateTime createdAt;
}