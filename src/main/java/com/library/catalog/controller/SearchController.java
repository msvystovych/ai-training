package com.library.catalog.controller;

import com.library.catalog.dto.response.PagedResponse;
import com.library.catalog.dto.response.SearchResultResponse;
import com.library.catalog.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@Tag(name = "Search", description = "Full-text search across books and authors")
public class SearchController {

    private final SearchService searchService;

    @GetMapping
    @Operation(summary = "Search books", description = "Full-text search across book titles, descriptions, and author names. "
        + "Uses PostgreSQL tsvector/tsquery. Results sorted by relevance (sort parameter is ignored). "
        + "Requires full words — prefix matching is not supported.")
    @ApiResponse(responseCode = "200", description = "Search results")
    @ApiResponse(responseCode = "400", description = "Query is blank or empty")
    public ResponseEntity<PagedResponse<SearchResultResponse>> search(
            @Parameter(description = "Search query (required, max 500 chars)", example = "effective java")
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        return ResponseEntity.ok(
            PagedResponse.from(searchService.search(q, PageRequest.of(page, size))));
    }
}
