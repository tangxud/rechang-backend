package com.rechang.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rechang.api.dto.ReviewReplyDTO;
import com.rechang.api.dto.ReviewReportDTO;
import com.rechang.api.dto.ReviewSubmitDTO;
import com.rechang.api.entity.OrderEntity;
import com.rechang.api.entity.Performance;
import com.rechang.api.entity.PerformanceReview;
import com.rechang.api.entity.ReviewHelpful;
import com.rechang.api.entity.ReviewReport;
import com.rechang.api.entity.ReviewReply;
import com.rechang.api.entity.ReviewSummary;
import com.rechang.api.entity.User;
import com.rechang.api.mapper.OrderMapper;
import com.rechang.api.mapper.PerformanceMapper;
import com.rechang.api.mapper.PerformanceReviewMapper;
import com.rechang.api.mapper.ReviewHelpfulMapper;
import com.rechang.api.mapper.ReviewReportMapper;
import com.rechang.api.mapper.ReviewReplyMapper;
import com.rechang.api.mapper.ReviewSummaryMapper;
import com.rechang.api.mapper.UserMapper;
import com.rechang.api.security.UserContext;
import com.rechang.api.vo.ReviewItemVO;
import com.rechang.api.vo.ReviewListVO;
import com.rechang.api.vo.ReviewReplyListVO;
import com.rechang.api.vo.ReviewReplyVO;
import com.rechang.api.vo.ReviewSummaryVO;
import com.rechang.common.exception.BusinessException;
import com.rechang.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final int REVIEW_WINDOW_DAYS = 30;
    private static final long MS_PER_DAY = 24L * 60 * 60 * 1000;
    private static final int MAX_IMAGES = 6;
    private static final Set<String> VALID_REPORT_TYPES = Set.of("SPAM", "ABUSE", "FALSE", "OTHER");
    private static final long RATE_LIMIT_SECONDS = 60;
    private static final Set<String> SENSITIVE_WORDS = Set.of(
            "广告", "代购", "黄牛", "刷单", "诈骗", "赌博", "色情", "暴力", "违禁",
            "fuck", "shit", "垃圾广告", "微信号", "加微信", "免费领", "刷评价");

    private final PerformanceReviewMapper reviewMapper;
    private final ReviewSummaryMapper summaryMapper;
    private final ReviewHelpfulMapper helpfulMapper;
    private final ReviewReplyMapper replyMapper;
    private final ReviewReportMapper reportMapper;
    private final PerformanceMapper performanceMapper;
    private final OrderMapper orderMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    public ReviewListVO getReviews(Long performanceId, String sortBy, int page, int size) {
        Performance perf = performanceMapper.selectById(performanceId);
        if (perf == null) {
            throw new BusinessException(ResultCode.PERFORMANCE_NOT_FOUND);
        }
        String groupId = resolveGroupId(perf);

        LambdaQueryWrapper<PerformanceReview> wrapper = new LambdaQueryWrapper<PerformanceReview>()
                .eq(PerformanceReview::getGroupId, groupId)
                .eq(PerformanceReview::getStatus, "VISIBLE");
        if ("LATEST".equalsIgnoreCase(sortBy)) {
            wrapper.orderByDesc(PerformanceReview::getCreateTime);
        } else {
            wrapper.orderByDesc(PerformanceReview::getHelpfulCount)
                    .orderByDesc(PerformanceReview::getCreateTime);
        }

        Page<PerformanceReview> pageResult = reviewMapper.selectPage(new Page<>(page, size), wrapper);
        List<PerformanceReview> reviews = pageResult.getRecords();

        Long currentUserId = UserContext.getUserId();
        Set<Long> helpfulReviewIds = currentUserId != null ? findHelpfulReviewIds(reviews, currentUserId) : Collections.emptySet();
        Map<Long, User> userMap = batchLoadUsers(reviews);

        List<ReviewItemVO> list = reviews.stream()
                .map(r -> toReviewItemVO(r, userMap, currentUserId, helpfulReviewIds))
                .toList();

        ReviewListVO vo = new ReviewListVO();
        vo.setSummary(buildSummary(groupId));
        vo.setList(list);
        vo.setPage(page);
        vo.setSize(size);
        vo.setTotal(pageResult.getTotal());
        return vo;
    }

    @Transactional
    public Long submitReview(ReviewSubmitDTO dto, Long userId) {
        if (dto.getRating() == null || dto.getRating() < 1 || dto.getRating() > 5) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "评分需为 1-5 星");
        }
        if (dto.getImages() != null && dto.getImages().size() > MAX_IMAGES) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片最多 " + MAX_IMAGES + " 张");
        }

        String rateKey = "review:rate:" + userId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(rateKey, "1", RATE_LIMIT_SECONDS, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(acquired)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "操作过于频繁，请稍后再试");
        }

        String fullText = (dto.getContent() != null ? dto.getContent() : "")
                + " " + String.join(" ", dto.getTags() != null ? dto.getTags() : Collections.emptyList());
        String matched = findSensitiveWord(fullText);
        if (matched != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "评价包含敏感内容「" + matched + "」，请修改后重新提交");
        }

        Performance perf = performanceMapper.selectById(dto.getPerformanceId());
        if (perf == null) {
            throw new BusinessException(ResultCode.PERFORMANCE_NOT_FOUND);
        }

        OrderEntity order = findAttendedOrder(userId, dto.getPerformanceId());
        if (order == null) {
            throw new BusinessException(ResultCode.REVIEW_NOT_ALLOWED);
        }

        PerformanceReview existing = reviewMapper.selectOne(
                new LambdaQueryWrapper<PerformanceReview>()
                        .eq(PerformanceReview::getOrderId, order.getId()));
        if (existing != null) {
            throw new BusinessException(ResultCode.REVIEW_ALREADY_EXISTS);
        }

        if (!isPerformanceEnded(perf)) {
            throw new BusinessException(ResultCode.REVIEW_NOT_ALLOWED, "演出尚未结束，暂不可评价");
        }

        if (order.getCompletedAt() == null
                || System.currentTimeMillis() - order.getCompletedAt().getTime() > REVIEW_WINDOW_DAYS * MS_PER_DAY) {
            throw new BusinessException(ResultCode.REVIEW_WINDOW_EXPIRED);
        }

        String groupId = resolveGroupId(perf);
        Date now = new Date();

        PerformanceReview review = new PerformanceReview();
        review.setGroupId(groupId);
        review.setAttendedPerformanceId(dto.getPerformanceId());
        review.setUserId(userId);
        review.setOrderId(order.getId());
        review.setRating(dto.getRating());
        review.setTags(serializeJsonList(dto.getTags()));
        review.setContent(dto.getContent() != null ? dto.getContent() : "");
        review.setImages(serializeJsonList(dto.getImages()));
        review.setSiteCity(perf.getCityCode() != null ? perf.getCityCode() : "");
        review.setHelpfulCount(0);
        review.setReplyCount(0);
        review.setIsAnonymous(Boolean.TRUE.equals(dto.getIsAnonymous()) ? 1 : 0);
        review.setStatus("VISIBLE");
        review.setCreateTime(now);
        review.setUpdateTime(now);
        reviewMapper.insert(review);

        updateSummaryIncremental(groupId, dto.getRating(), true);

        // 4. 订单状态流转：ATTENDED → REVIEWED
        order.setStatus("REVIEWED");
        order.setReviewedAt(now);
        order.setUpdateTime(now);
        if (orderMapper.updateById(order) == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "订单状态已变化，评价失败");
        }

        return review.getId();
    }

    @Transactional
    public void deleteReview(Long reviewId, Long userId) {
        PerformanceReview review = reviewMapper.selectById(reviewId);
        if (review == null || "DELETED".equals(review.getStatus())) {
            throw new BusinessException(ResultCode.REVIEW_NOT_FOUND);
        }
        if (!review.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.REVIEW_PERMISSION_DENIED);
        }

        review.setStatus("DELETED");
        review.setUpdateTime(new Date());
        reviewMapper.updateById(review);

        updateSummaryIncremental(review.getGroupId(), review.getRating(), false);
    }

    @Transactional
    public boolean toggleHelpful(Long reviewId, Long userId) {
        PerformanceReview review = reviewMapper.selectById(reviewId);
        if (review == null || !"VISIBLE".equals(review.getStatus())) {
            throw new BusinessException(ResultCode.REVIEW_NOT_FOUND);
        }

        ReviewHelpful existing = helpfulMapper.selectOne(
                new LambdaQueryWrapper<ReviewHelpful>()
                        .eq(ReviewHelpful::getReviewId, reviewId)
                        .eq(ReviewHelpful::getUserId, userId));

        if (existing != null) {
            helpfulMapper.delete(
                    new LambdaQueryWrapper<ReviewHelpful>()
                            .eq(ReviewHelpful::getReviewId, reviewId)
                            .eq(ReviewHelpful::getUserId, userId));
            review.setHelpfulCount(Math.max(0, (review.getHelpfulCount() != null ? review.getHelpfulCount() : 0) - 1));
            reviewMapper.updateById(review);
            return false;
        }

        ReviewHelpful helpful = new ReviewHelpful();
        helpful.setReviewId(reviewId);
        helpful.setUserId(userId);
        helpful.setCreateTime(new Date());
        helpfulMapper.insert(helpful);
        review.setHelpfulCount((review.getHelpfulCount() != null ? review.getHelpfulCount() : 0) + 1);
        reviewMapper.updateById(review);
        return true;
    }

    public ReviewReplyListVO getReplies(Long reviewId, int page, int size) {
        PerformanceReview review = reviewMapper.selectById(reviewId);
        if (review == null || "DELETED".equals(review.getStatus())) {
            throw new BusinessException(ResultCode.REVIEW_NOT_FOUND);
        }

        Page<ReviewReply> pageResult = replyMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<ReviewReply>()
                        .eq(ReviewReply::getReviewId, reviewId)
                        .eq(ReviewReply::getStatus, "VISIBLE")
                        .orderByAsc(ReviewReply::getCreateTime));

        Long currentUserId = UserContext.getUserId();
        Set<Long> userIds = pageResult.getRecords().stream().map(ReviewReply::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userIds.isEmpty() ? Collections.emptyMap()
                : userMapper.selectBatchIds(userIds).stream().collect(Collectors.toMap(User::getId, u -> u));

        List<ReviewReplyVO> list = pageResult.getRecords().stream()
                .map(r -> toReplyVO(r, userMap, currentUserId))
                .toList();

        ReviewReplyListVO vo = new ReviewReplyListVO();
        vo.setList(list);
        vo.setPage(page);
        vo.setSize(size);
        vo.setTotal(pageResult.getTotal());
        return vo;
    }

    @Transactional
    public Long submitReply(Long reviewId, ReviewReplyDTO dto, Long userId) {
        if (dto.getContent() == null || dto.getContent().isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "回复内容不能为空");
        }
        if (dto.getContent().length() > 500) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "回复内容不超过 500 字");
        }

        String matched = findSensitiveWord(dto.getContent());
        if (matched != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "回复包含敏感内容「" + matched + "」，请修改后重新提交");
        }

        PerformanceReview review = reviewMapper.selectById(reviewId);
        if (review == null || !"VISIBLE".equals(review.getStatus())) {
            throw new BusinessException(ResultCode.REVIEW_NOT_FOUND);
        }

        ReviewReply reply = new ReviewReply();
        reply.setReviewId(reviewId);
        reply.setUserId(userId);
        reply.setContent(dto.getContent());
        reply.setStatus("VISIBLE");
        reply.setCreateTime(new Date());
        replyMapper.insert(reply);

        review.setReplyCount((review.getReplyCount() != null ? review.getReplyCount() : 0) + 1);
        review.setUpdateTime(new Date());
        reviewMapper.updateById(review);

        return reply.getId();
    }

    @Transactional
    public Long reportReview(Long reviewId, ReviewReportDTO dto, Long userId) {
        if (dto.getReportType() == null || !VALID_REPORT_TYPES.contains(dto.getReportType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "举报类型无效");
        }

        PerformanceReview review = reviewMapper.selectById(reviewId);
        if (review == null || "DELETED".equals(review.getStatus())) {
            throw new BusinessException(ResultCode.REVIEW_NOT_FOUND);
        }

        ReviewReport existing = reportMapper.selectOne(
                new LambdaQueryWrapper<ReviewReport>()
                        .eq(ReviewReport::getReviewId, reviewId)
                        .eq(ReviewReport::getReporterUserId, userId));
        if (existing != null) {
            throw new BusinessException(ResultCode.CONFLICT, "您已举报过该评价");
        }

        ReviewReport report = new ReviewReport();
        report.setReviewId(reviewId);
        report.setReporterUserId(userId);
        report.setReportType(dto.getReportType());
        report.setReason(dto.getReason() != null ? dto.getReason() : "");
        report.setStatus("PENDING");
        report.setCreateTime(new Date());
        reportMapper.insert(report);

        return report.getId();
    }

    public ReviewSummaryVO getSummary(String groupId) {
        return buildSummary(groupId);
    }

    public String resolveGroupId(Performance perf) {
        return perf.getTourId() != null && !perf.getTourId().isBlank()
                ? perf.getTourId()
                : String.valueOf(perf.getId());
    }

    private OrderEntity findAttendedOrder(Long userId, Long performanceId) {
        return orderMapper.selectOne(
                new LambdaQueryWrapper<OrderEntity>()
                        .eq(OrderEntity::getUserId, userId)
                        .eq(OrderEntity::getPerformanceId, performanceId)
                        .eq(OrderEntity::getStatus, "ATTENDED")
                        .last("LIMIT 1"));
    }

    private boolean isPerformanceEnded(Performance perf) {
        if (perf.getEndAt() == null) {
            return false;
        }
        return perf.getEndAt().before(new Date());
    }

    private Set<Long> findHelpfulReviewIds(List<PerformanceReview> reviews, Long userId) {
        if (reviews.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> reviewIds = reviews.stream().map(PerformanceReview::getId).collect(Collectors.toSet());
        return helpfulMapper.selectList(
                        new LambdaQueryWrapper<ReviewHelpful>()
                                .eq(ReviewHelpful::getUserId, userId)
                                .in(ReviewHelpful::getReviewId, reviewIds))
                .stream().map(ReviewHelpful::getReviewId).collect(Collectors.toSet());
    }

    private Map<Long, User> batchLoadUsers(List<PerformanceReview> reviews) {
        Set<Long> userIds = reviews.stream().map(PerformanceReview::getUserId).collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectBatchIds(userIds).stream().collect(Collectors.toMap(User::getId, u -> u));
    }

    private ReviewItemVO toReviewItemVO(PerformanceReview r, Map<Long, User> userMap,
                                        Long currentUserId, Set<Long> helpfulReviewIds) {
        ReviewItemVO vo = new ReviewItemVO();
        vo.setReviewId(r.getId());
        vo.setSiteCity(r.getSiteCity());
        vo.setRating(r.getRating());
        vo.setContent(r.getContent());
        vo.setImages(parseJsonList(r.getImages()));
        vo.setTags(parseJsonList(r.getTags()));
        vo.setHelpfulCount(r.getHelpfulCount());
        vo.setReplyCount(r.getReplyCount());
        vo.setIsAnonymous(r.getIsAnonymous() != null && r.getIsAnonymous() == 1);

        if (Boolean.TRUE.equals(vo.getIsAnonymous())) {
            vo.setUserNickname("匿名用户");
            vo.setUserAvatar(null);
        } else {
            User u = userMap.get(r.getUserId());
            vo.setUserNickname(u != null && u.getNickname() != null && !u.getNickname().isBlank()
                    ? maskNickname(u.getNickname()) : "热场用户");
            vo.setUserAvatar(u != null ? u.getAvatarUrl() : null);
        }

        vo.setIsHelpful(helpfulReviewIds.contains(r.getId()));
        vo.setIsMine(currentUserId != null && currentUserId.equals(r.getUserId()));
        vo.setCreatedAt(r.getCreateTime() != null ? r.getCreateTime().toInstant().toString() : null);
        return vo;
    }

    private ReviewReplyVO toReplyVO(ReviewReply r, Map<Long, User> userMap, Long currentUserId) {
        ReviewReplyVO vo = new ReviewReplyVO();
        vo.setReplyId(r.getId());
        vo.setReviewId(r.getReviewId());
        vo.setContent(r.getContent());
        User u = userMap.get(r.getUserId());
        vo.setUserNickname(u != null && u.getNickname() != null && !u.getNickname().isBlank()
                ? maskNickname(u.getNickname()) : "热场用户");
        vo.setUserAvatar(u != null ? u.getAvatarUrl() : null);
        vo.setIsMine(currentUserId != null && currentUserId.equals(r.getUserId()));
        vo.setCreatedAt(r.getCreateTime() != null ? r.getCreateTime().toInstant().toString() : null);
        return vo;
    }

    private String maskNickname(String nickname) {
        if (nickname == null || nickname.length() <= 1) {
            return "热场用户";
        }
        if (nickname.length() == 2) {
            return nickname.charAt(0) + "*";
        }
        return nickname.charAt(0) + "*".repeat(nickname.length() - 2) + nickname.charAt(nickname.length() - 1);
    }

    private ReviewSummaryVO buildSummary(String groupId) {
        ReviewSummaryVO vo = new ReviewSummaryVO();
        vo.setAvgRating(BigDecimal.ZERO);
        vo.setTotalCount(0);
        vo.setTopTags(List.of());

        ReviewSummary summary = summaryMapper.selectById(groupId);
        if (summary == null) {
            return vo;
        }
        vo.setTotalCount(summary.getTotalReviews() != null ? summary.getTotalReviews() : 0);
        vo.setAvgRating(summary.getAvgRating() != null ? summary.getAvgRating() : BigDecimal.ZERO);
        vo.setTopTags(parseTagCounts(summary.getTopTags()));
        return vo;
    }

    private List<ReviewSummaryVO.TagCount> parseTagCounts(String json) {
        List<String> tags = parseJsonList(json);
        if (tags.isEmpty()) {
            return List.of();
        }
        List<ReviewSummaryVO.TagCount> result = new ArrayList<>();
        for (String tag : tags) {
            result.add(new ReviewSummaryVO.TagCount(tag, 0));
        }
        return result;
    }

    private void updateSummaryIncremental(String groupId, int rating, boolean increment) {
        ReviewSummary summary = summaryMapper.selectById(groupId);
        Date now = new Date();
        if (summary == null) {
            summary = new ReviewSummary();
            summary.setGroupId(groupId);
            summary.setTotalReviews(increment ? 1 : 0);
            summary.setAvgRating(increment ? BigDecimal.valueOf(rating) : BigDecimal.ZERO);
            summary.setTopTags("");
            summary.setCreateTime(now);
            summary.setUpdateTime(now);
            summaryMapper.insert(summary);
            return;
        }

        int currentTotal = summary.getTotalReviews() != null ? summary.getTotalReviews() : 0;
        int newTotal = increment ? currentTotal + 1 : Math.max(0, currentTotal - 1);

        BigDecimal currentAvg = summary.getAvgRating() != null ? summary.getAvgRating() : BigDecimal.ZERO;
        BigDecimal newAvg;
        if (increment) {
            BigDecimal totalScore = currentAvg.multiply(BigDecimal.valueOf(currentTotal))
                    .add(BigDecimal.valueOf(rating));
            newAvg = newTotal > 0 ? totalScore.divide(BigDecimal.valueOf(newTotal), 1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
        } else {
            BigDecimal totalScore = currentAvg.multiply(BigDecimal.valueOf(currentTotal))
                    .subtract(BigDecimal.valueOf(rating));
            newAvg = newTotal > 0 ? totalScore.divide(BigDecimal.valueOf(newTotal), 1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
        }

        summary.setTotalReviews(newTotal);
        summary.setAvgRating(newAvg);
        summary.setUpdateTime(now);
        summaryMapper.updateById(summary);
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse JSON list: {}", json, e);
            return List.of();
        }
    }

    private String serializeJsonList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "";
        }
    }

    private String findSensitiveWord(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase();
        for (String word : SENSITIVE_WORDS) {
            if (lower.contains(word.toLowerCase())) {
                return word;
            }
        }
        return null;
    }
}
