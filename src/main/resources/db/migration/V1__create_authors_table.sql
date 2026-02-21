-- V1: Create the authors table.
-- created_at and updated_at default to now(); JPA @PrePersist/@PreUpdate will
-- also set these from the application side, but the DB defaults ensure that any
-- direct SQL inserts (e.g. seed data) are also timestamped correctly.
CREATE TABLE authors (
    id         BIGSERIAL    PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name  VARCHAR(100) NOT NULL,
    bio        TEXT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
