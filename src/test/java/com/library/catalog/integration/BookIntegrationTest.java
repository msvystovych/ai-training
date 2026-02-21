package com.library.catalog.integration;

import com.library.catalog.dto.request.CreateAuthorRequest;
import com.library.catalog.dto.request.CreateBookRequest;
import com.library.catalog.dto.request.UpdateBookRequest;
import com.library.catalog.dto.response.AuthorResponse;
import com.library.catalog.dto.response.BookResponse;
import com.library.catalog.dto.response.ErrorResponse;
import com.library.catalog.dto.response.PagedResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookIntegrationTest extends AbstractIntegrationTest {

    private static final String BOOKS_URL = "/api/v1/books";
    private static final String AUTHORS_URL = "/api/v1/authors";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void fullCrudLifecycle() {
        // Create an author first
        Long authorId = createAuthor("Joshua", "Bloch");

        // CREATE book
        var createRequest = new CreateBookRequest("Effective Java", "9780134685991",
            "Best practices", 2018, List.of(authorId));
        ResponseEntity<BookResponse> createResponse =
            restTemplate.postForEntity(BOOKS_URL, createRequest, BookResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        BookResponse created = createResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.title()).isEqualTo("Effective Java");
        assertThat(created.isbn()).isEqualTo("9780134685991");
        assertThat(created.authors()).hasSize(1);
        assertThat(created.authors().get(0).firstName()).isEqualTo("Joshua");

        Long bookId = created.id();

        // GET by ID
        ResponseEntity<BookResponse> getResponse =
            restTemplate.getForEntity(BOOKS_URL + "/" + bookId, BookResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().title()).isEqualTo("Effective Java");

        // UPDATE
        Long newAuthorId = createAuthor("Brian", "Goetz");
        var updateRequest = new UpdateBookRequest("Effective Java 3rd Ed", null,
            "Updated description", null, List.of(authorId, newAuthorId));
        ResponseEntity<BookResponse> updateResponse = restTemplate.exchange(
            BOOKS_URL + "/" + bookId, HttpMethod.PUT,
            new HttpEntity<>(updateRequest), BookResponse.class);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody().title()).isEqualTo("Effective Java 3rd Ed");
        assertThat(updateResponse.getBody().authors()).hasSize(2);

        // DELETE
        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
            BOOKS_URL + "/" + bookId, HttpMethod.DELETE, null, Void.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // GET after DELETE -> 404
        ResponseEntity<ErrorResponse> getAfterDelete =
            restTemplate.getForEntity(BOOKS_URL + "/" + bookId, ErrorResponse.class);

        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createBook_withDuplicateIsbn_returns409() {
        Long authorId = createAuthor("Joshua", "Bloch");

        var request1 = new CreateBookRequest("Effective Java", "9780134685991",
            null, 2018, List.of(authorId));
        restTemplate.postForEntity(BOOKS_URL, request1, BookResponse.class);

        var request2 = new CreateBookRequest("Another Book", "9780134685991",
            null, 2020, List.of(authorId));
        ResponseEntity<ErrorResponse> response =
            restTemplate.postForEntity(BOOKS_URL, request2, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).containsIgnoringCase("ISBN");
    }

    @Test
    void createBook_withNonExistentAuthor_returns404() {
        var request = new CreateBookRequest("Effective Java", "9780134685991",
            null, 2018, List.of(99999L));
        ResponseEntity<ErrorResponse> response =
            restTemplate.postForEntity(BOOKS_URL, request, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createBook_withInvalidData_returns400() {
        var request = new CreateBookRequest("", "invalid", null, null, List.of());
        ResponseEntity<ErrorResponse> response =
            restTemplate.postForEntity(BOOKS_URL, request, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().fieldErrors()).isNotEmpty();
    }

    @Test
    void deleteBook_withReservationHistory_returns409() {
        Long authorId = createAuthor("Joshua", "Bloch");
        var createRequest = new CreateBookRequest("Effective Java", "9780134685991",
            null, 2018, List.of(authorId));
        ResponseEntity<BookResponse> bookResponse =
            restTemplate.postForEntity(BOOKS_URL, createRequest, BookResponse.class);
        Long bookId = bookResponse.getBody().id();

        // Insert a reservation directly via SQL
        jdbcTemplate.update(
            "INSERT INTO reservations (book_id, user_name, status, reserved_at, expires_at, version, created_at, updated_at) " +
            "VALUES (?, 'alice', 'ACTIVE', now(), now() + interval '14 days', 0, now(), now())", bookId);

        ResponseEntity<ErrorResponse> deleteResponse = restTemplate.exchange(
            BOOKS_URL + "/" + bookId, HttpMethod.DELETE, null, ErrorResponse.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(deleteResponse.getBody().message()).contains("reservation history");
    }

    @Test
    void findAll_returnsPaginatedBooks() {
        Long authorId = createAuthor("Joshua", "Bloch");
        restTemplate.postForEntity(BOOKS_URL,
            new CreateBookRequest("Book 1", "1234567890123", null, null, List.of(authorId)),
            BookResponse.class);
        restTemplate.postForEntity(BOOKS_URL,
            new CreateBookRequest("Book 2", "1234567890124", null, null, List.of(authorId)),
            BookResponse.class);

        ResponseEntity<PagedResponse> response = restTemplate.getForEntity(
            BOOKS_URL + "?page=0&size=10", PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().totalElements()).isEqualTo(2);
    }

    @Test
    void getBook_nonExistent_returns404() {
        ResponseEntity<ErrorResponse> response =
            restTemplate.getForEntity(BOOKS_URL + "/99999", ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Long createAuthor(String firstName, String lastName) {
        var request = new CreateAuthorRequest(firstName, lastName, null);
        ResponseEntity<AuthorResponse> response =
            restTemplate.postForEntity(AUTHORS_URL, request, AuthorResponse.class);
        return response.getBody().id();
    }
}
