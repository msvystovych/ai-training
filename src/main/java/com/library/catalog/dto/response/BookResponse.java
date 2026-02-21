package com.library.catalog.dto.response;

import java.time.Instant;
import java.util.List;

public record BookResponse(
    Long id,
    String title,
    String isbn,
    String description,
    Integer publishedYear,
    List<AuthorSummary> authors,
    Instant createdAt,
    Instant updatedAt
) {
    public record AuthorSummary(Long id, String firstName, String lastName) {}
}
