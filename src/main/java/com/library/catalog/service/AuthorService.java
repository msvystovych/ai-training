package com.library.catalog.service;

import com.library.catalog.dto.request.CreateAuthorRequest;
import com.library.catalog.dto.request.UpdateAuthorRequest;
import com.library.catalog.dto.response.AuthorResponse;
import com.library.catalog.entity.Author;
import com.library.catalog.entity.Book;
import com.library.catalog.exception.ActiveReservationExistsException;
import com.library.catalog.exception.ResourceNotFoundException;
import com.library.catalog.mapper.AuthorMapper;
import com.library.catalog.repository.AuthorRepository;
import com.library.catalog.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthorService {

    private final AuthorRepository authorRepository;
    private final BookRepository bookRepository;

    @Transactional(readOnly = true)
    public Page<AuthorResponse> findAll(Pageable pageable) {
        return authorRepository.findAll(pageable)
            .map(AuthorMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public AuthorResponse findById(Long id) {
        Author author = authorRepository.findByIdWithBooks(id)
            .orElseThrow(() -> new ResourceNotFoundException("Author", id));
        return AuthorMapper.toResponse(author);
    }

    @Transactional
    public AuthorResponse create(CreateAuthorRequest request) {
        Author author = AuthorMapper.toEntity(request);
        Author saved = authorRepository.save(author);
        return AuthorMapper.toResponse(saved);
    }

    @Transactional
    public AuthorResponse update(Long id, UpdateAuthorRequest request) {
        Author author = authorRepository.findByIdWithBooks(id)
            .orElseThrow(() -> new ResourceNotFoundException("Author", id));
        AuthorMapper.updateEntity(author, request);
        Author saved = authorRepository.save(author);
        return AuthorMapper.toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        Author author = authorRepository.findByIdWithBooks(id)
            .orElseThrow(() -> new ResourceNotFoundException("Author", id));

        List<Book> authorBooks = bookRepository.findAllByAuthorId(id);
        for (Book book : authorBooks) {
            long authorCount = bookRepository.countAuthorsByBookId(book.getId());
            if (authorCount == 1) {
                throw new ActiveReservationExistsException(
                    "Cannot delete author: sole author of book '" + book.getTitle() + "'");
            }
        }

        authorRepository.delete(author);
    }
}
