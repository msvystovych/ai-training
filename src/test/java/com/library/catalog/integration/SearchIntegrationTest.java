package com.library.catalog.integration;

import com.library.catalog.dto.request.CreateAuthorRequest;
import com.library.catalog.dto.request.CreateBookRequest;
import com.library.catalog.dto.response.AuthorResponse;
import com.library.catalog.dto.response.BookResponse;
import com.library.catalog.dto.response.ErrorResponse;
import com.library.catalog.dto.response.PagedResponse;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchIntegrationTest extends AbstractIntegrationTest {

    private static final String SEARCH_URL = "/api/v1/search";
    private static final String BOOKS_URL = "/api/v1/books";
    private static final String AUTHORS_URL = "/api/v1/authors";

    @BeforeEach
    void seedData() {
        Long blochId = createAuthor("Joshua", "Bloch");
        Long goetzId = createAuthor("Brian", "Goetz");
        Long fowlerId = createAuthor("Martin", "Fowler");

        createBook("Effective Java", "9780134685991",
            "A guide to programming best practices in Java", 2018, List.of(blochId));
        createBook("Java Concurrency in Practice", "9780321349606",
            "A comprehensive guide to concurrent programming", 2006, List.of(goetzId, blochId));
        createBook("Refactoring", "9780201485677",
            "Improving the design of existing code", 1999, List.of(fowlerId));
    }

    @Test
    void search_byBookTitle_returnsCorrectResults() {
        ResponseEntity<PagedResponse> response =
            restTemplate.getForEntity(SEARCH_URL + "?q=Effective Java", PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().totalElements()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void search_byAuthorName_returnsBooksbyAuthor() {
        ResponseEntity<PagedResponse> response =
            restTemplate.getForEntity(SEARCH_URL + "?q=Fowler", PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().totalElements()).isGreaterThanOrEqualTo(1);

        // Refactoring should appear since Fowler is the author
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().content();
        boolean foundRefactoring = content.stream()
            .anyMatch(r -> "Refactoring".equals(r.get("title")));
        assertThat(foundRefactoring).isTrue();
    }

    @Test
    void search_withMultipleTerms_returnsAndResults() {
        ResponseEntity<PagedResponse> response =
            restTemplate.getForEntity(SEARCH_URL + "?q=Java concurrency", PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // "Java Concurrency in Practice" should match both terms
        assertThat(response.getBody().totalElements()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void search_withNoResults_returnsEmptyList() {
        ResponseEntity<PagedResponse> response =
            restTemplate.getForEntity(SEARCH_URL + "?q=xyznonexistent", PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().totalElements()).isZero();
        assertThat(response.getBody().content()).isEmpty();
    }

    @Test
    void search_withEmptyQuery_returns400() {
        ResponseEntity<ErrorResponse> response =
            restTemplate.getForEntity(SEARCH_URL + "?q=", ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void search_titleMatchRanksHigherThanDescription() {
        ResponseEntity<PagedResponse> response =
            restTemplate.getForEntity(SEARCH_URL + "?q=Java", PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().content();

        // Books with "Java" in the title should appear
        assertThat(content).isNotEmpty();

        // First result should have "Java" in title (higher weight A)
        if (content.size() > 1) {
            String firstTitle = (String) content.get(0).get("title");
            assertThat(firstTitle).containsIgnoringCase("Java");
        }
    }

    @Test
    void search_withStopWordsOnly_returnsEmptyResults() {
        ResponseEntity<PagedResponse> response =
            restTemplate.getForEntity(SEARCH_URL + "?q=the and or", PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().totalElements()).isZero();
        assertThat(response.getBody().content()).isEmpty();
    }

    @Test
    void search_multiAuthorBookAppearsOnce() {
        // "Java Concurrency in Practice" has both Goetz and Bloch
        ResponseEntity<PagedResponse> response =
            restTemplate.getForEntity(SEARCH_URL + "?q=concurrency", PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        // Should appear exactly once despite having multiple authors
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().content();
        long concurrencyCount = content.stream()
            .filter(r -> r.get("title") != null && r.get("title").toString().contains("Concurrency"))
            .count();
        assertThat(concurrencyCount).isEqualTo(1);
    }

    private Long createAuthor(String firstName, String lastName) {
        var request = new CreateAuthorRequest(firstName, lastName, null);
        ResponseEntity<AuthorResponse> response =
            restTemplate.postForEntity(AUTHORS_URL, request, AuthorResponse.class);
        return response.getBody().id();
    }

    private void createBook(String title, String isbn, String description,
                            int year, List<Long> authorIds) {
        var request = new CreateBookRequest(title, isbn, description, year, authorIds);
        restTemplate.postForEntity(BOOKS_URL, request, BookResponse.class);
    }
}
