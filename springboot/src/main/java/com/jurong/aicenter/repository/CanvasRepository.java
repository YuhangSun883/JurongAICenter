package com.jurong.aicenter.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jurong.aicenter.entity.Canvas;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CanvasRepository extends BaseMapper<Canvas> {
}