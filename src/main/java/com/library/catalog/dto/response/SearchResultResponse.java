package com.library.catalog.dto.response;

import java.util.List;

public record SearchResultResponse(
    Long id,
    String title,
    String isbn,
    Integer publishedYear,
    List<BookResponse.AuthorSummary> authors,
    double relevanceScore
) {}
