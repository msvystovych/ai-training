package com.library.catalog.repository;

import com.library.catalog.entity.Reservation;
import com.library.catalog.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long>,
        JpaSpecificationExecutor<Reservation> {

    boolean existsByBookIdAndStatus(Long bookId, ReservationStatus status);

    boolean existsByBookId(Long bookId);

    Optional<Reservation> findByBookIdAndStatus(Long bookId, ReservationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT r FROM Reservation r WHERE r.book.id = :bookId AND r.status = :status")
    Optional<Reservation> findByBookIdAndStatusForUpdate(
        @Param("bookId") Long bookId,
        @Param("status") ReservationStatus status
    );

    @Query("SELECT r FROM Reservation r LEFT JOIN FETCH r.book WHERE r.id = :id")
    Optional<Reservation> findByIdWithBook(@Param("id") Long id);
}
