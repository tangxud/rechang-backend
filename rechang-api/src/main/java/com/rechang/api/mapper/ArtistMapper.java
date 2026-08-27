package com.rechang.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rechang.api.entity.Artist;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ArtistMapper extends BaseMapper<Artist> {
}
