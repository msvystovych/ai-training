package com.library.catalog.exception;

public class DuplicateIsbnException extends RuntimeException {

    public DuplicateIsbnException(String isbn) {
        super("ISBN already exists: " + isbn);
    }
}
