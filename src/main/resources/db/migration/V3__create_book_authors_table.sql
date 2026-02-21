-- V3: Create the book_authors join table for the Book <-> Author many-to-many.
-- Both FKs use ON DELETE CASCADE so that:
--   * deleting a Book automatically removes its rows from this table (JPA also
--     handles this via the owning @JoinTable on Book, but the DB constraint is
--     the authoritative safety net);
--   * deleting an Author removes their rows from this table (the association),
--     but NOT the Book rows themselves — Author.books uses mappedBy, not a
--     separate join-table management, so JPA cascade does not delete Books.
-- The application enforces an additional invariant: an Author may not be deleted
-- if doing so would leave any Book with zero authors (checked in AuthorService).
CREATE TABLE book_authors (
    book_id   BIGINT NOT NULL REFERENCES books(id)   ON DELETE CASCADE,
    author_id BIGINT NOT NULL REFERENCES authors(id) ON DELETE CASCADE,
    PRIMARY KEY (book_id, author_id)
);

-- Index on author_id to speed up reverse lookups (find all books by an author).
-- The composite PK already covers queries that lead with book_id.
CREATE INDEX idx_book_authors_author_id ON book_authors (author_id);
