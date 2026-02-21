package com.library.catalog.exception;

import com.library.catalog.entity.ReservationStatus;

public class InvalidReservationStateException extends RuntimeException {

    public InvalidReservationStateException(Long reservationId, ReservationStatus currentStatus) {
        super("Reservation " + reservationId + " cannot be cancelled — current status is " + currentStatus);
    }
}
