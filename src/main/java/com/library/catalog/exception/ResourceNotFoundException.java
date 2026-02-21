package com.library.catalog.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String entityName, Long id) {
        super(entityName + " not found with id " + id);
    }
}
