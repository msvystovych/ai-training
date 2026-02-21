package com.library.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.util.HashSet;
import java.util.Set;

/**
 * JPA entity representing a book author.
 *
 * <p><strong>Relationship ownership</strong>: Author is the <em>inverse</em> side of
 * the Book-Author many-to-many. The join table ({@code book_authors}) is owned by
 * {@link Book}. Consequently, any changes to the association must be made through
 * the {@code Book.authors} collection, not through this collection.
 *
 * <p><strong>Fetch strategy</strong>: {@code books} is {@code LAZY}. Loading all books
 * for an author is expensive and rarely needed in a single request. The collection is
 * loaded explicitly by the service layer via a JOIN FETCH query when building the
 * {@code AuthorResponse} DTO.
 *
 * <p><strong>@BatchSize</strong>: When Hibernate loads the {@code books} collection for a
 * page of N authors (e.g. after a {@code findAll(Pageable)} call), it groups the
 * secondary SELECT statements into batches of 20 using an IN-clause, mitigating
 * the N+1 query problem.
 *
 * <p><strong>Lombok notes</strong>:
 * <ul>
 *   <li>{@code @NoArgsConstructor(access = PROTECTED)} satisfies the JPA requirement for a
 *       no-arg constructor while preventing direct instantiation from outside the hierarchy.
 *       Lombok generates this protected constructor in addition to the inherited
 *       {@code BaseEntity()} protected constructor.</li>
 *   <li>{@code @EqualsAndHashCode(of = "id", callSuper = false)} bases equality solely on
 *       the database-assigned primary key, which is the correct semantic for persistent
 *       entities. {@code callSuper = false} prevents attempting to call
 *       {@code BaseEntity.equals()}, which is not defined.</li>
 *   <li>No {@code @ToString} is added because Lombok's default {@code toString()} would
 *       traverse the {@code books} collection, triggering a lazy-load outside a transaction
 *       and causing {@code LazyInitializationException} during logging or debugging.</li>
 * </ul>
 */
@Entity
@Table(name = "authors")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id", callSuper = false)
public class Author extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    /**
     * Optional short author biography. Stored as PostgreSQL {@code TEXT} (unbounded).
     * Nullable — not all authors have a biography entry.
     */
    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    /**
     * Inverse side of the Book-Author many-to-many association.
     * Do NOT add {@code CascadeType} here — the Author must not cascade any
     * operation to Books. All cascade behaviour is defined on the owning side
     * ({@link Book#getAuthors()}).
     */
    @ManyToMany(mappedBy = "authors", fetch = FetchType.LAZY)
    @BatchSize(size = 20)
    private Set<Book> books = new HashSet<>();
}
