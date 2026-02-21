package com.library.catalog.integration;

import com.library.catalog.dto.request.CreateAuthorRequest;
import com.library.catalog.dto.request.CreateBookRequest;
import com.library.catalog.dto.request.CreateReservationRequest;
import com.library.catalog.dto.response.AuthorResponse;
import com.library.catalog.dto.response.BookResponse;
import com.library.catalog.dto.response.ErrorResponse;
import com.library.catalog.dto.response.PagedResponse;
import com.library.catalog.dto.response.ReservationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationIntegrationTest extends AbstractIntegrationTest {

    private static final String RESERVATIONS_URL = "/api/v1/reservations";
    private static final String BOOKS_URL = "/api/v1/books";
    private static final String AUTHORS_URL = "/api/v1/authors";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void fullLifecycle_reserveAndCancel() {
        Long bookId = createBookWithAuthor("Effective Java", "9780134685991");

        // CREATE reservation
        var createRequest = new CreateReservationRequest(bookId, "alice");
        ResponseEntity<ReservationResponse> createResponse =
            restTemplate.postForEntity(RESERVATIONS_URL, createRequest, ReservationResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ReservationResponse created = createResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.bookId()).isEqualTo(bookId);
        assertThat(created.bookTitle()).isEqualTo("Effective Java");
        assertThat(created.userName()).isEqualTo("alice");
        assertThat(created.status().name()).isEqualTo("ACTIVE");
        assertThat(created.reservedAt()).isNotNull();
        assertThat(created.expiresAt()).isNotNull();
        assertThat(created.cancelledAt()).isNull();

        Long reservationId = created.id();

        // GET by ID
        ResponseEntity<ReservationResponse> getResponse =
            restTemplate.getForEntity(RESERVATIONS_URL + "/" + reservationId, ReservationResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().status().name()).isEqualTo("ACTIVE");

        // CANCEL
        ResponseEntity<ReservationResponse> cancelResponse = restTemplate.exchange(
            RESERVATIONS_URL + "/" + reservationId + "/cancel",
            HttpMethod.PATCH, null, ReservationResponse.class);

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelResponse.getBody().status().name()).isEqualTo("CANCELLED");
        assertThat(cancelResponse.getBody().cancelledAt()).isNotNull();

        // Verify after cancel
        ResponseEntity<ReservationResponse> afterCancel =
            restTemplate.getForEntity(RESERVATIONS_URL + "/" + reservationId, ReservationResponse.class);
        assertThat(afterCancel.getBody().status().name()).isEqualTo("CANCELLED");
    }

    @Test
    void reserve_nonExistentBook_returns404() {
        var request = new CreateReservationRequest(99999L, "alice");
        ResponseEntity<ErrorResponse> response =
            restTemplate.postForEntity(RESERVATIONS_URL, request, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reserve_alreadyReservedBook_returns409() {
        Long bookId = createBookWithAuthor("Effective Java", "9780134685991");

        // First reservation
        restTemplate.postForEntity(RESERVATIONS_URL,
            new CreateReservationRequest(bookId, "alice"), ReservationResponse.class);

        // Second reservation for same book
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(RESERVATIONS_URL,
            new CreateReservationRequest(bookId, "bob"), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).contains("active reservation");
    }

    @Test
    void reserve_withExpiredActiveReservation_succeeds() {
        Long bookId = createBookWithAuthor("Expired Test Book", "9780134685992");
        assertThat(bookId).isNotNull();

        // Insert an expired reservation directly via SQL
        jdbcTemplate.update(
            "INSERT INTO reservations (book_id, user_name, status, reserved_at, expires_at, version, created_at, updated_at) " +
            "VALUES (?, 'old_user', 'ACTIVE', now() - interval '30 days', now() - interval '1 day', 0, now(), now())",
            new Object[]{bookId});

        // New reservation should succeed (expire-then-create)
        var request = new CreateReservationRequest(bookId, "alice");
        ResponseEntity<ReservationResponse> response =
            restTemplate.postForEntity(RESERVATIONS_URL, request, ReservationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().userName()).isEqualTo("alice");
        assertThat(response.getBody().status().name()).isEqualTo("ACTIVE");
    }

    @Test
    void findAll_withFilters_returnsFilteredResults() {
        Long bookId1 = createBookWithAuthor("Book One", "1234567890123");
        Long bookId2 = createBookWithAuthor("Book Two", "1234567890124");

        restTemplate.postForEntity(RESERVATIONS_URL,
            new CreateReservationRequest(bookId1, "alice"), ReservationResponse.class);
        restTemplate.postForEntity(RESERVATIONS_URL,
            new CreateReservationRequest(bookId2, "bob"), ReservationResponse.class);

        // Filter by userName
        ResponseEntity<PagedResponse> aliceReservations = restTemplate.getForEntity(
            RESERVATIONS_URL + "?userName=alice", PagedResponse.class);
        assertThat(aliceReservations.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(aliceReservations.getBody().totalElements()).isEqualTo(1);

        // Filter by bookId
        ResponseEntity<PagedResponse> book1Reservations = restTemplate.getForEntity(
            RESERVATIONS_URL + "?bookId=" + bookId1, PagedResponse.class);
        assertThat(book1Reservations.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(book1Reservations.getBody().totalElements()).isEqualTo(1);

        // Filter by status
        ResponseEntity<PagedResponse> activeReservations = restTemplate.getForEntity(
            RESERVATIONS_URL + "?status=ACTIVE", PagedResponse.class);
        assertThat(activeReservations.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(activeReservations.getBody().totalElements()).isEqualTo(2);
    }

    @Test
    void cancel_alreadyCancelledReservation_returns409() {
        Long bookId = createBookWithAuthor("Cancel Test", "9780134685993");

        // Create and cancel a reservation
        ResponseEntity<ReservationResponse> createResponse = restTemplate.postForEntity(
            RESERVATIONS_URL, new CreateReservationRequest(bookId, "alice"), ReservationResponse.class);
        Long reservationId = createResponse.getBody().id();

        restTemplate.exchange(RESERVATIONS_URL + "/" + reservationId + "/cancel",
            HttpMethod.PATCH, null, ReservationResponse.class);

        // Try to cancel again
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
            RESERVATIONS_URL + "/" + reservationId + "/cancel",
            HttpMethod.PATCH, null, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).contains("CANCELLED");
    }

    @Test
    void reserve_withInvalidData_returns400() {
        var request = new CreateReservationRequest(null, "");
        ResponseEntity<ErrorResponse> response =
            restTemplate.postForEntity(RESERVATIONS_URL, request, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().fieldErrors()).isNotEmpty();
    }

    @Test
    void getReservation_nonExistent_returns404() {
        ResponseEntity<ErrorResponse> response =
            restTemplate.getForEntity(RESERVATIONS_URL + "/99999", ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Long createBookWithAuthor(String title, String isbn) {
        Long authorId = createAuthor("Test", "Author" + isbn.substring(isbn.length() - 4));
        var bookRequest = new CreateBookRequest(title, isbn, "Description", 2020, List.of(authorId));
        ResponseEntity<BookResponse> bookResponse =
            restTemplate.postForEntity(BOOKS_URL, bookRequest, BookResponse.class);
        return bookResponse.getBody().id();
    }

    private Long createAuthor(String firstName, String lastName) {
        var request = new CreateAuthorRequest(firstName, lastName, null);
        ResponseEntity<AuthorResponse> response =
            restTemplate.postForEntity(AUTHORS_URL, request, AuthorResponse.class);
        return response.getBody().id();
    }
}
