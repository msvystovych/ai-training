package com.library.catalog.unit.service;

import com.library.catalog.dto.request.CreateBookRequest;
import com.library.catalog.dto.request.UpdateBookRequest;
import com.library.catalog.dto.response.BookResponse;
import com.library.catalog.entity.Author;
import com.library.catalog.entity.Book;
import com.library.catalog.exception.ActiveReservationExistsException;
import com.library.catalog.exception.DuplicateIsbnException;
import com.library.catalog.exception.ResourceNotFoundException;
import com.library.catalog.repository.AuthorRepository;
import com.library.catalog.repository.BookRepository;
import com.library.catalog.repository.ReservationRepository;
import com.library.catalog.service.BookService;
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

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookServiceTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private AuthorRepository authorRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @InjectMocks
    private BookService bookService;

    @Test
    void createBook_withValidAuthors_returnsBookResponse() {
        Author author = createTestAuthor(1L, "Joshua", "Bloch");
        when(authorRepository.findAllById(List.of(1L))).thenReturn(List.of(author));
        when(bookRepository.existsByIsbn("9780134685991")).thenReturn(false);
        when(bookRepository.save(any(Book.class))).thenAnswer(invocation -> {
            Book saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 1L);
            return saved;
        });

        var request = new CreateBookRequest("Effective Java", "9780134685991",
            "A guide to best practices", 2018, List.of(1L));
        BookResponse response = bookService.create(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.title()).isEqualTo("Effective Java");
        assertThat(response.isbn()).isEqualTo("9780134685991");
        assertThat(response.authors()).hasSize(1);
        assertThat(response.authors().get(0).firstName()).isEqualTo("Joshua");
    }

    @Test
    void createBook_withDuplicateIsbn_throwsDuplicateIsbnException() {
        when(bookRepository.existsByIsbn("9780134685991")).thenReturn(true);

        var request = new CreateBookRequest("Effective Java", "9780134685991",
            null, null, List.of(1L));

        assertThatThrownBy(() -> bookService.create(request))
            .isInstanceOf(DuplicateIsbnException.class)
            .hasMessageContaining("9780134685991");

        verify(bookRepository, never()).save(any());
    }

    @Test
    void createBook_withNonExistentAuthor_throwsResourceNotFoundException() {
        when(bookRepository.existsByIsbn("9780134685991")).thenReturn(false);
        when(authorRepository.findAllById(List.of(1L, 99L))).thenReturn(
            List.of(createTestAuthor(1L, "Joshua", "Bloch")));

        var request = new CreateBookRequest("Effective Java", "9780134685991",
            null, null, List.of(1L, 99L));

        assertThatThrownBy(() -> bookService.create(request))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Author");

        verify(bookRepository, never()).save(any());
    }

    @Test
    void findById_whenFound_returnsBookResponseWithAuthors() {
        Book book = createTestBook(1L, "Effective Java", "9780134685991");
        Author author = createTestAuthor(1L, "Joshua", "Bloch");
        book.getAuthors().add(author);
        when(bookRepository.findByIdWithAuthors(1L)).thenReturn(Optional.of(book));

        BookResponse response = bookService.findById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.title()).isEqualTo("Effective Java");
        assertThat(response.authors()).hasSize(1);
    }

    @Test
    void findById_whenNotFound_throwsResourceNotFoundException() {
        when(bookRepository.findByIdWithAuthors(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.findById(99L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Book")
            .hasMessageContaining("99");
    }

    @Test
    void findAll_returnsPaginatedResults() {
        Book book = createTestBook(1L, "Effective Java", "9780134685991");
        Pageable pageable = PageRequest.of(0, 20);
        Page<Book> page = new PageImpl<>(List.of(book), pageable, 1);
        when(bookRepository.findAll(pageable)).thenReturn(page);

        Page<BookResponse> result = bookService.findAll(pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).title()).isEqualTo("Effective Java");
    }

    @Test
    void updateBook_withValidRequest_updatesFieldsAndAuthors() {
        Book book = createTestBook(1L, "Effective Java", "9780134685991");
        Author oldAuthor = createTestAuthor(1L, "Joshua", "Bloch");
        book.getAuthors().add(oldAuthor);

        Author newAuthor = createTestAuthor(2L, "Brian", "Goetz");

        when(bookRepository.findByIdWithAuthors(1L)).thenReturn(Optional.of(book));
        when(authorRepository.findAllById(List.of(2L))).thenReturn(List.of(newAuthor));
        when(bookRepository.save(any(Book.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new UpdateBookRequest("Effective Java 3rd Ed", null, null, null, List.of(2L));
        BookResponse response = bookService.update(1L, request);

        assertThat(response.title()).isEqualTo("Effective Java 3rd Ed");
        assertThat(response.authors()).hasSize(1);
        assertThat(response.authors().get(0).firstName()).isEqualTo("Brian");
    }

    @Test
    void updateBook_whenNotFound_throwsResourceNotFoundException() {
        when(bookRepository.findByIdWithAuthors(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.update(99L, new UpdateBookRequest(null, null, null, null, null)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteBook_withNoReservations_deletesSuccessfully() {
        Book book = createTestBook(1L, "Effective Java", "9780134685991");
        when(bookRepository.findByIdWithAuthors(1L)).thenReturn(Optional.of(book));
        when(reservationRepository.existsByBookId(1L)).thenReturn(false);

        bookService.delete(1L);

        verify(bookRepository).delete(book);
    }

    @Test
    void deleteBook_whenNotFound_throwsResourceNotFoundException() {
        when(bookRepository.findByIdWithAuthors(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.delete(99L))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(bookRepository, never()).delete(any());
    }

    @Test
    void deleteBook_withReservationHistory_throwsActiveReservationExistsException() {
        Book book = createTestBook(1L, "Effective Java", "9780134685991");
        when(bookRepository.findByIdWithAuthors(1L)).thenReturn(Optional.of(book));
        when(reservationRepository.existsByBookId(1L)).thenReturn(true);

        assertThatThrownBy(() -> bookService.delete(1L))
            .isInstanceOf(ActiveReservationExistsException.class)
            .hasMessageContaining("reservation history");

        verify(bookRepository, never()).delete(any());
    }

    private Author createTestAuthor(Long id, String firstName, String lastName) {
        Author author = new Author();
        ReflectionTestUtils.setField(author, "id", id);
        author.setFirstName(firstName);
        author.setLastName(lastName);
        return author;
    }

    private Book createTestBook(Long id, String title, String isbn) {
        Book book = new Book();
        ReflectionTestUtils.setField(book, "id", id);
        book.setTitle(title);
        book.setIsbn(isbn);
        book.setAuthors(new HashSet<>());
        return book;
    }
}
