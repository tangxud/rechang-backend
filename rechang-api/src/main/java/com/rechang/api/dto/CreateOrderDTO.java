package com.rechang.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrderDTO {

    @NotNull(message = "演出ID不能为空")
    private Long performanceId;

    private List<Long> seatIds;

    private Integer standingCount;

    private List<AttendeeItem> attendees;

    @Data
    public static class AttendeeItem {
        private Long attendeeId;
    }
}
