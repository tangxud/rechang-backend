package com.rechang.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rechang.api.entity.Performance;
import com.rechang.api.entity.PerformancePriceZone;
import com.rechang.api.entity.Seat;
import com.rechang.api.entity.Ticket;
import com.rechang.api.entity.Venue;
import com.rechang.api.mapper.PerformanceMapper;
import com.rechang.api.mapper.PerformancePriceZoneMapper;
import com.rechang.api.mapper.SeatMapper;
import com.rechang.api.mapper.TicketMapper;
import com.rechang.api.mapper.VenueMapper;
import com.rechang.api.vo.SeatMapVO;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SeatService {

    private final PerformanceMapper performanceMapper;
    private final VenueMapper venueMapper;
    private final SeatMapper seatMapper;
    private final PerformancePriceZoneMapper priceZoneMapper;
    private final TicketMapper ticketMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    public SeatMapVO getSeatMap(Long performanceId) {
        Performance perf = performanceMapper.selectById(performanceId);
        if (perf == null || "DRAFT".equals(perf.getPublishStatus())) {
            throw new BusinessException(ResultCode.PERFORMANCE_NOT_FOUND);
        }

        SeatMapVO vo = new SeatMapVO();
        vo.setPerformanceId(performanceId);

        if (perf.getVenueId() != null) {
            Venue venue = venueMapper.selectById(perf.getVenueId());
            if (venue != null) {
                vo.setVenueName(venue.getVenueName());
            }
        }

        List<PerformancePriceZone> zones = priceZoneMapper.selectList(
                new LambdaQueryWrapper<PerformancePriceZone>()
                        .eq(PerformancePriceZone::getPerformanceId, performanceId));
        List<SeatMapVO.PriceZoneInfo> priceZoneInfos = zones.stream().map(z -> {
            SeatMapVO.PriceZoneInfo pzi = new SeatMapVO.PriceZoneInfo();
            pzi.setRegion(z.getRegion());
            pzi.setZoneName(z.getZoneName());
            pzi.setPrice(z.getPrice());
            pzi.setTotalCount(z.getTotalCount());
            return pzi;
        }).toList();
        vo.setPriceZones(priceZoneInfos);

        List<Seat> seats = perf.getVenueId() != null
                ? seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                        .eq(Seat::getVenueId, perf.getVenueId())
                        .orderByAsc(Seat::getRegion)
                        .orderByAsc(Seat::getRowLabel)
                        .orderByAsc(Seat::getColLabel))
                : Collections.emptyList();

        if (seats.isEmpty()) {
            vo.setIsStanding(true);
            return vo;
        }

        vo.setIsStanding(false);

        Set<Long> lockedSeatIds = getLockedSeatIds(performanceId);
        Set<Long> soldSeatIds = getSoldSeatIds(performanceId);

        Map<String, Integer> priceByRegion = zones.stream()
                .collect(Collectors.toMap(PerformancePriceZone::getRegion, PerformancePriceZone::getPrice, (a, b) -> a));

        Map<String, Integer> regionOrder = new HashMap<>();
        for (int i = 0; i < zones.size(); i++) {
            regionOrder.put(zones.get(i).getRegion(), i);
        }

        Map<String, List<Seat>> seatsByRegion = seats.stream()
                .collect(Collectors.groupingBy(
                        s -> s.getRegion() != null ? s.getRegion() : "DEFAULT",
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<SeatMapVO.RegionInfo> regionInfos = seatsByRegion.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> regionOrder.getOrDefault(e.getKey(), Integer.MAX_VALUE)))
                .map(entry -> {
                    SeatMapVO.RegionInfo regionInfo = new SeatMapVO.RegionInfo();
                    regionInfo.setRegion(entry.getKey());
                    regionInfo.setPrice(priceByRegion.get(entry.getKey()));

                    Map<String, List<Seat>> seatsByRow = new TreeMap<>();
                    for (Seat seat : entry.getValue()) {
                        String rowKey = seat.getRowLabel() != null ? seat.getRowLabel() : "";
                        seatsByRow.computeIfAbsent(rowKey, k -> new ArrayList<>()).add(seat);
                    }

                    List<SeatMapVO.RowInfo> rowInfos = seatsByRow.entrySet().stream()
                            .map(rowEntry -> {
                                SeatMapVO.RowInfo rowInfo = new SeatMapVO.RowInfo();
                                rowInfo.setRowLabel(rowEntry.getKey());
                                List<SeatMapVO.SeatInfo> seatInfos = rowEntry.getValue().stream().map(seat -> {
                                    SeatMapVO.SeatInfo si = new SeatMapVO.SeatInfo();
                                    si.setSeatId(seat.getId());
                                    si.setColLabel(seat.getColLabel());
                                    si.setSeatLabel(seat.getSeatLabel());
                                    si.setStatus(resolveSeatStatus(seat, lockedSeatIds, soldSeatIds));
                                    return si;
                                }).toList();
                                rowInfo.setSeats(seatInfos);
                                return rowInfo;
                            }).toList();
                    regionInfo.setRows(rowInfos);
                    return regionInfo;
                }).toList();
        vo.setRegions(regionInfos);

        return vo;
    }

    private Set<Long> getLockedSeatIds(Long performanceId) {
        String pattern = "seat:lock:" + performanceId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return Collections.emptySet();
        }
        String prefix = "seat:lock:" + performanceId + ":";
        Set<Long> result = new java.util.HashSet<>();
        for (String key : keys) {
            String seatIdStr = key.substring(prefix.length());
            try {
                result.add(Long.parseLong(seatIdStr));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private Set<Long> getSoldSeatIds(Long performanceId) {
        List<Ticket> tickets = ticketMapper.selectList(
                new LambdaQueryWrapper<Ticket>()
                        .eq(Ticket::getPerformanceId, performanceId)
                        .isNotNull(Ticket::getSeatId)
                        .in(Ticket::getStatus, List.of("USABLE", "USED", "TRANSFERRED")));
        return tickets.stream().map(Ticket::getSeatId).collect(Collectors.toSet());
    }

    private String resolveSeatStatus(Seat seat, Set<Long> lockedSeatIds, Set<Long> soldSeatIds) {
        if ("DISABLED".equals(seat.getStatus())) {
            return "DISABLED";
        }
        Long seatId = seat.getId();
        if (soldSeatIds.contains(seatId)) {
            return "SOLD";
        }
        if (lockedSeatIds.contains(seatId)) {
            return "LOCKED";
        }
        return "AVAILABLE";
    }
}
