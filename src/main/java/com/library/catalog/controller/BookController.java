package com.library.catalog.controller;

import com.library.catalog.dto.request.CreateBookRequest;
import com.library.catalog.dto.request.UpdateBookRequest;
import com.library.catalog.dto.response.BookResponse;
import com.library.catalog.dto.response.PagedResponse;
import com.library.catalog.service.BookService;
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
@RequestMapping("/api/v1/books")
@RequiredArgsConstructor
@Tag(name = "Books", description = "Book management operations")
public class BookController {

    private final BookService bookService;

    @GetMapping
    @Operation(summary = "List all books", description = "Returns a paginated list of books with their author summaries.")
    public ResponseEntity<PagedResponse<BookResponse>> findAll(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.from(bookService.findAll(pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get book by ID")
    @ApiResponse(responseCode = "200", description = "Book found")
    @ApiResponse(responseCode = "404", description = "Book not found")
    public ResponseEntity<BookResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(bookService.findById(id));
    }

    @PostMapping
    @Operation(summary = "Create a new book", description = "ISBN must be exactly 13 digits and unique. At least one author ID required.")
    @ApiResponse(responseCode = "201", description = "Book created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "ISBN already exists")
    public ResponseEntity<BookResponse> create(@Valid @RequestBody CreateBookRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bookService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a book", description = "Partial update — null fields are ignored.")
    @ApiResponse(responseCode = "200", description = "Book updated")
    @ApiResponse(responseCode = "404", description = "Book not found")
    @ApiResponse(responseCode = "409", description = "ISBN already exists")
    public ResponseEntity<BookResponse> update(@PathVariable Long id,
                                                @Valid @RequestBody UpdateBookRequest request) {
        return ResponseEntity.ok(bookService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a book", description = "Returns 409 if the book has any reservation history.")
    @ApiResponse(responseCode = "204", description = "Book deleted")
    @ApiResponse(responseCode = "404", description = "Book not found")
    @ApiResponse(responseCode = "409", description = "Book has reservation history")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        bookService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
