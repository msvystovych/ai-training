package com.library.catalog.controller;

import com.library.catalog.dto.request.CreateReservationRequest;
import com.library.catalog.dto.response.PagedResponse;
import com.library.catalog.dto.response.ReservationResponse;
import com.library.catalog.entity.ReservationStatus;
import com.library.catalog.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
@Tag(name = "Reservations", description = "Book reservation management with concurrency control")
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @Operation(summary = "Create a reservation", description = "Reserves a book for a user. "
        + "Only one active reservation per book is allowed. "
        + "If an expired active reservation exists, it is automatically expired and replaced.")
    @ApiResponse(responseCode = "201", description = "Reservation created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Book not found")
    @ApiResponse(responseCode = "409", description = "Book already has an active reservation")
    public ResponseEntity<ReservationResponse> create(@Valid @RequestBody CreateReservationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reservationService.create(request));
    }

    @PatchMapping("/{id}/cancel")
    @Operation(summary = "Cancel a reservation", description = "Cancels an active reservation. The reservation record is retained for history.")
    @ApiResponse(responseCode = "200", description = "Reservation cancelled")
    @ApiResponse(responseCode = "404", description = "Reservation not found")
    @ApiResponse(responseCode = "409", description = "Reservation is not in ACTIVE state")
    public ResponseEntity<ReservationResponse> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(reservationService.cancel(id));
    }

    @GetMapping
    @Operation(summary = "List reservations", description = "Returns a paginated list of reservations with optional filters.")
    public ResponseEntity<PagedResponse<ReservationResponse>> findAll(
            @Parameter(description = "Filter by book ID") @RequestParam(required = false) Long bookId,
            @Parameter(description = "Filter by user name") @RequestParam(required = false) String userName,
            @Parameter(description = "Filter by status (ACTIVE, CANCELLED, EXPIRED)") @RequestParam(required = false) ReservationStatus status,
            Pageable pageable) {
        return ResponseEntity.ok(
            PagedResponse.from(reservationService.findAll(bookId, userName, status, pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get reservation by ID")
    @ApiResponse(responseCode = "200", description = "Reservation found")
    @ApiResponse(responseCode = "404", description = "Reservation not found")
    public ResponseEntity<ReservationResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(reservationService.findById(id));
    }
}
