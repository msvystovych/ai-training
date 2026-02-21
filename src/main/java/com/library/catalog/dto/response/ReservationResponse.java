package com.library.catalog.dto.response;

import com.library.catalog.entity.ReservationStatus;

import java.time.Instant;

public record ReservationResponse(
    Long id,
    Long bookId,
    String bookTitle,
    String userName,
    ReservationStatus status,
    Instant reservedAt,
    Instant expiresAt,
    Instant cancelledAt
) {}
