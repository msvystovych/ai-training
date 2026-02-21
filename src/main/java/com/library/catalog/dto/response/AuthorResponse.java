package com.library.catalog.dto.response;

import java.time.Instant;
import java.util.List;

public record AuthorResponse(
    Long id,
    String firstName,
    String lastName,
    String bio,
    List<BookSummary> books,
    Instant createdAt,
    Instant updatedAt
) {
    public record BookSummary(Long id, String title) {}
}
