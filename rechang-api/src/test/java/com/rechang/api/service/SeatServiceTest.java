package com.rechang.api.service;

import com.rechang.api.entity.Performance;
import com.rechang.api.entity.PerformancePriceZone;
import com.rechang.api.entity.Seat;
import com.rechang.api.entity.Ticket;
import com.rechang.api.mapper.PerformanceMapper;
import com.rechang.api.mapper.PerformancePriceZoneMapper;
import com.rechang.api.mapper.SeatMapper;
import com.rechang.api.mapper.TicketMapper;
import com.rechang.api.mapper.VenueMapper;
import com.rechang.api.support.Fixtures;
import com.rechang.api.vo.SeatMapVO;
import com.rechang.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 座位图：状态优先级 DISABLED > SOLD > LOCKED > AVAILABLE、站票短路、keys 解析容错。
 */
@ExtendWith(MockitoExtension.class)
class SeatServiceTest {

    @Mock PerformanceMapper performanceMapper;
    @Mock VenueMapper venueMapper;
    @Mock SeatMapper seatMapper;
    @Mock PerformancePriceZoneMapper priceZoneMapper;
    @Mock TicketMapper ticketMapper;
    @Mock RedisTemplate<String, Object> redisTemplate;
    @InjectMocks SeatService seatService;

    private Performance perf;

    @BeforeEach
    void setUp() {
        perf = Fixtures.performance("ON_SALE");
        perf.setVenueId(3L);
        lenient().when(performanceMapper.selectById(Fixtures.PERF_ID)).thenReturn(perf);
        lenient().when(priceZoneMapper.selectList(any())).thenReturn(List.of(
                Fixtures.zone("A", 58000), Fixtures.zone("B", 38000)));
        lenient().when(seatMapper.selectList(any())).thenReturn(List.of());
        lenient().when(ticketMapper.selectList(any())).thenReturn(List.of());
        lenient().when(redisTemplate.keys("seat:lock:" + Fixtures.PERF_ID + ":*")).thenReturn(Set.of());
    }

    @Test
    @DisplayName("演出不存在或 DRAFT → PERFORMANCE_NOT_FOUND")
    void perfGuard() {
        assertThatThrownBy(() -> seatService.getSeatMap(999L))
                .matches(e -> ((BusinessException) e).getCode() == 1005);
        perf.setPublishStatus("DRAFT");
        assertThatThrownBy(() -> seatService.getSeatMap(Fixtures.PERF_ID))
                .matches(e -> ((BusinessException) e).getCode() == 1005);
    }

    @Test
    @DisplayName("无场馆的演出直接按站票返回，带价格区")
    void standingShortCircuit_noVenue() {
        perf.setVenueId(null);
        SeatMapVO vo = seatService.getSeatMap(Fixtures.PERF_ID);
        assertThat(vo.getIsStanding()).isTrue();
        assertThat(vo.getPriceZones()).hasSize(2);
    }

    @Test
    @DisplayName("有场馆但座位表为空同样按站票返回")
    void standingShortCircuit_noSeats() {
        SeatMapVO vo = seatService.getSeatMap(Fixtures.PERF_ID);
        assertThat(vo.getIsStanding()).isTrue();
    }

    @Test
    @DisplayName("座位状态优先级：DISABLED > SOLD > LOCKED > AVAILABLE")
    void seatStatusPriority() {
        Seat disabled = Fixtures.seat(1L, "A", "1", "1", "DISABLED");
        Seat sold = Fixtures.seat(2L, "A", "1", "2", "ENABLED");
        Seat locked = Fixtures.seat(3L, "A", "1", "3", "ENABLED");
        Seat available = Fixtures.seat(4L, "B", "2", "1", "ENABLED");
        when(seatMapper.selectList(any())).thenReturn(List.of(disabled, sold, locked, available));

        Ticket soldTicket = Fixtures.ticket(90L, 9L, Fixtures.PERF_ID, "USABLE");
        soldTicket.setSeatId(2L);
        when(ticketMapper.selectList(any())).thenReturn(List.of(soldTicket));
        when(redisTemplate.keys("seat:lock:" + Fixtures.PERF_ID + ":*")).thenReturn(Set.of("seat:lock:1:3"));

        SeatMapVO vo = seatService.getSeatMap(Fixtures.PERF_ID);
        assertThat(vo.getIsStanding()).isFalse();
        var statusById = vo.getRegions().stream()
                .flatMap(r -> r.getRows().stream())
                .flatMap(row -> row.getSeats().stream())
                .collect(java.util.stream.Collectors.toMap(SeatMapVO.SeatInfo::getSeatId, SeatMapVO.SeatInfo::getStatus));
        assertThat(statusById).containsEntry(1L, "DISABLED").containsEntry(2L, "SOLD")
                .containsEntry(3L, "LOCKED").containsEntry(4L, "AVAILABLE");
    }

    @Test
    @DisplayName("SOLD 优先于 LOCKED：同座既售又锁显示 SOLD")
    void soldBeatsLocked() {
        Seat both = Fixtures.seat(7L, "A", "1", "7", "ENABLED");
        when(seatMapper.selectList(any())).thenReturn(List.of(both));
        Ticket soldTicket = Fixtures.ticket(91L, 9L, Fixtures.PERF_ID, "TRANSFERRED");
        soldTicket.setSeatId(7L);
        when(ticketMapper.selectList(any())).thenReturn(List.of(soldTicket));
        when(redisTemplate.keys("seat:lock:" + Fixtures.PERF_ID + ":*")).thenReturn(Set.of("seat:lock:1:7"));

        SeatMapVO vo = seatService.getSeatMap(Fixtures.PERF_ID);
        var statusById = vo.getRegions().stream()
                .flatMap(r -> r.getRows().stream())
                .flatMap(row -> row.getSeats().stream())
                .collect(java.util.stream.Collectors.toMap(SeatMapVO.SeatInfo::getSeatId, SeatMapVO.SeatInfo::getStatus));
        assertThat(statusById.get(7L)).isEqualTo("SOLD");
    }

    @Test
    @DisplayName("keys 含非法 seatId 段时静默忽略")
    void keysGarbageTolerated() {
        Seat s = Fixtures.seat(5L, "A", "1", "5", "ENABLED");
        when(seatMapper.selectList(any())).thenReturn(List.of(s));
        when(redisTemplate.keys("seat:lock:" + Fixtures.PERF_ID + ":*"))
                .thenReturn(Set.of("seat:lock:1:abc", "seat:lock:1:5"));

        SeatMapVO vo = seatService.getSeatMap(Fixtures.PERF_ID);
        var statusById = vo.getRegions().stream()
                .flatMap(r -> r.getRows().stream())
                .flatMap(row -> row.getSeats().stream())
                .collect(java.util.stream.Collectors.toMap(SeatMapVO.SeatInfo::getSeatId, SeatMapVO.SeatInfo::getStatus));
        assertThat(statusById.get(5L)).isEqualTo("LOCKED");
    }

    @Test
    @DisplayName("区域顺序按价格区定义序；region 为空归入 DEFAULT 排最后")
    void regionOrdering() {
        Seat nullRegion = Fixtures.seat(6L, null, "9", "1", "ENABLED");
        Seat b1 = Fixtures.seat(8L, "B", "1", "1", "ENABLED");
        Seat a1 = Fixtures.seat(9L, "A", "1", "1", "ENABLED");
        when(seatMapper.selectList(any())).thenReturn(List.of(a1, b1, nullRegion));

        SeatMapVO vo = seatService.getSeatMap(Fixtures.PERF_ID);
        assertThat(vo.getRegions()).extracting(SeatMapVO.RegionInfo::getRegion)
                .containsExactly("A", "B", "DEFAULT");
        assertThat(vo.getRegions().get(0).getPrice()).isEqualTo(58000);
    }
}
