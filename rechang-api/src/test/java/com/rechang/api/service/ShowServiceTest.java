package com.rechang.api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rechang.api.entity.Performance;
import com.rechang.api.entity.ReviewSummary;
import com.rechang.api.entity.Venue;
import com.rechang.api.mapper.ArtistMapper;
import com.rechang.api.mapper.PerformanceMapper;
import com.rechang.api.mapper.PerformancePriceZoneMapper;
import com.rechang.api.mapper.ReviewSummaryMapper;
import com.rechang.api.mapper.UserSubscriptionMapper;
import com.rechang.api.mapper.UserWantMapper;
import com.rechang.api.mapper.VenueMapper;
import com.rechang.api.security.UserContext;
import com.rechang.api.support.Fixtures;
import com.rechang.api.vo.RankingVO;
import com.rechang.api.vo.ShowDetailVO;
import com.rechang.api.vo.SubscribeVO;
import com.rechang.api.vo.WantVO;
import com.rechang.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 演出列表/榜单/详情/订阅/想看。
 */
@ExtendWith(MockitoExtension.class)
class ShowServiceTest {

    @Mock PerformanceMapper performanceMapper;
    @Mock VenueMapper venueMapper;
    @Mock ArtistMapper artistMapper;
    @Mock PerformancePriceZoneMapper priceZoneMapper;
    @Mock UserSubscriptionMapper subscriptionMapper;
    @Mock UserWantMapper userWantMapper;
    @Mock ReviewSummaryMapper reviewSummaryMapper;
    @InjectMocks ShowService showService;

    private Performance perf;

    @BeforeEach
    void setUp() {
        perf = Fixtures.performance("ON_SALE");
        perf.setVenueId(3L);
        lenient().when(performanceMapper.selectById(Fixtures.PERF_ID)).thenReturn(perf);
        lenient().when(priceZoneMapper.selectList(any())).thenReturn(List.of());
        lenient().when(userWantMapper.selectCount(any())).thenReturn(0L);
        lenient().when(reviewSummaryMapper.selectById(any(String.class))).thenReturn(null);
    }

    @AfterEach
    void cleanUp() {
        UserContext.clear();
    }

    private Page<Performance> pageOf(Performance... items) {
        Page<Performance> page = new Page<>(1, 10);
        page.setRecords(List.of(items));
        page.setTotal(items.length);
        return page;
    }

    @Test
    @DisplayName("列表日期格式非法 → BAD_REQUEST")
    void listRejectsBadDates() {
        assertThatThrownBy(() -> showService.getShowList(null, null, null, null, null, "2026/01/01", null, 1, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("日期格式");
    }

    @Test
    @DisplayName("榜单：rank 递增、hotScore=1000-rank*100、场馆名回填")
    void rankingScores() {
        Performance p1 = Fixtures.performance("ON_SALE");
        p1.setVenueId(3L);
        Performance p2 = Fixtures.performance("ON_SALE");
        p2.setId(2L);
        p2.setVenueId(null);
        when(performanceMapper.selectList(any())).thenReturn(List.of(p1, p2));
        Venue venue = new Venue();
        venue.setId(3L);
        venue.setVenueName("国家体育馆");
        when(venueMapper.selectById(3L)).thenReturn(venue);

        List<RankingVO> ranking = showService.getRanking("WEEK", null, 2);
        assertThat(ranking).hasSize(2);
        assertThat(ranking.get(0).getRank()).isEqualTo(1);
        assertThat(ranking.get(0).getHotScore()).isEqualTo(900);
        assertThat(ranking.get(0).getVenueName()).isEqualTo("国家体育馆");
        assertThat(ranking.get(1).getRank()).isEqualTo(2);
        assertThat(ranking.get(1).getHotScore()).isEqualTo(800);
        assertThat(ranking.get(1).getVenueName()).isNull();
    }

    @Test
    @DisplayName("详情：DRAFT / 不存在 → PERFORMANCE_NOT_FOUND")
    void detailGuard() {
        assertThatThrownBy(() -> showService.getShowDetail(999L))
                .matches(e -> ((BusinessException) e).getCode() == 1005);
        perf.setPublishStatus("DRAFT");
        assertThatThrownBy(() -> showService.getShowDetail(Fixtures.PERF_ID))
                .matches(e -> ((BusinessException) e).getCode() == 1005);
    }

    @Test
    @DisplayName("详情：maxPrice 取价格区最高价；无价格区时为 null；巡演汇总均值回填")
    void detailAggregation() {
        var zones = List.of(Fixtures.zone("A", 58000), Fixtures.zone("B", 38000));
        when(priceZoneMapper.selectList(any())).thenReturn(zones);
        ReviewSummary summary = new ReviewSummary();
        summary.setGroupId("TOUR_JAY_2026");
        summary.setTotalReviews(156);
        summary.setAvgRating(new BigDecimal("4.8"));
        summary.setTopTags("[\"氛围超棒\"]");
        when(reviewSummaryMapper.selectById("TOUR_JAY_2026")).thenReturn(summary);

        ShowDetailVO vo = showService.getShowDetail(Fixtures.PERF_ID);
        assertThat(vo.getMaxPrice()).isEqualTo(58000);
        assertThat(vo.getWantCount()).isZero();
        assertThat(vo.getIsWanted()).isFalse(); // 未登录
        assertThat(vo.getReviewSummary().getAvgRating()).isEqualTo(4.8);
        assertThat(vo.getReviewSummary().getTotalReviews()).isEqualTo(156);
        assertThat(vo.getReviewSummary().getTopTags()).containsExactly("氛围超棒");

        // 无价格区 → maxPrice null
        when(priceZoneMapper.selectList(any())).thenReturn(List.of());
        perf.setTourId(null); // groupId 回退场次 id
        ShowDetailVO vo2 = showService.getShowDetail(Fixtures.PERF_ID);
        assertThat(vo2.getMaxPrice()).isNull();
        assertThat(vo2.getReviewSummary().getTotalReviews()).isZero();
    }

    @Test
    @DisplayName("详情：登录后回填 isWanted")
    void detailWantedFlagWhenLoggedIn() {
        UserContext.set(new UserContext.UserInfo(Fixtures.USER_A, "openid"));
        when(userWantMapper.selectOne(any())).thenReturn(new com.rechang.api.entity.UserWant());

        ShowDetailVO vo = showService.getShowDetail(Fixtures.PERF_ID);
        assertThat(vo.getIsWanted()).isTrue();
    }

    @Test
    @DisplayName("订阅三态：ACTIVE 取消 → CANCELLED 重新激活 → 无记录新增")
    void toggleSubscribeStates() {
        UserContext.set(new UserContext.UserInfo(Fixtures.USER_A, "openid"));
        var active = new com.rechang.api.entity.UserSubscription();
        active.setUserId(Fixtures.USER_A);
        active.setPerformanceId(Fixtures.PERF_ID);
        active.setSubType("ONSALE");
        active.setStatus("ACTIVE");

        when(subscriptionMapper.selectOne(any())).thenReturn(active);
        assertThat(showService.toggleSubscribe(Fixtures.PERF_ID, "ONSALE").getSubscribed()).isFalse();
        assertThat(active.getStatus()).isEqualTo("CANCELLED");

        when(subscriptionMapper.selectOne(any())).thenReturn(active);
        assertThat(showService.toggleSubscribe(Fixtures.PERF_ID, "ONSALE").getSubscribed()).isTrue();
        assertThat(active.getStatus()).isEqualTo("ACTIVE");

        when(subscriptionMapper.selectOne(any())).thenReturn(null);
        assertThat(showService.toggleSubscribe(Fixtures.PERF_ID, "ONSALE").getSubscribed()).isTrue();
        verify(subscriptionMapper).insert(any(com.rechang.api.entity.UserSubscription.class));
    }

    @Test
    @DisplayName("想看三态 + 回填 wantCount")
    void toggleWantStates() {
        UserContext.set(new UserContext.UserInfo(Fixtures.USER_A, "openid"));
        var existing = new com.rechang.api.entity.UserWant();
        existing.setUserId(Fixtures.USER_A);
        existing.setPerformanceId(Fixtures.PERF_ID);

        when(userWantMapper.selectOne(any())).thenReturn(existing);
        when(userWantMapper.selectCount(any())).thenReturn(3L);
        WantVO off = showService.toggleWant(Fixtures.PERF_ID);
        assertThat(off.getWanted()).isFalse();
        assertThat(off.getWantCount()).isEqualTo(3);

        when(userWantMapper.selectOne(any())).thenReturn(null);
        WantVO on = showService.toggleWant(Fixtures.PERF_ID);
        assertThat(on.getWanted()).isTrue();
        verify(userWantMapper).insert(any(com.rechang.api.entity.UserWant.class));
    }
}
