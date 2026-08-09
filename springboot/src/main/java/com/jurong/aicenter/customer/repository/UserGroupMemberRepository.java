package com.jurong.aicenter.customer.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jurong.aicenter.customer.entity.UserGroupMember;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserGroupMemberRepository extends BaseMapper<UserGroupMember> {
}
