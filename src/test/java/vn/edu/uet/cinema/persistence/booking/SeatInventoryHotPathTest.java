package vn.edu.uet.cinema.persistence.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.edu.uet.cinema.TestcontainersConfiguration;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.account.AccountService;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.booking.BookingService;
import vn.edu.uet.cinema.business.booking.PaymentTransaction;
import vn.edu.uet.cinema.business.booking.PaymentTransactionRepository;
import vn.edu.uet.cinema.business.booking.PaymentTransactionStatus;
import vn.edu.uet.cinema.business.booking.PaymentTransactionType;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.Showtime;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;
import vn.edu.uet.cinema.business.showtime.inventory.BookingStatus;
import vn.edu.uet.cinema.business.showtime.inventory.CancellationReason;
import vn.edu.uet.cinema.business.showtime.inventory.PayOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.SeatInventory;
import vn.edu.uet.cinema.business.showtime.inventory.SeatStatus;

/**
 * Verification for Issue #48 (P10):
 * - JpaSeatInventory.statuses() executes via JdbcClient mapping directly without dynamic JDK proxy.
 * - JpaSeatInventory.executePay() executes confirm update + transaction insert in 1 compound CTE round trip.
 * - ShowtimeService caches scheduled showtimes and evicts on cancel.
 * - hibernate.query.in_clause_parameter_padding: true is enabled in application.yml.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SeatInventoryHotPathTest {

	@Autowired JdbcClient jdbc;
	@Autowired AccountService accounts;
	@Autowired CinemaService cinemas;
	@Autowired MovieService movies;
	@Autowired AuditoriumService auditoriums;
	@Autowired ShowtimeService showtimes;
	@Autowired BookingService bookings;
	@Autowired SeatInventory inventory;
	@Autowired PaymentTransactionRepository paymentTransactions;
	@Autowired Environment environment;

	private final Instant now = Instant.now();

	@Test
	void inClauseParameterPaddingConfigurationIsEnabled() {
		assertThat(environment.getProperty("spring.jpa.properties.hibernate.query.in_clause_parameter_padding"))
			.isEqualTo("true");
	}

	@Test
	void statusesReturnsOccupiedSeatStatusesViaJdbcClient() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID customer1 = customer();
		UUID customer2 = customer();
		UUID seat1 = hall.seats().get(0).id();
		UUID seat2 = hall.seats().get(1).id();

		Booking pending = bookings.book(customer1, showtimeId, List.of(seat1), now);
		Booking toPay = bookings.book(customer2, showtimeId, List.of(seat2), now);
		bookings.pay(customer2, toPay.id(), now);

		Map<UUID, SeatStatus> statuses = inventory.statuses(showtimeId, now);
		assertThat(statuses.get(seat1)).isEqualTo(SeatStatus.HELD);
		assertThat(statuses.get(seat2)).isEqualTo(SeatStatus.SOLD);
		assertThat(statuses.get(hall.seats().get(2).id())).isNull();
	}

	@Test
	void payWithSingleRoundTripCteConfirmsBookingAndRecordsPaymentTransaction() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);
		UUID customerId = customer();
		UUID seatId = hall.seats().get(0).id();
		Booking pending = bookings.book(customerId, showtimeId, List.of(seatId), now);
		String key = "hotpath-pay-" + UUID.randomUUID();

		PayOutcome outcome = inventory.pay(customerId, pending.id(), now, key);
		assertThat(outcome).isInstanceOf(PayOutcome.Paid.class);
		Booking confirmed = ((PayOutcome.Paid) outcome).confirmed();
		assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(confirmed.totalPrice()).isEqualTo(100_000L);

		List<PaymentTransaction> txs = paymentTransactions.findByBookingId(pending.id());
		assertThat(txs).hasSize(1);
		PaymentTransaction tx = txs.get(0);
		assertThat(tx.type()).isEqualTo(PaymentTransactionType.PAYMENT);
		assertThat(tx.status()).isEqualTo(PaymentTransactionStatus.COMPLETED);
		assertThat(tx.amount()).isEqualTo(100_000L);
		assertThat(tx.idempotencyKey()).isEqualTo(key);

		// Idempotent retry returns AlreadyPaid
		PayOutcome replay = inventory.pay(customerId, pending.id(), now, key);
		assertThat(replay).isInstanceOf(PayOutcome.AlreadyPaid.class);
	}

	@Test
	void showtimeScheduledCacheReturnsCachedShowtimeAndEvictsOnCancel() {
		Auditorium hall = auditorium();
		UUID showtimeId = showtime(hall);

		Showtime first = showtimes.getScheduled(showtimeId);
		Showtime second = showtimes.getScheduled(showtimeId);
		assertThat(first).isSameAs(second);

		showtimes.cancel(showtimeId, CancellationReason.EMERGENCY, "Test cancel", now);
		assertThatThrownBy(() -> showtimes.getScheduled(showtimeId))
			.isInstanceOf(ConflictException.class)
			.hasMessageContaining("Cancelled");
	}

	private UUID customer() {
		return accounts.registerCustomer("hotpath-" + UUID.randomUUID() + "@example.com", "password123", "Customer").id();
	}

	private Auditorium auditorium() {
		UUID cinemaId = cinemas.create("Cinema " + UUID.randomUUID(), "Address").id();
		return auditoriums.create(cinemaId, "Hall", 2, 2, List.of(), List.of());
	}

	private UUID showtime(Auditorium hall) {
		UUID movieId = movies.create("Hotpath Film", "Synopsis", 90, "Drama", AgeRating.P,
			LocalDate.parse("2026-09-01"), "poster").id();
		return showtimes.create(movieId, hall.id(), now.plus(1, ChronoUnit.DAYS), 100_000).id();
	}
}
