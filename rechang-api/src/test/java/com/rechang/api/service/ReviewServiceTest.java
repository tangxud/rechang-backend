package com.rechang.api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rechang.api.dto.ReviewReplyDTO;
import com.rechang.api.dto.ReviewReportDTO;
import com.rechang.api.dto.ReviewSubmitDTO;
import com.rechang.api.entity.OrderEntity;
import com.rechang.api.entity.Performance;
import com.rechang.api.entity.PerformanceReview;
import com.rechang.api.entity.ReviewSummary;
import com.rechang.api.mapper.OrderMapper;
import com.rechang.api.mapper.PerformanceMapper;
import com.rechang.api.mapper.PerformanceReviewMapper;
import com.rechang.api.mapper.ReviewHelpfulMapper;
import com.rechang.api.mapper.ReviewReportMapper;
import com.rechang.api.mapper.ReviewReplyMapper;
import com.rechang.api.mapper.ReviewSummaryMapper;
import com.rechang.api.mapper.UserMapper;
import com.rechang.api.security.UserContext;
import com.rechang.api.support.Fixtures;
import com.rechang.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static com.rechang.api.support.Fixtures.daysAgo;
import static com.rechang.api.support.Fixtures.daysFromNow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评价资格九连守卫、汇总均值增量数学（HALF_UP 1位小数）、回复/举报/点赞规则。
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock PerformanceReviewMapper reviewMapper;
    @Mock ReviewSummaryMapper summaryMapper;
    @Mock ReviewHelpfulMapper helpfulMapper;
    @Mock ReviewReplyMapper replyMapper;
    @Mock ReviewReportMapper reportMapper;
    @Mock PerformanceMapper performanceMapper;
    @Mock OrderMapper orderMapper;
    @Mock UserMapper userMapper;
    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;
    @Spy
    ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks ReviewService reviewService;

    private Performance perf;
    private OrderEntity attendedOrder;
    private ReviewSubmitDTO dto;

    @BeforeEach
    void setUp() {
        lenient().doReturn(valueOps).when(redisTemplate).opsForValue();
        perf = Fixtures.performance("ON_SALE");
        perf.setEndAt(Fixtures.daysFromNow(-1));    // 已结束
        perf.setCityCode("BEIJING");
        attendedOrder = Fixtures.order(Fixtures.ORDER_ID, Fixtures.USER_A, Fixtures.PERF_ID, "ATTENDED");
        attendedOrder.setCompletedAt(daysAgo(1));

        dto = new ReviewSubmitDTO();
        dto.setPerformanceId(Fixtures.PERF_ID);
        dto.setRating(5);
        dto.setContent("非常好的观演体验");
        dto.setIsAnonymous(false);

        lenient().when(performanceMapper.selectById(Fixtures.PERF_ID)).thenReturn(perf);
        lenient().when(orderMapper.selectOne(any())).thenReturn(attendedOrder);
        // 乐观锁冲突检查默认放行（冲突语义由专项用例验证）
        lenient().when(orderMapper.updateById(any(OrderEntity.class))).thenReturn(1);
        lenient().when(reviewMapper.selectOne(any())).thenReturn(null);
        lenient().when(summaryMapper.selectById(any(String.class))).thenReturn(null);
        lenient().when(valueOps.setIfAbsent(any(String.class), any(), any(Long.class), any())).thenReturn(true);
        lenient().when(reviewMapper.insert(any(PerformanceReview.class))).thenAnswer(inv -> {
            inv.getArgument(0, PerformanceReview.class).setId(555L);
            return 1;
        });
    }

    @AfterEach
    void cleanUp() {
        UserContext.clear();
    }

    private void happySubmit() {
        reviewService.submitReview(dto, Fixtures.USER_A);
    }

    /* ================= submitReview 守卫链 ================= */

    @Nested
    class SubmitGuards {
        @Test
        @DisplayName("评分空/越界 → BAD_REQUEST")
        void ratingBounds() {
            dto.setRating(null);
            assertThatThrownBy(() -> happySubmit()).matches(e -> ((BusinessException) e).getCode() == 400);
            dto.setRating(0);
            assertThatThrownBy(() -> happySubmit()).matches(e -> ((BusinessException) e).getCode() == 400);
            dto.setRating(6);
            assertThatThrownBy(() -> happySubmit()).matches(e -> ((BusinessException) e).getCode() == 400);
        }

        @Test
        @DisplayName("图片超过 6 张 → BAD_REQUEST")
        void imageLimit() {
            dto.setImages(List.of("1", "2", "3", "4", "5", "6", "7"));
            assertThatThrownBy(() -> happySubmit())
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("图片最多");
        }

        @Test
        @DisplayName("60s 限流窗口内重复提交拒绝")
        void rateLimit() {
            when(valueOps.setIfAbsent(any(String.class), any(), any(Long.class), any())).thenReturn(false);
            assertThatThrownBy(() -> happySubmit())
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("频繁");
        }

        @Test
        @DisplayName("内容/标签命中敏感词拒绝")
        void sensitiveWords() {
            dto.setContent("这个场次全是黄牛");
            assertThatThrownBy(() -> happySubmit())
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("敏感");
            dto.setContent("正常内容");
            dto.setTags(List.of("加微信拿票"));
            assertThatThrownBy(() -> happySubmit())
                    .hasMessageContaining("敏感");
        }

        @Test
        @DisplayName("演出不存在 / 无 ATTENDED 订单 / 一单一评")
        void qualification() {
            when(performanceMapper.selectById(Fixtures.PERF_ID)).thenReturn(null);
            assertThatThrownBy(() -> happySubmit()).matches(e -> ((BusinessException) e).getCode() == 1005);

            when(performanceMapper.selectById(Fixtures.PERF_ID)).thenReturn(perf);
            when(orderMapper.selectOne(any())).thenReturn(null);
            assertThatThrownBy(() -> happySubmit()).matches(e -> ((BusinessException) e).getCode() == 1018);

            when(orderMapper.selectOne(any())).thenReturn(attendedOrder);
            when(reviewMapper.selectOne(any())).thenReturn(new PerformanceReview());
            assertThatThrownBy(() -> happySubmit()).matches(e -> ((BusinessException) e).getCode() == 1019);
        }

        @Test
        @DisplayName("演出未结束（endAt 为空或在未来）不可评价")
        void mustBeEnded() {
            perf.setEndAt(null);
            assertThatThrownBy(() -> happySubmit()).hasMessageContaining("尚未结束");
            perf.setEndAt(daysFromNow(1));
            assertThatThrownBy(() -> happySubmit()).hasMessageContaining("尚未结束");
        }

        @Test
        @DisplayName("completed_at 空 / 超 30 天窗口 → REVIEW_WINDOW_EXPIRED")
        void reviewWindow() {
            attendedOrder.setCompletedAt(null);
            assertThatThrownBy(() -> happySubmit()).matches(e -> ((BusinessException) e).getCode() == 1021);
            attendedOrder.setCompletedAt(daysAgo(31));
            assertThatThrownBy(() -> happySubmit()).matches(e -> ((BusinessException) e).getCode() == 1021);
        }
    }

    /* ================= submitReview 成功路径 ================= */

    @Test
    @DisplayName("提交成功：groupId 取巡演、匿名标记、tags/images 序列化、订单流转 REVIEWED")
    void submitSuccess() {
        dto.setTags(List.of("氛围", "值回票价"));
        dto.setImages(List.of("https://cdn.rechang.com/r/1.jpg"));

        Long id = reviewService.submitReview(dto, Fixtures.USER_A);
        assertThat(id).isEqualTo(555L);

        ArgumentCaptor<PerformanceReview> cap = ArgumentCaptor.forClass(PerformanceReview.class);
        verify(reviewMapper).insert(cap.capture());
        PerformanceReview inserted = cap.getValue();
        assertThat(inserted.getGroupId()).isEqualTo("TOUR_JAY_2026"); // 巡演聚合
        assertThat(inserted.getAttendedPerformanceId()).isEqualTo(Fixtures.PERF_ID);
        assertThat(inserted.getOrderId()).isEqualTo(Fixtures.ORDER_ID);
        assertThat(inserted.getStatus()).isEqualTo("VISIBLE");
        assertThat(inserted.getIsAnonymous()).isZero();
        assertThat(inserted.getHelpfulCount()).isZero();
        assertThat(inserted.getReplyCount()).isZero();
        assertThat(inserted.getImages()).isEqualTo("[\"https://cdn.rechang.com/r/1.jpg\"]");
        assertThat(inserted.getTags()).isEqualTo("[\"氛围\",\"值回票价\"]");

        // 订单对象直接流转 ATTENDED → REVIEWED
        assertThat(attendedOrder.getStatus()).isEqualTo("REVIEWED");
        assertThat(attendedOrder.getReviewedAt()).isNotNull();
        verify(orderMapper).updateById(attendedOrder);
    }

    @Test
    @DisplayName("匿名评价标记 isAnonymous=1")
    void anonymousFlag() {
        dto.setIsAnonymous(true);
        happySubmit();
        ArgumentCaptor<PerformanceReview> cap = ArgumentCaptor.forClass(PerformanceReview.class);
        verify(reviewMapper).insert(cap.capture());
        assertThat(cap.getValue().getIsAnonymous()).isEqualTo(1);
    }

    @Test
    @DisplayName("无巡演的演出 groupId 回退为场次 id 字符串")
    void groupIdFallsBackToPerformanceId() {
        perf.setTourId(null);
        happySubmit();
        ArgumentCaptor<PerformanceReview> cap = ArgumentCaptor.forClass(PerformanceReview.class);
        verify(reviewMapper).insert(cap.capture());
        assertThat(cap.getValue().getGroupId()).isEqualTo(String.valueOf(Fixtures.PERF_ID));
    }

    /* ================= 汇总增量数学 ================= */

    private ReviewSummary summary(String groupId, int total, String avg) {
        ReviewSummary s = new ReviewSummary();
        s.setGroupId(groupId);
        s.setTotalReviews(total);
        s.setAvgRating(new BigDecimal(avg));
        s.setTopTags("");
        return s;
    }

    @Test
    @DisplayName("首条评价：新建 summary，均值=评分")
    void summaryCreatedOnFirstReview() {
        happySubmit();
        ArgumentCaptor<ReviewSummary> cap = ArgumentCaptor.forClass(ReviewSummary.class);
        verify(summaryMapper).insert(cap.capture());
        assertThat(cap.getValue().getTotalReviews()).isEqualTo(1);
        assertThat(cap.getValue().getAvgRating()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("增量均值：avg=4.0,total=2 加 5 分 → (8+5)/3=4.3（HALF_UP 1位）")
    void summaryIncrementalMath() {
        when(summaryMapper.selectById("TOUR_JAY_2026")).thenReturn(summary("TOUR_JAY_2026", 2, "4.0"));
        happySubmit();
        ArgumentCaptor<ReviewSummary> cap = ArgumentCaptor.forClass(ReviewSummary.class);
        verify(summaryMapper).updateById(cap.capture());
        assertThat(cap.getValue().getTotalReviews()).isEqualTo(3);
        assertThat(cap.getValue().getAvgRating()).isEqualByComparingTo("4.3");
    }

    /* ================= deleteReview ================= */

    @Test
    @DisplayName("删除：不存在/非本人/成功后汇总回退（total 归 0 均值归 0）")
    void deleteReviewFlows() {
        when(reviewMapper.selectById(555L)).thenReturn(null);
        assertThatThrownBy(() -> reviewService.deleteReview(555L, Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1020);

        PerformanceReview review = new PerformanceReview();
        review.setId(555L);
        review.setUserId(Fixtures.USER_B);
        review.setGroupId("TOUR_JAY_2026");
        review.setRating(4);
        review.setStatus("VISIBLE");
        when(reviewMapper.selectById(555L)).thenReturn(review);
        assertThatThrownBy(() -> reviewService.deleteReview(555L, Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1022);

        review.setUserId(Fixtures.USER_A);
        when(summaryMapper.selectById("TOUR_JAY_2026")).thenReturn(summary("TOUR_JAY_2026", 1, "4.0"));
        reviewService.deleteReview(555L, Fixtures.USER_A);
        assertThat(review.getStatus()).isEqualTo("DELETED");
        ArgumentCaptor<ReviewSummary> cap = ArgumentCaptor.forClass(ReviewSummary.class);
        verify(summaryMapper).updateById(cap.capture());
        assertThat(cap.getValue().getTotalReviews()).isZero();
        assertThat(cap.getValue().getAvgRating()).isEqualByComparingTo("0");
    }

    /* ================= toggleHelpful ================= */

    @Test
    @DisplayName("点赞切换：新增+1 / 取消-1 且下限 0")
    void toggleHelpfulFlows() {
        PerformanceReview review = new PerformanceReview();
        review.setId(555L);
        review.setStatus("VISIBLE");
        review.setHelpfulCount(3);
        when(reviewMapper.selectById(555L)).thenReturn(review);

        when(helpfulMapper.selectOne(any())).thenReturn(null);
        assertThat(reviewService.toggleHelpful(555L, Fixtures.USER_A)).isTrue();
        assertThat(review.getHelpfulCount()).isEqualTo(4);

        when(helpfulMapper.selectOne(any())).thenReturn(new com.rechang.api.entity.ReviewHelpful());
        assertThat(reviewService.toggleHelpful(555L, Fixtures.USER_A)).isFalse();
        assertThat(review.getHelpfulCount()).isEqualTo(3);

        review.setHelpfulCount(0);
        assertThat(reviewService.toggleHelpful(555L, Fixtures.USER_A)).isFalse();
        assertThat(review.getHelpfulCount()).isZero();  // Math.max(0, -1) clamp

        review.setStatus("DELETED");
        assertThatThrownBy(() -> reviewService.toggleHelpful(555L, Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1020);
    }

    /* ================= reply / report ================= */

    @Test
    @DisplayName("回复：空白/超500字/敏感词/评价不可见 拒绝；成功自增 replyCount")
    void replyFlows() {
        ReviewReplyDTO replyDto = new ReviewReplyDTO();
        replyDto.setContent("  ");
        assertThatThrownBy(() -> reviewService.submitReply(555L, replyDto, Fixtures.USER_A))
                .hasMessageContaining("不能为空");

        replyDto.setContent("字".repeat(501));
        assertThatThrownBy(() -> reviewService.submitReply(555L, replyDto, Fixtures.USER_A))
                .hasMessageContaining("500");

        replyDto.setContent("加微信优惠");
        assertThatThrownBy(() -> reviewService.submitReply(555L, replyDto, Fixtures.USER_A))
                .hasMessageContaining("敏感");

        when(reviewMapper.selectById(555L)).thenReturn(null);
        replyDto.setContent("正常回复");
        assertThatThrownBy(() -> reviewService.submitReply(555L, replyDto, Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1020);

        PerformanceReview review = new PerformanceReview();
        review.setId(555L);
        review.setStatus("VISIBLE");
        review.setReplyCount(2);
        when(reviewMapper.selectById(555L)).thenReturn(review);
        reviewService.submitReply(555L, replyDto, Fixtures.USER_A);
        assertThat(review.getReplyCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("举报：类型白名单 / 同人重复举报 CONFLICT / 成功落 PENDING")
    void reportFlows() {
        ReviewReportDTO reportDto = new ReviewReportDTO();
        reportDto.setReportType("SPAM");

        when(reviewMapper.selectById(555L)).thenReturn(null);
        assertThatThrownBy(() -> reviewService.reportReview(555L, reportDto, Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 1020);

        PerformanceReview review = new PerformanceReview();
        review.setId(555L);
        review.setStatus("VISIBLE");
        when(reviewMapper.selectById(555L)).thenReturn(review);

        reportDto.setReportType("HACK");
        assertThatThrownBy(() -> reviewService.reportReview(555L, reportDto, Fixtures.USER_A))
                .hasMessageContaining("举报类型无效");

        reportDto.setReportType("SPAM");
        when(reportMapper.selectOne(any())).thenReturn(new com.rechang.api.entity.ReviewReport());
        assertThatThrownBy(() -> reviewService.reportReview(555L, reportDto, Fixtures.USER_A))
                .matches(e -> ((BusinessException) e).getCode() == 409);

        when(reportMapper.selectOne(any())).thenReturn(null);
        reviewService.reportReview(555L, reportDto, Fixtures.USER_A);
        ArgumentCaptor<com.rechang.api.entity.ReviewReport> cap =
                ArgumentCaptor.forClass(com.rechang.api.entity.ReviewReport.class);
        verify(reportMapper).insert(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(cap.getValue().getReportType()).isEqualTo("SPAM");
    }

    /* ================= 读路径 ================= */

    @Test
    @DisplayName("resolveGroupId 三分支：tourId 优先 / 空白回退 / null 回退")
    void resolveGroupIdRules() {
        assertThat(reviewService.resolveGroupId(perf)).isEqualTo("TOUR_JAY_2026");
        perf.setTourId(" ");
        assertThat(reviewService.resolveGroupId(perf)).isEqualTo(String.valueOf(Fixtures.PERF_ID));
        perf.setTourId(null);
        assertThat(reviewService.resolveGroupId(perf)).isEqualTo(String.valueOf(Fixtures.PERF_ID));
    }

    @Test
    @DisplayName("getSummary 读巡演聚合 summary")
    void getSummaryReadsByGroup() {
        when(summaryMapper.selectById("TOUR_JAY_2026")).thenReturn(summary("TOUR_JAY_2026", 156, "4.8"));
        var vo = reviewService.getSummary("TOUR_JAY_2026");
        assertThat(vo.getTotalCount()).isEqualTo(156);
        assertThat(vo.getAvgRating()).isEqualByComparingTo("4.8");
    }

    @Test
    @DisplayName("评价列表：匿名脱敏、分页回填、未登录不查 helpful")
    void reviewListMapping() throws Exception {
        PerformanceReview row = new PerformanceReview();
        row.setId(1L);
        row.setGroupId("TOUR_JAY_2026");
        row.setUserId(Fixtures.USER_A);
        row.setRating(5);
        row.setContent("好评");
        row.setIsAnonymous(1);
        row.setStatus("VISIBLE");
        row.setHelpfulCount(2);
        row.setReplyCount(0);
        row.setCreateTime(new Date());

        Page<PerformanceReview> page = new Page<>(1, 10);
        page.setRecords(List.of(row));
        page.setTotal(1);
        when(reviewMapper.selectPage(any(Page.class), any())).thenReturn(page);

        // 未登录
        var vo1 = reviewService.getReviews(Fixtures.PERF_ID, "HOT", 1, 10);
        assertThat(vo1.getList()).hasSize(1);
        assertThat(vo1.getList().get(0).getUserNickname()).isEqualTo("匿名用户");
        assertThat(vo1.getList().get(0).getIsHelpful()).isFalse();

        // 登录态
        UserContext.set(new UserContext.UserInfo(Fixtures.USER_A, "openid"));
        var vo2 = reviewService.getReviews(Fixtures.PERF_ID, "LATEST", 1, 10);
        assertThat(vo2.getList().get(0).getIsMine()).isTrue();
        assertThat(vo2.getTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("回复列表：空回复集 + 昵称兜底")
    void replyListMapping() {
        PerformanceReview review = new PerformanceReview();
        review.setId(555L);
        review.setStatus("VISIBLE");
        when(reviewMapper.selectById(555L)).thenReturn(review);

        Page<com.rechang.api.entity.ReviewReply> page = new Page<>(1, 10);
        com.rechang.api.entity.ReviewReply reply = new com.rechang.api.entity.ReviewReply();
        reply.setId(9L);
        reply.setReviewId(555L);
        reply.setUserId(Fixtures.USER_B);
        reply.setContent("赞");
        reply.setStatus("VISIBLE");
        page.setRecords(List.of(reply));
        when(replyMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of()); // 用户已注销 → 昵称兜底

        var vo = reviewService.getReplies(555L, 1, 10);
        assertThat(vo.getList()).hasSize(1);
        assertThat(vo.getList().get(0).getUserNickname()).isEqualTo("热场用户");
    }
}
