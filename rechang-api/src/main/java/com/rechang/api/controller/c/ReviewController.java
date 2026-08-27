package com.rechang.api.controller.c;

import com.rechang.api.dto.ReviewReplyDTO;
import com.rechang.api.dto.ReviewReportDTO;
import com.rechang.api.dto.ReviewSubmitDTO;
import com.rechang.api.security.UserContext;
import com.rechang.api.service.ReviewService;
import com.rechang.api.vo.ReviewListVO;
import com.rechang.api.vo.ReviewReplyListVO;
import com.rechang.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping("/shows/{id}/reviews")
    public Result<ReviewListVO> listReviews(
            @PathVariable Long id,
            @RequestParam(defaultValue = "HELPFUL") String sortBy,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(reviewService.getReviews(id, sortBy, page, size));
    }

    @PostMapping("/shows/{id}/reviews")
    public Result<Map<String, Long>> submitReview(@PathVariable Long id, @RequestBody ReviewSubmitDTO dto) {
        if (dto.getPerformanceId() == null) {
            dto.setPerformanceId(id);
        }
        Long reviewId = reviewService.submitReview(dto, UserContext.getUserId());
        return Result.success(Map.of("reviewId", reviewId));
    }

    @DeleteMapping("/reviews/{id}")
    public Result<Void> deleteReview(@PathVariable Long id) {
        reviewService.deleteReview(id, UserContext.getUserId());
        return Result.success(null);
    }

    @PostMapping("/reviews/{id}/helpful")
    public Result<Map<String, Object>> toggleHelpful(@PathVariable Long id) {
        boolean helpful = reviewService.toggleHelpful(id, UserContext.getUserId());
        return Result.success(Map.of("isHelpful", helpful));
    }

    @GetMapping("/reviews/{id}/replies")
    public Result<ReviewReplyListVO> listReplies(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(reviewService.getReplies(id, page, size));
    }

    @PostMapping("/reviews/{id}/replies")
    public Result<Map<String, Long>> submitReply(@PathVariable Long id, @RequestBody ReviewReplyDTO dto) {
        Long replyId = reviewService.submitReply(id, dto, UserContext.getUserId());
        return Result.success(Map.of("replyId", replyId));
    }

    @PostMapping("/reviews/{id}/reports")
    public Result<Map<String, Long>> reportReview(@PathVariable Long id, @RequestBody ReviewReportDTO dto) {
        Long reportId = reviewService.reportReview(id, dto, UserContext.getUserId());
        return Result.success(Map.of("reportId", reportId));
    }
}
