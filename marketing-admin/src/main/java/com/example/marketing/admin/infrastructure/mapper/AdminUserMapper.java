package com.example.marketing.admin.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.marketing.admin.infrastructure.entity.AdminUserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUserEntity> {
}
