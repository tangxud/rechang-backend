package com.rechang.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rechang.api.entity.Artist;
import com.rechang.api.entity.Banner;
import com.rechang.api.entity.Performance;
import com.rechang.api.entity.Venue;
import com.rechang.api.mapper.ArtistMapper;
import com.rechang.api.mapper.BannerMapper;
import com.rechang.api.mapper.PerformanceMapper;
import com.rechang.api.mapper.UserWantMapper;
import com.rechang.api.mapper.VenueMapper;
import com.rechang.api.vo.HomeVO;
import com.rechang.api.vo.PerformanceCardVO;
import com.rechang.api.vo.SearchSuggestVO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HomeService {

    private final BannerMapper bannerMapper;
    private final PerformanceMapper performanceMapper;
    private final VenueMapper venueMapper;
    private final ArtistMapper artistMapper;
    private final UserWantMapper userWantMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    public HomeVO getHomeData() {
        HomeVO vo = new HomeVO();
        vo.setServerTime(System.currentTimeMillis());

        List<Banner> banners = bannerMapper.selectList(
                new LambdaQueryWrapper<Banner>()
                        .eq(Banner::getStatus, "ACTIVE")
                        .orderByAsc(Banner::getSortOrder));
        List<HomeVO.BannerItem> bannerItems = banners.stream().map(b -> {
            HomeVO.BannerItem item = new HomeVO.BannerItem();
            item.setId(b.getId());
            item.setTitle(b.getTitle());
            item.setImageUrl(b.getCoverUrl());
            item.setLinkType(b.getLinkType());
            if ("PERFORMANCE".equals(b.getLinkType()) && b.getLinkTarget() != null) {
                try {
                    item.setLinkId(Long.parseLong(b.getLinkTarget()));
                } catch (NumberFormatException ignored) {
                }
            }
            return item;
        }).toList();
        vo.setBanners(bannerItems);

        Date now = new Date();
        List<Performance> upcomingPerfs = performanceMapper.selectList(
                new LambdaQueryWrapper<Performance>()
                        .eq(Performance::getPublishStatus, "ON_SALE")
                        .isNotNull(Performance::getSaleStartTime)
                        .gt(Performance::getSaleStartTime, now)
                        .orderByAsc(Performance::getSaleStartTime)
                        .last("LIMIT 5"));
        List<HomeVO.UpcomingItem> upcomingItems = upcomingPerfs.stream().map(p -> {
            HomeVO.UpcomingItem item = new HomeVO.UpcomingItem();
            item.setPerformanceId(p.getId());
            item.setName(p.getPerfName());
            item.setPosterUrl(p.getPosterUrl());
            item.setStartAt(p.getStartAt());
            item.setSaleStartTime(p.getSaleStartTime());
            item.setCountdownSeconds((p.getSaleStartTime().getTime() - now.getTime()) / 1000);
            item.setCity(p.getCityCode());
            Venue venue = p.getVenueId() != null ? venueMapper.selectById(p.getVenueId()) : null;
            item.setVenueName(venue != null ? venue.getVenueName() : null);
            return item;
        }).toList();
        vo.setUpcoming(upcomingItems);

        List<Performance> recPerfs = performanceMapper.selectList(
                new LambdaQueryWrapper<Performance>()
                        .eq(Performance::getPublishStatus, "ON_SALE")
                        .orderByAsc(Performance::getStartAt)
                        .last("LIMIT 10"));
        vo.setRecommendations(mapToCardList(recPerfs));

        List<Performance> hotPerfs = performanceMapper.selectList(
                new LambdaQueryWrapper<Performance>()
                        .eq(Performance::getIsHotSale, 1)
                        .eq(Performance::getPublishStatus, "ON_SALE")
                        .last("LIMIT 10"));
        vo.setHotList(mapToCardList(hotPerfs));

        return vo;
    }

    public SearchSuggestVO searchSuggest(String keyword) {
        SearchSuggestVO vo = new SearchSuggestVO();

        List<String> hotKeywords;
        try {
            Object cached = redisTemplate.opsForValue().get("search:hot_keywords");
            if (cached instanceof List<?> list) {
                hotKeywords = list.stream().map(Object::toString).toList();
            } else if (cached instanceof String s) {
                hotKeywords = List.of(s.replace("[", "").replace("]", "").replace("\"", "").split(","));
            } else {
                hotKeywords = List.of("周杰伦", "五月天", "话剧");
            }
        } catch (Exception e) {
            hotKeywords = List.of("周杰伦", "五月天", "话剧");
        }
        vo.setHotKeywords(hotKeywords);

        if (keyword == null || keyword.isBlank()) {
            vo.setSuggestions(List.of());
            return vo;
        }

        List<SearchSuggestVO.SuggestionItem> suggestions = new ArrayList<>();

        List<Performance> perfs = performanceMapper.selectList(
                new LambdaQueryWrapper<Performance>()
                        .like(Performance::getPerfName, keyword)
                        .eq(Performance::getPublishStatus, "ON_SALE")
                        .last("LIMIT 5"));
        for (Performance p : perfs) {
            SearchSuggestVO.SuggestionItem item = new SearchSuggestVO.SuggestionItem();
            item.setType("PERFORMANCE");
            item.setText(p.getPerfName());
            item.setPerformanceId(p.getId());
            suggestions.add(item);
        }

        List<Artist> artists = artistMapper.selectList(
                new LambdaQueryWrapper<Artist>()
                        .like(Artist::getArtistName, keyword)
                        .eq(Artist::getStatus, "ACTIVE")
                        .last("LIMIT 3"));
        for (Artist a : artists) {
            SearchSuggestVO.SuggestionItem item = new SearchSuggestVO.SuggestionItem();
            item.setType("ARTIST");
            item.setText(a.getArtistName());
            item.setArtistId(a.getId());
            suggestions.add(item);
        }

        vo.setSuggestions(suggestions);
        return vo;
    }

    private List<PerformanceCardVO> mapToCardList(List<Performance> perfs) {
        if (perfs.isEmpty()) {
            return List.of();
        }
        Map<Long, String> venueNameCache = new HashMap<>();
        for (Performance p : perfs) {
            if (p.getVenueId() != null && !venueNameCache.containsKey(p.getVenueId())) {
                Venue v = venueMapper.selectById(p.getVenueId());
                venueNameCache.put(p.getVenueId(), v != null ? v.getVenueName() : null);
            }
        }
        return perfs.stream().map(p -> toCardVO(p, venueNameCache)).toList();
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
