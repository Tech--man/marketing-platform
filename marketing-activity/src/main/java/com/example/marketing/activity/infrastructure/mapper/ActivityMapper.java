package com.example.marketing.activity.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ActivityMapper extends BaseMapper<ActivityEntity> {
}
