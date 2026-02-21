package com.library.catalog.exception;

public class ActiveReservationExistsException extends RuntimeException {

    public ActiveReservationExistsException(String message) {
        super(message);
    }
}
