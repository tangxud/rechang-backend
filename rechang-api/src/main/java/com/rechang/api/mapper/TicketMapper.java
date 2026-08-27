package com.rechang.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rechang.api.entity.Ticket;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {
}
