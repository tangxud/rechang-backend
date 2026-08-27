package com.rechang.api.vo;

import lombok.Data;
import java.util.List;

@Data
public class ShowListVO {
    private int page;
    private int size;
    private long total;
    private List<PerformanceCardVO> list;
}
