package com.library.catalog.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JPA entity representing a library book.
 *
 * <p><strong>Association ownership</strong>: Book is the <em>owning</em> side of the
 * Book-Author many-to-many. It manages the {@code book_authors} join table via
 * {@link #authors}. Changes to the association (adding/removing authors) must be
 * made through this collection.
 *
 * <p><strong>search_vector is intentionally omitted</strong>: The {@code search_vector}
 * column ({@code TSVECTOR}) is populated exclusively by a PostgreSQL trigger defined
 * in migration V5. Application code never reads or writes this column directly — search
 * queries access it via native SQL. Mapping it here would require
 * {@code insertable=false, updatable=false} and would expose a misleading field that
 * application code might accidentally read as a String. Omitting the field entirely is
 * cleaner and removes any risk of accidental writes.
 *
 * <p><strong>Optimistic locking</strong>: {@link #version} is annotated with {@link Version}.
 * Hibernate increments this field on every UPDATE. If two concurrent transactions read the
 * same version and both attempt to update, the second will throw
 * {@code OptimisticLockingFailureException}, which is translated to a 409 response by
 * {@code GlobalExceptionHandler}.
 *
 * <p><strong>Cascade strategy for authors</strong>:
 * <ul>
 *   <li>{@code PERSIST} — saving a new Book with new Author instances in its set will
 *       also persist those Author entities.</li>
 *   <li>{@code MERGE}   — merging a detached Book will also merge its Author associations.</li>
 *   <li>{@code REMOVE} is intentionally excluded: deleting a Book must not cascade-delete
 *       the Author entities themselves. Spring Data JPA automatically removes the
 *       {@code book_authors} join-table rows when the owning Book entity is deleted.</li>
 * </ul>
 *
 * <p><strong>reservations</strong> has no cascade type. Reservations have an independent
 * lifecycle and are never created, updated, or deleted by operating on a Book. This
 * collection is accessed exclusively in {@code BookService.delete()} to check for
 * blocking reservations before deletion.
 *
 * <p><strong>Lombok notes</strong>: See {@link Author} for the rationale for each annotation.
 * {@code @ToString} is omitted to avoid triggering lazy-load of {@code authors} or
 * {@code reservations} collections during logging.
 */
@Entity
@Table(name = "books")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(of = "id", callSuper = false)
public class Book extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /**
     * ISBN-13 identifier. Unique across all books.
     * Enforced at the DB level by {@code idx_books_isbn} (UNIQUE index defined in V2).
     * The index name is used by {@code GlobalExceptionHandler} to produce a descriptive
     * 409 response when a duplicate ISBN is detected at the database constraint level.
     */
    @Column(name = "isbn", nullable = false, unique = true, length = 13)
    private String isbn;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Publication year (year only, not a full date). Nullable — not always known. */
    @Column(name = "published_year")
    private Integer publishedYear;

    /**
     * Optimistic locking version counter managed by Hibernate.
     * Incremented automatically on every UPDATE. Never set manually by application code.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    /**
     * Authors of this book. Book owns the {@code book_authors} join table.
     *
     * <p>{@code FetchType.LAZY}: avoids loading all authors on every Book query.
     * Authors are fetched explicitly via a JOIN FETCH query when building
     * {@code BookResponse}.
     *
     * <p>{@code @BatchSize(size = 20)}: when Hibernate loads author collections for a
     * page of N books, it batches the secondary SELECTs into groups of 20, preventing
     * N+1 queries on paginated list endpoints.
     */
    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "book_authors",
            joinColumns = @JoinColumn(name = "book_id"),
            inverseJoinColumns = @JoinColumn(name = "author_id")
    )
    @BatchSize(size = 20)
    private Set<Author> authors = new HashSet<>();

    /**
     * All reservations ever created for this book (active, cancelled, expired).
     *
     * <p>This collection is NOT used to build any API response. It exists solely so
     * that {@code BookService.delete()} can check for blocking reservations without
     * executing a raw count query. The collection is loaded lazily on demand — it is
     * never touched in normal read/write flows.
     *
     * <p>No {@code CascadeType} is set: Reservation lifecycle is managed independently
     * by {@code ReservationService}.
     */
    @OneToMany(mappedBy = "book", fetch = FetchType.LAZY)
    private List<Reservation> reservations = new ArrayList<>();
}
