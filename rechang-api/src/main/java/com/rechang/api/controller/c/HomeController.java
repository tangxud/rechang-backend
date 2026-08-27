package com.rechang.api.controller.c;

import com.rechang.api.service.HomeService;
import com.rechang.api.vo.HomeVO;
import com.rechang.api.vo.SearchSuggestVO;
import com.rechang.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HomeController {

    private final HomeService homeService;

    @GetMapping("/home")
    public Result<HomeVO> home() {
        return Result.success(homeService.getHomeData());
    }

    @GetMapping("/search/suggest")
    public Result<SearchSuggestVO> searchSuggest(@RequestParam(required = false) String keyword) {
        return Result.success(homeService.searchSuggest(keyword));
    }
}
