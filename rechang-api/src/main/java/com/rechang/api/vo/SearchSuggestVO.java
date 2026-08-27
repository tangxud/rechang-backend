package com.rechang.api.vo;

import lombok.Data;
import java.util.List;

@Data
public class SearchSuggestVO {
    private List<SuggestionItem> suggestions;
    private List<String> hotKeywords;

    @Data
    public static class SuggestionItem {
        private String type;
        private String text;
        private Long performanceId;
        private Long artistId;
    }
}
