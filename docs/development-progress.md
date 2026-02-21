# Development Progress: Library Catalog MVP

**Started**: 2026-02-21
**Plan**: docs/development-plan.md (revision 6)

---

## Day 1: Project Scaffold, Docker Compose, Flyway, Entity Layer

### Status: COMPLETE

| # | Task | Status | Notes |
|---|------|--------|-------|
| 1 | Delete Maven scaffold | DONE | Removed pom.xml, .mvn/, target/, src/main/java/com/company/ |
| 2 | Replace .gitignore | DONE | Spring Boot + Gradle + IntelliJ entries |
| 3 | Git repository | DONE | Pre-existing |
| 4 | Initialize Spring Boot 3 project (Gradle) | DONE | build.gradle.kts, settings.gradle.kts, Gradle 8.12 wrapper, LibraryCatalogApplication.java |
| 5 | Create docker-compose.yml | DONE | PostgreSQL 16 Alpine on port 5433 (5432 was occupied) |
| 6 | Configure application.yml | DONE | OSIV=false, ddl-auto=validate, batch_fetch_size=20, actuator, springdoc |
| 7 | Configure application-test.yml | DONE | Minimal; datasource injected by @DynamicPropertySource |
| 8 | Create BaseEntity | DONE | @MappedSuperclass, createdAt/updatedAt with @PrePersist/@PreUpdate |
| 9 | Write Flyway migrations V1-V4 | DONE | authors, books, book_authors, reservations (with partial unique index) |
| 10 | Create JPA entity classes | DONE | Author, Book, Reservation, ReservationStatus; all LAZY + @BatchSize(20) |
| 11 | Create AbstractIntegrationTest | DONE | Testcontainers, @DynamicPropertySource, @Sql cleanup |
| 12 | Write LibraryCatalogApplicationTests | DONE | contextLoads() extends AbstractIntegrationTest |
| 13 | Verify app starts + Flyway runs | DONE | bootRun OK, /actuator/health UP (db: PostgreSQL), Swagger UI 200 |
| 14 | Create CLAUDE.md | DONE | Project conventions documented |

### Issues Resolved
- **Testcontainers + Docker Desktop 4.61**: API version mismatch → created `docker-java.properties` with `api.version=1.44`
- **Missing columns in V4**: Reservation extends BaseEntity but migration lacked created_at/updated_at → added to V4
- **Port 5432 conflict**: Existing container on 5432 → changed to port 5433
- **Port 8080 conflict**: Existing process → killed before bootRun verification

### Verification Results
- `./gradlew clean test` → BUILD SUCCESSFUL (7s), Testcontainers + Flyway + Hibernate validation pass
- `./gradlew bootRun` → App starts, `/actuator/health` returns UP with PostgreSQL connected
- Swagger UI accessible at `/swagger-ui/index.html` (200 OK)

---

## Day 2: Author CRUD API with TDD

### Status: COMPLETE

| # | Task | Status | Notes |
|---|------|--------|-------|
| 1 | Create exception classes | DONE | ResourceNotFoundException, ActiveReservationExistsException |
| 2 | Create Author DTOs | DONE | CreateAuthorRequest, UpdateAuthorRequest, AuthorResponse (records with validation) |
| 3 | Create ErrorResponse + PagedResponse | DONE | @JsonInclude(NON_EMPTY), FieldError inner record, generic PagedResponse |
| 4 | Create AuthorMapper | DONE | Static methods: toEntity, toResponse, updateEntity |
| 5 | Create AuthorRepository | DONE | findByIdWithBooks (LEFT JOIN FETCH) |
| 6 | Create BookRepository | DONE | findByIdWithAuthors, findAllByAuthorId, countAuthorsByBookId (for sole-author check) |
| 7 | Write AuthorServiceTest (TDD) | DONE | 10 unit tests with @ExtendWith(MockitoExtension.class) |
| 8 | Implement AuthorService | DONE | CRUD + sole-author deletion check, all @Transactional |
| 9 | Create GlobalExceptionHandler | DONE | @RestControllerAdvice with 8 exception handlers |
| 10 | Create AuthorController | DONE | REST endpoints at /api/v1/authors with @Valid |
| 11 | Write AuthorIntegrationTest | DONE | 5 integration tests: full CRUD lifecycle, validation, sole-author 409, pagination, 404 |
| 12 | Verify all tests pass | DONE | 16 tests total, all passing |

### Issues Resolved
- **Entity constructor access**: Changed `@NoArgsConstructor(access = PROTECTED)` to `@NoArgsConstructor` on Author, Book, Reservation — mapper in different package needs public constructor
- **Testcontainers singleton pattern**: Changed from `@Container`/`@Testcontainers` to singleton pattern (static block with `postgres.start()`) to fix container lifecycle issue across multiple test classes

### Verification Results
- `./gradlew clean test` → BUILD SUCCESSFUL (9s), 16 tests passing
  - 10 unit tests (AuthorServiceTest)
  - 5 integration tests (AuthorIntegrationTest)
  - 1 context load test (LibraryCatalogApplicationTests)

---

## Day 3: Book CRUD API with TDD

### Status: COMPLETE

| # | Task | Status | Notes |
|---|------|--------|-------|
| 1 | Create Book DTOs | DONE | CreateBookRequest (with @NotBlank, @Pattern ISBN, @NotEmpty authorIds), UpdateBookRequest, BookResponse |
| 2 | Create DuplicateIsbnException | DONE | RuntimeException for ISBN conflicts |
| 3 | Create BookMapper | DONE | Static methods: toEntity, toResponse, updateEntity |
| 4 | Create ReservationRepository | DONE | existsByBookIdAndStatus, existsByBookId, findByBookIdAndStatusForUpdate (pessimistic lock) |
| 5 | Add existsByIsbn to BookRepository | DONE | existsByIsbn, existsByIsbnAndIdNot (for update check) |
| 6 | Add DuplicateIsbnException handler | DONE | Added to GlobalExceptionHandler -> 409 |
| 7 | Write BookServiceTest (TDD) | DONE | 11 unit tests with @ExtendWith(MockitoExtension.class) |
| 8 | Implement BookService | DONE | CRUD + ISBN duplicate check + author association + deletion protection |
| 9 | Create BookController | DONE | REST endpoints at /api/v1/books with @Valid |
| 10 | Write BookIntegrationTest | DONE | 7 integration tests: full CRUD, duplicate ISBN 409, non-existent author 404, invalid data 400, delete with reservation 409, pagination, 404 |
| 11 | Verify all tests pass | DONE | 34 tests total, all passing |

### Verification Results
- `./gradlew clean test` → BUILD SUCCESSFUL (9s), 34 tests passing
  - 10 unit tests (AuthorServiceTest)
  - 11 unit tests (BookServiceTest)
  - 5 integration tests (AuthorIntegrationTest)
  - 7 integration tests (BookIntegrationTest)
  - 1 context load test (LibraryCatalogApplicationTests)

---

## Day 4: Search Implementation with TDD

### Status: COMPLETE

| # | Task | Status | Notes |
|---|------|--------|-------|
| 1 | Write V5 migration (full-text search) | DONE | tsvector column, GIN index, trigger, backfill |
| 2 | Create SearchResultResponse DTO | DONE | Record with id, title, isbn, publishedYear, authors, relevanceScore |
| 3 | Implement SearchService | DONE | Native SQL with EntityManager, sanitizeQuery, plainto_tsquery, MAX() aggregation |
| 4 | Create SearchController | DONE | GET /api/v1/search?q=...&page=...&size=... |
| 5 | Add IllegalArgumentException handler | DONE | Blank query -> 400 |
| 6 | Write SearchServiceTest (TDD) | DONE | 8 unit tests: sanitization (trim, truncate, null bytes, control chars, tabs, null), blank/empty query |
| 7 | Write SearchIntegrationTest | DONE | 8 tests: title search, author search, multi-term, no results, empty query 400, stop words only, ranking, multi-author uniqueness |
| 8 | Write V6 migration (seed data) | DONE | 5 authors, 8 books with associations |
| 9 | Verify all tests pass | DONE | 50 tests total, all passing |

### Issues Resolved
- **Missing stop words test**: Validation rejected Day 4 for missing "stop words only → empty results" test per plan requirement. Added `search_withStopWordsOnly_returnsEmptyResults` test.

### Verification Results
- `./gradlew clean test` → BUILD SUCCESSFUL (9s), 50 tests passing
  - 10 unit tests (AuthorServiceTest)
  - 11 unit tests (BookServiceTest)
  - 8 unit tests (SearchServiceTest)
  - 5 integration tests (AuthorIntegrationTest)
  - 7 integration tests (BookIntegrationTest)
  - 8 integration tests (SearchIntegrationTest)
  - 1 context load test (LibraryCatalogApplicationTests)

---

## Day 5: Reservation System + Concurrency with TDD

### Status: COMPLETE

| # | Task | Status | Notes |
|---|------|--------|-------|
| 1 | Create exception classes | DONE | BookAlreadyReservedException, InvalidReservationStateException |
| 2 | Create Reservation DTOs | DONE | CreateReservationRequest (@NotNull bookId, @NotBlank @Size userName), ReservationResponse |
| 3 | Create ReservationMapper | DONE | Static toResponse method |
| 4 | Add exception handlers | DONE | BookAlreadyReservedException → 409, InvalidReservationStateException → 409 |
| 5 | Write ReservationServiceTest (TDD) | DONE | 10 unit tests: create happy/not found/already reserved/expired expire-then-create, cancel happy/not found/already cancelled, findById found/not found, findAll |
| 6 | Implement ReservationService | DONE | create with pessimistic lock + expire-then-create + saveAndFlush, cancel, findById, findAll with Specification |
| 7 | Create ReservationController | DONE | POST /, PATCH /{id}/cancel, GET /, GET /{id} at /api/v1/reservations |
| 8 | Write ReservationIntegrationTest | DONE | 5 tests: full lifecycle (reserve→verify→cancel→verify), non-existent book 404, already reserved 409, expired→success, list with filters |
| 9 | Write ReservationConcurrencyTest | DONE | 10 threads, 1 success + 9 conflicts, ran 3x for flakiness check |
| 10 | Verify all tests pass | DONE | 66 tests total, all passing |

### Issues Resolved
- **Hibernate flush order (INSERTs before UPDATEs)**: When expiring an old reservation and creating a new one in the same transaction, Hibernate's default flush order executes INSERTs before UPDATEs. This caused the new ACTIVE reservation INSERT to fire before the old one was UPDATE'd to EXPIRED, violating `idx_reservations_active_book`. Fixed by using `saveAndFlush()` after expiring the old reservation to force the UPDATE before the INSERT.

### Verification Results
- `./gradlew clean test` → BUILD SUCCESSFUL (8s), 66 tests passing
  - 10 unit tests (AuthorServiceTest)
  - 11 unit tests (BookServiceTest)
  - 8 unit tests (SearchServiceTest)
  - 10 unit tests (ReservationServiceTest)
  - 5 integration tests (AuthorIntegrationTest)
  - 7 integration tests (BookIntegrationTest)
  - 8 integration tests (SearchIntegrationTest)
  - 5 integration tests (ReservationIntegrationTest)
  - 1 concurrency test (ReservationConcurrencyTest)
  - 1 context load test (LibraryCatalogApplicationTests)

---

## Day 6: Integration Polish, Swagger Configuration, Edge Cases

### Status: COMPLETE

| # | Task | Status | Notes |
|---|------|--------|-------|
| 1 | Configure OpenApiConfig.java | DONE | API title "Library Catalog API", description, version 1.0.0 |
| 2 | Add Swagger annotations to controllers | DONE | @Tag, @Operation, @ApiResponse, @Parameter on all 4 controllers |
| 3 | Add edge case integration tests | DONE | 3 new tests: cancel already-cancelled 409, invalid reservation data 400, non-existent reservation 404 |
| 4 | Manual Swagger UI testing walkthrough | DONE | Full flow verified: create author → create book → search → reserve → duplicate 409 → cancel → re-reserve → delete with reservation 409 |
| 5 | Run full test suite | DONE | 69 tests total, all passing |

### Manual Testing Results
- `/actuator/health` → UP with PostgreSQL connected
- Swagger UI at `/swagger-ui/index.html` → 200 OK with tagged endpoints
- Create author → 201, correct response shape
- Create book with author → 201, authors array populated
- Search by title → 200, results with relevance scores
- Create reservation → 201, ACTIVE status, expiresAt = reservedAt + 14 days
- Duplicate reservation → 409 "already has an active reservation"
- Cancel reservation → 200, CANCELLED status, cancelledAt set
- Re-reserve same book → 201, new ACTIVE reservation
- Delete book with reservation history → 409 "Cannot delete a book with reservation history"

### Verification Results
- `./gradlew clean test` → BUILD SUCCESSFUL (9s), 69 tests passing
  - 10 unit tests (AuthorServiceTest)
  - 11 unit tests (BookServiceTest)
  - 8 unit tests (SearchServiceTest)
  - 10 unit tests (ReservationServiceTest)
  - 5 integration tests (AuthorIntegrationTest)
  - 7 integration tests (BookIntegrationTest)
  - 8 integration tests (SearchIntegrationTest)
  - 8 integration tests (ReservationIntegrationTest)
  - 1 concurrency test (ReservationConcurrencyTest)
  - 1 context load test (LibraryCatalogApplicationTests)

---

## Day 7: Demo Recording, Report, Agent Log, Buffer

### Status: COMPLETE

| # | Task | Status | Notes |
|---|------|--------|-------|
| 1 | One-page report (docs/report.md) | DONE | AI suggestions accepted/rejected, design decisions, test coverage, known deviations |
| 2 | Agent log review | DONE | 12 iterations documented in docs/agent-log.md, 4 rejected suggestions with rationale |
| 3 | Final verification | DONE | 69 tests passing, app starts cleanly, Swagger UI works |

### Final Project Summary
- **Total tests**: 69 (39 unit + 29 integration + 1 concurrency)
- **Total Flyway migrations**: 6 (V1-V4 schema, V5 full-text search, V6 seed data)
- **REST endpoints**: 14 across 4 controllers (Authors, Books, Search, Reservations)
- **Key features**: CRUD for books/authors, PostgreSQL full-text search with ranking, reservation system with concurrency control (partial unique index + pessimistic lock + optimistic locking)
