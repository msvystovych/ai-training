package com.library.catalog.exception;

public class BookAlreadyReservedException extends RuntimeException {

    public BookAlreadyReservedException(Long bookId) {
        super("Book with id " + bookId + " already has an active reservation");
    }
}
