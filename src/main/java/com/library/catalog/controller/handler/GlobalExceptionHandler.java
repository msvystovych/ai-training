package com.library.catalog.controller.handler;

import com.library.catalog.dto.response.ErrorResponse;
import com.library.catalog.exception.ActiveReservationExistsException;
import com.library.catalog.exception.BookAlreadyReservedException;
import com.library.catalog.exception.DuplicateIsbnException;
import com.library.catalog.exception.InvalidReservationStateException;
import com.library.catalog.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            new ErrorResponse(404, "Not Found", ex.getMessage(),
                              Instant.now(), request.getRequestURI()));
    }

    @ExceptionHandler(DuplicateIsbnException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateIsbn(DuplicateIsbnException ex,
                                                              HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            new ErrorResponse(409, "Conflict", ex.getMessage(),
                              Instant.now(), request.getRequestURI()));
    }

    @ExceptionHandler(ActiveReservationExistsException.class)
    public ResponseEntity<ErrorResponse> handleActiveReservation(ActiveReservationExistsException ex,
                                                                  HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            new ErrorResponse(409, "Conflict", ex.getMessage(),
                              Instant.now(), request.getRequestURI()));
    }

    @ExceptionHandler(BookAlreadyReservedException.class)
    public ResponseEntity<ErrorResponse> handleBookAlreadyReserved(BookAlreadyReservedException ex,
                                                                     HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            new ErrorResponse(409, "Conflict", ex.getMessage(),
                              Instant.now(), request.getRequestURI()));
    }

    @ExceptionHandler(InvalidReservationStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidReservationState(InvalidReservationStateException ex,
                                                                        HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            new ErrorResponse(409, "Conflict", ex.getMessage(),
                              Instant.now(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                           HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors()
            .stream()
            .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
            .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            new ErrorResponse(400, "Bad Request", "Validation failed",
                              Instant.now(), request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                           HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            new ErrorResponse(400, "Bad Request", "Malformed request body",
                              Instant.now(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                             HttpServletRequest request) {
        String msg = String.format("Invalid value '%s' for parameter '%s'",
                                   ex.getValue(), ex.getName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            new ErrorResponse(400, "Bad Request", msg, Instant.now(), request.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex,
                                                              HttpServletRequest request) {
        String constraintName = extractConstraintName(ex);
        if ("idx_reservations_active_book".equals(constraintName)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ErrorResponse(409, "Conflict", "Book already has an active reservation",
                                  Instant.now(), request.getRequestURI()));
        }
        if ("idx_books_isbn".equals(constraintName)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ErrorResponse(409, "Conflict", "ISBN already exists",
                                  Instant.now(), request.getRequestURI()));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            new ErrorResponse(400, "Bad Request", "Data integrity violation",
                              Instant.now(), request.getRequestURI()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                               HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            new ErrorResponse(409, "Conflict",
                              "Resource was modified by another request. Please retry.",
                              Instant.now(), request.getRequestURI()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                                HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            new ErrorResponse(400, "Bad Request", ex.getMessage(),
                              Instant.now(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            new ErrorResponse(500, "Internal Server Error", "An unexpected error occurred",
                              Instant.now(), request.getRequestURI()));
    }

    private String extractConstraintName(DataIntegrityViolationException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof ConstraintViolationException cve) {
            return cve.getConstraintName();
        }
        return null;
    }
}
