# Library Catalog MVP: One-Page Report

## AI Suggestions: Accepted

1. **Partial unique index for reservation concurrency** (Karen, Iteration 3): The AI recommended `CREATE UNIQUE INDEX ... WHERE status = 'ACTIVE'` as the primary concurrency mechanism instead of application-level locking alone. Accepted because the database constraint is immune to application bugs and works across multiple instances.

2. **`plainto_tsquery` over `to_tsquery` for search** (Jenny, Iteration 4): AI recommended avoiding prefix matching (`to_tsquery` with `:*`) in favor of `plainto_tsquery`. Accepted because it safely handles all user input without manual escaping, and full-word search with stemming is sufficient for the MVP.

3. **`saveAndFlush()` for expire-then-create** (discovered during implementation): Hibernate's default flush order executes INSERTs before UPDATEs. When expiring an old reservation and creating a new one in the same transaction, this caused the partial unique index to reject the INSERT. Using `saveAndFlush()` forces the UPDATE (expire) to commit before the INSERT (create).

4. **Singleton Testcontainers pattern** (discovered during implementation): AI-generated `@Container`/`@Testcontainers` pattern on the abstract base class caused the container to die between test classes. Switched to singleton pattern (`static { postgres.start(); }`) which keeps one container alive for all integration tests.

5. **PATCH instead of DELETE for cancel** (Code Review, Iteration 10): AI correctly identified that `DELETE /reservations/{id}` was semantically wrong because the resource persists for history. Changed to `PATCH /reservations/{id}/cancel`.

## AI Suggestions: Rejected

1. **MapStruct for DTO mapping**: Rejected because plain static mapper methods are sufficient for an MVP with 3 entities. MapStruct adds annotation processing complexity for trivial mappings.

2. **EAGER fetch for any association**: All reviewers confirmed LAZY as universal rule. `@BatchSize(20)` handles N+1 while LAZY prevents unnecessary loading.

3. **`@Transient` on `searchVector`**: Chose full removal from entity because `@Transient` implies the field exists for application use — misleading when the column is managed by a database trigger.

## Key Design Decisions

- **Concurrency**: 4-layer strategy — (1) partial unique index as primary safety net, (2) application-level pre-check for friendly error messages, (3) `DataIntegrityViolationException` disambiguation for race condition survivors, (4) `@Version` optimistic locking on Reservation for cancel conflicts.
- **Search**: PostgreSQL tsvector/tsquery with GIN index. Title at weight A, description at weight C, author names computed at query time via JOIN. No prefix matching (documented limitation).
- **Expiration**: On-write only (during reservation creation). Read-only endpoints return stored status as-is.

## Test Coverage Summary

| Category | Count |
|----------|-------|
| Unit tests (AuthorService) | 10 |
| Unit tests (BookService) | 11 |
| Unit tests (SearchService) | 8 |
| Unit tests (ReservationService) | 10 |
| Integration tests (Author) | 5 |
| Integration tests (Book) | 7 |
| Integration tests (Search) | 8 |
| Integration tests (Reservation) | 8 |
| Concurrency test | 1 |
| Context load test | 1 |
| **Total** | **69** |

## Known Deviations from Spec

1. **CI passing badge**: CI/CD pipeline not implemented (explicitly scoped out). Tests runnable via `./gradlew test` with only Docker required.
2. **No prefix matching**: `plainto_tsquery` requires full words. Stemming works ("running" matches "run").
3. **Accented characters**: Not supported. Would require `unaccent` PostgreSQL extension.
4. **Agent log file format**: `docs/agent-log.md` instead of `agent_log.txt`. Markdown provides better formatting for structured logs.
