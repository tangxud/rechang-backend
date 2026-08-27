package com.rechang.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rechang.api.entity.Artist;
import com.rechang.api.entity.Performance;
import com.rechang.api.entity.PerformancePriceZone;
import com.rechang.api.entity.ReviewSummary;
import com.rechang.api.entity.UserSubscription;
import com.rechang.api.entity.UserWant;
import com.rechang.api.entity.Venue;
import com.rechang.api.mapper.ArtistMapper;
import com.rechang.api.mapper.PerformanceMapper;
import com.rechang.api.mapper.PerformancePriceZoneMapper;
import com.rechang.api.mapper.ReviewSummaryMapper;
import com.rechang.api.mapper.UserSubscriptionMapper;
import com.rechang.api.mapper.UserWantMapper;
import com.rechang.api.mapper.VenueMapper;
import com.rechang.api.security.UserContext;
import com.rechang.api.vo.PerformanceCardVO;
import com.rechang.api.vo.RankingVO;
import com.rechang.api.vo.ShowDetailVO;
import com.rechang.api.vo.ShowListVO;
import com.rechang.api.vo.SubscribeVO;
import com.rechang.api.vo.WantVO;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ShowService {

    private final PerformanceMapper performanceMapper;
    private final VenueMapper venueMapper;
    private final ArtistMapper artistMapper;
    private final PerformancePriceZoneMapper priceZoneMapper;
    private final UserSubscriptionMapper subscriptionMapper;
    private final UserWantMapper userWantMapper;
    private final ReviewSummaryMapper reviewSummaryMapper;

    public ShowListVO getShowList(String keyword, String type, String city, Integer minPrice, Integer maxPrice,
                                  String startDate, String endDate, int page, int size) {
        LambdaQueryWrapper<Performance> wrapper = new LambdaQueryWrapper<Performance>()
                .eq(Performance::getPublishStatus, "ON_SALE");
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(Performance::getPerfName, keyword)
                    .or().like(Performance::getTourName, keyword));
        }
        if (type != null && !type.isBlank()) {
            wrapper.eq(Performance::getPerfType, type);
        }
        if (city != null && !city.isBlank()) {
            wrapper.eq(Performance::getCityCode, city);
        }
        if (minPrice != null) {
            wrapper.ge(Performance::getMinPrice, minPrice);
        }
        if (maxPrice != null) {
            wrapper.le(Performance::getMinPrice, maxPrice);
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        try {
            if (startDate != null && !startDate.isBlank()) {
                wrapper.ge(Performance::getStartAt, sdf.parse(startDate));
            }
            if (endDate != null && !endDate.isBlank()) {
                wrapper.le(Performance::getStartAt, sdf.parse(endDate));
            }
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "日期格式错误，应为 yyyy-MM-dd");
        }
        wrapper.orderByAsc(Performance::getStartAt);

        Page<Performance> pageResult = performanceMapper.selectPage(new Page<>(page, size), wrapper);
        return buildShowListVO(pageResult.getRecords(), page, size, pageResult.getTotal());
    }

    public List<RankingVO> getRanking(String period, String city, int limit) {
        LambdaQueryWrapper<Performance> wrapper = new LambdaQueryWrapper<Performance>()
                .eq(Performance::getPublishStatus, "ON_SALE");
        if (city != null && !city.isBlank()) {
            wrapper.eq(Performance::getCityCode, city);
        }
        wrapper.orderByAsc(Performance::getStartAt)
                .last("LIMIT " + limit);

        List<Performance> perfs = performanceMapper.selectList(wrapper);
        List<RankingVO> result = new ArrayList<>();
        int rank = 1;
        for (Performance p : perfs) {
            RankingVO vo = new RankingVO();
            vo.setRank(rank);
            vo.setPerformanceId(p.getId());
            vo.setName(p.getPerfName());
            vo.setPosterUrl(p.getPosterUrl());
            vo.setMinPrice(p.getMinPrice());
            vo.setHotScore(1000 - rank * 100);
            vo.setCity(p.getCityCode());
            Venue venue = p.getVenueId() != null ? venueMapper.selectById(p.getVenueId()) : null;
            vo.setVenueName(venue != null ? venue.getVenueName() : null);
            result.add(vo);
            rank++;
        }
        return result;
    }

    public ShowListVO getNearby(Double lat, Double lng, int radius, int page, int size) {
        LambdaQueryWrapper<Performance> wrapper = new LambdaQueryWrapper<Performance>()
                .eq(Performance::getPublishStatus, "ON_SALE")
                .orderByAsc(Performance::getStartAt);

        Page<Performance> pageResult = performanceMapper.selectPage(new Page<>(page, size), wrapper);
        return buildShowListVO(pageResult.getRecords(), page, size, pageResult.getTotal());
    }

    public ShowDetailVO getShowDetail(Long id) {
        Performance perf = performanceMapper.selectById(id);
        if (perf == null || "DRAFT".equals(perf.getPublishStatus())) {
            throw new BusinessException(ResultCode.PERFORMANCE_NOT_FOUND);
        }

        ShowDetailVO vo = new ShowDetailVO();
        vo.setPerformanceId(perf.getId());
        vo.setName(perf.getPerfName());
        vo.setShowType(perf.getPerfType());
        vo.setShowForm(perf.getShowForm());
        vo.setTourId(perf.getTourId());
        vo.setTourName(perf.getTourName());
        vo.setPosterUrl(perf.getPosterUrl());
        vo.setDescription(perf.getDescription());
        vo.setStartAt(perf.getStartAt());
        vo.setEndAt(perf.getEndAt());
        vo.setCity(perf.getCityCode());
        vo.setMinPrice(perf.getMinPrice());
        vo.setPublishStatus(perf.getPublishStatus());
        vo.setSaleStartTime(perf.getSaleStartTime());
        vo.setSaleEndTime(perf.getSaleEndTime());
        vo.setIsHotSale(perf.getIsHotSale() != null && perf.getIsHotSale() == 1);
        vo.setIsStrongRealName(perf.getIsStrongRealName() != null && perf.getIsStrongRealName() == 1);
        vo.setPurchaseLimitPerId(perf.getPurchaseLimitPerId());

        if (perf.getVenueId() != null) {
            Venue venue = venueMapper.selectById(perf.getVenueId());
            if (venue != null) {
                ShowDetailVO.VenueInfo venueInfo = new ShowDetailVO.VenueInfo();
                venueInfo.setVenueId(venue.getId());
                venueInfo.setVenueName(venue.getVenueName());
                venueInfo.setAddress(venue.getAddress());
                vo.setVenue(venueInfo);
            }
        }

        if (perf.getArtistId() != null) {
            Artist artist = artistMapper.selectById(perf.getArtistId());
            if (artist != null) {
                ShowDetailVO.ArtistInfo artistInfo = new ShowDetailVO.ArtistInfo();
                artistInfo.setArtistId(artist.getId());
                artistInfo.setArtistName(artist.getArtistName());
                artistInfo.setAvatarUrl(artist.getAvatarUrl());
                vo.setArtist(artistInfo);
            }
        }

        List<PerformancePriceZone> zones = priceZoneMapper.selectList(
                new LambdaQueryWrapper<PerformancePriceZone>()
                        .eq(PerformancePriceZone::getPerformanceId, id));
        List<ShowDetailVO.PriceZoneInfo> zoneInfos = new ArrayList<>();
        int maxPrice = 0;
        for (PerformancePriceZone zone : zones) {
            ShowDetailVO.PriceZoneInfo zi = new ShowDetailVO.PriceZoneInfo();
            zi.setZoneId(zone.getId());
            zi.setZoneName(zone.getZoneName());
            zi.setPrice(zone.getPrice());
            zi.setRegion(zone.getRegion());
            zoneInfos.add(zi);
            if (zone.getPrice() != null && zone.getPrice() > maxPrice) {
                maxPrice = zone.getPrice();
            }
        }
        vo.setPriceZones(zoneInfos);
        vo.setMaxPrice(maxPrice > 0 ? maxPrice : null);

        Long wantCount = userWantMapper.selectCount(
                new LambdaQueryWrapper<UserWant>()
                        .eq(UserWant::getPerformanceId, id));
        vo.setWantCount(wantCount != null ? Math.toIntExact(wantCount) : 0);

        Long userId = UserContext.getUserId();
        if (userId != null) {
            UserWant want = userWantMapper.selectOne(
                    new LambdaQueryWrapper<UserWant>()
                            .eq(UserWant::getUserId, userId)
                            .eq(UserWant::getPerformanceId, id));
            vo.setIsWanted(want != null);
        } else {
            vo.setIsWanted(false);
        }

        ShowDetailVO.ReviewSummaryInfo reviewSummary = buildReviewSummary(perf);
        vo.setReviewSummary(reviewSummary);

        return vo;
    }

    private ShowDetailVO.ReviewSummaryInfo buildReviewSummary(Performance perf) {
        String groupId = perf.getTourId() != null && !perf.getTourId().isBlank()
                ? perf.getTourId()
                : String.valueOf(perf.getId());
        ReviewSummary summary = reviewSummaryMapper.selectById(groupId);
        ShowDetailVO.ReviewSummaryInfo info = new ShowDetailVO.ReviewSummaryInfo();
        if (summary == null) {
            info.setAvgRating(0);
            info.setTotalReviews(0);
            info.setTopTags(List.of());
            return info;
        }
        info.setAvgRating(summary.getAvgRating() != null ? summary.getAvgRating().doubleValue() : 0);
        info.setTotalReviews(summary.getTotalReviews() != null ? summary.getTotalReviews() : 0);
        info.setTopTags(parseTags(summary.getTopTags()));
        return info;
    }

    private List<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public SubscribeVO toggleSubscribe(Long performanceId, String subType) {
        Long userId = UserContext.getUserId();
        UserSubscription existing = subscriptionMapper.selectOne(
                new LambdaQueryWrapper<UserSubscription>()
                        .eq(UserSubscription::getUserId, userId)
                        .eq(UserSubscription::getPerformanceId, performanceId)
                        .eq(UserSubscription::getSubType, subType));

        SubscribeVO vo = new SubscribeVO();
        if (existing != null && "ACTIVE".equals(existing.getStatus())) {
            existing.setStatus("CANCELLED");
            subscriptionMapper.updateById(existing);
            vo.setSubscribed(false);
        } else if (existing != null) {
            existing.setStatus("ACTIVE");
            subscriptionMapper.updateById(existing);
            vo.setSubscribed(true);
        } else {
            UserSubscription sub = new UserSubscription();
            sub.setUserId(userId);
            sub.setPerformanceId(performanceId);
            sub.setSubType(subType);
            sub.setStatus("ACTIVE");
            subscriptionMapper.insert(sub);
            vo.setSubscribed(true);
        }
        return vo;
    }

    public WantVO toggleWant(Long performanceId) {
        Long userId = UserContext.getUserId();
        UserWant existing = userWantMapper.selectOne(
                new LambdaQueryWrapper<UserWant>()
                        .eq(UserWant::getUserId, userId)
                        .eq(UserWant::getPerformanceId, performanceId));

        WantVO vo = new WantVO();
        if (existing != null) {
            userWantMapper.delete(
                    new LambdaQueryWrapper<UserWant>()
                            .eq(UserWant::getUserId, userId)
                            .eq(UserWant::getPerformanceId, performanceId));
            vo.setWanted(false);
        } else {
            UserWant want = new UserWant();
            want.setUserId(userId);
            want.setPerformanceId(performanceId);
            want.setCreateTime(new Date());
            userWantMapper.insert(want);
            vo.setWanted(true);
        }

        Long count = userWantMapper.selectCount(
                new LambdaQueryWrapper<UserWant>()
                        .eq(UserWant::getPerformanceId, performanceId));
        vo.setWantCount(count != null ? Math.toIntExact(count) : 0);
        return vo;
    }

    public int getWantCount(Long performanceId) {
        return Math.toIntExact(userWantMapper.selectCount(
                new LambdaQueryWrapper<UserWant>()
                        .eq(UserWant::getPerformanceId, performanceId)));
    }

    private ShowListVO buildShowListVO(List<Performance> perfs, int page, int size, long total) {
        Map<Long, String> venueNameCache = new HashMap<>();
        for (Performance p : perfs) {
            if (p.getVenueId() != null && !venueNameCache.containsKey(p.getVenueId())) {
                Venue v = venueMapper.selectById(p.getVenueId());
                venueNameCache.put(p.getVenueId(), v != null ? v.getVenueName() : null);
            }
        }
        List<PerformanceCardVO> list = perfs.stream()
                .map(p -> toCardVO(p, venueNameCache))
                .toList();

        ShowListVO vo = new ShowListVO();
        vo.setPage(page);
        vo.setSize(size);
        vo.setTotal(total);
        vo.setList(list);
        return vo;
    }

    private PerformanceCardVO toCardVO(Performance p, Map<Long, String> venueNameCache) {
        PerformanceCardVO card = new PerformanceCardVO();
        card.setPerformanceId(p.getId());
        card.setName(p.getPerfName());
        card.setPosterUrl(p.getPosterUrl());
        card.setStartAt(p.getStartAt());
        card.setEndAt(p.getEndAt());
        card.setCity(p.getCityCode());
        card.setVenueName(p.getVenueId() != null ? venueNameCache.get(p.getVenueId()) : null);
        card.setMinPrice(p.getMinPrice());
        card.setShowType(p.getPerfType());
        card.setShowForm(p.getShowForm());
        card.setPublishStatus(p.getPublishStatus());
        card.setIsHotSale(p.getIsHotSale() != null && p.getIsHotSale() == 1);
        return card;
    }
}
