package com.library.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;

/**
 * Shared superclass for all JPA entities.
 *
 * <p>Provides automatic audit timestamps ({@code created_at} and {@code updated_at})
 * via JPA lifecycle callbacks. The {@code created_at} column is set once on first
 * persist and is thereafter immutable ({@code updatable = false}). The
 * {@code updated_at} column is refreshed on every UPDATE via {@link #onUpdate()}.
 *
 * <p>The protected no-arg constructor is required by the JPA specification and
 * Hibernate's proxy/bytecode-enhancement machinery. Without it, Hibernate cannot
 * instantiate entity proxies for lazy loading. Protected visibility prevents direct
 * instantiation from outside the class hierarchy while satisfying the specification.
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BaseEntity() {}

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
