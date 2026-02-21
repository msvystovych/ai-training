package com.library.catalog.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateBookRequest(

    @NotBlank(message = "Title must not be blank")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    String title,

    @NotBlank(message = "ISBN must not be blank")
    @Pattern(regexp = "\\d{13}", message = "ISBN must be exactly 13 digits")
    String isbn,

    @Size(max = 10000, message = "Description must not exceed 10000 characters")
    String description,

    @Min(value = 1000, message = "Published year must be 1000 or later")
    @Max(value = 2100, message = "Published year must be 2100 or earlier")
    Integer publishedYear,

    @NotNull(message = "Author ID list must not be null")
    @NotEmpty(message = "At least one author ID is required")
    List<@NotNull Long> authorIds
) {}
