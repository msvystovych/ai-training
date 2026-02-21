package com.library.catalog.integration;

import com.library.catalog.dto.request.CreateAuthorRequest;
import com.library.catalog.dto.request.UpdateAuthorRequest;
import com.library.catalog.dto.response.AuthorResponse;
import com.library.catalog.dto.response.ErrorResponse;
import com.library.catalog.dto.response.PagedResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE_URL = "/api/v1/authors";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void fullCrudLifecycle() {
        // CREATE
        var createRequest = new CreateAuthorRequest("Joshua", "Bloch", "Java expert");
        ResponseEntity<AuthorResponse> createResponse =
            restTemplate.postForEntity(BASE_URL, createRequest, AuthorResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AuthorResponse created = createResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.firstName()).isEqualTo("Joshua");
        assertThat(created.lastName()).isEqualTo("Bloch");
        assertThat(created.bio()).isEqualTo("Java expert");
        assertThat(created.books()).isEmpty();
        assertThat(created.createdAt()).isNotNull();

        Long authorId = created.id();

        // GET by ID
        ResponseEntity<AuthorResponse> getResponse =
            restTemplate.getForEntity(BASE_URL + "/" + authorId, AuthorResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().firstName()).isEqualTo("Joshua");

        // UPDATE
        var updateRequest = new UpdateAuthorRequest("Josh", null, "Updated bio");
        ResponseEntity<AuthorResponse> updateResponse = restTemplate.exchange(
            BASE_URL + "/" + authorId, HttpMethod.PUT,
            new HttpEntity<>(updateRequest), AuthorResponse.class);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody()).isNotNull();
        assertThat(updateResponse.getBody().firstName()).isEqualTo("Josh");
        assertThat(updateResponse.getBody().lastName()).isEqualTo("Bloch");
        assertThat(updateResponse.getBody().bio()).isEqualTo("Updated bio");

        // GET after UPDATE - verify changes persisted
        ResponseEntity<AuthorResponse> getAfterUpdate =
            restTemplate.getForEntity(BASE_URL + "/" + authorId, AuthorResponse.class);

        assertThat(getAfterUpdate.getBody()).isNotNull();
        assertThat(getAfterUpdate.getBody().firstName()).isEqualTo("Josh");
        assertThat(getAfterUpdate.getBody().bio()).isEqualTo("Updated bio");

        // DELETE
        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
            BASE_URL + "/" + authorId, HttpMethod.DELETE, null, Void.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // GET after DELETE -> 404
        ResponseEntity<ErrorResponse> getAfterDelete =
            restTemplate.getForEntity(BASE_URL + "/" + authorId, ErrorResponse.class);

        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createAuthor_withInvalidData_returns400() {
        var invalidRequest = new CreateAuthorRequest("", "", null);
        ResponseEntity<ErrorResponse> response =
            restTemplate.postForEntity(BASE_URL, invalidRequest, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Validation failed");
        assertThat(response.getBody().fieldErrors()).isNotEmpty();
    }

    @Test
    void deleteAuthor_whenSoleAuthorOfBook_returns409() {
        // Create an author via API
        var authorRequest = new CreateAuthorRequest("Joshua", "Bloch", null);
        ResponseEntity<AuthorResponse> authorResponse =
            restTemplate.postForEntity(BASE_URL, authorRequest, AuthorResponse.class);
        Long authorId = authorResponse.getBody().id();

        // Insert a book and link it to this author directly via SQL
        // (BookController doesn't exist yet in Day 2)
        jdbcTemplate.update(
            "INSERT INTO books (id, title, isbn, published_year, version, created_at, updated_at) " +
            "VALUES (1000, 'Effective Java', '9780134685991', 2018, 0, now(), now())");
        jdbcTemplate.update(
            "INSERT INTO book_authors (book_id, author_id) VALUES (1000, ?)", authorId);

        // DELETE should fail with 409 - sole author
        ResponseEntity<ErrorResponse> deleteResponse = restTemplate.exchange(
            BASE_URL + "/" + authorId, HttpMethod.DELETE, null, ErrorResponse.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(deleteResponse.getBody()).isNotNull();
        assertThat(deleteResponse.getBody().message()).contains("sole author");
    }

    @Test
    void findAll_returnsPaginatedResults() {
        // Create two authors
        restTemplate.postForEntity(BASE_URL,
            new CreateAuthorRequest("Joshua", "Bloch", null), AuthorResponse.class);
        restTemplate.postForEntity(BASE_URL,
            new CreateAuthorRequest("Brian", "Goetz", null), AuthorResponse.class);

        ResponseEntity<PagedResponse> response = restTemplate.getForEntity(
            BASE_URL + "?page=0&size=10", PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().totalElements()).isEqualTo(2);
        assertThat(response.getBody().content()).hasSize(2);
    }

    @Test
    void getAuthor_nonExistent_returns404() {
        ResponseEntity<ErrorResponse> response =
            restTemplate.getForEntity(BASE_URL + "/99999", ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
    }
}
