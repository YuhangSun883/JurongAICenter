package com.jurong.aicenter.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jurong.aicenter.entity.CanvasNode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CanvasNodeRepository extends BaseMapper<CanvasNode> {
}