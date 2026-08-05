package com.jurong.aicenter.customer.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jurong.aicenter.customer.entity.UserGroup;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserGroupRepository extends BaseMapper<UserGroup> {
}
