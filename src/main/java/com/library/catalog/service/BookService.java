package com.library.catalog.service;

import com.library.catalog.dto.request.CreateBookRequest;
import com.library.catalog.dto.request.UpdateBookRequest;
import com.library.catalog.dto.response.BookResponse;
import com.library.catalog.entity.Author;
import com.library.catalog.entity.Book;
import com.library.catalog.exception.ActiveReservationExistsException;
import com.library.catalog.exception.DuplicateIsbnException;
import com.library.catalog.exception.ResourceNotFoundException;
import com.library.catalog.mapper.BookMapper;
import com.library.catalog.repository.AuthorRepository;
import com.library.catalog.repository.BookRepository;
import com.library.catalog.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookService {

    private final BookRepository bookRepository;
    private final AuthorRepository authorRepository;
    private final ReservationRepository reservationRepository;

    @Transactional(readOnly = true)
    public Page<BookResponse> findAll(Pageable pageable) {
        return bookRepository.findAll(pageable)
            .map(BookMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public BookResponse findById(Long id) {
        Book book = bookRepository.findByIdWithAuthors(id)
            .orElseThrow(() -> new ResourceNotFoundException("Book", id));
        return BookMapper.toResponse(book);
    }

    @Transactional
    public BookResponse create(CreateBookRequest request) {
        if (bookRepository.existsByIsbn(request.isbn())) {
            throw new DuplicateIsbnException(request.isbn());
        }

        List<Author> authors = resolveAuthors(request.authorIds());

        Book book = BookMapper.toEntity(request);
        book.setAuthors(new HashSet<>(authors));
        Book saved = bookRepository.save(book);
        return BookMapper.toResponse(saved);
    }

    @Transactional
    public BookResponse update(Long id, UpdateBookRequest request) {
        Book book = bookRepository.findByIdWithAuthors(id)
            .orElseThrow(() -> new ResourceNotFoundException("Book", id));

        if (request.isbn() != null && !request.isbn().equals(book.getIsbn())
                && bookRepository.existsByIsbnAndIdNot(request.isbn(), id)) {
            throw new DuplicateIsbnException(request.isbn());
        }

        BookMapper.updateEntity(book, request);

        if (request.authorIds() != null) {
            List<Author> authors = resolveAuthors(request.authorIds());
            book.getAuthors().clear();
            book.getAuthors().addAll(authors);
        }

        Book saved = bookRepository.save(book);
        return BookMapper.toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        Book book = bookRepository.findByIdWithAuthors(id)
            .orElseThrow(() -> new ResourceNotFoundException("Book", id));

        if (reservationRepository.existsByBookId(id)) {
            throw new ActiveReservationExistsException(
                "Cannot delete a book with reservation history");
        }

        bookRepository.delete(book);
    }

    private List<Author> resolveAuthors(List<Long> authorIds) {
        List<Author> authors = authorRepository.findAllById(authorIds);
        if (authors.size() != authorIds.size()) {
            List<Long> foundIds = authors.stream().map(Author::getId).toList();
            List<Long> missingIds = authorIds.stream()
                .filter(id -> !foundIds.contains(id))
                .toList();
            throw new ResourceNotFoundException("Author", missingIds.get(0));
        }
        return authors;
    }
}
