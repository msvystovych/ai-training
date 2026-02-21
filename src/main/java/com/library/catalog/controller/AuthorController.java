package com.library.catalog.controller;

import com.library.catalog.dto.request.CreateAuthorRequest;
import com.library.catalog.dto.request.UpdateAuthorRequest;
import com.library.catalog.dto.response.AuthorResponse;
import com.library.catalog.dto.response.PagedResponse;
import com.library.catalog.service.AuthorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/authors")
@RequiredArgsConstructor
@Tag(name = "Authors", description = "Author management operations")
public class AuthorController {

    private final AuthorService authorService;

    @GetMapping
    @Operation(summary = "List all authors", description = "Returns a paginated list of authors with their book summaries.")
    public ResponseEntity<PagedResponse<AuthorResponse>> findAll(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.from(authorService.findAll(pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get author by ID")
    @ApiResponse(responseCode = "200", description = "Author found")
    @ApiResponse(responseCode = "404", description = "Author not found")
    public ResponseEntity<AuthorResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(authorService.findById(id));
    }

    @PostMapping
    @Operation(summary = "Create a new author")
    @ApiResponse(responseCode = "201", description = "Author created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    public ResponseEntity<AuthorResponse> create(@Valid @RequestBody CreateAuthorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authorService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an author", description = "Partial update — null fields are ignored.")
    @ApiResponse(responseCode = "200", description = "Author updated")
    @ApiResponse(responseCode = "404", description = "Author not found")
    public ResponseEntity<AuthorResponse> update(@PathVariable Long id,
                                                  @Valid @RequestBody UpdateAuthorRequest request) {
        return ResponseEntity.ok(authorService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an author", description = "Returns 409 if author is the sole author of any book.")
    @ApiResponse(responseCode = "204", description = "Author deleted")
    @ApiResponse(responseCode = "404", description = "Author not found")
    @ApiResponse(responseCode = "409", description = "Author is the sole author of a book")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        authorService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
