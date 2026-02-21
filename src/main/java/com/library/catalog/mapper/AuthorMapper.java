package com.library.catalog.mapper;

import com.library.catalog.dto.request.CreateAuthorRequest;
import com.library.catalog.dto.request.UpdateAuthorRequest;
import com.library.catalog.dto.response.AuthorResponse;
import com.library.catalog.entity.Author;

import java.util.Collections;
import java.util.List;

public final class AuthorMapper {

    private AuthorMapper() {}

    public static Author toEntity(CreateAuthorRequest request) {
        Author author = new Author();
        author.setFirstName(request.firstName());
        author.setLastName(request.lastName());
        author.setBio(request.bio());
        return author;
    }

    public static AuthorResponse toResponse(Author author) {
        List<AuthorResponse.BookSummary> books = author.getBooks() != null
            ? author.getBooks().stream()
                .map(book -> new AuthorResponse.BookSummary(book.getId(), book.getTitle()))
                .toList()
            : Collections.emptyList();

        return new AuthorResponse(
            author.getId(),
            author.getFirstName(),
            author.getLastName(),
            author.getBio(),
            books,
            author.getCreatedAt(),
            author.getUpdatedAt()
        );
    }

    public static void updateEntity(Author author, UpdateAuthorRequest request) {
        if (request.firstName() != null) {
            author.setFirstName(request.firstName());
        }
        if (request.lastName() != null) {
            author.setLastName(request.lastName());
        }
        if (request.bio() != null) {
            author.setBio(request.bio());
        }
    }
}
