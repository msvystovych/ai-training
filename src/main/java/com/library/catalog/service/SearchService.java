package com.library.catalog.service;

import com.library.catalog.dto.response.BookResponse;
import com.library.catalog.dto.response.SearchResultResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SearchService {

    private final EntityManager entityManager;

    private static final String SEARCH_QUERY = """
        SELECT b.id, b.title, b.isbn, b.published_year,
               a.id AS author_id, a.first_name, a.last_name,
               GREATEST(
                 ts_rank(b.search_vector, plainto_tsquery('english', :query)),
                 COALESCE(MAX(ts_rank(
                   to_tsvector('english', COALESCE(a.first_name || ' ' || a.last_name, '')),
                   plainto_tsquery('english', :query)
                 )) OVER (PARTITION BY b.id), 0)
               ) AS relevance_score
        FROM books b
        LEFT JOIN book_authors ba ON b.id = ba.book_id
        LEFT JOIN authors a ON ba.author_id = a.id
        WHERE b.search_vector @@ plainto_tsquery('english', :query)
           OR to_tsvector('english', COALESCE(a.first_name || ' ' || a.last_name, ''))
              @@ plainto_tsquery('english', :query)
        ORDER BY relevance_score DESC, b.id ASC
        """;

    private static final String COUNT_QUERY = """
        SELECT COUNT(DISTINCT b.id)
        FROM books b
        LEFT JOIN book_authors ba ON b.id = ba.book_id
        LEFT JOIN authors a ON ba.author_id = a.id
        WHERE b.search_vector @@ plainto_tsquery('english', :query)
           OR to_tsvector('english', COALESCE(a.first_name || ' ' || a.last_name, ''))
              @@ plainto_tsquery('english', :query)
        """;

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<SearchResultResponse> search(String query, Pageable pageable) {
        String sanitized = sanitizeQuery(query);
        if (sanitized.isBlank()) {
            throw new IllegalArgumentException("Search query must not be blank");
        }

        // Execute count query
        Query countQ = entityManager.createNativeQuery(COUNT_QUERY);
        countQ.setParameter("query", sanitized);
        long total = ((Number) countQ.getSingleResult()).longValue();

        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        // Execute search query with pagination
        Query searchQ = entityManager.createNativeQuery(SEARCH_QUERY);
        searchQ.setParameter("query", sanitized);
        searchQ.setFirstResult((int) pageable.getOffset());
        searchQ.setMaxResults(pageable.getPageSize());

        List<Object[]> rows = searchQ.getResultList();

        // Group rows by book id (a book may appear multiple times for multiple authors)
        Map<Long, SearchResultResponse> resultMap = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Long bookId = ((Number) row[0]).longValue();
            String title = (String) row[1];
            String isbn = (String) row[2];
            Integer publishedYear = row[3] != null ? ((Number) row[3]).intValue() : null;
            Long authorId = row[4] != null ? ((Number) row[4]).longValue() : null;
            String firstName = (String) row[5];
            String lastName = (String) row[6];
            double relevanceScore = ((Number) row[7]).doubleValue();

            resultMap.computeIfAbsent(bookId, id -> new SearchResultResponse(
                bookId, title, isbn, publishedYear, new ArrayList<>(), relevanceScore
            ));

            if (authorId != null) {
                resultMap.get(bookId).authors().add(
                    new BookResponse.AuthorSummary(authorId, firstName, lastName)
                );
            }
        }

        List<SearchResultResponse> results = new ArrayList<>(resultMap.values());
        return new PageImpl<>(results, pageable, total);
    }

    String sanitizeQuery(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() > 500) {
            trimmed = trimmed.substring(0, 500);
        }
        // Strip null bytes and control characters (except tab and newline)
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (char c : trimmed.toCharArray()) {
            if (c == '\t' || c == '\n' || c >= 0x20) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }
}
