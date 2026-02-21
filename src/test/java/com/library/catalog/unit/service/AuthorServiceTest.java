package com.library.catalog.unit.service;

import com.library.catalog.dto.request.CreateAuthorRequest;
import com.library.catalog.dto.request.UpdateAuthorRequest;
import com.library.catalog.dto.response.AuthorResponse;
import com.library.catalog.entity.Author;
import com.library.catalog.entity.Book;
import com.library.catalog.exception.ActiveReservationExistsException;
import com.library.catalog.exception.ResourceNotFoundException;
import com.library.catalog.repository.AuthorRepository;
import com.library.catalog.repository.BookRepository;
import com.library.catalog.service.AuthorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorServiceTest {

    @Mock
    private AuthorRepository authorRepository;

    @Mock
    private BookRepository bookRepository;

    @InjectMocks
    private AuthorService authorService;

    @Test
    void createAuthor_withValidRequest_returnsAuthorResponse() {
        var request = new CreateAuthorRequest("Joshua", "Bloch", "Java expert");

        when(authorRepository.save(any(Author.class))).thenAnswer(invocation -> {
            Author saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 1L);
            return saved;
        });

        AuthorResponse response = authorService.create(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.firstName()).isEqualTo("Joshua");
        assertThat(response.lastName()).isEqualTo("Bloch");
        assertThat(response.bio()).isEqualTo("Java expert");
        assertThat(response.books()).isEmpty();
        verify(authorRepository).save(any(Author.class));
    }

    @Test
    void findById_whenFound_returnsAuthorResponse() {
        Author author = createTestAuthor(1L, "Joshua", "Bloch", "Java expert");
        when(authorRepository.findByIdWithBooks(1L)).thenReturn(Optional.of(author));

        AuthorResponse response = authorService.findById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.firstName()).isEqualTo("Joshua");
        assertThat(response.lastName()).isEqualTo("Bloch");
    }

    @Test
    void findById_whenNotFound_throwsResourceNotFoundException() {
        when(authorRepository.findByIdWithBooks(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authorService.findById(99L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Author")
            .hasMessageContaining("99");
    }

    @Test
    void findAll_returnsPaginatedResults() {
        Author author = createTestAuthor(1L, "Joshua", "Bloch", null);
        Pageable pageable = PageRequest.of(0, 20);
        Page<Author> page = new PageImpl<>(List.of(author), pageable, 1);
        when(authorRepository.findAll(pageable)).thenReturn(page);

        Page<AuthorResponse> result = authorService.findAll(pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).firstName()).isEqualTo("Joshua");
    }

    @Test
    void updateAuthor_withValidRequest_updatesFields() {
        Author author = createTestAuthor(1L, "Josh", "Bloch", null);
        when(authorRepository.findByIdWithBooks(1L)).thenReturn(Optional.of(author));
        when(authorRepository.save(any(Author.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var request = new UpdateAuthorRequest("Joshua", null, "Updated bio");
        AuthorResponse response = authorService.update(1L, request);

        assertThat(response.firstName()).isEqualTo("Joshua");
        assertThat(response.lastName()).isEqualTo("Bloch");
        assertThat(response.bio()).isEqualTo("Updated bio");
    }

    @Test
    void updateAuthor_whenNotFound_throwsResourceNotFoundException() {
        when(authorRepository.findByIdWithBooks(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authorService.update(99L, new UpdateAuthorRequest("New", null, null)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteAuthor_withNoBooks_deletesSuccessfully() {
        Author author = createTestAuthor(1L, "Joshua", "Bloch", null);
        when(authorRepository.findByIdWithBooks(1L)).thenReturn(Optional.of(author));
        when(bookRepository.findAllByAuthorId(1L)).thenReturn(Collections.emptyList());

        authorService.delete(1L);

        verify(authorRepository).delete(author);
    }

    @Test
    void deleteAuthor_whenNotFound_throwsResourceNotFoundException() {
        when(authorRepository.findByIdWithBooks(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authorService.delete(99L))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(authorRepository, never()).delete(any());
    }

    @Test
    void deleteAuthor_whenSoleAuthorOfBook_throwsException() {
        Author author = createTestAuthor(1L, "Joshua", "Bloch", null);
        Book book = createTestBook(10L, "Effective Java");

        when(authorRepository.findByIdWithBooks(1L)).thenReturn(Optional.of(author));
        when(bookRepository.findAllByAuthorId(1L)).thenReturn(List.of(book));
        when(bookRepository.countAuthorsByBookId(10L)).thenReturn(1L);

        assertThatThrownBy(() -> authorService.delete(1L))
            .isInstanceOf(ActiveReservationExistsException.class)
            .hasMessageContaining("sole author");

        verify(authorRepository, never()).delete(any());
    }

    @Test
    void deleteAuthor_whenCoAuthorOfAllBooks_deletesSuccessfully() {
        Author author = createTestAuthor(1L, "Joshua", "Bloch", null);
        Book book1 = createTestBook(10L, "Effective Java");
        Book book2 = createTestBook(11L, "Java Concurrency");

        when(authorRepository.findByIdWithBooks(1L)).thenReturn(Optional.of(author));
        when(bookRepository.findAllByAuthorId(1L)).thenReturn(List.of(book1, book2));
        when(bookRepository.countAuthorsByBookId(10L)).thenReturn(2L);
        when(bookRepository.countAuthorsByBookId(11L)).thenReturn(3L);

        authorService.delete(1L);

        verify(authorRepository).delete(author);
    }

    private Author createTestAuthor(Long id, String firstName, String lastName, String bio) {
        Author author = new Author();
        ReflectionTestUtils.setField(author, "id", id);
        author.setFirstName(firstName);
        author.setLastName(lastName);
        author.setBio(bio);
        return author;
    }

    private Book createTestBook(Long id, String title) {
        Book book = new Book();
        ReflectionTestUtils.setField(book, "id", id);
        book.setTitle(title);
        return book;
    }
}
