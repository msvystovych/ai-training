package com.library.catalog.mapper;

import com.library.catalog.dto.request.CreateBookRequest;
import com.library.catalog.dto.request.UpdateBookRequest;
import com.library.catalog.dto.response.BookResponse;
import com.library.catalog.entity.Book;

import java.util.Collections;
import java.util.List;

public final class BookMapper {

    private BookMapper() {}

    public static Book toEntity(CreateBookRequest request) {
        Book book = new Book();
        book.setTitle(request.title());
        book.setIsbn(request.isbn());
        book.setDescription(request.description());
        book.setPublishedYear(request.publishedYear());
        return book;
    }

    public static BookResponse toResponse(Book book) {
        List<BookResponse.AuthorSummary> authors = book.getAuthors() != null
            ? book.getAuthors().stream()
                .map(a -> new BookResponse.AuthorSummary(a.getId(), a.getFirstName(), a.getLastName()))
                .toList()
            : Collections.emptyList();

        return new BookResponse(
            book.getId(),
            book.getTitle(),
            book.getIsbn(),
            book.getDescription(),
            book.getPublishedYear(),
            authors,
            book.getCreatedAt(),
            book.getUpdatedAt()
        );
    }

    public static void updateEntity(Book book, UpdateBookRequest request) {
        if (request.title() != null) {
            book.setTitle(request.title());
        }
        if (request.isbn() != null) {
            book.setIsbn(request.isbn());
        }
        if (request.description() != null) {
            book.setDescription(request.description());
        }
        if (request.publishedYear() != null) {
            book.setPublishedYear(request.publishedYear());
        }
    }
}
