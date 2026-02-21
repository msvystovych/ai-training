package com.library.catalog.unit.service;

import com.library.catalog.dto.request.CreateReservationRequest;
import com.library.catalog.dto.response.ReservationResponse;
import com.library.catalog.entity.Book;
import com.library.catalog.entity.Reservation;
import com.library.catalog.entity.ReservationStatus;
import com.library.catalog.exception.BookAlreadyReservedException;
import com.library.catalog.exception.InvalidReservationStateException;
import com.library.catalog.exception.ResourceNotFoundException;
import com.library.catalog.repository.BookRepository;
import com.library.catalog.repository.ReservationRepository;
import com.library.catalog.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    void create_happyPath_createsReservation() {
        Book book = createTestBook(1L, "Effective Java");
        when(bookRepository.findById(1L)).thenReturn(Optional.of(book));
        when(reservationRepository.findByBookIdAndStatusForUpdate(1L, ReservationStatus.ACTIVE))
            .thenReturn(Optional.empty());
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 1L);
            return saved;
        });

        var request = new CreateReservationRequest(1L, "alice");
        ReservationResponse response = reservationService.create(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.bookId()).isEqualTo(1L);
        assertThat(response.bookTitle()).isEqualTo("Effective Java");
        assertThat(response.userName()).isEqualTo("alice");
        assertThat(response.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(response.reservedAt()).isNotNull();
        assertThat(response.expiresAt()).isAfter(response.reservedAt());
    }

    @Test
    void create_whenBookNotFound_throwsResourceNotFoundException() {
        when(bookRepository.findById(99L)).thenReturn(Optional.empty());

        var request = new CreateReservationRequest(99L, "alice");

        assertThatThrownBy(() -> reservationService.create(request))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Book")
            .hasMessageContaining("99");

        verify(reservationRepository, never()).save(any());
    }

    @Test
    void create_whenBookAlreadyReserved_throwsBookAlreadyReservedException() {
        Book book = createTestBook(1L, "Effective Java");
        Reservation activeReservation = createTestReservation(1L, book, ReservationStatus.ACTIVE,
            Instant.now().plus(7, ChronoUnit.DAYS));

        when(bookRepository.findById(1L)).thenReturn(Optional.of(book));
        when(reservationRepository.findByBookIdAndStatusForUpdate(1L, ReservationStatus.ACTIVE))
            .thenReturn(Optional.of(activeReservation));

        var request = new CreateReservationRequest(1L, "bob");

        assertThatThrownBy(() -> reservationService.create(request))
            .isInstanceOf(BookAlreadyReservedException.class)
            .hasMessageContaining("1");

        verify(reservationRepository, never()).save(any());
    }

    @Test
    void create_whenExpiredActiveReservation_expiresAndCreatesNew() {
        Book book = createTestBook(1L, "Effective Java");
        Reservation expiredReservation = createTestReservation(10L, book, ReservationStatus.ACTIVE,
            Instant.now().minus(1, ChronoUnit.DAYS)); // expired yesterday

        when(bookRepository.findById(1L)).thenReturn(Optional.of(book));
        when(reservationRepository.findByBookIdAndStatusForUpdate(1L, ReservationStatus.ACTIVE))
            .thenReturn(Optional.of(expiredReservation));
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation saved = invocation.getArgument(0);
            return saved;
        });
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", 2L);
            }
            return saved;
        });

        var request = new CreateReservationRequest(1L, "alice");
        ReservationResponse response = reservationService.create(request);

        // Verify expired reservation was expired
        assertThat(expiredReservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        verify(reservationRepository).saveAndFlush(expiredReservation);
        // Verify new reservation was created
        assertThat(response.id()).isEqualTo(2L);
        assertThat(response.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(response.userName()).isEqualTo("alice");
    }

    @Test
    void cancel_happyPath_cancelsReservation() {
        Book book = createTestBook(1L, "Effective Java");
        Reservation reservation = createTestReservation(1L, book, ReservationStatus.ACTIVE,
            Instant.now().plus(7, ChronoUnit.DAYS));

        when(reservationRepository.findByIdWithBook(1L)).thenReturn(Optional.of(reservation));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationResponse response = reservationService.cancel(1L);

        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(response.cancelledAt()).isNotNull();
    }

    @Test
    void cancel_whenNotFound_throwsResourceNotFoundException() {
        when(reservationRepository.findByIdWithBook(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.cancel(99L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Reservation")
            .hasMessageContaining("99");
    }

    @Test
    void cancel_whenAlreadyCancelled_throwsInvalidReservationStateException() {
        Book book = createTestBook(1L, "Effective Java");
        Reservation reservation = createTestReservation(1L, book, ReservationStatus.CANCELLED,
            Instant.now().plus(7, ChronoUnit.DAYS));

        when(reservationRepository.findByIdWithBook(1L)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationService.cancel(1L))
            .isInstanceOf(InvalidReservationStateException.class)
            .hasMessageContaining("CANCELLED");

        verify(reservationRepository, never()).save(any());
    }

    @Test
    void findById_whenFound_returnsReservationResponse() {
        Book book = createTestBook(1L, "Effective Java");
        Reservation reservation = createTestReservation(1L, book, ReservationStatus.ACTIVE,
            Instant.now().plus(7, ChronoUnit.DAYS));

        when(reservationRepository.findByIdWithBook(1L)).thenReturn(Optional.of(reservation));

        ReservationResponse response = reservationService.findById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.bookTitle()).isEqualTo("Effective Java");
        assertThat(response.status()).isEqualTo(ReservationStatus.ACTIVE);
    }

    @Test
    void findById_whenNotFound_throwsResourceNotFoundException() {
        when(reservationRepository.findByIdWithBook(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.findById(99L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAll_returnsPaginatedResults() {
        Book book = createTestBook(1L, "Effective Java");
        Reservation reservation = createTestReservation(1L, book, ReservationStatus.ACTIVE,
            Instant.now().plus(7, ChronoUnit.DAYS));
        Pageable pageable = PageRequest.of(0, 20);
        Page<Reservation> page = new PageImpl<>(List.of(reservation), pageable, 1);

        when(reservationRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        Page<ReservationResponse> result = reservationService.findAll(null, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).bookTitle()).isEqualTo("Effective Java");
    }

    private Book createTestBook(Long id, String title) {
        Book book = new Book();
        ReflectionTestUtils.setField(book, "id", id);
        book.setTitle(title);
        return book;
    }

    private Reservation createTestReservation(Long id, Book book, ReservationStatus status, Instant expiresAt) {
        Reservation reservation = new Reservation();
        ReflectionTestUtils.setField(reservation, "id", id);
        reservation.setBook(book);
        reservation.setUserName("testuser");
        reservation.setStatus(status);
        reservation.setReservedAt(Instant.now().minus(7, ChronoUnit.DAYS));
        reservation.setExpiresAt(expiresAt);
        return reservation;
    }
}
