package com.jurong.aicenter.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jurong.aicenter.entity.AgentSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentSessionRepository extends BaseMapper<AgentSession> {
}
