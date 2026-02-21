package com.library.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * JPA entity representing a book reservation.
 *
 * <p>A reservation ties a specific {@link Book} to a named user for a fixed period
 * (typically 14 days). Only one {@code ACTIVE} reservation per book can exist at any
 * time — enforced at the database level by the partial unique index
 * {@code idx_reservations_active_book} (defined in V4 migration):
 * <pre>
 *   CREATE UNIQUE INDEX idx_reservations_active_book
 *       ON reservations (book_id) WHERE status = 'ACTIVE';
 * </pre>
 * This index is the authoritative concurrency control mechanism. The application-level
 * pre-check in {@code ReservationService} is a user-friendly optimistic guard that
 * produces a descriptive 409 before the DB constraint fires under normal load.
 *
 * <p><strong>Optimistic locking</strong>: {@link #version} is annotated with {@link Version}.
 * This guards against concurrent cancellation attempts on the same reservation. If two
 * requests both try to cancel the same reservation simultaneously, the second will
 * receive an {@code OptimisticLockingFailureException}, mapped to a 409 response by
 * {@code GlobalExceptionHandler}.
 *
 * <p><strong>Reservation.book fetch strategy</strong>: {@code FetchType.LAZY} — the full
 * {@link Book} object is not needed for most reservation queries. The
 * {@code ReservationResponse} requires only {@code bookId} and {@code bookTitle}, which
 * are accessible via {@code book.getId()} and {@code book.getTitle()} once the proxy is
 * initialised within an open transaction.
 *
 * <p><strong>reservedAt is immutable</strong>: {@code updatable = false} prevents the
 * reservation timestamp from ever being changed after initial insert. This protects the
 * audit trail.
 *
 * <p><strong>Lifecycle states</strong>:
 * <ul>
 *   <li>{@link ReservationStatus#ACTIVE}    — reservation is currently held.</li>
 *   <li>{@link ReservationStatus#CANCELLED} — user cancelled; {@link #cancelledAt} is set.</li>
 *   <li>{@link ReservationStatus#EXPIRED}   — {@link #expiresAt} has passed without cancellation.</li>
 * </ul>
 *
 * <p><strong>Lombok notes</strong>: See {@link Author} for rationale. {@code @ToString} is
 * omitted to avoid lazy-loading {@code book} during logging or debugging output.
 */
@Entity
@Table(name = "reservations")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id", callSuper = false)
public class Reservation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The book being reserved. {@code optional = false} tells Hibernate that this FK
     * is mandatory, allowing it to generate an inner join (instead of a left join) when
     * the association is fetched — a small but consistent query optimisation.
     *
     * <p>The corresponding DB column uses {@code ON DELETE RESTRICT}, meaning the Book
     * cannot be deleted while any reservation (in any status) references it.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    /** Name of the user who made the reservation. No authentication is in scope for MVP. */
    @Column(name = "user_name", nullable = false, length = 100)
    private String userName;

    /**
     * Current lifecycle status of the reservation.
     * Stored as a {@code VARCHAR} via {@code EnumType.STRING} so that the enum name
     * ("ACTIVE", "CANCELLED", "EXPIRED") is stored literally, making the column
     * human-readable and safe against enum re-ordering.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    /**
     * Timestamp when the reservation was created. Set once on insert and never changed
     * ({@code updatable = false}). Distinct from {@code BaseEntity.createdAt} in that
     * it carries business meaning and is exposed in the API response.
     */
    @Column(name = "reserved_at", nullable = false, updatable = false)
    private Instant reservedAt;

    /**
     * Timestamp after which the reservation is considered expired.
     * Typically {@code reservedAt + 14 days}, computed and set by
     * {@code ReservationService.createReservation()}.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Timestamp when the reservation was cancelled. {@code null} for non-cancelled
     * reservations. Set by {@code ReservationService.cancelReservation()} when
     * transitioning to {@link ReservationStatus#CANCELLED}.
     */
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /**
     * Optimistic locking version counter managed by Hibernate.
     * Guards against concurrent cancellation conflicts.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}
