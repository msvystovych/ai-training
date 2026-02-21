-- V2: Create the books table.
-- isbn has a unique index (idx_books_isbn) rather than an inline UNIQUE constraint
-- so that the index name is deterministic and can be referenced by name in
-- GlobalExceptionHandler when catching DataIntegrityViolationException.
-- title has a B-tree index to support LIKE prefix queries (e.g. "Clean%").
-- search_vector is populated by a PostgreSQL trigger in V5; it is NOT mapped
-- in the Java Book entity — see Section 4.4 of the development plan for rationale.
CREATE TABLE books (
    id             BIGSERIAL    PRIMARY KEY,
    title          VARCHAR(255) NOT NULL,
    isbn           VARCHAR(13)  NOT NULL,
    description    TEXT,
    published_year INTEGER,
    version        INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_books_isbn  ON books (isbn);
CREATE INDEX        idx_books_title ON books (title);
