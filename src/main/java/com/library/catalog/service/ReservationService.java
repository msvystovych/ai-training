package com.library.catalog.service;

import com.library.catalog.dto.request.CreateReservationRequest;
import com.library.catalog.dto.response.ReservationResponse;
import com.library.catalog.entity.Book;
import com.library.catalog.entity.Reservation;
import com.library.catalog.entity.ReservationStatus;
import com.library.catalog.exception.BookAlreadyReservedException;
import com.library.catalog.exception.InvalidReservationStateException;
import com.library.catalog.exception.ResourceNotFoundException;
import com.library.catalog.mapper.ReservationMapper;
import com.library.catalog.repository.BookRepository;
import com.library.catalog.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final BookRepository bookRepository;
    private final ReservationRepository reservationRepository;

    @Transactional
    public ReservationResponse create(CreateReservationRequest request) {
        Book book = bookRepository.findById(request.bookId())
            .orElseThrow(() -> new ResourceNotFoundException("Book", request.bookId()));

        Optional<Reservation> existing = reservationRepository
            .findByBookIdAndStatusForUpdate(request.bookId(), ReservationStatus.ACTIVE);

        if (existing.isPresent()) {
            Reservation active = existing.get();
            if (active.getExpiresAt().isBefore(Instant.now())) {
                active.setStatus(ReservationStatus.EXPIRED);
                reservationRepository.saveAndFlush(active);
            } else {
                throw new BookAlreadyReservedException(request.bookId());
            }
        }

        Instant now = Instant.now();
        Reservation reservation = new Reservation();
        reservation.setBook(book);
        reservation.setUserName(request.userName());
        reservation.setStatus(ReservationStatus.ACTIVE);
        reservation.setReservedAt(now);
        reservation.setExpiresAt(now.plus(14, ChronoUnit.DAYS));

        Reservation saved = reservationRepository.save(reservation);
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
        return ReservationMapper.toResponse(reservationRepository.save(reservation));
    }

    @Transactional(readOnly = true)
    public ReservationResponse findById(Long id) {
        Reservation reservation = reservationRepository.findByIdWithBook(id)
            .orElseThrow(() -> new ResourceNotFoundException("Reservation", id));
        return ReservationMapper.toResponse(reservation);
    }

    @Transactional(readOnly = true)
    public Page<ReservationResponse> findAll(Long bookId, String userName,
                                              ReservationStatus status, Pageable pageable) {
        Specification<Reservation> spec = Specification.where(null);

        if (bookId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("book").get("id"), bookId));
        }
        if (userName != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("userName"), userName));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }

        return reservationRepository.findAll(spec, pageable)
            .map(ReservationMapper::toResponse);
    }
}
