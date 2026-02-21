package com.library.catalog.dto.request;

import jakarta.validation.constraints.Size;

public record UpdateAuthorRequest(

    @Size(max = 100, message = "First name must not exceed 100 characters")
    String firstName,

    @Size(max = 100, message = "Last name must not exceed 100 characters")
    String lastName,

    @Size(max = 5000, message = "Bio must not exceed 5000 characters")
    String bio
) {}
