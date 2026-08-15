package com.jurong.aicenter.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jurong.aicenter.entity.MediaAsset;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MediaAssetRepository extends BaseMapper<MediaAsset> {
}
