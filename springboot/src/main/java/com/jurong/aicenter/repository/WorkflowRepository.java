package com.jurong.aicenter.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jurong.aicenter.entity.Workflow;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowRepository extends BaseMapper<Workflow> {
}