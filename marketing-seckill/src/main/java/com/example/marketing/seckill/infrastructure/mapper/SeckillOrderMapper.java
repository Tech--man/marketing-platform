package com.example.marketing.seckill.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.marketing.seckill.infrastructure.entity.SeckillOrderEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SeckillOrderMapper extends BaseMapper<SeckillOrderEntity> {
}
