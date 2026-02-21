package com.library.catalog.entity;

/**
 * Lifecycle states for a {@link Reservation}.
 *
 * <p>Mapped as {@code VARCHAR} via {@code @Enumerated(EnumType.STRING)} so that
 * the stored value is always the enum name ("ACTIVE", "CANCELLED", "EXPIRED").
 * {@code EnumType.ORDINAL} is intentionally avoided because re-ordering enum
 * constants would silently corrupt existing rows.
 *
 * <ul>
 *   <li>{@link #ACTIVE}    — reservation is currently held by a user</li>
 *   <li>{@link #CANCELLED} — user explicitly cancelled the reservation</li>
 *   <li>{@link #EXPIRED}   — reservation passed its {@code expires_at} timestamp
 *                            without being cancelled</li>
 * </ul>
 */
public enum ReservationStatus {
    ACTIVE,
    CANCELLED,
    EXPIRED
}
