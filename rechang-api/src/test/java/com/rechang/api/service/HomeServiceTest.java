package com.rechang.api.service;

import com.rechang.api.entity.Banner;
import com.rechang.api.entity.Performance;
import com.rechang.api.entity.Venue;
import com.rechang.api.mapper.ArtistMapper;
import com.rechang.api.mapper.BannerMapper;
import com.rechang.api.mapper.PerformanceMapper;
import com.rechang.api.mapper.UserWantMapper;
import com.rechang.api.mapper.VenueMapper;
import com.rechang.api.support.Fixtures;
import com.rechang.api.vo.HomeVO;
import com.rechang.api.vo.SearchSuggestVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 首页聚合与搜索建议（热词三级降级、banner linkTarget 解析容错、倒计时秒数）。
 */
@ExtendWith(MockitoExtension.class)
class HomeServiceTest {

    @Mock BannerMapper bannerMapper;
    @Mock PerformanceMapper performanceMapper;
    @Mock VenueMapper venueMapper;
    @Mock ArtistMapper artistMapper;
    @Mock UserWantMapper userWantMapper;
    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;
    @InjectMocks HomeService homeService;

    @BeforeEach
    void setUp() {
        lenient().doReturn(valueOps).when(redisTemplate).opsForValue();
        lenient().when(bannerMapper.selectList(any())).thenReturn(List.of());
        lenient().when(performanceMapper.selectList(any())).thenReturn(List.of());
        lenient().when(valueOps.get("search:hot_keywords")).thenReturn(null);
    }

    @Test
    @DisplayName("banner：PERFORMANCE 类型解析 linkTarget 数字，非法值静默跳过")
    void bannerLinkParsing() {
        Banner good = new Banner();
        good.setId(1L);
        good.setTitle("周杰伦");
        good.setCoverUrl("https://cdn.rechang.com/b1.jpg");
        good.setLinkType("PERFORMANCE");
        good.setLinkTarget("123");
        good.setSortOrder(1);
        good.setStatus("ACTIVE");
        Banner bad = new Banner();
        bad.setId(2L);
        bad.setTitle("活动");
        bad.setCoverUrl("https://cdn.rechang.com/b2.jpg");
        bad.setLinkType("PERFORMANCE");
        bad.setLinkTarget("not-a-number");
        bad.setSortOrder(2);
        bad.setStatus("ACTIVE");
        when(bannerMapper.selectList(any())).thenReturn(List.of(good, bad));

        HomeVO vo = homeService.getHomeData();
        assertThat(vo.getBanners()).hasSize(2);
        assertThat(vo.getBanners().get(0).getLinkId()).isEqualTo(123L);
        assertThat(vo.getBanners().get(1).getLinkId()).isNull();
    }

    @Test
    @DisplayName("即将开售：countdownSeconds 为开售剩余秒数，场馆名回填")
    void upcomingCountdown() {
        Performance p = Fixtures.performance("ON_SALE");
        p.setSaleStartTime(Fixtures.msFromNow(3600 * 1000));
        p.setVenueId(3L);
        when(performanceMapper.selectList(any())).thenReturn(List.of(p));
        Venue venue = new Venue();
        venue.setId(3L);
        venue.setVenueName("蜂巢剧场");
        when(venueMapper.selectById(3L)).thenReturn(venue);

        HomeVO vo = homeService.getHomeData();
        assertThat(vo.getUpcoming()).hasSize(1);
        Long countdown = vo.getUpcoming().get(0).getCountdownSeconds();
        assertThat(countdown).isBetween(3500L, 3600L);
        assertThat(vo.getUpcoming().get(0).getVenueName()).isEqualTo("蜂巢剧场");
    }

    @Test
    @DisplayName("推荐/热卖卡片 isHotSale 标记正确")
    void cardHotSaleFlag() {
        Performance hot = Fixtures.performance("ON_SALE");
        hot.setIsHotSale(1);
        // selectList 桩同时喂给 upcoming/recommendations/hot 三个分支，upcoming 分支要求 saleStartTime 非空
        hot.setSaleStartTime(Fixtures.msFromNow(3600 * 1000));
        when(performanceMapper.selectList(any())).thenReturn(List.of(hot));

        HomeVO vo = homeService.getHomeData();
        assertThat(vo.getRecommendations().get(0).getIsHotSale()).isTrue();
        assertThat(vo.getHotList().get(0).getIsHotSale()).isTrue();
    }

    @Test
    @DisplayName("热词：Redis List 结构直接使用")
    void hotKeywordsFromRedisList() {
        when(valueOps.get("search:hot_keywords")).thenReturn(List.of("周杰伦", "演唱会"));
        SearchSuggestVO vo = homeService.searchSuggest(null);
        assertThat(vo.getHotKeywords()).containsExactly("周杰伦", "演唱会");
        assertThat(vo.getSuggestions()).isEmpty();  // 空关键词不联想
    }

    @Test
    @DisplayName("热词：Redis String CSV 结构拆分")
    void hotKeywordsFromRedisString() {
        when(valueOps.get("search:hot_keywords")).thenReturn("[\"周杰伦\",\"五月天\"]");
        SearchSuggestVO vo = homeService.searchSuggest("");
        assertThat(vo.getHotKeywords()).containsExactly("周杰伦", "五月天");
    }

    @Test
    @DisplayName("热词：Redis 异常降级到默认词")
    void hotKeywordsFallbackOnRedisError() {
        when(valueOps.get("search:hot_keywords")).thenThrow(new RuntimeException("redis down"));
        SearchSuggestVO vo = homeService.searchSuggest(null);
        assertThat(vo.getHotKeywords()).containsExactly("周杰伦", "五月天", "话剧");
    }

    @Test
    @DisplayName("联想：演出与艺人混合返回")
    void suggestionsMixed() {
        Performance p = Fixtures.performance("ON_SALE");
        when(performanceMapper.selectList(any())).thenReturn(List.of(p));
        com.rechang.api.entity.Artist artist = new com.rechang.api.entity.Artist();
        artist.setId(7L);
        artist.setArtistName("周杰伦");
        artist.setStatus("ACTIVE");
        when(artistMapper.selectList(any())).thenReturn(List.of(artist));

        SearchSuggestVO vo = homeService.searchSuggest("周杰伦");
        assertThat(vo.getSuggestions()).hasSize(2);
        assertThat(vo.getSuggestions().get(0).getType()).isEqualTo("PERFORMANCE");
        assertThat(vo.getSuggestions().get(1).getType()).isEqualTo("ARTIST");
        assertThat(vo.getSuggestions().get(1).getText()).isEqualTo("周杰伦");
    }
}
