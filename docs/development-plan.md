# Library Catalog -- Development Plan

**Project**: Library Catalog MVP
**Duration**: 7 days
**Domain**: Minimal service to manage books, authors, and simple reservations with reliable search and correct filtering under edge cases.
**Author**: Development Team
**Date**: 2026-02-21
**Revision**: 5 (Spring Boot code review fixes)

---

## Table of Contents

1. [Tech Stack Summary](#1-tech-stack-summary)
2. [Architecture Overview](#2-architecture-overview) *(added rev 3)*
3. [Project Structure](#3-project-structure)
4. [Data Model](#4-data-model) *(subsections 4.4–4.8 added rev 3)*
5. [Flyway Migrations](#5-flyway-migrations)
6. [API Endpoints](#6-api-endpoints)
7. [Search Implementation](#7-search-implementation)
8. [Concurrency Handling](#8-concurrency-handling)
9. [Testing Strategy](#9-testing-strategy)
10. [Day-by-Day Breakdown](#10-day-by-day-breakdown)
11. [Acceptance Criteria Checklist](#11-acceptance-criteria-checklist)
12. [Risk Register](#12-risk-register)
13. [Known Deviations from Spec](#13-known-deviations-from-spec)
14. [Appendix A: Build Configuration](#14-appendix-a-build-configuration) *(added rev 3)*
15. [Appendix B: Configuration Files](#15-appendix-b-configuration-files) *(added rev 3)*
16. [Appendix C: Error Codes and Response Shapes](#appendix-c-error-codes-and-response-shapes)
17. [Known Action Items from Previous Reviews](#17-known-action-items-from-previous-reviews) *(added rev 3)*
18. [Changes in Revision 4](#18-changes-in-revision-4) *(added rev 4)*
19. [Changes in Revision 5](#19-changes-in-revision-5) *(added rev 5)*

---

## 1. Tech Stack Summary

| Component            | Technology                     | Purpose                                     |
|----------------------|--------------------------------|---------------------------------------------|
| Language             | Java 21                        | Primary language, using records where appropriate |
| Framework            | Spring Boot 3                  | REST API, dependency injection, configuration |
| Database             | PostgreSQL 16                  | Primary data store with full-text search     |
| Migrations           | Flyway                         | Versioned, repeatable database migrations    |
| API Documentation    | SpringDoc / Swagger UI         | Interactive API docs, doubles as the project UI |
| Health / Monitoring  | Spring Boot Actuator           | `/actuator/health` endpoint for verifying app is running |
| Integration Tests    | Testcontainers (PostgreSQL)    | Disposable Postgres containers for test isolation |
| Unit Tests           | JUnit 5 + Mockito              | Service-layer unit tests                     |
| Local Dev DB         | Docker Compose                 | Runs Postgres for local development          |
| Build Tool           | Gradle (Kotlin DSL)            | Build, dependency management, test execution |

**Explicitly out of scope**: CI/CD pipelines, any frontend beyond Swagger UI, authentication/authorization.

---

## 2. Architecture Overview

> *Added in revision 3. This section provides the architectural context needed for implementation decisions.*

### 2.1 Component / Layer Diagram

The following diagram shows the full request flow from client to database and back. Each layer has a single, well-defined responsibility.

```
┌─────────────────────────────────────────────────────────────────┐
│  CLIENT (Browser / Swagger UI / curl)                           │
└────────────────────────────┬────────────────────────────────────┘
                             │  HTTP Request
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  CONTROLLER LAYER                                               │
│  BookController | AuthorController | ReservationController      │
│  SearchController                                               │
│                                                                 │
│  Responsibilities:                                              │
│   - Parse and bind HTTP request (path vars, query params, body) │
│   - Invoke @Valid on request DTOs (triggers Bean Validation)    │
│   - Delegate all business logic to the Service layer           │
│   - Map service return values to HTTP responses                 │
│   - Return ResponseEntity with correct HTTP status              │
│                                                                 │
│  Must NOT contain: business logic, JPA queries, transactions    │
└────────────────────────────┬────────────────────────────────────┘
                             │  Validated request DTOs / primitives
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  SERVICE LAYER                                                  │
│  BookService | AuthorService | ReservationService | SearchService│
│                                                                 │
│  Responsibilities:                                              │
│   - Enforce all business rules and invariants                   │
│   - Own transaction boundaries (@Transactional)                 │
│   - Orchestrate repository calls                                │
│   - Throw domain exceptions (ResourceNotFoundException, etc.)   │
│   - Map entities to response DTOs via Mappers                   │
│                                                                 │
│  Must NOT contain: HTTP concepts, JPA query strings             │
└──────────┬────────────────────────────────┬─────────────────────┘
           │ Entities                       │ Entities
           ▼                               ▼
┌──────────────────────┐     ┌──────────────────────────────────┐
│  REPOSITORY LAYER    │     │  MAPPER LAYER                    │
│  BookRepository      │     │  BookMapper | AuthorMapper       │
│  AuthorRepository    │     │  ReservationMapper               │
│  ReservationRepository│    │                                  │
│                      │     │  Static pure-function classes.   │
│  Spring Data JPA     │     │  Entity -> Response DTO          │
│  interfaces. Custom  │     │  Request DTO -> Entity           │
│  @Query methods for  │     │  No state, no Spring beans.      │
│  search and complex  │     └──────────────────────────────────┘
│  reservation queries.│
│                      │
│  Must NOT contain:   │
│  business logic or   │
│  transaction mgmt    │
└──────────┬───────────┘
           │  JDBC / Hibernate
           ▼
┌─────────────────────────────────────────────────────────────────┐
│  PostgreSQL 16                                                  │
│                                                                 │
│  Tables: authors, books, book_authors, reservations             │
│  Constraints: partial unique index on reservations (concurrency)│
│  Indexes: GIN on search_vector, B-tree on title, unique on isbn │
│  Triggers: auto-update search_vector on book INSERT/UPDATE      │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 Cross-Cutting Concerns

These concerns span all layers and are implemented centrally rather than scattered across classes.

#### Exception Handling

`GlobalExceptionHandler` (annotated `@RestControllerAdvice`) is the single point where all exceptions are caught and translated to HTTP responses. No controller or service returns `null` or catches exceptions for the purpose of shaping HTTP responses -- they always throw domain exceptions and let the handler deal with HTTP mapping.

Hierarchy of exception catch:
1. Specific domain exceptions (`ResourceNotFoundException`, `BookAlreadyReservedException`, etc.) -> precise HTTP status + message
2. `MethodArgumentNotValidException` -> 400 with per-field error list
3. `DataIntegrityViolationException` -> inspects constraint name, returns 409 or 400
4. `OptimisticLockingFailureException` -> 409 with retry guidance
5. `Exception` (catch-all) -> 500, logged at ERROR level with stack trace

#### Validation

Two complementary layers are used. They are not redundant -- they serve different purposes:

| Layer | Mechanism | Scope | When Triggered |
|-------|-----------|-------|----------------|
| HTTP input | Bean Validation (`@Valid` on `@RequestBody`) | Request DTOs only | Before the controller method body executes |
| Business rules | Explicit checks in Service methods | Cross-entity invariants, state transitions | Inside `@Transactional` service methods |

Bean Validation catches structural problems (blank fields, size violations, format constraints) early and cheaply. Service-level checks enforce domain rules that require database state (e.g., "does a book with this ISBN already exist?", "does this author have other books?").

#### Transaction Management

Transactions are managed exclusively at the Service layer via `@Transactional`. The guiding rule is:

- **Read operations**: `@Transactional(readOnly = true)` -- enables Hibernate read-only optimizations, prevents accidental dirty writes.
- **Write operations**: `@Transactional` (read-write, REQUIRED propagation by default).
- **Controller layer**: never annotated with `@Transactional`. Controllers must not enlarge transaction boundaries.
- **Repository layer**: individual repository methods run in whatever transaction the caller opened (REQUIRED propagation). Do not annotate repository methods with `@Transactional`.

### 2.3 Package Dependency Rules

These rules are strict. Violations create circular dependencies and break testability.

```
controller  -->  service   (allowed)
controller  -->  dto       (allowed)
service     -->  repository (allowed)
service     -->  entity    (allowed)
service     -->  dto       (allowed)
service     -->  exception (allowed)
service     -->  mapper    (allowed)
mapper      -->  entity    (allowed)
mapper      -->  dto       (allowed)
repository  -->  entity    (allowed)

controller  -->  repository (FORBIDDEN -- bypasses service layer)
controller  -->  entity    (FORBIDDEN -- leaks persistence model to HTTP layer)
repository  -->  service   (FORBIDDEN -- circular dependency)
service     -->  controller (FORBIDDEN -- circular dependency)
entity      -->  dto       (FORBIDDEN -- persistence model must not know HTTP model)
```

The `exception` package is a leaf package -- it has no dependencies on other internal packages. Any layer can throw or reference exceptions. `GlobalExceptionHandler` is NOT in the `exception` package; it lives in `controller/handler/` because it is a web-tier concern (`@RestControllerAdvice`) that depends on Spring MVC. Placing it in `exception/` would give the exception package a dependency on the web layer, violating the leaf-package rule.

---

## 3. Project Structure

```
library-catalog/
|-- build.gradle.kts
|-- settings.gradle.kts
|-- docker-compose.yml
|-- CLAUDE.md
|-- docs/
|   |-- development-plan.md
|   |-- report.md                          # One-page AI suggestion report
|-- automated-claude-code-agent-log.md     # Agent log (automated from Claude Code)
|-- src/
|   |-- main/
|   |   |-- java/com/library/catalog/
|   |   |   |-- LibraryCatalogApplication.java
|   |   |   |-- config/
|   |   |   |   |-- OpenApiConfig.java
|   |   |   |-- controller/
|   |   |   |   |-- BookController.java
|   |   |   |   |-- AuthorController.java
|   |   |   |   |-- ReservationController.java
|   |   |   |   |-- SearchController.java
|   |   |   |   |-- handler/
|   |   |   |   |   |-- GlobalExceptionHandler.java
|   |   |   |-- service/
|   |   |   |   |-- BookService.java
|   |   |   |   |-- AuthorService.java
|   |   |   |   |-- ReservationService.java
|   |   |   |   |-- SearchService.java
|   |   |   |-- repository/
|   |   |   |   |-- BookRepository.java
|   |   |   |   |-- AuthorRepository.java
|   |   |   |   |-- ReservationRepository.java
|   |   |   |-- entity/
|   |   |   |   |-- Book.java              # @ManyToMany with Author via @JoinTable
|   |   |   |   |-- Author.java
|   |   |   |   |-- Reservation.java
|   |   |   |   |-- ReservationStatus.java
|   |   |   |-- dto/
|   |   |   |   |-- request/
|   |   |   |   |   |-- CreateBookRequest.java
|   |   |   |   |   |-- UpdateBookRequest.java
|   |   |   |   |   |-- CreateAuthorRequest.java
|   |   |   |   |   |-- UpdateAuthorRequest.java
|   |   |   |   |   |-- CreateReservationRequest.java
|   |   |   |   |-- response/
|   |   |   |   |   |-- BookResponse.java
|   |   |   |   |   |-- AuthorResponse.java
|   |   |   |   |   |-- ReservationResponse.java
|   |   |   |   |   |-- SearchResultResponse.java
|   |   |   |   |   |-- ErrorResponse.java
|   |   |   |   |   |-- PagedResponse.java
|   |   |   |-- mapper/
|   |   |   |   |-- BookMapper.java
|   |   |   |   |-- AuthorMapper.java
|   |   |   |   |-- ReservationMapper.java
|   |   |   |-- exception/
|   |   |   |   |-- ResourceNotFoundException.java
|   |   |   |   |-- DuplicateIsbnException.java
|   |   |   |   |-- BookAlreadyReservedException.java
|   |   |   |   |-- InvalidReservationStateException.java
|   |   |   |   |-- ActiveReservationExistsException.java  # Prevents book/author deletion
|   |   |-- resources/
|   |   |   |-- application.yml
|   |   |   |-- db/migration/
|   |   |       |-- V1__create_authors_table.sql
|   |   |       |-- V2__create_books_table.sql
|   |   |       |-- V3__create_book_authors_table.sql
|   |   |       |-- V4__create_reservations_table.sql
|   |   |       |-- V5__add_full_text_search.sql
|   |   |       |-- V6__seed_sample_data.sql
|   |-- test/
|       |-- java/com/library/catalog/
|       |   |-- LibraryCatalogApplicationTests.java
|       |   |-- unit/
|       |   |   |-- service/
|       |   |       |-- BookServiceTest.java
|       |   |       |-- AuthorServiceTest.java
|       |   |       |-- ReservationServiceTest.java
|       |   |       |-- SearchServiceTest.java
|       |   |-- integration/
|       |       |-- AbstractIntegrationTest.java
|       |       |-- BookIntegrationTest.java
|       |       |-- AuthorIntegrationTest.java
|       |       |-- SearchIntegrationTest.java
|       |       |-- ReservationIntegrationTest.java
|       |       |-- ReservationConcurrencyTest.java
|       |-- resources/
|           |-- application-test.yml
```

**Design rationale**:
- `LibraryCatalogApplication.java` at `com.library.catalog` uses `@SpringBootApplication`, which includes `@ComponentScan` for that package and all sub-packages. No explicit `@ComponentScan` annotation is needed or should be added -- adding it manually overrides the auto-detection and commonly causes beans to be missed or double-registered.
- Standard layered architecture (controller -> service -> repository) keeps the codebase predictable and testable.
- DTOs are split into `request` and `response` sub-packages so intent is clear at a glance.
- Mappers are plain static-method classes (no MapStruct dependency) to keep the dependency tree small. Mapper methods must be called exclusively from within `@Transactional` service methods. Because all entity associations are `FetchType.LAZY`, accessing `book.getAuthors()` or `author.getBooks()` in a mapper called outside a transaction throws `LazyInitializationException`. Services are responsible for ensuring the relevant collections are initialized (via JOIN FETCH queries or `@BatchSize` batch loading) before delegating to the mapper.
- A shared `AbstractIntegrationTest` base class configures the Testcontainers PostgreSQL instance once for all integration tests.
- `GlobalExceptionHandler` lives in `controller/handler/` (not `exception/`) because it is a web-tier concern: it translates exceptions into HTTP responses. The `exception/` package contains only plain `RuntimeException` subclasses with no dependency on Spring MVC or HTTP. This keeps the exception package a true leaf package with no web-layer imports.

**Changes from v1** (post-review):
- Removed explicit `BookAuthor` entity. Using `@ManyToMany` with `@JoinTable` on `Book` directly -- no extra entity needed for an MVP with no join-table metadata.
- Removed `SearchRequest` DTO. Search uses `@RequestParam` directly in the controller.
- Removed dedicated mapper test files (`BookMapperTest`, `AuthorMapperTest`, `ReservationMapperTest`). Mappers are trivial static methods; their correctness is verified implicitly through integration tests. Test effort is redirected to more integration test scenarios.
- Added `ActiveReservationExistsException` for preventing book/author deletion when active reservations exist.
- Added `docs/report.md` as the defined location for the one-page report.
- Fixed agent log filename: `automated-claude-code-agent-log.md` (was "cloude").

---

## 4. Data Model

### 4.1 Entity Relationship Diagram (textual)

```
Author (*) --------- (*) Book
       @ManyToMany          |
       via book_authors     | 1
                            |
                            *
                       Reservation
```

- An Author can write many Books; a Book can have many Authors (many-to-many via `book_authors` join table, managed by JPA `@ManyToMany`).
- A Book can have many Reservations over time, but only one ACTIVE reservation at a time (enforced at DB level).

### 4.2 Entity Definitions

#### Author

| Column       | Type                  | Constraints                     | Notes                      |
|--------------|-----------------------|---------------------------------|----------------------------|
| id           | BIGINT (BIGSERIAL)    | PK, auto-generated              |                            |
| first_name   | VARCHAR(100)          | NOT NULL                        |                            |
| last_name    | VARCHAR(100)          | NOT NULL                        |                            |
| bio          | TEXT                  | NULLABLE                        | Short author biography     |
| created_at   | TIMESTAMPTZ           | NOT NULL, DEFAULT now()         |                            |
| updated_at   | TIMESTAMPTZ           | NOT NULL, DEFAULT now()         | Updated via JPA `@PreUpdate` |

#### Book

| Column         | Type                  | Constraints                     | Notes                      |
|----------------|-----------------------|---------------------------------|----------------------------|
| id             | BIGINT (BIGSERIAL)    | PK, auto-generated              |                            |
| title          | VARCHAR(255)          | NOT NULL                        |                            |
| isbn           | VARCHAR(13)           | NOT NULL, UNIQUE                | ISBN-13 format             |
| description    | TEXT                  | NULLABLE                        |                            |
| published_year | INTEGER               | NULLABLE                        | Year only, e.g. 2024       |
| search_vector  | TSVECTOR              | NULLABLE                        | Auto-populated by trigger  |
| version        | INTEGER               | NOT NULL, DEFAULT 0             | For optimistic locking (JPA @Version) |
| created_at     | TIMESTAMPTZ           | NOT NULL, DEFAULT now()         |                            |
| updated_at     | TIMESTAMPTZ           | NOT NULL, DEFAULT now()         | Updated via JPA `@PreUpdate` |

Indexes:
- `idx_books_isbn` UNIQUE on `isbn`
- `idx_books_title` B-tree on `title` (supports LIKE prefix queries)
- `idx_books_search_vector` GIN on `search_vector` (full-text search)

#### book_authors (join table -- managed by JPA `@ManyToMany`, no explicit entity)

| Column    | Type    | Constraints                         |
|-----------|---------|-------------------------------------|
| book_id   | BIGINT  | NOT NULL, FK -> books(id) ON DELETE CASCADE |
| author_id | BIGINT  | NOT NULL, FK -> authors(id) ON DELETE CASCADE |

- Composite PK on `(book_id, author_id)`.

**Author deletion behavior**: The FK uses CASCADE on the join table only (removing the book-author association). However, `AuthorService.delete()` will **check at application level** whether deleting the author would leave any book with zero authors. If so, the deletion is rejected with a 409 error. This prevents orphaned books.

#### Reservation

| Column      | Type                  | Constraints                                | Notes                      |
|-------------|-----------------------|--------------------------------------------|----------------------------|
| id          | BIGINT (BIGSERIAL)    | PK, auto-generated                          |                            |
| book_id     | BIGINT                | NOT NULL, FK -> books(id) ON DELETE RESTRICT | Cannot delete book with reservations |
| user_name   | VARCHAR(100)          | NOT NULL                                    | Simple string, no auth     |
| status      | VARCHAR(20)           | NOT NULL, DEFAULT 'ACTIVE'                  | ACTIVE, CANCELLED, EXPIRED |
| reserved_at | TIMESTAMPTZ           | NOT NULL, DEFAULT now()                     |                            |
| expires_at  | TIMESTAMPTZ           | NOT NULL                                    | reserved_at + 14 days      |
| cancelled_at| TIMESTAMPTZ           | NULLABLE                                    | Set when status -> CANCELLED |
| version     | INTEGER               | NOT NULL, DEFAULT 0                         | For optimistic locking      |

Constraints:
- **Partial unique index**: `CREATE UNIQUE INDEX idx_reservations_active_book ON reservations (book_id) WHERE status = 'ACTIVE';`
  This is the **primary concurrency control mechanism** -- the database itself guarantees that at most one ACTIVE reservation exists per book at any time. See Section 7 for full details.
- **FK ON DELETE RESTRICT**: A book cannot be deleted if any reservation (active, cancelled, or expired) references it. This prevents data integrity issues. The application layer provides a friendlier check for active reservations specifically (409 with descriptive message).

#### ReservationStatus (Java enum)

```java
public enum ReservationStatus {
    ACTIVE,
    CANCELLED,
    EXPIRED
}
```

### 4.3 JPA Mapping Notes

- Use `@Entity`, `@Table`, `@Id`, `@GeneratedValue(strategy = GenerationType.IDENTITY)` for all entities.
- Use `@Version` on `Book.version` and `Reservation.version` for optimistic locking at the application level.
- The Book-Author relationship uses `@ManyToMany` with `@JoinTable(name = "book_authors", joinColumns = @JoinColumn(name = "book_id"), inverseJoinColumns = @JoinColumn(name = "author_id"))` on the `Book` entity. The inverse side (`Author`) uses `@ManyToMany(mappedBy = "authors")`.
- `ReservationStatus` is mapped via `@Enumerated(EnumType.STRING)`.
- All timestamps use `Instant` in Java, mapped to `TIMESTAMPTZ` in PostgreSQL.
- All entities implement a `@PreUpdate` callback to set `updated_at = Instant.now()`. This can be done via a shared `@MappedSuperclass` or `@EntityListeners`.

```java
@MappedSuperclass
public abstract class BaseEntity {

    // JPA requires a no-arg constructor accessible to Hibernate's proxy machinery.
    // Without it, Hibernate cannot instantiate proxies for lazy loading or
    // bytecode enhancement. Protected visibility prevents direct instantiation
    // from outside the class hierarchy while satisfying the JPA specification.
    protected BaseEntity() {}

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
```

### 4.4 Complete JPA Entity Annotations

> *Added in revision 3. Provides field-level annotation detail to remove implementation ambiguity.*

#### Author Entity

```java
@Entity
@Table(name = "authors")
public class Author extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    // Inverse side of the ManyToMany. Author does not own the join table.
    // FetchType.LAZY: loading all books for an author is expensive and
    // rarely needed in a single request. Always loaded explicitly when required.
    // @BatchSize: mirrors the setting on Book.authors -- when Hibernate loads
    // books for a page of N authors, it batches the IN-clause to 20 at a time,
    // mitigating the N+1 problem on paginated list endpoints.
    @ManyToMany(mappedBy = "authors", fetch = FetchType.LAZY)
    @BatchSize(size = 20)
    private Set<Book> books = new HashSet<>();
}
```

**Fetch strategy rationale for `Author.books`**: The collection is `LAZY` because the typical Author API responses include only a brief book summary list (id + title). Loading the full `Book` object graph eagerly would trigger additional queries for every book's own author list, creating an N+1 risk. The mapper accesses `author.getBooks()` explicitly when building the response -- at that point we are still within the open transaction, so lazy loading works correctly.

#### Book Entity

```java
@Entity
@Table(name = "books")
public class Book extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "isbn", nullable = false, unique = true, length = 13)
    private String isbn;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "published_year")
    private Integer publishedYear;

    // NOTE: The search_vector column is managed exclusively by the PostgreSQL
    // trigger (V5 migration). It is NOT mapped in the Java entity because it
    // is never read by application code -- the search uses a native SQL query
    // that accesses the column directly. Mapping it here would require
    // insertable=false, updatable=false but still creates a misleading field
    // that application code might mistakenly read as a String. Omitting it
    // entirely is cleaner and removes any risk of accidental writes.

    // Optimistic locking. Hibernate increments this on every UPDATE.
    // If two transactions read the same version and both try to update,
    // the second will throw OptimisticLockingFailureException.
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    // Book owns the join table. CascadeType.PERSIST and MERGE are included
    // so that when a Book is saved, any new Author associations are
    // persisted automatically. CascadeType.REMOVE is intentionally excluded:
    // deleting a Book must not cascade-delete Authors.
    // FetchType.LAZY: avoid loading all authors on every Book query.
    // Authors are fetched explicitly when building BookResponse.
    // @BatchSize: when loading authors for a page of N books, Hibernate will
    // fetch author collections in batches of 20 rather than one query per book,
    // mitigating the N+1 problem on paginated list endpoints.
    @ManyToMany(
        fetch = FetchType.LAZY,
        cascade = {CascadeType.PERSIST, CascadeType.MERGE}
    )
    @JoinTable(
        name = "book_authors",
        joinColumns = @JoinColumn(name = "book_id"),
        inverseJoinColumns = @JoinColumn(name = "author_id")
    )
    @BatchSize(size = 20)
    private Set<Author> authors = new HashSet<>();

    // One book -> many reservations. FetchType.LAZY: reservation history
    // is never returned as part of a BookResponse. This collection is only
    // accessed in BookService.delete() to check for blocking reservations.
    // CascadeType: none. Reservations have independent lifecycle.
    // mappedBy references the field name in Reservation, not the column name.
    @OneToMany(mappedBy = "book", fetch = FetchType.LAZY)
    private List<Reservation> reservations = new ArrayList<>();
}
```

**Why `CascadeType.REMOVE` is excluded from `@ManyToMany`**: Removing a Book should remove the rows in `book_authors` (the join table) but must NOT delete the Author entities themselves. Spring Data JPA handles join-table cleanup automatically when a Book is deleted, because the Book entity owns the `@JoinTable`. Adding `CascadeType.REMOVE` here would incorrectly delete the Author records as well.

**Why `search_vector` is not mapped in the Java entity**: The `tsvector` column is populated entirely by a PostgreSQL trigger (`trg_books_search_vector`) on INSERT and UPDATE of `title` or `description`. Application code never reads `search_vector` directly -- the search native query accesses the column in SQL without hydrating it into a Java object. Omitting the field entirely prevents any accidental read or write from application code and keeps the entity model aligned with the data the application actually manages.

#### Reservation Entity

```java
@Entity
@Table(name = "reservations")
public class Reservation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ManyToOne with FetchType.LAZY: loading the full Book is not needed
    // for most reservation queries. The ReservationResponse only needs
    // bookId and bookTitle, which can be accessed via book.getId() and
    // book.getTitle() without triggering a second query when within a
    // transaction.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @Column(name = "user_name", nullable = false, length = 100)
    private String userName;

    // EnumType.STRING: stores the enum name ("ACTIVE", "CANCELLED", "EXPIRED")
    // as a VARCHAR. EnumType.ORDINAL is fragile -- reordering enum values
    // would silently corrupt data.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    // updatable = false: the reservation timestamp is set once at creation and
    // must never be changed. This prevents accidental overwrites of audit data.
    @Column(name = "reserved_at", nullable = false, updatable = false)
    private Instant reservedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    // Optimistic locking for concurrent cancellation attempts.
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}
```

### 4.5 Fetch Strategy and Cascade Summary

> *Added in revision 3.*

| Relationship | FetchType | CascadeType | Rationale |
|---|---|---|---|
| `Author.books` (inverse `@ManyToMany`) | LAZY | none | Books are large objects; author responses include only summaries |
| `Book.authors` (owning `@ManyToMany`) | LAZY | PERSIST, MERGE | Authors are loaded explicitly for BookResponse; exclude REMOVE to avoid Author deletion |
| `Book.reservations` (`@OneToMany`) | LAZY | none | Reservation history is never returned in a BookResponse; only accessed for deletion check |
| `Reservation.book` (`@ManyToOne`) | LAZY | none | Only bookId and bookTitle are needed in ReservationResponse |

**Universal rule**: Every collection and association is `FetchType.LAZY`. There are no `FetchType.EAGER` associations in this project. Eager fetching on collections creates N+1 query problems that are difficult to diagnose in production. All required data is loaded explicitly within the open transaction via the service layer.

### 4.6 Repository Layer Detail

> *Added in revision 3. Complete method signatures for each repository interface.*

#### BookRepository

```java
public interface BookRepository extends JpaRepository<Book, Long> {

    // Used by BookService to detect duplicate ISBN before insert.
    boolean existsByIsbn(String isbn);

    // Used by BookService.update() to detect ISBN collision when changing
    // a book's ISBN (must exclude the book being updated from the check).
    boolean existsByIsbnAndIdNot(String isbn, Long id);

    // NOTE: findAll(Pageable) is inherited from JpaRepository and must NOT
    // be redeclared here. Redeclaring it adds no value and can shadow the
    // inherited default method in confusing ways.

    // Eagerly join-fetch authors to avoid N+1 when building BookResponse
    // for a list endpoint. Returns all books with their authors in one query.
    // Cannot use Pageable with JOIN FETCH directly (Hibernate HHH90003004 warning).
    // Use this for single-item fetches; for paginated lists, use findAll(Pageable)
    // and accept two queries (one for ids, one for authors via EntityGraph or
    // a second fetch).
    @Query("SELECT b FROM Book b LEFT JOIN FETCH b.authors WHERE b.id = :id")
    Optional<Book> findByIdWithAuthors(@Param("id") Long id);

    // Used by AuthorService.delete() to find all books associated with
    // an author, so we can check whether any would be left with zero authors.
    @Query("SELECT b FROM Book b JOIN b.authors a WHERE a.id = :authorId")
    List<Book> findAllByAuthorId(@Param("authorId") Long authorId);

    // NOTE: hasActiveReservation was removed in revision 5.
    // The equivalent check is performed by ReservationRepository.existsByBookIdAndStatus(),
    // which Spring Data derives correctly as a boolean EXISTS query.
    // The original @Query("SELECT COUNT(r) > 0 FROM Reservation r ...") was invalid JPQL --
    // JPQL does not allow boolean expressions as projection items. The derived query
    // in ReservationRepository is type-safe and correct.

    // Used by AuthorService.delete() to count the number of authors on a
    // specific book without loading the full author collection. This avoids
    // the N+1 pattern of calling book.getAuthors().size() for each book.
    @Query("SELECT COUNT(a) FROM Book b JOIN b.authors a WHERE b.id = :bookId")
    long countAuthorsByBookId(@Param("bookId") Long bookId);
}
```

#### AuthorRepository

```java
public interface AuthorRepository extends JpaRepository<Author, Long> {

    // NOTE: findAll(Pageable) is inherited from JpaRepository and must NOT
    // be redeclared here. Redeclaring it adds no value and can shadow the
    // inherited default method in confusing ways.

    // Fetch author with books collection initialized.
    // Used in AuthorMapper to build AuthorResponse with book summaries.
    @Query("SELECT a FROM Author a LEFT JOIN FETCH a.books WHERE a.id = :id")
    Optional<Author> findByIdWithBooks(@Param("id") Long id);
}
```

#### ReservationRepository

```java
public interface ReservationRepository extends JpaRepository<Reservation, Long>,
        JpaSpecificationExecutor<Reservation> {

    // Used in ReservationService.createReservation() to do the
    // application-level pre-check before the DB constraint fires.
    boolean existsByBookIdAndStatus(Long bookId, ReservationStatus status);

    // Used by BookService.delete() to check whether a book has ANY reservation
    // (active, cancelled, or expired) before attempting deletion. This provides
    // a friendlier error message than letting the FK ON DELETE RESTRICT fire.
    boolean existsByBookId(Long bookId);

    // Used in the expire-then-create flow. Returns the current active
    // reservation for a book, if one exists, so we can check its expiresAt.
    // NOTE: Use findByBookIdAndStatusForUpdate (below) instead of this method
    // on the expire-then-create path to prevent TOCTOU races.
    Optional<Reservation> findByBookIdAndStatus(Long bookId, ReservationStatus status);

    // Used EXCLUSIVELY on the expire-then-create path in
    // ReservationService.createReservation(). The pessimistic write lock
    // prevents a TOCTOU (time-of-check/time-of-use) race where two threads
    // both see an expired reservation, both expire it, and both attempt to
    // insert a new ACTIVE reservation at the same time.
    // The partial unique index is still the final safety net, but the lock
    // reduces contention and makes the conflict deterministic.
    //
    // @QueryHints lock.timeout = 5000 ms: PostgreSQL waits indefinitely for a
    // pessimistic lock by default. Without a timeout, a thread holding the lock
    // that crashes or stalls will cause other threads to block forever. The 5-second
    // timeout causes the waiting thread to fail with a LockTimeoutException, which
    // should be caught and translated to a 409 response. This also prevents test
    // hangs if a thread fails unexpectedly during the concurrency test.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT r FROM Reservation r WHERE r.book.id = :bookId AND r.status = :status")
    Optional<Reservation> findByBookIdAndStatusForUpdate(
        @Param("bookId") Long bookId,
        @Param("status") ReservationStatus status
    );

    // Filtered paginated list -- replaced with JpaSpecificationExecutor.
    // Use ReservationSpecification.withFilters(...) to build predicates dynamically
    // and call reservationRepository.findAll(spec, pageable) in the service.
    // Rationale: a JPQL @Query with IS NULL checks for optional parameters
    // prevents the query planner from using selective indexes and is difficult
    // to extend. The Specification pattern composes predicates only for
    // non-null parameters, producing an optimal WHERE clause each time.

    // Used in ReservationService to fetch a reservation together with its
    // Book (for building the response without a separate query).
    @Query("SELECT r FROM Reservation r LEFT JOIN FETCH r.book WHERE r.id = :id")
    Optional<Reservation> findByIdWithBook(@Param("id") Long id);
}
```

#### ReservationSpecification

```java
public class ReservationSpecification {

    // Static utility class -- not a Spring bean. No instantiation needed.
    private ReservationSpecification() {}

    /**
     * Builds a JPA Specification that filters Reservation entities by the
     * provided optional criteria. Null parameters are ignored, producing a
     * predicate only for the values that are actually supplied.
     */
    public static Specification<Reservation> withFilters(
            Long bookId, String userName, ReservationStatus status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (bookId != null) {
                predicates.add(cb.equal(root.get("book").get("id"), bookId));
            }
            if (userName != null && !userName.isBlank()) {
                predicates.add(cb.equal(root.get("userName"), userName));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
```

In `ReservationService.findAll(...)`:
```java
Page<Reservation> page = reservationRepository.findAll(
    ReservationSpecification.withFilters(bookId, userName, status),
    pageable
);
```

**Search query lives in `SearchService`, not a repository**: The search native SQL is complex enough (multi-table join, aggregation, ranking) that placing it in a `@Query` on a repository would return raw `Object[]` arrays that need manual mapping. Instead, `SearchService` uses `EntityManager.createNativeQuery()` and maps the results manually. This keeps the repository interfaces clean and the mapping logic explicit.

### 4.7 Service Layer Contracts

> *Added in revision 3. Full method signatures and transaction annotations.*

#### BookService

```java
@Service
@RequiredArgsConstructor
public class BookService {

    // @Transactional(readOnly = true) on read methods:
    // - Tells Hibernate not to dirty-check entities (performance).
    // - Signals to the connection pool that a read-only connection may be used.
    // - Prevents accidental writes within a read method.

    @Transactional(readOnly = true)
    public Page<BookResponse> findAll(Pageable pageable);

    @Transactional(readOnly = true)
    public BookResponse findById(Long id);
    // Throws: ResourceNotFoundException if not found

    @Transactional
    public BookResponse create(CreateBookRequest request);
    // Throws: ResourceNotFoundException if any authorId not found
    //         DuplicateIsbnException if ISBN already exists

    @Transactional
    public BookResponse update(Long id, UpdateBookRequest request);
    // Throws: ResourceNotFoundException if book or any new authorId not found
    //         DuplicateIsbnException if new ISBN collides with another book

    @Transactional
    public void delete(Long id);
    // Throws: ResourceNotFoundException if not found
    //         ActiveReservationExistsException if book has ANY reservation history
    // Logic: check reservationRepository.existsByBookId(bookId) (any status).
    //        If any reservation exists (active, cancelled, or expired), reject
    //        with "Cannot delete a book with reservation history."
    //        This is friendlier than letting the FK ON DELETE RESTRICT fire and
    //        also prevents silent data loss of historical reservation records.
    // Note: FK ON DELETE RESTRICT is still the database-level safety net for
    //       any reservation that application code fails to detect.
}
```

#### AuthorService

```java
@Service
@RequiredArgsConstructor
public class AuthorService {

    @Transactional(readOnly = true)
    public Page<AuthorResponse> findAll(Pageable pageable);

    @Transactional(readOnly = true)
    public AuthorResponse findById(Long id);
    // Throws: ResourceNotFoundException if not found

    @Transactional
    public AuthorResponse create(CreateAuthorRequest request);

    @Transactional
    public AuthorResponse update(Long id, UpdateAuthorRequest request);
    // Throws: ResourceNotFoundException if not found

    @Transactional
    public void delete(Long id);
    // Throws: ResourceNotFoundException if not found
    //         ActiveReservationExistsException if author is the sole author
    //         of at least one book (deletion would orphan that book).
    // Logic: load the author's books via authorRepository.findByIdWithBooks().
    //        For each book associated with this author, call
    //        bookRepository.countAuthorsByBookId(book.getId()).
    //        If any book returns count == 1, the author is its sole author --
    //        reject deletion.
    //        This avoids the N+1 pattern of calling book.getAuthors().size()
    //        (which would trigger a lazy-load per book). The count query is a
    //        single SQL COUNT per book, which is efficient and accurate.
}
```

#### ReservationService

```java
@Service
@RequiredArgsConstructor
public class ReservationService {

    @Transactional
    public ReservationResponse create(CreateReservationRequest request);
    // Throws: ResourceNotFoundException if bookId not found
    //         BookAlreadyReservedException if an unexpired ACTIVE reservation exists
    // Side effect: if an ACTIVE reservation exists but is expired, expires it
    //              within the same transaction before creating the new one.

    @Transactional
    public ReservationResponse cancel(Long reservationId);
    // Throws: ResourceNotFoundException if not found
    //         InvalidReservationStateException if status != ACTIVE

    @Transactional(readOnly = true)
    public ReservationResponse findById(Long id);
    // Throws: ResourceNotFoundException if not found

    @Transactional(readOnly = true)
    public Page<ReservationResponse> findAll(
        Long bookId, String userName, ReservationStatus status, Pageable pageable);
}
```

#### SearchService

```java
@Service
@RequiredArgsConstructor
public class SearchService {

    @Transactional(readOnly = true)
    public Page<SearchResultResponse> search(String query, Pageable pageable);
    // Throws: IllegalArgumentException (-> 400) if query is blank after sanitization
    // Side effect: sanitizes query before passing to native SQL
    //   1. trim()
    //   2. truncate to 500 chars
    //   3. strip null bytes (\u0000) and ASCII control chars (< 0x20, except tab/newline)
    //   4. pass sanitized string to plainto_tsquery (further SQL-safe by design)

    // private method -- not part of the public contract, but documented here
    // for implementation clarity:
    private String sanitizeQuery(String raw);
}
```

#### ReservationProperties (@ConfigurationProperties)

The 14-day reservation duration is currently hardcoded in `ReservationService`. Extracting it to a `@ConfigurationProperties` class makes it configurable without code changes and testable in isolation:

```java
@ConfigurationProperties(prefix = "library.reservation")
public record ReservationProperties(
    @DefaultValue("14") int durationDays
) {}
```

In `application.yml`:
```yaml
library:
  reservation:
    duration-days: 14  # Default reservation duration in days
```

Enable with `@EnableConfigurationProperties(ReservationProperties.class)` on the main application class or a `@Configuration` class. `ReservationService` then receives `ReservationProperties` via constructor injection and uses `properties.durationDays()` instead of the hardcoded literal.
```

**Input validation strategy**:

| Scenario | Where enforced | How |
|---|---|---|
| Blank `firstName` / `lastName` on CreateAuthorRequest | Controller (Bean Validation) | `@NotBlank` on DTO field |
| ISBN format | Controller (Bean Validation) | `@Pattern(regexp = "\\d{13}")` on DTO field |
| `publishedYear` range | Controller (Bean Validation) | `@Min(1000)` + `@Max` (current year) |
| `authorIds` not empty | Controller (Bean Validation) | `@NotEmpty` on the list |
| ISBN uniqueness | Service layer | `bookRepository.existsByIsbn()` check + `DuplicateIsbnException` |
| Author ID existence | Service layer | `authorRepository.findById()` with `ResourceNotFoundException` |
| Active reservation on create | Service layer | `reservationRepository.existsByBookIdAndStatus()` check |
| Search query blank | Service layer | Explicit blank check in `SearchService.sanitizeQuery()` |
| Concurrent constraint violation | DB + Exception handler | Partial unique index + `DataIntegrityViolationException` disambiguation |

**Why not validate ISBN uniqueness in Bean Validation?** A custom `@UniqueIsbn` constraint validator would need to query the database, which couples the validation layer to the persistence layer and makes unit testing awkward. Service-level checks keep the DTO purely structural and the business rule where it belongs.

### 4.8 DTO Design

> *Added in revision 3. Full Java record definitions with Bean Validation annotations.*

**Why Java records?** Records provide immutability by design (all fields are `final`), a canonical constructor, and auto-generated `equals`, `hashCode`, and `toString` without boilerplate. For DTOs that flow in one direction (in to service, or out from service), immutability is exactly the right contract -- nobody should be mutating a request DTO after it has been validated.

#### Request DTOs

```java
// CreateBookRequest.java
public record CreateBookRequest(

    @NotBlank(message = "Title must not be blank")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    String title,

    @NotBlank(message = "ISBN must not be blank")
    @Pattern(regexp = "\\d{13}", message = "ISBN must be exactly 13 digits")
    String isbn,

    @Size(max = 10000, message = "Description must not exceed 10000 characters")
    String description,          // nullable -- no @NotNull

    @Min(value = 1000, message = "Published year must be 1000 or later")
    @Max(value = 2100, message = "Published year must be 2100 or earlier")
    // MVP: hard-coded upper bound. Replace with custom @PastOrPresentYear validator for production.
    Integer publishedYear,       // nullable

    @NotNull(message = "Author ID list must not be null")
    @NotEmpty(message = "At least one author ID is required")
    List<@NotNull Long> authorIds
) {}

// UpdateBookRequest.java
// All fields are optional (nullable). A null field means "do not change this field."
// The service applies only non-null fields to the existing entity.
// Note: an all-null or empty body ({}) is accepted and treated as a no-op -- the book
// is returned unchanged with 200 OK. This is intentional partial-update behaviour.
// Document this explicitly in Swagger @Operation description so API consumers are not
// surprised. A custom @ValidUpdateRequest class-level constraint may be added later to
// require at least one non-null field if the no-op behavior proves confusing.
public record UpdateBookRequest(

    @Size(max = 255, message = "Title must not exceed 255 characters")
    String title,

    @Pattern(regexp = "\\d{13}", message = "ISBN must be exactly 13 digits")
    String isbn,

    @Size(max = 10000, message = "Description must not exceed 10000 characters")
    String description,

    @Min(value = 1000, message = "Published year must be 1000 or later")
    @Max(value = 2100, message = "Published year must be 2100 or earlier")
    // MVP: hard-coded upper bound. Replace with custom @PastOrPresentYear validator for production.
    Integer publishedYear,

    // If non-null, replaces the full author set. Empty list is rejected.
    @Size(min = 1, message = "Author list must contain at least one author ID if provided")
    List<@NotNull Long> authorIds
) {}

// CreateAuthorRequest.java
public record CreateAuthorRequest(

    @NotBlank(message = "First name must not be blank")
    @Size(max = 100, message = "First name must not exceed 100 characters")
    String firstName,

    @NotBlank(message = "Last name must not be blank")
    @Size(max = 100, message = "Last name must not exceed 100 characters")
    String lastName,

    @Size(max = 5000, message = "Bio must not exceed 5000 characters")
    String bio   // nullable, length-bounded to prevent multi-megabyte payloads
) {}

// UpdateAuthorRequest.java
// All fields nullable -- null means "do not change".
// Note: an all-null body is accepted and treated as a no-op (the book is returned unchanged
// with 200). This is consistent with partial-update semantics where the client only sends
// fields it wants to change. Document this behaviour clearly for API consumers.
public record UpdateAuthorRequest(

    @Size(max = 100, message = "First name must not exceed 100 characters")
    String firstName,

    @Size(max = 100, message = "Last name must not exceed 100 characters")
    String lastName,

    @Size(max = 5000, message = "Bio must not exceed 5000 characters")
    String bio
) {}

// CreateReservationRequest.java
public record CreateReservationRequest(

    @NotNull(message = "Book ID is required")
    Long bookId,

    @NotBlank(message = "User name must not be blank")
    @Size(max = 100, message = "User name must not exceed 100 characters")
    String userName
) {}
```

#### Response DTOs

```java
// BookResponse.java
public record BookResponse(
    Long id,
    String title,
    String isbn,
    String description,
    Integer publishedYear,
    List<AuthorSummary> authors,
    Instant createdAt,
    Instant updatedAt
) {
    // Nested record -- used inside BookResponse to avoid circular references.
    // AuthorResponse itself contains a list of book summaries; if we used the
    // full AuthorResponse here we would create Book -> Author -> Book -> ...
    public record AuthorSummary(Long id, String firstName, String lastName) {}
}

// AuthorResponse.java
public record AuthorResponse(
    Long id,
    String firstName,
    String lastName,
    String bio,
    List<BookSummary> books,    // only id and title -- avoids circular nesting
    Instant createdAt,
    Instant updatedAt
) {
    public record BookSummary(Long id, String title) {}
}

// ReservationResponse.java
public record ReservationResponse(
    Long id,
    Long bookId,
    String bookTitle,
    String userName,
    ReservationStatus status,
    Instant reservedAt,
    Instant expiresAt,
    Instant cancelledAt         // null unless status == CANCELLED
) {}

// SearchResultResponse.java
public record SearchResultResponse(
    Long id,
    String title,
    String isbn,
    Integer publishedYear,
    List<BookResponse.AuthorSummary> authors,
    double relevanceScore
) {}

// PagedResponse.java
// Generic wrapper for all paginated list responses.
public record PagedResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean last
) {
    // Static factory method to build from Spring's Page<T>.
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
            page.getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages(),
            page.isLast()
        );
    }
}

// ErrorResponse.java
// @JsonInclude(NON_EMPTY): the fieldErrors list is omitted from the JSON response
// entirely when it is empty, keeping non-validation error responses clean.
// Using List.of() as the default (rather than null) means callers never receive
// a null fieldErrors field -- they either see an omitted field (non-validation
// errors) or a populated list (validation errors). This avoids NPE in clients.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(
    int status,
    String error,
    String message,
    Instant timestamp,
    String path,
    List<FieldError> fieldErrors  // List.of() for non-validation errors (omitted by @JsonInclude)
) {
    // Convenience constructor for non-validation errors -- fieldErrors defaults to empty.
    public ErrorResponse(int status, String error, String message,
                         Instant timestamp, String path) {
        this(status, error, message, timestamp, path, List.of());
    }

    public record FieldError(String field, String message) {}
}
```

---

## 5. Flyway Migrations

Migrations are numbered sequentially and each is small and focused.

| Migration                          | Description                                                       |
|------------------------------------|-------------------------------------------------------------------|
| `V1__create_authors_table.sql`     | Creates `authors` table with id, first_name, last_name, bio, timestamps |
| `V2__create_books_table.sql`       | Creates `books` table with id, title, isbn, description, published_year, version, timestamps. Adds unique index on isbn and B-tree index on title |
| `V3__create_book_authors_table.sql`| Creates `book_authors` join table with composite PK, foreign keys (CASCADE on both) |
| `V4__create_reservations_table.sql`| Creates `reservations` table with **ON DELETE RESTRICT** FK to books, partial unique index on `(book_id) WHERE status = 'ACTIVE'`. This index is the concurrency enforcement mechanism |
| `V5__add_full_text_search.sql`     | Adds `search_vector` column (tsvector) to `books`, creates GIN index, creates trigger to auto-update the vector on INSERT/UPDATE of title or description |
| `V6__seed_sample_data.sql`         | Inserts 10-15 sample books and 5-8 authors for demo/Swagger walkthrough purposes |

**Migration ordering rationale**: Authors before books (because books reference authors via join table), books before reservations (because reservations reference books), search after core tables are established, seed data last.

---

## 6. API Endpoints

Base path: `/api/v1`

### 6.1 Books

| Method | Path                 | Description            | Request Body         | Response        | Status Codes       |
|--------|----------------------|------------------------|----------------------|-----------------|--------------------|
| GET    | `/books`             | List all books (paginated) | Query: `page`, `size`, `sort` | `PagedResponse<BookResponse>` | 200              |
| GET    | `/books/{id}`        | Get book by ID         | --                   | `BookResponse`  | 200, 404           |
| POST   | `/books`             | Create a new book      | `CreateBookRequest`  | `BookResponse`  | 201, 400, 409      |
| PUT    | `/books/{id}`        | Update a book          | `UpdateBookRequest`  | `BookResponse`  | 200, 400, 404, 409 |
| DELETE | `/books/{id}`        | Delete a book          | --                   | --              | 204, 404, **409**  |

**DELETE returns 409** when the book has any reservation history (active, cancelled, or expired). The `BookService.delete()` method calls `reservationRepository.existsByBookId(bookId)` and throws `ActiveReservationExistsException` with the message "Cannot delete a book with reservation history." The FK ON DELETE RESTRICT provides a database-level safety net as a final backstop.

**CreateBookRequest**:
```json
{
  "title": "string (required, 1-255 chars)",
  "isbn": "string (required, valid ISBN-13)",
  "description": "string (optional)",
  "publishedYear": "integer (optional, 1000-current year)",
  "authorIds": ["array of Long (required, at least one)"]
}
```

**UpdateBookRequest**:
```json
{
  "title": "string (optional)",
  "isbn": "string (optional, valid ISBN-13)",
  "description": "string (optional)",
  "publishedYear": "integer (optional)",
  "authorIds": ["array of Long (optional)"]
}
```

**BookResponse**:
```json
{
  "id": 1,
  "title": "Effective Java",
  "isbn": "9780134685991",
  "description": "...",
  "publishedYear": 2018,
  "authors": [
    { "id": 1, "firstName": "Joshua", "lastName": "Bloch" }
  ],
  "createdAt": "2026-02-21T10:00:00Z",
  "updatedAt": "2026-02-21T10:00:00Z"
}
```

### 6.2 Authors

| Method | Path                 | Description              | Request Body           | Response          | Status Codes  |
|--------|----------------------|--------------------------|------------------------|-------------------|---------------|
| GET    | `/authors`           | List all authors (paginated) | Query: `page`, `size`  | `PagedResponse<AuthorResponse>` | 200 |
| GET    | `/authors/{id}`      | Get author by ID         | --                     | `AuthorResponse`  | 200, 404      |
| POST   | `/authors`           | Create a new author      | `CreateAuthorRequest`  | `AuthorResponse`  | 201, 400      |
| PUT    | `/authors/{id}`      | Update an author         | `UpdateAuthorRequest`  | `AuthorResponse`  | 200, 400, 404 |
| DELETE | `/authors/{id}`      | Delete an author         | --                     | --                | 204, 404, **409** |

**DELETE returns 409** when the author is the sole author of any book. `AuthorService.delete()` checks: for each book associated with this author, does the book have at least one other author? If any book would be left with zero authors, the deletion is rejected.

**CreateAuthorRequest**:
```json
{
  "firstName": "string (required, 1-100 chars)",
  "lastName": "string (required, 1-100 chars)",
  "bio": "string (optional)"
}
```

**AuthorResponse** includes a list of book summaries (id and title only) to avoid circular nesting.

### 6.3 Search

| Method | Path                 | Description                        | Params               | Response                          | Status Codes |
|--------|----------------------|------------------------------------|-----------------------|-----------------------------------|--------------|
| GET    | `/search`            | Full-text search across books and authors | Query: `q` (required), `page`, `size` | `PagedResponse<SearchResultResponse>` | 200, 400  |

**Query parameter details** (used directly as `@RequestParam` -- no DTO):
- `q` -- The search query string. Minimum 1 character, maximum 500 characters (truncated silently if exceeded). Supports multiple terms (implicit AND). Example: `q=effective java`
- `page` -- Page number, 0-indexed, default 0.
- `size` -- Page size, default 20, max 100.
- `sort` -- **NOT SUPPORTED**. Search results are always sorted by `relevance_score DESC` from the native SQL query. The Spring Data `Pageable.sort` parameter is silently ignored. Document this explicitly in the `@Operation` description for the search endpoint so API consumers are not confused. Do not expose `sort` as an accepted parameter in the Swagger UI.

**Input sanitization**: The `SearchService` validates and sanitizes the query:
1. Trim whitespace
2. Truncate to 500 characters
3. Strip null bytes and control characters
4. Pass to `plainto_tsquery` which safely handles remaining special characters

**SearchResultResponse**:
```json
{
  "id": 1,
  "title": "Effective Java",
  "isbn": "9780134685991",
  "publishedYear": 2018,
  "authors": [
    { "id": 1, "firstName": "Joshua", "lastName": "Bloch" }
  ],
  "relevanceScore": 0.85
}
```

Results are sorted by relevance score descending (most relevant first), using PostgreSQL's `ts_rank`.

### 6.4 Reservations

| Method | Path                              | Description                  | Request Body              | Response              | Status Codes        |
|--------|-----------------------------------|------------------------------|---------------------------|-----------------------|---------------------|
| POST   | `/reservations`                   | Create a reservation         | `CreateReservationRequest`| `ReservationResponse` | 201, 400, 404, 409  |
| PATCH  | `/reservations/{id}/cancel`       | Cancel a reservation         | --                        | `ReservationResponse` | 200, 404, 409       |
| GET    | `/reservations`                   | List reservations (filtered) | Query: `bookId`, `userName`, `status`, `page`, `size` | `PagedResponse<ReservationResponse>` | 200 |
| GET    | `/reservations/{id}`              | Get reservation by ID        | --                        | `ReservationResponse` | 200, 404            |

**CreateReservationRequest**:
```json
{
  "bookId": "long (required)",
  "userName": "string (required, 1-100 chars)"
}
```

**ReservationResponse**:
```json
{
  "id": 1,
  "bookId": 10,
  "bookTitle": "Effective Java",
  "userName": "alice",
  "status": "ACTIVE",
  "reservedAt": "2026-02-21T10:00:00Z",
  "expiresAt": "2026-03-07T10:00:00Z",
  "cancelledAt": null
}
```

**Status 409 on POST**: Returned when the book already has an ACTIVE reservation. The response body includes a clear error message indicating who currently holds the reservation and when it expires.

**Status 409 on PATCH /cancel**: Returned when trying to cancel a reservation that is already CANCELLED or EXPIRED.

**Why PATCH not DELETE for cancel**: The reservation resource is not deleted — it persists in CANCELLED state for historical record-keeping. The FK `ON DELETE RESTRICT` on `reservations.book_id` also prevents hard deletion while the book exists. `PATCH /reservations/{id}/cancel` correctly expresses a partial state-transition update. Using `DELETE` with a response body (as in earlier revisions) was semantically incorrect: HTTP `DELETE` implies resource removal, and many HTTP clients discard `DELETE` response bodies.

### 6.5 Error Handling Strategy

All errors follow a consistent shape (see Appendix). The `GlobalExceptionHandler` uses `@RestControllerAdvice` and handles:

| Exception                            | HTTP Status | When                                               |
|--------------------------------------|-------------|-----------------------------------------------------|
| `ResourceNotFoundException`          | 404         | Entity not found by ID                               |
| `DuplicateIsbnException`             | 409         | ISBN already exists                                  |
| `BookAlreadyReservedException`       | 409         | Book has an ACTIVE reservation (on reserve attempt)  |
| `ActiveReservationExistsException`   | 409         | Book or author deletion blocked by active reservation |
| `InvalidReservationStateException`   | 409         | Cancelling an already cancelled/expired reservation  |
| `MethodArgumentNotValidException`    | 400         | Jakarta Bean Validation failures (see mapping below) |
| `HttpMessageNotReadableException`    | 400         | Malformed JSON request body (missing braces, wrong types) |
| `MethodArgumentTypeMismatchException`| 400         | Non-numeric value for a path variable (e.g. `/books/abc`) |
| `DataIntegrityViolationException`    | 409 or 400  | DB constraint violation (see disambiguation below)   |
| `OptimisticLockingFailureException`  | 409         | Concurrent modification detected -- "Resource was modified by another request. Please retry." |
| `Exception` (fallback)              | 500         | Unexpected errors, logged at ERROR level             |

**DataIntegrityViolationException disambiguation**: The handler inspects the underlying `ConstraintViolationException` to determine the constraint name:
- Constraint `idx_reservations_active_book` -> 409 "Book already has an active reservation" (race condition survivor)
- Constraint `idx_books_isbn` -> 409 "ISBN already exists"
- Other constraints -> 400 "Data integrity violation" with generic message

```java
@ExceptionHandler(DataIntegrityViolationException.class)
public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex,
                                                         HttpServletRequest request) {
    String constraintName = extractConstraintName(ex); // extract from nested cause
    if ("idx_reservations_active_book".equals(constraintName)) {
        return ResponseEntity.status(409).body(
            new ErrorResponse(409, "Conflict", "Book already has an active reservation",
                              Instant.now(), request.getRequestURI()));
    }
    if ("idx_books_isbn".equals(constraintName)) {
        return ResponseEntity.status(409).body(
            new ErrorResponse(409, "Conflict", "ISBN already exists",
                              Instant.now(), request.getRequestURI()));
    }
    // IMPORTANT: never include ex.getMessage() or ex.getCause().getMessage() in the
    // response body -- these strings contain internal table names, column names, and
    // constraint names that must not be exposed to API clients.
    return ResponseEntity.status(400).body(
        new ErrorResponse(400, "Bad Request", "Data integrity violation",
                          Instant.now(), request.getRequestURI()));
}

@ExceptionHandler(HttpMessageNotReadableException.class)
public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                      HttpServletRequest request) {
    return ResponseEntity.status(400).body(
        new ErrorResponse(400, "Bad Request", "Malformed request body",
                          Instant.now(), request.getRequestURI()));
}

@ExceptionHandler(MethodArgumentTypeMismatchException.class)
public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                        HttpServletRequest request) {
    String msg = String.format("Invalid value '%s' for parameter '%s'",
                               ex.getValue(), ex.getName());
    return ResponseEntity.status(400).body(
        new ErrorResponse(400, "Bad Request", msg, Instant.now(), request.getRequestURI()));
}

@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                      HttpServletRequest request) {
    List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors()
        .stream()
        .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
        .toList();
    return ResponseEntity.status(400).body(
        new ErrorResponse(400, "Bad Request", "Validation failed",
                          Instant.now(), request.getRequestURI(), fieldErrors));
}

// Private helper: extracts the constraint name from the nested Hibernate
// ConstraintViolationException. Returns null if the cause chain does not
// contain a ConstraintViolationException (null-safe navigation).
// IMPORTANT: the extracted name is compared against known constraint names.
// It is NEVER included in error response bodies.
private String extractConstraintName(DataIntegrityViolationException ex) {
    if (ex.getCause() instanceof org.hibernate.exception.ConstraintViolationException cve) {
        return cve.getConstraintName();
    }
    return null;
}
```

---

## 7. Search Implementation

### 7.1 Approach: PostgreSQL Full-Text Search (tsvector/tsquery)

PostgreSQL's built-in full-text search is chosen over simple LIKE/ILIKE queries because:
- It supports stemming (searching "running" matches "run").
- It supports ranking via `ts_rank`.
- It requires no external dependencies (no Elasticsearch or Solr).
- It is sufficient for the MVP scale.

**Decision: No prefix matching.** We use `plainto_tsquery` exclusively, which does NOT support prefix matching (e.g., "Effec" will NOT match "Effective"). This is a deliberate trade-off:
- `plainto_tsquery` safely handles all user input without manual sanitization
- `to_tsquery` with `:*` prefix requires careful input escaping to prevent syntax errors
- For an MVP, full-word search with stemming is sufficient
- Documented as a known limitation (see Section 12)

### 7.2 Implementation Details

**Migration V5** adds the following to the `books` table:

```sql
-- Add tsvector column
ALTER TABLE books ADD COLUMN search_vector tsvector;

-- Create a function that builds the search vector from book title + description
CREATE OR REPLACE FUNCTION books_search_vector_update() RETURNS trigger AS $$
BEGIN
  NEW.search_vector :=
    setweight(to_tsvector('english', COALESCE(NEW.title, '')), 'A') ||
    setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'C');
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger on book insert/update
CREATE TRIGGER trg_books_search_vector
  BEFORE INSERT OR UPDATE OF title, description ON books
  FOR EACH ROW EXECUTE FUNCTION books_search_vector_update();

-- GIN index for fast full-text lookups
CREATE INDEX idx_books_search_vector ON books USING GIN (search_vector);

-- Backfill search_vector for any existing rows
UPDATE books SET search_vector =
  setweight(to_tsvector('english', COALESCE(title, '')), 'A') ||
  setweight(to_tsvector('english', COALESCE(description, '')), 'C');
```

**Important note on search_vector scope**: The stored `search_vector` column contains only title and description (Weight A and C). Author names are in a separate table and cannot be included in the trigger. Author-name search is computed at query time via the JOIN. This is an architectural decision, not a bug.

### 7.3 Search Query (corrected for multi-author books)

The repository method uses a native query that properly aggregates across multiple authors:

```sql
SELECT b.id, b.title, b.isbn, b.published_year,
       ts_rank(b.search_vector, plainto_tsquery('english', :query)) AS book_rank,
       MAX(ts_rank(
         to_tsvector('english', COALESCE(a.first_name || ' ' || a.last_name, '')),
         plainto_tsquery('english', :query)
       )) AS author_rank,
       GREATEST(
         ts_rank(b.search_vector, plainto_tsquery('english', :query)),
         MAX(ts_rank(
           to_tsvector('english', COALESCE(a.first_name || ' ' || a.last_name, '')),
           plainto_tsquery('english', :query)
         ))
       ) AS relevance_score
FROM books b
LEFT JOIN book_authors ba ON b.id = ba.book_id
LEFT JOIN authors a ON ba.author_id = a.id
WHERE b.search_vector @@ plainto_tsquery('english', :query)
   OR to_tsvector('english', COALESCE(a.first_name || ' ' || a.last_name, ''))
      @@ plainto_tsquery('english', :query)
GROUP BY b.id, b.title, b.isbn, b.published_year, b.search_vector
ORDER BY relevance_score DESC
```

**Key difference from v1**: `MAX()` wraps the author rank computation so that when a book has multiple authors, the highest-matching author rank is used. The `GROUP BY` includes all non-aggregated SELECT columns. Each book appears exactly once in the results.

**Count query for pagination**: When the search is implemented via `SearchService` using `EntityManager.createNativeQuery()`, a separate count query must be executed to populate `Page.totalElements`. Spring Data cannot derive a count query from a complex native query automatically. The explicit count query is:

```sql
SELECT COUNT(DISTINCT b.id)
FROM books b
LEFT JOIN book_authors ba ON b.id = ba.book_id
LEFT JOIN authors a ON ba.author_id = a.id
WHERE b.search_vector @@ plainto_tsquery('english', :query)
   OR to_tsvector('english', COALESCE(a.first_name || ' ' || a.last_name, ''))
      @@ plainto_tsquery('english', :query)
```

`COUNT(DISTINCT b.id)` ensures that books appearing multiple times in the join (due to multiple authors) are counted only once. This count query is passed as the `countQuery` parameter when using `@Query` with `nativeQuery = true`, or executed separately and used to construct a `PageImpl` when using `EntityManager` directly.

### 7.4 Ranking Strategy

- **Weight A** (highest): Book title -- a title match is the strongest signal.
- **Weight C** (moderate): Book description.
- **Author name match**: Computed at query time via `MAX(ts_rank(...))`, ranked as high as title match.
- `GREATEST()` is used so that a book ranks well whether the match is in the title or the author name.

### 7.5 Edge Cases to Handle

| Edge Case                                    | Expected Behavior                                          |
|----------------------------------------------|------------------------------------------------------------|
| Empty query string                           | Return 400 Bad Request with validation error               |
| Query with only stop words ("the", "a")      | Return 200 with empty results (PostgreSQL drops stop words)|
| Query matching no books or authors           | Return 200 with empty results list                        |
| Query matching author name but not title     | Return books by that author, ranked by author name match   |
| Partial word match ("Effec")                 | **No match** -- plainto_tsquery requires full words (known limitation) |
| Special characters in query                  | Sanitized; `plainto_tsquery` handles this safely           |
| Book with multiple authors, one matches      | Book appears once with the highest author's relevance score |
| Very long query string (>500 chars)          | Truncated to 500 characters in `SearchService` before processing |
| Accented characters                          | Out of scope for MVP; document as known limitation         |
| Null bytes / control characters              | Stripped in `SearchService` before passing to query        |

### 7.6 Fallback Decision Point

**If full-text search is not working by end of Day 4 morning**, switch immediately to ILIKE-based search with B-tree index. This is still valid per the spec ("full-text search **or** simple indexed search"). The ILIKE fallback:

```sql
SELECT b.* FROM books b
LEFT JOIN book_authors ba ON b.id = ba.book_id
LEFT JOIN authors a ON ba.author_id = a.id
WHERE LOWER(b.title) LIKE LOWER(CONCAT('%', :query, '%'))
   OR LOWER(a.first_name || ' ' || a.last_name) LIKE LOWER(CONCAT('%', :query, '%'))
GROUP BY b.id
ORDER BY CASE WHEN LOWER(b.title) LIKE LOWER(CONCAT('%', :query, '%')) THEN 0 ELSE 1 END,
         b.title
```

Do NOT spend Day 5 morning still debugging tsvector. Make the decision and move on.

---

## 8. Concurrency Handling

This is a **critical acceptance criterion**. The system must correctly handle concurrent reservation attempts for the same book.

### 8.1 Strategy: Partial Unique Index (Primary) + Optimistic Locking (Secondary)

**Layer 1 -- Database Constraint (the safety net)**:

```sql
CREATE UNIQUE INDEX idx_reservations_active_book
  ON reservations (book_id)
  WHERE status = 'ACTIVE';
```

This partial unique index ensures that the database itself will reject any INSERT that would create a second ACTIVE reservation for the same book. Even if the application-level check has a race condition, the DB constraint catches it. This is the most robust approach because it is immune to application-level bugs.

**Layer 2 -- Application Check (friendly error handling)**:

Before inserting, the `ReservationService` queries:
```java
boolean exists = reservationRepository.existsByBookIdAndStatus(bookId, ReservationStatus.ACTIVE);
if (exists) {
    throw new BookAlreadyReservedException(bookId);
}
```

This catches the common case and returns a clean 409 error with a descriptive message. Without this, the DB constraint violation would bubble up as a less friendly error.

**Layer 3 -- Exception Translation (race condition survivor)**:

If two requests pass the application check simultaneously and one hits the DB constraint, the `GlobalExceptionHandler` catches the `DataIntegrityViolationException`, inspects the constraint name (`idx_reservations_active_book`), and translates it to a 409 with the same friendly message. See Section 5.5 for the disambiguation logic.

**Layer 4 -- Optimistic Locking on Reservation**:

The `@Version` field on `Reservation` prevents lost updates when cancelling (e.g., two threads trying to cancel the same reservation simultaneously).

### 8.2 Transaction Boundaries

**Critical**: The reservation creation flow MUST be explicitly transactional.

```java
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final BookRepository bookRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;

    @Transactional
    public ReservationResponse create(CreateReservationRequest request) {
        // 1. Verify book exists
        Book book = bookRepository.findById(request.bookId())
            .orElseThrow(() -> new ResourceNotFoundException("Book", request.bookId()));

        // 2. Check for existing active reservation and handle expiration.
        // IMPORTANT: use findByBookIdAndStatusForUpdate (pessimistic write lock)
        // rather than findByBookIdAndStatus here. Without the lock, two threads
        // can both read the same expired reservation, both mark it EXPIRED, and
        // both proceed to INSERT a new ACTIVE reservation -- the partial unique
        // index will reject the second INSERT, but this creates unnecessary
        // contention. The pessimistic lock serializes this path: the second
        // thread blocks until the first commits, then re-reads the now-EXPIRED
        // (or ACTIVE) row and makes the correct decision.
        Optional<Reservation> existing = reservationRepository
            .findByBookIdAndStatusForUpdate(request.bookId(), ReservationStatus.ACTIVE);

        if (existing.isPresent()) {
            Reservation active = existing.get();
            if (active.getExpiresAt().isBefore(Instant.now())) {
                // Expire the stale reservation within this same transaction.
                // The explicit save() call is kept for clarity; Hibernate dirty-checking
                // would flush this UPDATE automatically at commit time for a managed entity,
                // but the explicit call makes the intent clear to the reader.
                active.setStatus(ReservationStatus.EXPIRED);
                reservationRepository.save(active);
            } else {
                throw new BookAlreadyReservedException(request.bookId());
            }
        }

        // 3. Create new reservation.
        // Capture Instant.now() once to ensure reservedAt and expiresAt are
        // consistent: two separate now() calls could straddle a clock tick.
        Instant now = Instant.now();
        Reservation reservation = new Reservation();
        reservation.setBook(book);
        reservation.setUserName(request.userName());
        reservation.setStatus(ReservationStatus.ACTIVE);
        reservation.setReservedAt(now);
        reservation.setExpiresAt(now.plus(14, ChronoUnit.DAYS));

        Reservation saved = reservationRepository.save(reservation);
        // If two threads reach step 3 simultaneously, the partial unique index
        // ensures only one INSERT succeeds. The other gets DataIntegrityViolationException,
        // which is translated to 409 by GlobalExceptionHandler.
        return ReservationMapper.toResponse(saved);
    }

    @Transactional
    public ReservationResponse cancel(Long reservationId) {
        Reservation reservation = reservationRepository.findByIdWithBook(reservationId)
            .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));

        if (reservation.getStatus() != ReservationStatus.ACTIVE) {
            throw new InvalidReservationStateException(reservationId, reservation.getStatus());
        }

        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelledAt(Instant.now());
        // Explicit save for clarity; dirty-checking handles the flush automatically.
        return ReservationMapper.toResponse(reservationRepository.save(reservation));
    }
}
```

**Why the expire-then-create must be in one transaction**: If the expiration and creation were separate transactions, Thread A could expire the reservation, then Thread B could create a new one before Thread A creates its own -- resulting in Thread A getting a constraint violation despite doing the right thing. By wrapping it in `@Transactional`, the expire and create happen atomically. The partial unique index is still the final safety net for true simultaneous attempts.

**Other transactional methods**:
- `BookService.delete()` -- must be `@Transactional` to check reservations and delete atomically
- `AuthorService.delete()` -- must be `@Transactional` to check sole-author status and delete atomically

### 8.3 Reservation Lifecycle

```
                    +--------+
     POST /reserve  | ACTIVE |
     -------------->|        |
                    +----+---+
                         |
            +------------+------------+
            |                         |
PATCH /{id}/cancel          Expiration (on create)
            |                         |
            v                         v
      +-----------+            +-----------+
      | CANCELLED |            |  EXPIRED  |
      +-----------+            +-----------+
```

- **Creation**: POST with bookId and userName. Sets `reserved_at = now()` and `expires_at = now() + 14 days`. Status = ACTIVE. Wrapped in `@Transactional`.
- **Cancellation**: `PATCH /reservations/{id}/cancel`. Sets `status = CANCELLED`, `cancelled_at = now()`. Wrapped in `@Transactional`. The reservation record is retained (not deleted) for historical audit.
- **Expiration**: Handled on-read during the reservation creation flow only (see Section 8.2 step 2). When creating a new reservation, if the existing active reservation has expired, it is updated to EXPIRED within the same transaction before the new one is created. `findById` and `findAll` are `@Transactional(readOnly = true)` and therefore do NOT expire reservations -- attempting a write inside a readOnly transaction throws `InvalidDataAccessApiUsageException`. These methods return the reservation's stored status as-is; clients may see stale ACTIVE status for an expired reservation until the next create-reservation call triggers expiration.

### 8.4 Pessimistic Locking: Targeted Use vs. General Strategy

**Pessimistic locking is NOT used as the general concurrency strategy**, but IS used on the expire-then-create path to prevent TOCTOU races.

**General strategy (partial unique index)**: For the common case of two threads racing to reserve a book that has no existing reservation, the partial unique index (`idx_reservations_active_book`) is the primary mechanism. It is simpler, requires no explicit lock management, works across multiple application instances, and has lower overhead for the non-contended path.

**Targeted pessimistic lock (expire-then-create path only)**: `findByBookIdAndStatusForUpdate` uses `@Lock(LockModeType.PESSIMISTIC_WRITE)` exclusively when checking for an existing ACTIVE reservation that may need to be expired. The risk here is a TOCTOU race:
- Thread A reads expired reservation, proceeds to mark it EXPIRED.
- Thread B also reads the same expired reservation before A commits, also marks it EXPIRED.
- Both A and B now try to INSERT a new ACTIVE reservation.
- The partial unique index rejects B's INSERT (correct), but the double-expiration of the reservation is wasteful and creates surprising retry behavior.

The pessimistic lock serializes this specific path. Thread B blocks at the `SELECT ... FOR UPDATE` until Thread A commits, then re-reads the row (now EXPIRED) and proceeds correctly. The partial unique index remains the final safety net for any case that slips through.

**Why not pessimistic locking everywhere**: For the general reservation path (no existing ACTIVE reservation at all), there is no row to lock against. The partial unique index approach is simpler, requires no explicit lock management, and works even across multiple application instances.

---

## 9. Testing Strategy

### 9.1 Test Data Cleanup

All integration tests extend `AbstractIntegrationTest` which uses a shared Testcontainers PostgreSQL instance. To prevent test pollution, a **single unified cleanup strategy** is enforced:

```java
@Sql(scripts = "/test-cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
```

The `test-cleanup.sql` file (placed at `src/test/resources/test-cleanup.sql`):

```sql
TRUNCATE reservations, book_authors, books, authors RESTART IDENTITY CASCADE;
```

`RESTART IDENTITY` resets the `BIGSERIAL` sequences on all tables back to 1 after each test. Without it, sequences continue incrementing across tests, making IDs non-deterministic across test runs. Tests must never assert on specific ID values (e.g., `assertThat(response.id()).isEqualTo(1L)`) because sequence values depend on test execution order -- but resetting sequences still provides a cleaner state and avoids sequence exhaustion in long test suite runs.

This annotation is declared on `AbstractIntegrationTest` so all subclasses inherit it automatically. Using a single strategy prevents the inconsistency and confusion that arises from mixing `@Transactional` rollback, SQL truncation, and repository-based deletion in different test classes.

**Exception -- ReservationConcurrencyTest**: This test class explicitly overrides cleanup behavior because it spawns multiple threads that each commit their own transactions. `@Transactional` rollback cannot span thread boundaries, and the `@Sql` cleanup fires after the test method completes -- which is after all threads have finished. The `TRUNCATE` approach still applies here; the concurrency test simply performs its own explicit pre-test setup and relies on the inherited `@Sql` post-method cleanup.

### 9.2 Unit Tests (JUnit 5 + Mockito)

Unit tests cover service-layer logic with all dependencies mocked.

| Test Class              | What It Tests                                                   |
|-------------------------|-----------------------------------------------------------------|
| `BookServiceTest`       | CRUD logic, validation, ISBN duplicate detection, author association, delete-with-active-reservation check |
| `AuthorServiceTest`     | CRUD logic, validation, delete-when-sole-author check           |
| `ReservationServiceTest`| Create reservation (happy path), create when already reserved (409), create when existing expired (expire-then-create), cancel (happy path), cancel already cancelled (409), transaction boundary assumptions |
| `SearchServiceTest`     | Query sanitization (truncation, null bytes), empty query handling, result mapping, ranking order |

**Mocking approach**:
- All unit test classes must be annotated with `@ExtendWith(MockitoExtension.class)`. Without this annotation, `@Mock` and `@InjectMocks` are not processed and all injected fields remain null, causing NullPointerExceptions in every test.
- Repository layer is mocked with `@Mock` / `when(...).thenReturn(...)`.
- Services are tested via `@InjectMocks`. This works correctly with constructor injection because Mockito's `@InjectMocks` invokes the constructor with the declared `@Mock` instances.
- No Spring context is loaded for unit tests (fast execution).

**Unit test class structure** (example for `BookServiceTest`):
```java
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
    void createBook_withValidRequest_returnsBookResponse() { ... }
}
```

**Mapper tests are not included**. Mappers are trivial static methods (entity -> record, record -> entity). Their correctness is verified implicitly through integration tests that assert on response bodies. Dedicated mapper tests would be over-testing for no MVP benefit.

### 9.3 Integration Tests (Testcontainers + Spring Boot Test)

Integration tests verify end-to-end behavior against a real PostgreSQL instance.

**Base class** (`AbstractIntegrationTest`):
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
// @ActiveProfiles("test") ensures that application-test.yml is loaded during
// integration tests. Without this annotation the test profile is not activated
// automatically, even though application-test.yml exists in test resources.
@ActiveProfiles("test")
// Unified cleanup strategy: truncate all tables after every test method.
// Declared here so all subclasses inherit it without repeating the annotation.
// Exception: ReservationConcurrencyTest commits across threads -- the @Sql
// cleanup still fires after the test method, which is correct because all
// threads have completed by then.
@Sql(scripts = "/test-cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
public abstract class AbstractIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("library_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    protected TestRestTemplate restTemplate;
}
```

| Test Class                    | What It Tests                                                    |
|-------------------------------|------------------------------------------------------------------|
| `BookIntegrationTest`         | Full CRUD cycle via HTTP: create, read, update, delete. Verifies 404 for missing book. Verifies 409 for duplicate ISBN. Verifies 409 for deleting book with active reservation. |
| `AuthorIntegrationTest`       | Full CRUD cycle via HTTP. Verifies 409 when deleting sole author of a book. Verifies successful deletion when author has co-authors on all books. |
| `SearchIntegrationTest`       | Seeds data, performs searches, validates ranking order. Tests edge cases: empty results, stop-word-only queries, author-name search, multi-author book appears once. |
| `ReservationIntegrationTest`  | Full reservation lifecycle: create -> verify active -> cancel -> verify cancelled. Tests creating reservation for non-existent book (404). Tests creating reservation for already-reserved book (409). Tests expire-then-create flow. |
| `ReservationConcurrencyTest`  | **Critical test**. Uses `ExecutorService` with multiple threads to simultaneously reserve the same book. Verifies exactly one succeeds and all others get 409. |

### 9.4 Concurrency Test Detail

```java
@Test
void whenMultipleThreadsReserveSameBook_thenExactlyOneSucceeds() throws Exception {
    // Given: a book exists with no reservation
    Long bookId = createTestBook();

    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger conflictCount = new AtomicInteger(0);
    // errorCount tracks unexpected exceptions (not 409 conflicts).
    // After the test, errorCount must be 0 -- any unexpected error
    // indicates a bug in the concurrency handling, not expected contention.
    AtomicInteger errorCount = new AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
        final String userName = "user-" + i;
        executor.submit(() -> {
            try {
                startLatch.await(); // All threads start simultaneously
                ResponseEntity<String> response = restTemplate.postForEntity(
                    "/api/v1/reservations",
                    new CreateReservationRequest(bookId, userName),
                    String.class
                );
                if (response.getStatusCode().value() == 201) {
                    successCount.incrementAndGet();
                } else if (response.getStatusCode().value() == 409) {
                    conflictCount.incrementAndGet();
                } else {
                    // Any other status code is an unexpected error.
                    errorCount.incrementAndGet();
                }
            } catch (Exception e) {
                // Network or client errors -- not expected in a healthy test run.
                errorCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });
    }

    startLatch.countDown(); // Release all threads
    // Assert that the latch reaches zero (all threads completed) within
    // the timeout. If it returns false, some threads are hanging.
    boolean allCompleted = doneLatch.await(10, TimeUnit.SECONDS);
    executor.shutdown();
    // awaitTermination ensures all thread pool threads have fully stopped
    // (including any network activity completing after doneLatch.countDown())
    // before assertions run. Without this, threads may still be active when
    // assertions execute, leading to non-deterministic results.
    executor.awaitTermination(5, TimeUnit.SECONDS);

    assertThat(allCompleted)
        .as("All threads should complete within the timeout")
        .isTrue();
    assertThat(errorCount.get())
        .as("No unexpected errors should occur -- only 201 or 409 responses expected")
        .isEqualTo(0);
    assertThat(successCount.get()).isEqualTo(1);
    assertThat(conflictCount.get()).isEqualTo(threadCount - 1);
}
```

### 9.5 Edge Cases Covered by Tests

| Category     | Edge Case                                     | Test Type    |
|--------------|-----------------------------------------------|-------------|
| Books        | Create book with empty title                  | Unit        |
| Books        | Create book with duplicate ISBN               | Integration |
| Books        | Update book with ISBN that collides with another book | Integration |
| Books        | Delete book that has an active reservation    | Integration |
| Books        | Delete book that has only historical reservations | Integration |
| Books        | Get book that does not exist                  | Integration |
| Authors      | Delete author who is sole author of a book    | Integration |
| Authors      | Delete author who co-authored all their books | Integration |
| Authors      | Create author with blank first name           | Unit        |
| Search       | Search with stop words only                   | Integration |
| Search       | Search matching author name only              | Integration |
| Search       | Search with no results                        | Integration |
| Search       | Search ranking: title match > description match | Integration |
| Search       | Multi-author book appears once in results     | Integration |
| Search       | Query exceeding 500 chars is truncated        | Unit        |
| Reservations | Reserve an already-reserved book              | Integration |
| Reservations | Cancel an already-cancelled reservation       | Unit + Integration |
| Reservations | Reserve a book that does not exist            | Integration |
| Reservations | Concurrent reservation (10 threads)           | Integration |
| Reservations | Reserve book with expired (stale) active reservation | Integration |
| Reservations | Reservation expires on read                   | Unit        |

---

## 10. Day-by-Day Breakdown

### Day 1: Git Init, Project Scaffold, Docker Compose, Flyway, Entity Layer

**Goal**: A git repository with a running Spring Boot application connected to PostgreSQL with all tables created.

**Tasks**:

- [ ] **`git init`** -- Initialize the git repository. This is literally the first action.
- [ ] Initialize Spring Boot 3 project with Gradle (Kotlin DSL) via Spring Initializr or manually
  - Dependencies: spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-validation, spring-boot-starter-actuator, flyway-core, flyway-database-postgresql, postgresql driver, springdoc-openapi-starter-webmvc-ui, lombok (compileOnly + annotationProcessor)
  - Local dev: spring-boot-devtools (developmentOnly)
  - Test dependencies: spring-boot-starter-test, testcontainers, testcontainers-postgresql, testcontainers-junit-jupiter
  - Verify `application-test.yml` is in `src/test/resources/` ONLY (not in `src/main/resources/`)
- [ ] Create `docker-compose.yml` with PostgreSQL 16 service
  - Port mapping: 5432:5432
  - Volume for data persistence across restarts
  - Environment variables: POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD
- [ ] Configure `application.yml` for local dev (pointing to Docker Compose Postgres)
- [ ] Configure `application-test.yml` for Testcontainers (placeholder, overridden by `@DynamicPropertySource`)
- [ ] Create `BaseEntity` with `@PrePersist` / `@PreUpdate` lifecycle callbacks for `created_at` / `updated_at`
- [ ] Write Flyway migrations V1 through V4 (authors, books, book_authors, reservations tables)
  - V4 must use `ON DELETE RESTRICT` for reservation -> book FK
- [ ] Create JPA entity classes: `Author`, `Book` (with `@ManyToMany`), `Reservation`, `ReservationStatus`
- [ ] Create `AbstractIntegrationTest` base class with Testcontainers setup
- [ ] Write `LibraryCatalogApplicationTests` to verify application context loads and Flyway runs
- [ ] Verify: `docker compose up -d` starts Postgres, `./gradlew bootRun` starts the app, Flyway creates all tables, `/actuator/health` returns UP
- [ ] Create `CLAUDE.md` with project conventions and rules
- [ ] Create empty `automated-claude-code-agent-log.md` with format template
- [ ] First git commit

**Deliverable**: Running application, tables created, one passing integration test (context loads), git history started.

---

### Day 2: Author CRUD API with TDD

**Goal**: Fully working Author CRUD endpoints with tests written first.

**Tasks**:

- [ ] Write `AuthorServiceTest` (unit tests first, all red) -- annotate with `@ExtendWith(MockitoExtension.class)`:
  - Test create author happy path
  - Test create author with blank name (validation)
  - Test get author by ID (found)
  - Test get author by ID (not found -> exception)
  - Test update author happy path
  - Test delete author (no books)
  - Test delete author who is sole author of a book (-> 409)
  - Test delete author who co-authored all books (-> success)
- [ ] Implement `AuthorService` to make unit tests green (with `@Transactional` on delete)
- [ ] Create DTOs: `CreateAuthorRequest`, `UpdateAuthorRequest`, `AuthorResponse` (Java records)
- [ ] Create `AuthorMapper` (static methods)
- [ ] Create `AuthorRepository` (Spring Data JPA interface)
- [ ] Create `AuthorController` with `@Valid` annotations
- [ ] Create `GlobalExceptionHandler` with `ResourceNotFoundException` and `ActiveReservationExistsException` handling
- [ ] Write `AuthorIntegrationTest`:
  - POST -> GET -> PUT -> GET -> DELETE -> GET (404)
  - POST with invalid data -> 400
  - DELETE sole author -> 409
- [ ] Verify all tests pass: `./gradlew test`
- [ ] Git commit

**Deliverable**: Author CRUD fully working, ~12 tests passing.

---

### Day 3: Book CRUD API with TDD

**Goal**: Fully working Book CRUD endpoints including author association and deletion protection.

**Tasks**:

- [ ] Write `BookServiceTest` (unit tests first) -- annotate with `@ExtendWith(MockitoExtension.class)`:
  - Test create book with valid authors
  - Test create book with duplicate ISBN
  - Test create book with non-existent author ID
  - Test get book by ID (found, includes authors)
  - Test get book by ID (not found)
  - Test update book (change title, change authors)
  - Test delete book (no reservations)
  - Test delete book with active reservation (-> 409)
- [ ] Implement `BookService` to make unit tests green (with `@Transactional` on delete)
- [ ] Create DTOs: `CreateBookRequest`, `UpdateBookRequest`, `BookResponse` (Java records)
- [ ] Create `BookMapper` (static methods)
- [ ] Create `BookRepository`
- [ ] Create `BookController`
- [ ] Add `DuplicateIsbnException` and `DataIntegrityViolationException` disambiguation to exception handler
- [ ] Write `BookIntegrationTest`:
  - Create author first, then create book referencing that author
  - Full CRUD cycle
  - Duplicate ISBN -> 409
  - Invalid author ID -> 400 or 404
  - Delete book with active reservation -> 409
- [ ] Create `PagedResponse` wrapper DTO for paginated list endpoints
- [ ] Verify all tests pass
- [ ] Git commit

**Deliverable**: Book CRUD fully working with author association and deletion protection, ~24 cumulative tests passing.

---

### Day 4: Search Implementation with TDD

**Goal**: Full-text search across books and authors with ranking.

**Hard decision point**: If full-text search tsvector approach is not working by end of morning, switch to ILIKE fallback immediately (see Section 7.6). Do not spend the afternoon debugging tsvector.

**Tasks**:

- [ ] Write migration `V5__add_full_text_search.sql` (tsvector column, GIN index, trigger, backfill)
- [ ] Write `SearchServiceTest` (unit tests):
  - Test query sanitization (truncation, null bytes, control chars)
  - Test empty query returns error
  - Test result mapping includes relevance score
- [ ] Implement `SearchService` with native query for full-text search (using corrected MAX() aggregation query)
- [ ] Create `SearchResultResponse` DTO (Java record)
- [ ] Create `SearchController` with `@RequestParam` (no DTO for input)
- [ ] Write `SearchIntegrationTest`:
  - Seed specific books and authors
  - Search by book title -> verify correct results and order
  - Search by author name -> verify books by that author appear
  - Search with multiple terms -> verify AND behavior
  - Search with no results -> verify empty list
  - Search with stop words only -> verify empty results
  - Verify ranking: title match outranks description match
  - Multi-author book appears exactly once
- [ ] Write migration `V6__seed_sample_data.sql` for demo data
- [ ] Verify all tests pass
- [ ] Git commit

**Deliverable**: Search working with ranking, ~34 cumulative tests passing.

---

### Day 5: Reservation System + Concurrency with TDD

**Goal**: Working reservation system that correctly handles concurrent access.

**Tasks**:

- [ ] Write `ReservationServiceTest` (unit tests first) -- annotate with `@ExtendWith(MockitoExtension.class)`:
  - Test create reservation happy path
  - Test create reservation when book already reserved -> exception
  - Test create reservation when book not found -> exception
  - Test create reservation when existing active reservation has expired (expire-then-create)
  - Test cancel reservation happy path
  - Test cancel already-cancelled reservation -> exception
  - Test on-read expiration logic
- [ ] Implement `ReservationService` with explicit `@Transactional` on createReservation and cancelReservation (per Section 8.2)
- [ ] Create DTOs: `CreateReservationRequest`, `ReservationResponse` (Java records)
- [ ] Create `ReservationMapper` (static methods)
- [ ] Create `ReservationRepository` with `findByBookIdAndStatus` and custom queries
- [ ] Create `ReservationController` (use `PATCH /{id}/cancel` for cancellation, not `DELETE`)
- [ ] Add `BookAlreadyReservedException` and `InvalidReservationStateException` to exception handler
- [ ] Add `HttpMessageNotReadableException` and `MethodArgumentTypeMismatchException` handlers to `GlobalExceptionHandler`
- [ ] Verify `DataIntegrityViolationException` disambiguation handles `idx_reservations_active_book` constraint
- [ ] Write `ReservationIntegrationTest`:
  - Full lifecycle: reserve -> verify -> cancel -> verify
  - Reserve non-existent book -> 404
  - Reserve already-reserved book -> 409
  - Reserve book with expired active reservation -> success (expire-then-create)
  - List reservations with filters
- [ ] Write `ReservationConcurrencyTest`:
  - 10 threads simultaneously reserving the same book
  - Assert exactly 1 success, 9 conflicts
- [ ] Verify all tests pass, especially the concurrency test (run it 3 times to check for flakiness)
- [ ] Git commit

**Deliverable**: Reservation system with proven concurrency safety, ~48 cumulative tests passing.

---

### Day 6: Integration Polish, Swagger Configuration, Edge Cases

**Goal**: Polish the API, ensure Swagger UI is demo-ready, cover remaining edge cases.

This day is explicitly a **buffer day**. If Days 4 or 5 bled over, use this day to finish them first. Priority order:
1. Any unfinished work from Days 4-5
2. Edge case tests
3. Swagger annotations
4. Manual testing walkthrough

**Tasks**:

- [ ] Configure `OpenApiConfig.java`:
  - API title, description, version
  - Group endpoints logically in Swagger UI
  - Add example request/response values via `@Schema` and `@ExampleObject` annotations
- [ ] Add `@Operation`, `@ApiResponse` annotations to all controllers for clear Swagger documentation
- [ ] Review and fix any edge cases discovered during testing:
  - ISBN format validation (13 digits, optional hyphens)
  - Published year validation (not in the future)
  - Pagination edge cases (negative page, zero size)
  - Empty string vs null handling in updates
- [ ] Add additional integration tests for edge cases not yet covered
- [ ] Test the full Swagger UI flow manually:
  - Create authors
  - Create books with author associations
  - Search for books
  - Create a reservation
  - Try to create a second reservation for the same book (expect 409)
  - Cancel the reservation
  - Reserve again successfully
  - Try to delete a book with active reservation (expect 409)
- [ ] Fix any bugs found during manual testing
- [ ] Run full test suite, ensure everything passes
- [ ] Review agent log -- ensure at least three substantive entries exist. If not, add retrospective entries while the context is fresh.
- [ ] Git commit

**Deliverable**: Polished API, demo-ready Swagger UI, comprehensive test suite, ~50+ tests passing.

---

### Day 7: Demo Recording, Report, Agent Log, Buffer

**Goal**: Produce all required deliverables.

**Tasks**:

- [ ] **Demo recording** (3-4 minutes):
  - Show Swagger UI and `/actuator/health`
  - Walk through: create author -> create book -> search -> reserve -> attempt duplicate reservation (409) -> cancel -> reserve again -> attempt delete book with reservation (409)
  - Show test suite running green
  - Brief narration explaining design choices
- [ ] **One-page report** (`docs/report.md`):
  - Which AI suggestions were accepted and why
  - Which AI suggestions were rejected and why
  - Key design decisions (concurrency approach, search strategy)
  - Test coverage summary
  - Known deviations from spec (see Section 12)
- [ ] **Agent log curation**:
  - Review `automated-claude-code-agent-log.md`
  - Ensure at least three AI suggestions are documented with rationale
  - Format per entry: Prompt -> AI Suggestion -> Accepted/Rejected -> Reason
- [ ] **Buffer time**: Address any remaining issues, failed tests, or overlooked requirements
- [ ] Final verification: all tests pass, application starts cleanly, Swagger UI works
- [ ] Final git commit, clean up any work-in-progress

**Deliverable**: Demo video, one-page report (`docs/report.md`), curated agent log (`automated-claude-code-agent-log.md`), clean repository.

---

## 11. Acceptance Criteria Checklist

This maps each acceptance criterion from the specification to specific implementations and tests.

| # | Acceptance Criterion (from spec)                              | Implementation                                  | Verified By                                      | Day |
|---|---------------------------------------------------------------|--------------------------------------------------|--------------------------------------------------|-----|
| 1 | REST API for CRUD on books                                    | `BookController` + `BookService`                | `BookServiceTest`, `BookIntegrationTest`         | 3   |
| 2 | REST API for CRUD on authors                                  | `AuthorController` + `AuthorService`            | `AuthorServiceTest`, `AuthorIntegrationTest`     | 2   |
| 3 | Full-text search or simple indexed search by title and author | `SearchController` + `SearchService` + tsvector | `SearchServiceTest`, `SearchIntegrationTest`     | 4   |
| 4 | Swagger page to exercise search and reservation flows         | SpringDoc / Swagger UI configuration            | Manual walkthrough on Day 6                      | 6   |
| 5 | Search returns correct ranked results for provided cases      | `ts_rank` scoring, weight A for title, MAX() for authors | `SearchIntegrationTest` ranking assertions | 4   |
| 6 | Reservation endpoint enforces simple concurrency rule         | Partial unique index + application check + @Transactional | `ReservationConcurrencyTest`              | 5   |
| 7 | Unit tests for CRUD and search ranking                        | JUnit 5 + Mockito tests                         | `./gradlew test`                                 | 2-4 |
| 8 | Integration test: reservation lifecycle                       | `ReservationIntegrationTest`                    | `./gradlew test`                                 | 5   |
| 9 | Integration test: concurrent reservation attempt              | `ReservationConcurrencyTest` (10 threads)       | `./gradlew test`                                 | 5   |
| 10| Tests pass in CI                                              | Testcontainers ensures portability              | `./gradlew test` (see Section 12 for CI note)    | 5-6 |
| 11| Agent log documents at least three AI suggestions with rationale | `automated-claude-code-agent-log.md`         | Manual review on Day 7                           | 7   |
| 12| 3-4 minute demo recording showing search and reservation     | Screen recording                                | Manual on Day 7                                  | 7   |
| 13| Repository with API code and tests                           | Git repository (initialized Day 1)              | All source committed                             | 1-7 |

---

## 12. Risk Register

| # | Risk                                                | Likelihood | Impact   | Mitigation                                                                                           |
|---|-----------------------------------------------------|------------|----------|------------------------------------------------------------------------------------------------------|
| 1 | PostgreSQL full-text search tsvector is too complex for timeline | Medium | High | **Hard decision point**: if not working by end of Day 4 morning, switch to ILIKE fallback (Section 7.6). Do not spend Day 5 morning on search. |
| 2 | Testcontainers Docker dependency fails on dev machine | Low      | Critical | Verify Docker is running before Day 1 work. Document Docker version requirement. |
| 3 | Concurrency test is flaky (timing-dependent)        | Medium     | High     | Use `CountDownLatch` to synchronize thread start. Use 10 threads. Run test 3x to verify stability. |
| 4 | Flyway migration ordering issues                    | Low        | Medium   | Strict sequential numbering (V1-V6). Test migrations from scratch in integration tests. |
| 5 | Search ranking does not match expected order         | Medium     | Medium   | Write specific test cases with known data and expected ranking. Tune weights if needed. |
| 6 | Days 4-5 overrun into Day 6 buffer                  | Medium     | Medium   | Day 6 priority order defined: finish unfinished work > edge cases > Swagger polish. |
| 7 | Swagger UI does not display endpoints correctly      | Low        | Low      | Use `springdoc-openapi-starter-webmvc-ui` which auto-discovers controllers. Verify on Day 6. |
| 8 | Book deletion while active reservation exists        | N/A        | N/A      | **Resolved**: FK ON DELETE RESTRICT + application-level check returning 409. See Section 6.1. |
| 9 | Author deletion leaves book with zero authors       | N/A        | N/A      | **Resolved**: Application-level check in `AuthorService.delete()`. See Section 6.2. |
| 10| Agent log not capturing enough AI interactions       | Medium     | Medium   | Review on Day 6 evening. Minimum 3 entries. Backfill retrospectively if needed. |
| 11| Demo recording takes longer than expected            | Low        | Low      | Prepare script/checklist before recording. Aim for one or two takes maximum. |
| 12| On-read expiration race condition between threads    | Low        | Medium   | Expire-then-create is wrapped in single `@Transactional`. Partial unique index is final arbiter. See Section 7.2. |

---

## 13. Known Deviations from Spec

These deviations are intentional and should be documented in the one-page report (`docs/report.md`).

| # | Spec Requirement | Deviation | Rationale |
|---|------------------|-----------|-----------|
| 1 | "CI passing badge" (spec line 12) | CI/CD pipeline is not implemented | Explicitly scoped out. All tests are fully runnable via `./gradlew test` with only Docker required. Testcontainers ensures portability to any CI environment. |
| 2 | "full-text search or simple indexed search" | No prefix matching support | Using `plainto_tsquery` which requires full words. Stemming still works ("running" -> "run"). Trade-off for input safety and implementation simplicity. |
| 3 | Accented character handling | Not supported | Out of scope for MVP. Would require `unaccent` PostgreSQL extension. |

---

## 14. Appendix A: Build Configuration

> *Added in revision 3.*

### 14.1 build.gradle.kts

```kotlin
plugins {
    id("org.springframework.boot") version "3.4.2"
    id("io.spring.dependency-management") version "1.1.7"
    // NOTE: kotlin("jvm") is NOT required here. The Kotlin Gradle DSL (build.gradle.kts)
    // does not require the Kotlin JVM plugin -- that plugin is only needed when compiling
    // Kotlin source files. This is a pure Java project; the DSL itself is compiled by
    // Gradle's embedded Kotlin compiler regardless of whether the plugin is applied.
    java
}

group = "com.library"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Testcontainers BOM to align all Testcontainers module versions.
// Without this, testcontainers-postgresql and testcontainers-junit-jupiter
// can be on different versions and cause subtle compatibility issues.
dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.19.8")
    }
}

dependencies {
    // --- Core Spring Boot ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // --- Lombok ---
    // Required for @RequiredArgsConstructor (constructor injection) and @Slf4j (logging).
    // compileOnly: present at compile time but not bundled in the JAR.
    // annotationProcessor: runs during compilation to generate the constructor and logger code.
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    // Lombok must also be on the test annotation processor path so @RequiredArgsConstructor
    // and @Slf4j are generated for test helper classes if any are annotated.
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // --- Database ---
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")  // Required for PG 15+ Flyway support
    runtimeOnly("org.postgresql:postgresql")

    // --- API Documentation ---
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.0")

    // --- Local development ---
    // DevTools provides automatic application restart on class file changes during local dev.
    // developmentOnly ensures this is NOT included in production builds.
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // --- Test dependencies ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Testcontainers -- versions managed by BOM above
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<Test> {
    useJUnitPlatform()   // Required to activate JUnit 5 (Jupiter) test runner
    // Increase heap for Testcontainers + Spring Boot context in CI-like environments
    jvmArgs("-Xmx512m")
}
```

**Key dependency notes**:
- `flyway-database-postgresql` is a separate artifact required since Flyway 10. Without it, Flyway silently falls back to the generic JDBC driver and may fail on PostgreSQL 15+ features.
- `springdoc-openapi-starter-webmvc-ui:2.8.0` includes both the OpenAPI JSON generation and the Swagger UI static assets. No separate Swagger UI dependency is needed.
- Testcontainers BOM usage is essential. Individual Testcontainers modules (`testcontainers`, `postgresql`, `junit-jupiter`) must all be on the same version.

### 14.2 settings.gradle.kts

```kotlin
rootProject.name = "library-catalog"
```

---

## 15. Appendix B: Configuration Files

> *Added in revision 3.*

### 15.1 application.yml (main -- for local Docker Compose dev)

```yaml
spring:
  application:
    name: library-catalog

  datasource:
    url: jdbc:postgresql://localhost:5432/library_catalog
    username: library_user
    password: library_pass
    # driver-class-name is intentionally omitted: Spring Boot auto-detects
    # org.postgresql.Driver from the jdbc:postgresql:// URL prefix.
    # HikariCP connection pool defaults are appropriate for MVP.
    # Explicitly set pool size to avoid surprises.
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 20000

  jpa:
    open-in-view: false    # CRITICAL: disable Open Session In View. OSIV keeps the
                           # Hibernate session open for the full HTTP request lifecycle,
                           # which masks LazyInitializationException during development
                           # and holds DB connections longer than necessary. With
                           # open-in-view=false, any lazy-load outside a @Transactional
                           # method fails fast with LazyInitializationException, enforcing
                           # the rule that all data access happens within the service layer.
    hibernate:
      ddl-auto: validate   # NEVER create/update -- Flyway owns the schema
    show-sql: false         # Set to true for debugging; never commit as true
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
        default_batch_fetch_size: 20  # Aligned with @BatchSize(size = 20) on entity collections

  flyway:
    enabled: true
    locations: classpath:db/migration  # Explicit for clarity; this is also Flyway's default
    baseline-on-migrate: false  # Schema is always created fresh by Flyway

server:
  port: 8080

springdoc:
  api-docs:
    path: /api-docs           # JSON OpenAPI spec at /api-docs
  swagger-ui:
    path: /swagger-ui.html    # Swagger UI at /swagger-ui.html
    operationsSorter: method  # Sort endpoints by HTTP method in UI
    tagsSorter: alpha         # Sort tag groups alphabetically

management:
  endpoints:
    web:
      exposure:
        include: health, info  # Only expose health and info -- MVP scope
  endpoint:
    health:
      show-details: always     # Show DB, disk, etc. details in health response
                               # NOTE: change to 'when-authorized' for any real deployment.
  info:
    app:
      name: Library Catalog
      version: 1.0.0-SNAPSHOT

logging:
  level:
    com.library.catalog: DEBUG   # Application code
    org.springframework.web: INFO
    org.hibernate.SQL: WARN      # Show SQL only when debugging
```

### 15.2 application-test.yml (test profile -- loaded automatically in test runs)

The test profile intentionally has minimal overrides. Testcontainers (`@DynamicPropertySource`) overrides the datasource URL, username, and password at runtime. The remaining overrides exist only to speed up test startup and reduce noise.

```yaml
spring:
  flyway:
    enabled: true      # Flyway must run in tests to create the schema
    clean-on-validation-error: false

  jpa:
    show-sql: false    # Keep test output clean
    hibernate:
      ddl-auto: validate

logging:
  level:
    com.library.catalog: WARN    # Suppress DEBUG output during tests
    org.testcontainers: WARN
    com.github.dockerjava: WARN
```

**Why `application-test.yml` is at `src/test/resources/`**: Placing it in the test source set ensures it is never bundled into the production JAR. The Spring Boot test runner automatically loads this file when `spring.profiles.active=test` is set, or when `@ActiveProfiles("test")` is applied to a test class.

### 15.3 docker-compose.yml

```yaml
version: "3.8"

services:
  postgres:
    image: postgres:16-alpine
    container_name: library_catalog_db
    environment:
      POSTGRES_DB: library_catalog
      POSTGRES_USER: library_user
      POSTGRES_PASSWORD: library_pass
    ports:
      - "5432:5432"
    volumes:
      # Named volume: data persists across `docker compose down` / `up` cycles.
      # Use `docker compose down -v` to wipe data and start fresh.
      - library_catalog_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U library_user -d library_catalog"]
      interval: 5s
      timeout: 5s
      retries: 5

volumes:
  library_catalog_data:
```

**Usage**:
```bash
# Start the database in the background
docker compose up -d

# Wait for health check, then start the application
./gradlew bootRun

# Stop and remove containers (keeps data volume)
docker compose down

# Stop, remove containers, AND wipe data volume (clean state)
docker compose down -v
```

### 15.4 Swagger/OpenAPI Configuration Detail

> *Added in revision 3. Extends the Day 6 Swagger configuration task.*

#### OpenApiConfig.java outline

```java
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI libraryOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Library Catalog API")
                .description("""
                    REST API for managing books, authors, and reservations.
                    Supports full-text search across titles and author names.
                    Use the Reservations endpoints to demonstrate concurrency safety.
                    """)
                .version("1.0.0")
                .contact(new Contact()
                    .name("Library Catalog Team")
                    .email("team@library.example.com")));
    }

    // Endpoint groups in Swagger UI: Books, Authors, Search, Reservations.
    // GroupedOpenApi creates separate "tags" in the UI sidebar, allowing
    // the reviewer to navigate directly to the relevant set of endpoints.

    @Bean
    public GroupedOpenApi booksApi() {
        return GroupedOpenApi.builder()
            .group("books")
            .pathsToMatch("/api/v1/books/**")
            .build();
    }

    @Bean
    public GroupedOpenApi authorsApi() {
        return GroupedOpenApi.builder()
            .group("authors")
            .pathsToMatch("/api/v1/authors/**")
            .build();
    }

    @Bean
    public GroupedOpenApi searchApi() {
        return GroupedOpenApi.builder()
            .group("search")
            .pathsToMatch("/api/v1/search/**")
            .build();
    }

    @Bean
    public GroupedOpenApi reservationsApi() {
        return GroupedOpenApi.builder()
            .group("reservations")
            .pathsToMatch("/api/v1/reservations/**")
            .build();
    }
}
```

#### Controller annotation pattern

Apply this pattern consistently across all controllers. Example for `BookController`:

```java
@RestController
@RequestMapping(value = "/api/v1/books", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Books", description = "CRUD operations for the book catalog")
@RequiredArgsConstructor
public class BookController {

    private final BookService bookService;

    @Operation(
        summary = "List all books",
        description = "Returns a paginated list of all books. Sort by title or createdAt."
    )
    @ApiResponse(responseCode = "200", description = "Page of books returned")
    @GetMapping
    public ResponseEntity<PagedResponse<BookResponse>> findAll(
        @PageableDefault(size = 20, sort = "title") Pageable pageable
    ) {
        return ResponseEntity.ok(bookService.findAll(pageable));
    }

    @Operation(summary = "Get a book by ID")
    @ApiResponse(responseCode = "200", description = "Book found")
    @ApiResponse(responseCode = "404", description = "Book not found")
    @GetMapping("/{id}")
    public ResponseEntity<BookResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(bookService.findById(id));
    }

    @Operation(
        summary = "Create a new book",
        description = "Creates a book and associates it with existing authors by ID."
    )
    @ApiResponse(responseCode = "201", description = "Book created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "ISBN already exists")
    @PostMapping
    public ResponseEntity<BookResponse> create(
        @Valid @RequestBody CreateBookRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bookService.create(request));
    }

    @Operation(summary = "Update a book")
    @ApiResponse(responseCode = "200", description = "Book updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Book not found")
    @ApiResponse(responseCode = "409", description = "ISBN collision")
    @PutMapping("/{id}")
    public ResponseEntity<BookResponse> update(
        @PathVariable Long id,
        @Valid @RequestBody UpdateBookRequest request
    ) {
        return ResponseEntity.ok(bookService.update(id, request));
    }

    @Operation(summary = "Delete a book")
    @ApiResponse(responseCode = "204", description = "Book deleted")
    @ApiResponse(responseCode = "404", description = "Book not found")
    @ApiResponse(responseCode = "409", description = "Book has active reservation")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        bookService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

**Tagging rule**: Each controller class has a single `@Tag` annotation. The `name` value must match the group name in `OpenApiConfig` so that the Swagger UI sidebar groups the endpoints correctly. The `description` is shown in the Swagger UI group header.

**`ReservationController` cancel method** (uses `PATCH`, not `DELETE`):

```java
@RestController
@RequestMapping(value = "/api/v1/reservations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Reservations", description = "Reservation creation, cancellation, and listing")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @Operation(summary = "Cancel a reservation",
               description = "Transitions an ACTIVE reservation to CANCELLED. " +
                             "The reservation record is preserved for history.")
    @ApiResponse(responseCode = "200", description = "Reservation cancelled")
    @ApiResponse(responseCode = "404", description = "Reservation not found")
    @ApiResponse(responseCode = "409", description = "Reservation is not in ACTIVE state")
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<ReservationResponse> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(reservationService.cancel(id));
    }
}
```

`produces = MediaType.APPLICATION_JSON_VALUE` must be applied to the `@RequestMapping` of every controller. This makes content negotiation explicit, ensures SpringDoc generates accurate response content-type documentation, and prevents edge-case failures when clients send `Accept: application/xml` (Spring returns 406 Not Acceptable instead of an unhelpful 500).

---

## 16. Appendix C: Error Codes and Response Shapes

### Standard Error Response

All error responses follow this shape:

```json
{
  "status": 409,
  "error": "Conflict",
  "message": "Book with ID 42 already has an active reservation",
  "timestamp": "2026-02-21T10:00:00Z",
  "path": "/api/v1/reservations"
}
```

### Validation Error Response (400)

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "timestamp": "2026-02-21T10:00:00Z",
  "path": "/api/v1/books",
  "fieldErrors": [
    {
      "field": "title",
      "message": "must not be blank"
    },
    {
      "field": "isbn",
      "message": "must be a valid ISBN-13"
    }
  ]
}
```

### HTTP Status Code Summary

| Code | Meaning              | When Used                                              |
|------|----------------------|--------------------------------------------------------|
| 200  | OK                   | Successful GET, PUT, or PATCH (cancel reservation)     |
| 201  | Created              | Successful POST (new resource created)                 |
| 204  | No Content           | Successful DELETE (resource hard-removed)              |
| 400  | Bad Request          | Validation errors, malformed input, type mismatch      |
| 404  | Not Found            | Resource with given ID does not exist                  |
| 409  | Conflict             | Duplicate ISBN, book already reserved, invalid state transition, deletion blocked by reservation history |
| 500  | Internal Server Error| Unexpected server errors                               |

---

## 17. Known Action Items from Previous Reviews

> *Added in revision 3. These items were flagged during plan validation and must be addressed before final submission.*

### Action Item 1: Agent Log Filename Must Be `agent_log.txt`

**Status**: Correction required.

The specification (initial-task.md, line 12) states the artifact name is **`agent_log.txt`** at the repository root. The current plan (Sections 2, 9, 10, 11) references `automated-claude-code-agent-log.md` as the filename.

**Required correction**:
- The file at the repository root must be named `agent_log.txt`, not `automated-claude-code-agent-log.md`.
- Update the project structure diagram in Section 3 to show `agent_log.txt`.
- Update all references in Sections 10, 11, and 13 accordingly.
- Ensure the file uses plain text format (`.txt`) as implied by the spec artifact name.

**Impact on Day 1 task**: The Day 1 task "Create empty `automated-claude-code-agent-log.md` with format template" must create `agent_log.txt` instead.

**Impact on Day 7 deliverable**: The deliverable line must read `agent_log.txt`, not `automated-claude-code-agent-log.md`.

Until this is corrected in a subsequent revision, treat `agent_log.txt` as the canonical filename wherever this plan mentions `automated-claude-code-agent-log.md`.

### Action Item 2: Demo Recording Must Show `./gradlew test` Output

**Status**: Addition required to Day 7 checklist.

The specification requires "CI passing badge" as an artifact. Since CI/CD is explicitly out of scope (Section 1 and Section 13, deviation #1), the demo recording is the substitute that demonstrates test passage. The recording MUST visibly show the `./gradlew test` command running and completing with a green BUILD SUCCESSFUL output. This serves as the functional equivalent of a CI badge for the reviewer.

**Required addition to Day 7 demo script**:
- [ ] Terminal window: run `./gradlew test` from the repository root and record the full output including the test summary table and BUILD SUCCESSFUL line.
- The recording should show the number of tests passed, e.g., `50 tests completed, 0 failed`.
- Do not use `./gradlew test --info` or `--debug` -- the condensed output is cleaner for the recording.
- If the test run takes more than 90 seconds (likely due to Testcontainers startup), it is acceptable to show the beginning (container startup) and skip to near the end (test completion) using a cut.

### Action Item 3: Add Cross-Query Search Test Case to `SearchIntegrationTest`

**Status**: Addition required to test plan.

Add the following test case to `SearchIntegrationTest` and to the edge-case table in Section 9.5:

**Test scenario**: Query term matches a book title for one book AND matches an author name for a different book.

**Purpose**: Validates that the search query correctly returns both books (one by title match, one by author match) and that neither suppresses the other in the result set. This is the most important correctness test for the multi-source search design -- it is easy to write a query that returns only title matches or only author matches when both should appear.

**Concrete test setup**:

```java
@Test
void search_whenQueryMatchesTitleOfOneBookAndAuthorOfAnother_thenBothBooksAreReturned() {
    // Setup:
    // Author: "Java Gosling" (last name matches query term "Gosling")
    // Book A: "Gosling Techniques" by author "Alice Smith" (title matches "Gosling")
    // Book B: "Programming Languages" by author "Java Gosling" (author name matches "Gosling")
    //
    // Query: "Gosling"
    // Expected: both Book A and Book B appear in results.
    // Book A should rank high (title weight A match).
    // Book B should rank high (author name match).
    // Neither should suppress the other.

    Long authorGoslingId = createAuthor("Java", "Gosling");
    Long authorSmithId = createAuthor("Alice", "Smith");

    Long bookAId = createBook("Gosling Techniques", "9780000000001", List.of(authorSmithId));
    Long bookBId = createBook("Programming Languages", "9780000000002", List.of(authorGoslingId));

    PagedResponse<SearchResultResponse> results = search("Gosling");

    assertThat(results.totalElements()).isEqualTo(2);
    List<Long> returnedIds = results.content().stream()
        .map(SearchResultResponse::id).toList();
    assertThat(returnedIds).containsExactlyInAnyOrder(bookAId, bookBId);

    // Book A (title match) should outrank Book B (author match)
    // because title has weight A vs author computed at query time.
    // Verify ordering:
    assertThat(results.content().get(0).id()).isEqualTo(bookAId);
    assertThat(results.content().get(1).id()).isEqualTo(bookBId);
}
```

**Add to edge-case table** (Section 9.5):

| Search | Query matches book title (Book A) AND author name of different book (Book B) | Integration | Both books returned; Book A ranked first (title weight A > author weight) |

This test was flagged because the search SQL uses an OR condition (`WHERE b.search_vector @@ ... OR to_tsvector(...) @@ ...`) which is correct in principle, but aggregation bugs (e.g., incorrect GROUP BY or WHERE clause short-circuiting) could silently drop one of the result rows. Having an explicit test for this case prevents that regression.

---

## 18. Changes in Revision 4

> *Added in revision 4. Summarizes all architectural fixes applied from the Java architecture review.*

### CRITICAL Fixes

**Fix 1: JPQL enum literal replaced with parameter binding**
- `BookRepository.hasActiveReservation()`: changed `AND r.status = 'ACTIVE'` to `AND r.status = :status` with `@Param("status") ReservationStatus status`.
- String literals in JPQL bypass Hibernate's type conversion and are fragile against enum renames. Parameter binding with the enum type is compile-safe and lets Hibernate apply `EnumType.STRING` conversion correctly.

**Fix 2: Pessimistic lock added to expire-then-create path**
- Added `findByBookIdAndStatusForUpdate(@Lock(LockModeType.PESSIMISTIC_WRITE))` to `ReservationRepository`.
- `ReservationService.createReservation()` now uses this locked method on the expire-then-create path to prevent TOCTOU (time-of-check/time-of-use) races where two threads concurrently expire the same stale reservation.
- Section 8.4 updated: "Pessimistic locking is NOT used as the general concurrency strategy, but IS used on the expire-then-create path to prevent TOCTOU races."

### HIGH Fixes

**Fix 3: Removed `searchVector` field from Book entity**
- The `searchVector` field (`insertable = false, updatable = false`) has been removed from the `Book` entity entirely.
- Note added: "The `search_vector` column is managed exclusively by the PostgreSQL trigger (V5 migration). It is NOT mapped in the Java entity because it is never read by application code -- the search uses a native SQL query that accesses the column directly."

**Fix 4: Added `@BatchSize(size = 20)` to lazy collections**
- `Book.authors`: added `@BatchSize(size = 20)` to mitigate N+1 on paginated list endpoints.
- `Author.books`: added `@BatchSize(size = 20)` for the same reason.
- Batch size of 20 matches the `default_batch_fetch_size` setting in `application.yml`.

**Fix 5: Added protected no-arg constructor to BaseEntity**
- Added `protected BaseEntity() {}` to `BaseEntity`.
- Note: "JPA requires a no-arg constructor accessible to Hibernate's proxy machinery."

**Fix 6: Removed `kotlin("jvm")` plugin from build.gradle.kts**
- Removed `kotlin("jvm") version "2.0.0"` from the plugins block.
- Corrected the comment: the Kotlin DSL does NOT require the Kotlin JVM plugin.
- Updated Spring Boot to `3.4.2` and `io.spring.dependency-management` to `1.1.7`.

**Fix 7: Documented `@Max(2100)` limitation on `publishedYear`**
- Added comment on `publishedYear` fields in `CreateBookRequest` and `UpdateBookRequest`:
  `// MVP: hard-coded upper bound. Replace with custom @PastOrPresentYear validator for production.`

**Fix 8: Replaced `findAllWithFilters` JPQL with Specification pattern**
- Removed the `findAllWithFilters` JPQL `@Query` from `ReservationRepository`.
- `ReservationRepository` now extends `JpaSpecificationExecutor<Reservation>`.
- Added `ReservationSpecification` utility class with static `withFilters(Long bookId, String userName, ReservationStatus status)` method.
- `ReservationService.findAll(...)` now calls `reservationRepository.findAll(ReservationSpecification.withFilters(...), pageable)`.
- Rationale: dynamic JPQL with `IS NULL` checks prevents index use; the Specification pattern builds predicates only for non-null parameters.

### MEDIUM Fixes

**Fix 9: BookService.delete() checks ANY reservation, not just active**
- `BookService.delete()` now calls `reservationRepository.existsByBookId(bookId)` (any status) before deleting.
- Added `boolean existsByBookId(Long bookId)` to `ReservationRepository`.
- Error message updated: "Cannot delete a book with reservation history."
- This prevents silent loss of historical reservation data that the FK RESTRICT would catch at the DB level but with a less friendly message.

**Fix 10: Added `@NotNull` to `authorIds` list in `CreateBookRequest`**
- Changed `@NotEmpty List<@NotNull Long> authorIds` to `@NotNull @NotEmpty List<@NotNull Long> authorIds`.
- Without `@NotNull`, a JSON body with `"authorIds": null` bypasses `@NotEmpty`.

**Fix 11: AuthorService.delete() sole-author check uses count query**
- Replaced the N+1 pattern (`book.getAuthors().size()` per book) with `bookRepository.countAuthorsByBookId(bookId)`.
- Added `@Query("SELECT COUNT(a) FROM Book b JOIN b.authors a WHERE b.id = :bookId") long countAuthorsByBookId(@Param("bookId") Long bookId)` to `BookRepository`.

**Fix 12: Concurrency test — added error bucket and latch assertion**
- Added `AtomicInteger errorCount` to track unexpected (non-201, non-409) responses and exceptions.
- `catch` block now increments `errorCount` instead of `conflictCount`.
- Added assertion: `assertThat(doneLatch.await(...)).isTrue()` — ensures all threads complete within the timeout.
- Added assertion: `assertThat(errorCount.get()).isEqualTo(0)` — ensures no unexpected errors occurred.

**Fix 13: Unified test cleanup strategy**
- Chose a single strategy: `@Sql(scripts = "/test-cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)` declared on `AbstractIntegrationTest`.
- Created `test-cleanup.sql` with `TRUNCATE reservations, book_authors, books, authors CASCADE;`.
- Removed the mention of mixed strategies (`@Transactional` rollback, `@Sql`, and `@BeforeEach`/`@AfterEach`).
- `ReservationConcurrencyTest` is the exception and relies on the inherited cleanup firing after thread completion.

**Fix 14: Removed redundant `findAll(Pageable)` declarations**
- Removed the explicit `Page<Book> findAll(Pageable pageable)` declaration from `BookRepository`.
- Removed the explicit `Page<Author> findAll(Pageable pageable)` declaration from `AuthorRepository`.
- Both are inherited from `JpaRepository` and must not be redeclared.

**Fix 15: Aligned `default_batch_fetch_size` to 20**
- Changed `default_batch_fetch_size` in `application.yml` from `16` to `20`.
- Comment added: "Aligned with @BatchSize(size = 20) on entity collections."

### LOW Fixes

**Fix 16: Updated Spring Boot and springdoc versions**
- Spring Boot updated from `3.3.0` to `3.4.2`.
- `springdoc-openapi-starter-webmvc-ui` updated from `2.5.0` to `2.8.0`.

**Fix 17: ErrorResponse `fieldErrors` uses empty list instead of null**
- `fieldErrors` field default changed from `null` to `List.of()`.
- Added `@JsonInclude(JsonInclude.Include.NON_EMPTY)` to omit the field from JSON when empty.
- Added a convenience constructor for non-validation errors that defaults `fieldErrors` to `List.of()`.

**Fix 18: Added `updatable = false` to `Reservation.reservedAt`**
- `@Column(name = "reserved_at", nullable = false)` changed to `@Column(name = "reserved_at", nullable = false, updatable = false)`.
- Prevents accidental overwrites of the creation timestamp.

**Fix 19: Added explicit `countQuery` for native search pagination**
- Added a `COUNT(DISTINCT b.id)` count query to Section 7.3 with explanation.
- Spring Data cannot derive a count query from a complex native query automatically. The explicit count query must be passed as `countQuery` when using `@Query(nativeQuery = true)`, or executed separately when using `EntityManager` directly to construct a `PageImpl`.

**Fix 20: Moved `GlobalExceptionHandler` to `controller/handler/` package**
- `GlobalExceptionHandler.java` moved from `exception/` to `controller/handler/`.
- The `exception/` package now contains only plain `RuntimeException` subclasses with no Spring MVC dependencies.
- Section 2.3 updated: `GlobalExceptionHandler` belongs in the web tier because it is annotated with `@RestControllerAdvice` and depends on Spring MVC types. Placing it in `exception/` would give the leaf package a dependency on the web layer.

**Fix 21: Added `@ActiveProfiles("test")` to `AbstractIntegrationTest`**
- Added `@ActiveProfiles("test")` to the `AbstractIntegrationTest` base class.
- Without this annotation, `application-test.yml` is not loaded during integration tests even though the file exists in `src/test/resources/`.

---

## 19. Changes in Revision 5

> *Added in revision 5. Summarizes all Spring Boot code review fixes applied from the Spring Boot specialist review.*

### CRITICAL Fixes

**Fix 22: Added `spring.jpa.open-in-view: false` to `application.yml`**
- Added `open-in-view: false` under `spring.jpa` in `application.yml`.
- Spring Boot enables OSIV by default. OSIV keeps the Hibernate session open for the full HTTP request lifecycle, masking `LazyInitializationException` during development and holding DB connections longer than necessary.
- With `open-in-view: false`, any lazy-load outside a `@Transactional` method fails fast, enforcing the plan's rule that all data access happens within the service layer.
- A Spring Boot warning is emitted at startup when OSIV is enabled with a JPA datasource; this fix also eliminates that startup warning.

**Fix 23: Added Lombok dependency to `build.gradle.kts`**
- Added `compileOnly("org.projectlombok:lombok")`, `annotationProcessor("org.projectlombok:lombok")`, `testCompileOnly("org.projectlombok:lombok")`, and `testAnnotationProcessor("org.projectlombok:lombok")`.
- Without Lombok on the annotation processor path, `@RequiredArgsConstructor` is not processed and no constructor is generated, causing compilation failure on every service class.

**Fix 24: Removed `application-test.yml` from `src/main/resources/`**
- `application-test.yml` was listed in both `src/main/resources/` (line 254 of previous revision) and `src/test/resources/` (line 279).
- Removed the incorrect `src/main/resources/` entry from the project structure diagram. The file must exist ONLY at `src/test/resources/` to prevent it from being bundled into the production JAR.

**Fix 25: Removed invalid `BookRepository.hasActiveReservation` JPQL method**
- `SELECT COUNT(r) > 0 FROM Reservation r ...` is not valid JPQL. JPQL does not permit boolean expressions as projection items.
- The method was also redundant with `ReservationRepository.existsByBookIdAndStatus()`, which Spring Data derives correctly as a boolean EXISTS query.
- Replaced with a documentation comment explaining the removal and directing implementers to the correct location.

**Fix 26: `ReservationService` in Section 8.2 corrected to match service contract**
- Added `@RequiredArgsConstructor` and field declarations to the `ReservationService` code snippet in Section 8.2.
- Changed `createReservation(Long bookId, String userName)` return type from `Reservation` (entity) to `ReservationResponse` (DTO), matching the Section 4.7 contract.
- Changed `cancelReservation(Long reservationId)` to `cancel(Long reservationId)` returning `ReservationResponse`, matching the Section 4.7 contract.
- Corrected method signature to accept `CreateReservationRequest request` instead of raw primitives, matching how the controller calls the service.
- Service methods now call `ReservationMapper.toResponse(saved)` before returning, respecting the layering rule that entities must not escape the service layer.

**Fix 27: Added `@ExtendWith(MockitoExtension.class)` requirement to unit test classes**
- Without `@ExtendWith(MockitoExtension.class)` on unit test classes, `@Mock` and `@InjectMocks` annotations are not processed by Mockito. All injected fields remain `null`, causing `NullPointerException` in every unit test method.
- Added the annotation requirement to the unit test mocking approach documentation in Section 9.2.
- Added a concrete unit test class structure example (`BookServiceTest` skeleton) showing the correct annotation pattern.

### HIGH Fixes

**Fix 28: Added `@QueryHints` lock timeout to `findByBookIdAndStatusForUpdate`**
- Added `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))` to the pessimistic-lock repository method.
- Without a timeout, a thread holding the `SELECT ... FOR UPDATE` lock that crashes or stalls causes all waiting threads to block indefinitely, hanging the concurrency test.
- A 5-second timeout causes the waiting thread to fail with a `LockTimeoutException` instead of hanging, which is translated to a 409 response by the exception handler.

**Fix 29: Cancel reservation changed from `DELETE /reservations/{id}` to `PATCH /reservations/{id}/cancel`**
- The reservation resource is not deleted on cancellation — it persists in CANCELLED state for historical audit. Using `DELETE` with a response body is semantically incorrect (DELETE implies resource removal) and many HTTP clients discard DELETE response bodies.
- Changed to `PATCH /reservations/{id}/cancel`, which correctly expresses a partial state-transition update.
- Updated Section 6.4 endpoint table, the status-code notes, Appendix C status-code table, the `ReservationController` example, the Day 5 tasks, and the manual testing walkthrough on Day 6.

**Fix 30: Added `HttpMessageNotReadableException` and `MethodArgumentTypeMismatchException` handlers**
- `HttpMessageNotReadableException`: thrown when a client sends malformed JSON (missing braces, wrong value types). Without a handler, this falls to the 500 catch-all. Added handler returning 400 "Malformed request body."
- `MethodArgumentTypeMismatchException`: thrown when a non-numeric value is passed for a `@PathVariable Long id` (e.g., `/books/abc`). Without a handler, this returns 500. Added handler returning 400 "Invalid value 'abc' for parameter 'id'."
- Both exceptions added to the exception-to-HTTP-status mapping table in Section 6.5.

**Fix 31: Added explicit `MethodArgumentNotValidException` field-error mapping code**
- Specified the exact mapping from `ex.getBindingResult().getFieldErrors()` to `ErrorResponse.FieldError` records using `.stream().map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage())).toList()`.
- This prevents implementation gaps where the developer populates `fieldErrors` with null or incomplete data.

**Fix 32: Added `extractConstraintName` private method specification**
- Added the concrete null-safe implementation: `if (ex.getCause() instanceof ConstraintViolationException cve) { return cve.getConstraintName(); } return null;`
- Added explicit note: "The extracted constraint name must never be included in error response bodies — it is compared internally only."

**Fix 33: Added `executor.awaitTermination(5, TimeUnit.SECONDS)` to concurrency test**
- Without `awaitTermination`, the thread pool may still have active threads (completing network calls) when assertions execute, leading to non-deterministic assertion results.
- Added after `executor.shutdown()` to ensure full thread termination before assertions.

### MEDIUM Fixes

**Fix 34: Added `RESTART IDENTITY` to `test-cleanup.sql`**
- Changed `TRUNCATE ... CASCADE` to `TRUNCATE ... RESTART IDENTITY CASCADE`.
- `RESTART IDENTITY` resets `BIGSERIAL` sequences to 1 after each test. Without it, auto-increment IDs grow monotonically across test runs, and tests that assert on specific ID values will fail non-deterministically. Resetting sequences provides a consistent baseline.
- Added documentation note: tests must never assert on specific ID values regardless, because sequence values depend on test execution order even with `RESTART IDENTITY`.

**Fix 35: Clarified on-read expiration scope — readOnly transactions cannot expire reservations**
- Section 8.3 previously stated expiration is "also handled on-read in `findById` and `findAll` queries for display purposes."
- This was incorrect: `findById` and `findAll` are `@Transactional(readOnly = true)`. Attempting a write (status update) inside a readOnly transaction throws `InvalidDataAccessApiUsageException`.
- Corrected: on-read expiration happens ONLY in the `create()` flow. `findById` and `findAll` return the stored status as-is. Clients may see stale ACTIVE status for expired reservations until the next create call.

**Fix 36: Added `@Size(max = 5000)` to `bio` fields in author DTOs**
- `CreateAuthorRequest.bio` and `UpdateAuthorRequest.bio` had no length constraint. Without bounds, a client can submit a multi-megabyte bio, causing memory pressure.
- Added `@Size(max = 5000, message = "Bio must not exceed 5000 characters")` to both DTOs.

**Fix 37: Documented no-op behavior of all-null UpdateBookRequest and UpdateAuthorRequest**
- An all-null or empty request body (`{}`) is accepted and returns 200 OK with the unchanged resource. This is intentional partial-update semantics but may surprise API consumers.
- Added documentation notes in the DTO definitions and UpdateBookRequest record.

**Fix 38: Added produces = MediaType.APPLICATION_JSON_VALUE to @RequestMapping**
- Added `produces = MediaType.APPLICATION_JSON_VALUE` to the `@RequestMapping` on `BookController` as a pattern example for all controllers.
- Makes content negotiation explicit, prevents 500 on `Accept: application/xml` requests (returns 406 Not Acceptable), and ensures SpringDoc generates accurate response content-type documentation.
- `ReservationController` example also shows `produces = MediaType.APPLICATION_JSON_VALUE`.

**Fix 39: Documented search `sort` parameter limitation**
- Added explicit note that the `sort` Pageable parameter is not supported on the search endpoint. Search results are always sorted by `relevance_score DESC` from the native SQL query.
- The Spring Data `Pageable.sort` parameter is silently ignored on this endpoint. Documented in Section 6.3 and advised to hide `sort` from Swagger UI for the search endpoint.

**Fix 40: Added mapper transaction-boundary documentation**
- Clarified in Section 3 design rationale: mapper methods must be called exclusively from within `@Transactional` service methods.
- Because all associations are `FetchType.LAZY`, accessing `book.getAuthors()` or `author.getBooks()` from a mapper called outside a transaction throws `LazyInitializationException`.
- Services are responsible for ensuring relevant collections are initialized (via JOIN FETCH or `@BatchSize` batch loading) before delegating to the mapper.

**Fix 41: Corrected `ReservationService.createReservation` to capture `Instant.now()` once**
- Changed two separate `Instant.now()` calls (one for `reservedAt`, one for `expiresAt`) to capture time once: `Instant now = Instant.now(); reservation.setReservedAt(now); reservation.setExpiresAt(now.plus(14, ChronoUnit.DAYS));`
- Without this, `expiresAt` could be microseconds ahead of `reservedAt + 14 days` due to two distinct clock reads straddling a tick.

**Fix 42: Added `findByIdWithBook` usage clarification in ReservationService**
- The `cancel()` method now uses `reservationRepository.findByIdWithBook(reservationId)` instead of plain `findById()`.
- This ensures `book.getTitle()` is available to `ReservationMapper.toResponse()` without triggering a separate lazy-load query or requiring OSIV.

### LOW Fixes

**Fix 43: Added Spring Boot DevTools to `build.gradle.kts`**
- Added `developmentOnly("org.springframework.boot:spring-boot-devtools")`.
- `developmentOnly` scope ensures DevTools is excluded from the production JAR and only active during local development.
- Provides automatic application restart on class changes, reducing the dev feedback loop.

**Fix 44: Fixed springdoc version in Key Dependency Notes**
- The "Key dependency notes" paragraph in Section 14.1 still referenced `springdoc-openapi-starter-webmvc-ui:2.5.0`. Updated to `2.8.0` to match the `dependencies` block.

**Fix 45: Added `management.info.app` entries to `application.yml`**
- Added `management.info.app.name` and `management.info.app.version` so the `/actuator/info` endpoint returns meaningful data instead of an empty `{}` object.
- The `info` endpoint is exposed but was previously returning no content, making its inclusion in `exposure.include` pointless.

**Fix 46: Added `@SpringBootApplication` scan scope note to Section 3**
- Documented that `LibraryCatalogApplication` at `com.library.catalog` provides automatic component scanning for all sub-packages. No explicit `@ComponentScan` is needed or should be added.
- Adding `@ComponentScan` manually overrides auto-detection and can cause beans to be missed or double-registered.

**Fix 47: Added `ReservationProperties @ConfigurationProperties` class**
- Introduced `ReservationProperties` record bound to `library.reservation.duration-days` in `application.yml`.
- The hardcoded `14`-day reservation duration is moved from service source code to configuration, making it adjustable without code changes and testable in isolation.
- Noted `@EnableConfigurationProperties(ReservationProperties.class)` requirement.

**Fix 48: Added note on internal DB details in error responses**
- Added explicit note to the `DataIntegrityViolationException` handler: `ex.getMessage()` and `ex.getCause().getMessage()` must never be included in error response bodies. These strings contain table names, column names, and constraint names that expose internal schema details.

**Fix 49: Added `@ExtendWith(MockitoExtension.class)` to Day 2, 3, 5 unit test task items**
- Day 2 `AuthorServiceTest`, Day 3 `BookServiceTest`, and Day 5 `ReservationServiceTest` task items now explicitly include the annotation requirement.

**Fix 50: Documented driver-class-name omission rationale**
- Replaced `driver-class-name: org.postgresql.Driver` (redundant) with a comment explaining it is intentionally omitted: Spring Boot auto-detects the driver from the `jdbc:postgresql://` URL prefix.
