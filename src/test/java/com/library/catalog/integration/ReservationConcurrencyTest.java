package com.library.catalog.integration;

import com.library.catalog.dto.request.CreateAuthorRequest;
import com.library.catalog.dto.request.CreateBookRequest;
import com.library.catalog.dto.request.CreateReservationRequest;
import com.library.catalog.dto.response.AuthorResponse;
import com.library.catalog.dto.response.BookResponse;
import com.library.catalog.dto.response.ReservationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationConcurrencyTest extends AbstractIntegrationTest {

    private static final String RESERVATIONS_URL = "/api/v1/reservations";
    private static final String BOOKS_URL = "/api/v1/books";
    private static final String AUTHORS_URL = "/api/v1/authors";

    @Test
    void concurrentReservations_onlyOneSucceeds() throws Exception {
        Long bookId = createBookWithAuthor("Concurrent Test Book", "9780134685999");

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String userName = "user" + i;
            futures.add(executor.submit(() -> {
                startLatch.await();
                var request = new CreateReservationRequest(bookId, userName);
                return restTemplate.postForEntity(RESERVATIONS_URL, request, String.class);
            }));
        }

        // Release all threads simultaneously
        startLatch.countDown();

        // Collect results
        List<HttpStatus> statuses = new ArrayList<>();
        for (Future<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> response = future.get();
            statuses.add((HttpStatus) response.getStatusCode());
        }

        executor.shutdown();

        long successCount = statuses.stream()
            .filter(s -> s == HttpStatus.CREATED)
            .count();
        long conflictCount = statuses.stream()
            .filter(s -> s == HttpStatus.CONFLICT)
            .count();

        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(threadCount - 1);
    }

    private Long createBookWithAuthor(String title, String isbn) {
        var authorRequest = new CreateAuthorRequest("Concurrency", "TestAuthor", null);
        ResponseEntity<AuthorResponse> authorResponse =
            restTemplate.postForEntity(AUTHORS_URL, authorRequest, AuthorResponse.class);
        Long authorId = authorResponse.getBody().id();

        var bookRequest = new CreateBookRequest(title, isbn, "Test description", 2020, List.of(authorId));
        ResponseEntity<BookResponse> bookResponse =
            restTemplate.postForEntity(BOOKS_URL, bookRequest, BookResponse.class);
        return bookResponse.getBody().id();
    }
}
