package vn.edu.uet.cinema.persistence.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.edu.uet.cinema.TestcontainersConfiguration;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.account.Account;
import vn.edu.uet.cinema.business.account.AccountService;
import vn.edu.uet.cinema.business.audit.AuditAction;
import vn.edu.uet.cinema.business.audit.AuditLog;
import vn.edu.uet.cinema.business.audit.AuditLogFilter;
import vn.edu.uet.cinema.business.audit.AuditLogRepository;
import vn.edu.uet.cinema.business.audit.AuditTargetType;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.booking.BookingService;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.Showtime;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;
import vn.edu.uet.cinema.business.showtime.inventory.CancellationReason;

/**
 * PostgreSQL integration tests verifying append-only database enforcement and same-transaction
 * atomicity for {@code audit_log} (Issue #18 / S4).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditLogPersistenceTest {

	@Autowired AccountService accounts;
	@Autowired CinemaService cinemas;
	@Autowired AuditoriumService auditoriums;
	@Autowired MovieService movies;
	@Autowired ShowtimeService showtimes;
	@Autowired BookingService bookings;
	@Autowired AuditLogRepository auditLogs;
	@Autowired JdbcClient jdbc;

	@Test
	void appendOnlyTriggerRejectsUpdateAndDeleteOnAuditLog() {
		Account admin = accounts.authenticate("admin@cinema.local", "admin12345");
		UUID targetId = UUID.randomUUID();
		AuditLog entry = auditLogs.append(AuditLog.of(admin.id(), AuditAction.SHOWTIME_CREATED,
			AuditTargetType.SHOWTIME, targetId, "{\"basePrice\":100000}", Instant.now()));

		assertThatThrownBy(() -> jdbc.sql("update audit_log set details = '{}'::jsonb where id = ?")
			.param(entry.id()).update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("audit_log is append-only");

		assertThatThrownBy(() -> jdbc.sql("delete from audit_log where id = ?")
			.param(entry.id()).update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("audit_log is append-only");

		String jsonType = jdbc.sql("select jsonb_typeof(details) from audit_log where id = ?")
			.param(entry.id()).query(String.class).single();
		assertThat(jsonType).isEqualTo("object");
	}

	@Test
	void showtimeCreateCancelAndDeleteWriteAuditLogsAtomicallyAndSkipFailedMutations() {
		Account admin = accounts.authenticate("admin@cinema.local", "admin12345");
		UUID cinemaId = cinemas.create(admin.id(), "Audit Cinema " + UUID.randomUUID(), "1 Audit St", Instant.now()).id();
		Auditorium hall = auditoriums.create(admin.id(), cinemaId, "Hall A", 1, 2, List.of(), List.of(), Instant.now());
		UUID movieId = movies.create(admin.id(), "Audit Movie", "Synopsis", 100, "Drama", AgeRating.P,
			LocalDate.parse("2026-10-01"), "https://example.com/p.jpg", Instant.now()).id();
		Instant now = Instant.now();
		Instant start = now.plusSeconds(86_400);

		Showtime showtime = showtimes.create(admin.id(), movieId, hall.id(), start, 100_000L, now);
		assertThatThrownBy(() -> showtimes.create(admin.id(), movieId, hall.id(), start.plusSeconds(60), 100_000L, now))
			.isInstanceOf(ConflictException.class);

		Account customerA = accounts.registerCustomer("audit-a-" + UUID.randomUUID() + "@example.com", "password123", "A");
		Account customerB = accounts.registerCustomer("audit-b-" + UUID.randomUUID() + "@example.com", "password123", "B");
		UUID paidBookingId = bookings.book(customerA.id(), showtime.id(), List.of(hall.seats().get(0).id()), now).id();
		bookings.pay(customerA.id(), paidBookingId, now);
		bookings.book(customerB.id(), showtime.id(), List.of(hall.seats().get(1).id()), now);

		// Deleting with active bookings fails with 409 and must not emit a SHOWTIME_DELETED audit entry.
		assertThatThrownBy(() -> showtimes.delete(admin.id(), showtime.id(), now.plusSeconds(1)))
			.isInstanceOf(ConflictException.class);

		Instant cancelTime = now.plusSeconds(2);
		showtimes.cancel(admin.id(), showtime.id(), CancellationReason.POWER_OUTAGE, "Grid outage", cancelTime);
		// Second cancel fails with 409 and must not emit a duplicate SHOWTIME_CANCELLED entry.
		assertThatThrownBy(() -> showtimes.cancel(admin.id(), showtime.id(), CancellationReason.EMERGENCY, null,
			cancelTime.plusSeconds(1)))
			.isInstanceOf(ConflictException.class);

		PageSlice<AuditLog> showtimeLogs = auditLogs.findAll(PageQuery.DEFAULT,
			new AuditLogFilter(null, AuditTargetType.SHOWTIME, showtime.id(), admin.id()));
		assertThat(showtimeLogs.items()).extracting(AuditLog::action)
			.containsExactly(AuditAction.SHOWTIME_CANCELLED, AuditAction.SHOWTIME_CREATED);
		assertThat(showtimeLogs.items().get(0).details())
			.contains("\"reason\": \"POWER_OUTAGE\"")
			.contains("\"deletedPendingBookings\": 1")
			.contains("\"refundedConfirmedBookings\": 1");

		// Create a second showtime with only a lapsed hold, then delete it and verify audit log survives hard-delete.
		Showtime deletable = showtimes.create(admin.id(), movieId, hall.id(), start.plusSeconds(20_000), 95_000L, now);
		Account customerC = accounts.registerCustomer("audit-c-" + UUID.randomUUID() + "@example.com", "password123", "C");
		bookings.book(customerC.id(), deletable.id(), List.of(hall.seats().get(0).id()), now.minusSeconds(1_200));

		showtimes.delete(admin.id(), deletable.id(), now);
		assertThatThrownBy(() -> showtimes.get(deletable.id())).isInstanceOf(NotFoundException.class);

		PageSlice<AuditLog> deletedLogs = auditLogs.findAll(PageQuery.DEFAULT,
			new AuditLogFilter(AuditAction.SHOWTIME_DELETED, AuditTargetType.SHOWTIME, deletable.id(), admin.id()));
		assertThat(deletedLogs.items()).hasSize(1);
		assertThat(deletedLogs.items().get(0).details()).contains("\"purgedLapsedHolds\": 1");
	}

	@Test
	void auditFailureRollsBackShowtimeCreateInSameTransaction() {
		UUID cinemaId = cinemas.create("Rollback Cinema " + UUID.randomUUID(), "1 Rollback St").id();
		Auditorium hall = auditoriums.create(cinemaId, "Hall R", 1, 1, List.of(), List.of());
		UUID movieId = movies.create("Rollback Movie", "Synopsis", 90, "Drama", AgeRating.P,
			LocalDate.parse("2026-10-01"), "https://example.com/r.jpg").id();
		UUID unknownActorId = UUID.randomUUID();
		Instant start = Instant.now().plusSeconds(172_800);

		// Inserting into audit_log fails fk_audit_log_actor; the showtime insert in the same tx must also roll back.
		assertThatThrownBy(() -> showtimes.create(unknownActorId, movieId, hall.id(), start, 100_000L, Instant.now()))
			.isInstanceOf(DataAccessException.class);

		assertThat(showtimes.list(start.minusSeconds(60), start.plusSeconds(60), cinemaId, movieId)).isEmpty();
	}
}
