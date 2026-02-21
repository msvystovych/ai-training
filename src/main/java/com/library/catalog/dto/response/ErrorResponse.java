package com.library.catalog.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(
    int status,
    String error,
    String message,
    Instant timestamp,
    String path,
    List<FieldError> fieldErrors
) {
    public ErrorResponse(int status, String error, String message,
                         Instant timestamp, String path) {
        this(status, error, message, timestamp, path, List.of());
    }

    public record FieldError(String field, String message) {}
}
