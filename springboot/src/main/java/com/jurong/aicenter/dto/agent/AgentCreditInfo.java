package com.jurong.aicenter.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentCreditInfo {
    /** 本月月额度（queryQuota 字段） */
    private Integer monthlyQuota;
    /** 已消耗 */
    private Integer used;
    /** 剩余 = monthlyQuota - used */
    private Integer remaining;
    /** 兼容前端的"used" 字段展示 */
    private Integer total;
    private Integer estimated;
}
