package com.library.catalog.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateReservationRequest(

    @NotNull(message = "Book ID is required")
    Long bookId,

    @NotBlank(message = "User name must not be blank")
    @Size(max = 100, message = "User name must not exceed 100 characters")
    String userName
) {}
