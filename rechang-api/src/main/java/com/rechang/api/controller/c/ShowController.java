package com.rechang.api.controller.c;

import com.rechang.api.service.ShowService;
import com.rechang.api.vo.RankingVO;
import com.rechang.api.vo.ShowDetailVO;
import com.rechang.api.vo.ShowListVO;
import com.rechang.api.vo.SubscribeVO;
import com.rechang.api.vo.WantVO;
import com.rechang.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/shows")
@RequiredArgsConstructor
public class ShowController {

    private final ShowService showService;

    @GetMapping
    public Result<ShowListVO> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Integer minPrice,
            @RequestParam(required = false) Integer maxPrice,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(showService.getShowList(keyword, type, city, minPrice, maxPrice, startDate, endDate, page, size));
    }

    @GetMapping("/ranking")
    public Result<List<RankingVO>> ranking(
            @RequestParam(defaultValue = "realtime") String period,
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "10") int limit) {
        return Result.success(showService.getRanking(period, city, limit));
    }

    @GetMapping("/nearby")
    public Result<ShowListVO> nearby(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(defaultValue = "50") int radius,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(showService.getNearby(lat, lng, radius, page, size));
    }

    @GetMapping("/{id}")
    public Result<ShowDetailVO> detail(@PathVariable Long id) {
        return Result.success(showService.getShowDetail(id));
    }

    @PostMapping("/{id}/subscribe")
    public Result<SubscribeVO> subscribe(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String subType = body.getOrDefault("sub_type", "ON_SALE");
        return Result.success(showService.toggleSubscribe(id, subType));
    }

    @PostMapping("/{id}/want")
    public Result<WantVO> want(@PathVariable Long id) {
        return Result.success(showService.toggleWant(id));
    }

    @GetMapping("/{id}/want/count")
    public Result<WantVO> wantCount(@PathVariable Long id) {
        WantVO vo = new WantVO();
        vo.setWantCount(showService.getWantCount(id));
        return Result.success(vo);
    }
}
