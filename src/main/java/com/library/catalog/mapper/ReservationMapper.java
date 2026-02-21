package com.library.catalog.mapper;

import com.library.catalog.dto.response.ReservationResponse;
import com.library.catalog.entity.Reservation;

public final class ReservationMapper {

    private ReservationMapper() {}

    public static ReservationResponse toResponse(Reservation reservation) {
        return new ReservationResponse(
            reservation.getId(),
            reservation.getBook().getId(),
            reservation.getBook().getTitle(),
            reservation.getUserName(),
            reservation.getStatus(),
            reservation.getReservedAt(),
            reservation.getExpiresAt(),
            reservation.getCancelledAt()
        );
    }
}
