package com.example.marketing.account.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.marketing.account.infrastructure.entity.ConsumerUserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConsumerUserMapper extends BaseMapper<ConsumerUserEntity> {
}
