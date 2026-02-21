-- V4: Create the reservations table.
-- book_id uses ON DELETE RESTRICT (not CASCADE): a Book cannot be deleted while
-- any reservation — active, cancelled, or expired — references it.  This
-- preserves the full reservation history for audit purposes.  The application
-- layer provides a friendlier 409 error for the active-reservation case before
-- the DB constraint fires (see BookService.delete()).
--
-- Primary concurrency control mechanism:
--   idx_reservations_active_book is a PARTIAL UNIQUE INDEX on (book_id) filtered
--   to rows WHERE status = 'ACTIVE'.  This guarantees at the database level that
--   at most one ACTIVE reservation can exist per book at any time, regardless of
--   concurrent requests.  The application-level pre-check in ReservationService
--   is a user-friendly optimistic guard; the index is the authoritative enforcement.
CREATE TABLE reservations (
    id           BIGSERIAL    PRIMARY KEY,
    book_id      BIGINT       NOT NULL REFERENCES books(id) ON DELETE RESTRICT,
    user_name    VARCHAR(100) NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    reserved_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL,
    cancelled_at TIMESTAMPTZ,
    version      INTEGER      NOT NULL DEFAULT 0
);

-- Partial unique index: only one ACTIVE reservation per book is permitted.
-- The WHERE clause makes this a partial index so CANCELLED and EXPIRED rows
-- do not participate in the uniqueness constraint, allowing a book to be
-- re-reserved after its previous reservation is cancelled or expires.
CREATE UNIQUE INDEX idx_reservations_active_book
    ON reservations (book_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_reservations_book_id ON reservations (book_id);
CREATE INDEX idx_reservations_status  ON reservations (status);
