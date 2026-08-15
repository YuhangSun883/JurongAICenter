package com.jurong.aicenter.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jurong.aicenter.entity.CanvasTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CanvasTaskRepository extends BaseMapper<CanvasTask> {
}