package com.rechang.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rechang.api.entity.OrderEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper extends BaseMapper<OrderEntity> {
}
