package com.example.marketing.account.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.marketing.account.infrastructure.entity.ConsumerSessionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConsumerSessionMapper extends BaseMapper<ConsumerSessionEntity> {
}
