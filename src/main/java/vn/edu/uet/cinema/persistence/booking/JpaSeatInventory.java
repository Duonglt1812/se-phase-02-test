package vn.edu.uet.cinema.persistence.booking;

import static vn.edu.uet.cinema.persistence.ConstraintViolations.hasConstraint;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import vn.edu.uet.cinema.business.audit.AuditAction;
import vn.edu.uet.cinema.business.audit.AuditDetails;
import vn.edu.uet.cinema.business.audit.AuditLog;
import vn.edu.uet.cinema.business.audit.AuditLogRepository;
import vn.edu.uet.cinema.business.audit.AuditTargetType;
import vn.edu.uet.cinema.business.showtime.inventory.AbandonOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.Booking;
import vn.edu.uet.cinema.business.showtime.inventory.BookingStatus;
import vn.edu.uet.cinema.business.showtime.inventory.CancelOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.Cancellation;
import vn.edu.uet.cinema.business.showtime.inventory.DeleteOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.HoldOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.PayOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.SeatInventory;
import vn.edu.uet.cinema.business.showtime.inventory.SeatStatus;
import vn.edu.uet.cinema.config.BookingMetrics;
import vn.edu.uet.cinema.config.SeatInventoryStrategy;

/**
 * Every write to {@code booking} and {@code booked_seat}, and the Showtime writes that go with them, each in one
 * transaction (ADR 0009). The live-Hold rule is {@link HoldSql}'s.
 */
@Repository("jpaSeatInventory")
public class JpaSeatInventory implements SeatInventory {

	private static final String FIND_OCCUPIED_SEATS_SQL = """
		select s.seat_id, b.status from booked_seat s join booking b on b.id = s.booking_id
		where s.showtime_id = ? and
		(b.status in ('CONFIRMED', 'REFUNDED') or (b.status = 'PENDING' and b.expires_at > ?))
		""";

	private static final String PAY_CONFIRM_AND_INSERT_SQL = """
		WITH updated AS (
			UPDATE booking
			SET status = 'CONFIRMED'
			WHERE id = ? AND customer_id = ? AND status = 'PENDING' AND expires_at > ?
			RETURNING id, customer_id, showtime_id, created_at, expires_at
		),
		seats AS (
			SELECT seat_id, price FROM booked_seat WHERE booking_id = ?
		),
		ins_tx AS (
			INSERT INTO payment_transaction (id, booking_id, customer_id, type, status, amount, idempotency_key, created_at)
			SELECT ?, u.id, u.customer_id, 'PAYMENT', 'COMPLETED', coalesce((SELECT sum(price) FROM seats), 0), cast(? as text), ?
			FROM updated u
		)
		SELECT u.id, u.customer_id, u.showtime_id, u.created_at, u.expires_at, s.seat_id, s.price
		FROM updated u
		LEFT JOIN seats s ON true
		""";

	private record OccupiedSeatRow(UUID seatId, String status) {}

	private static OccupiedSeatRow mapOccupiedSeat(ResultSet rs, int rowNum) throws SQLException {
		return new OccupiedSeatRow(rs.getObject("seat_id", UUID.class), rs.getString("status"));
	}

	private record PaidBookingRow(
		UUID id,
		UUID customerId,
		UUID showtimeId,
		Instant createdAt,
		Instant expiresAt,
		UUID seatId,
		Long price
	) {}

	private static PaidBookingRow mapPaidBookingRow(ResultSet rs, int rowNum) throws SQLException {
		return new PaidBookingRow(
			rs.getObject("id", UUID.class),
			rs.getObject("customer_id", UUID.class),
			rs.getObject("showtime_id", UUID.class),
			rs.getObject("created_at", OffsetDateTime.class).toInstant(),
			rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
			rs.getObject("seat_id", UUID.class),
			rs.getObject("price") != null ? rs.getLong("price") : null
		);
	}

	private final BookingEntityRepository jpa;
	private final TransactionOperations transaction;
	private final SeatInventoryStrategy strategy;
	private final BookingMetrics metrics;
	private final AuditLogRepository auditLogs;
	private final JdbcClient jdbc;
	private final Claim claim;

	@Autowired
	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction,
		@Value("${app.seat-inventory.strategy:unique}") String strategyValue,
		ObjectProvider<BookingMetrics> metricsProvider,
		ObjectProvider<AuditLogRepository> auditLogsProvider,
		ObjectProvider<JdbcClient> jdbcProvider) {
		this(jpa, transaction, SeatInventoryStrategy.from(strategyValue),
			metricsProvider != null ? metricsProvider.getIfAvailable(BookingMetrics::noop) : BookingMetrics.noop(),
			auditLogsProvider != null ? auditLogsProvider.getIfAvailable(AuditLogRepository::noop)
				: AuditLogRepository.noop(),
			jdbcProvider != null ? jdbcProvider.getIfAvailable() : null);
	}

	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction, String strategyValue) {
		this(jpa, transaction, SeatInventoryStrategy.from(strategyValue), BookingMetrics.noop(),
			AuditLogRepository.noop(), null);
	}

	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction,
		SeatInventoryStrategy strategy) {
		this(jpa, transaction, strategy, BookingMetrics.noop(), AuditLogRepository.noop(), null);
	}

	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction,
		SeatInventoryStrategy strategy, BookingMetrics metrics) {
		this(jpa, transaction, strategy, metrics, AuditLogRepository.noop(), null);
	}

	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction,
		SeatInventoryStrategy strategy, BookingMetrics metrics, AuditLogRepository auditLogs) {
		this(jpa, transaction, strategy, metrics, auditLogs, null);
	}

	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction,
		SeatInventoryStrategy strategy, BookingMetrics metrics, AuditLogRepository auditLogs, JdbcClient jdbc) {
		this.jpa = jpa;
		this.transaction = transaction;
		this.strategy = strategy;
		this.metrics = metrics != null ? metrics : BookingMetrics.noop();
		this.auditLogs = auditLogs != null ? auditLogs : AuditLogRepository.noop();
		this.jdbc = jdbc;
		this.claim = switch (strategy) {
			case UNIQUE, SINGLE_WRITER -> new UniqueClaim(jpa, transaction, this.metrics, strategy.value());
			case SHOWTIME_LOCK -> new ShowtimeLockClaim(jpa, transaction, this.metrics, strategy.value());
			case SEAT_ROWS -> new SeatRowsClaim(jpa, transaction, this.metrics, strategy.value());
		};
	}

	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction) {
		this(jpa, transaction, SeatInventoryStrategy.UNIQUE, BookingMetrics.noop(), AuditLogRepository.noop(), null);
	}

	public JpaSeatInventory(BookingEntityRepository jpa, TransactionOperations transaction, Claim claim) {
		this.jpa = jpa;
		this.transaction = transaction;
		this.strategy = SeatInventoryStrategy.UNIQUE;
		this.metrics = BookingMetrics.noop();
		this.auditLogs = AuditLogRepository.noop();
		this.jdbc = null;
		this.claim = claim;
	}

	public SeatInventoryStrategy strategy() {
		return strategy;
	}

	/** Not {@code @Transactional}: each attempt runs in its own transaction, so a deadlock loser can retry. */
	@Override
	public HoldOutcome hold(Booking pending, Instant now) {
		long start = System.nanoTime();
		HoldOutcome outcome = null;
		try {
			try {
				outcome = claim.hold(pending, now);
			} catch (PessimisticLockingFailureException deadlock) {
				metrics.recordDeadlockRetry(strategy.value(), "retried");
				// Overlapping lapsed-Hold deletes can deadlock even when the two Bookings want different Seats.
				try {
					outcome = claim.hold(pending, now);
				} catch (PessimisticLockingFailureException again) {
					metrics.recordDeadlockRetry(strategy.value(), "exhausted");
					outcome = new HoldOutcome.Busy();
				}
			}
			return outcome;
		} finally {
			metrics.recordOperation("hold", strategy.value(), holdOutcomeTag(outcome), System.nanoTime() - start);
		}
	}

	/**
	 * When the conditional update changes nothing, the row is read again in the same transaction. Each statement
	 * sees what was committed when it started, so the read sees a concurrent payment the update waited for. A single
	 * statement (a data-modifying CTE) would not: its outer select shares the snapshot taken before the wait.
	 */
	@Override
	public PayOutcome pay(UUID customerId, UUID bookingId, Instant now) {
		return executePay(customerId, bookingId, now, null, null);
	}

	@Override
	public PayOutcome pay(UUID customerId, UUID bookingId, Instant now, String idempotencyKey) {
		return executePay(customerId, bookingId, now, idempotencyKey, null);
	}

	@Override
	public PayOutcome pay(Booking booking, Instant now) {
		return executePay(booking.customerId(), booking.id(), now, null, booking);
	}

	private PayOutcome executePay(UUID customerId, UUID bookingId, Instant now, String idempotencyKey,
		Booking preloadedBooking) {
		long start = System.nanoTime();
		long[] bodyEnd = {-1L};
		boolean[] rollbackOnly = {false};
		PayOutcome outcome = null;
		try {
			try {
				outcome = transaction.execute(status -> {
					PayOutcome result;
					if (idempotencyKey != null) {
						Optional<BookingEntityRepository.PaymentTxLookup> existing = metrics.timeDbQuery(
							"pay", "idempotency_lookup", strategy.value(),
							() -> jpa.findPaymentByIdempotencyKey(customerId, idempotencyKey));
						if (existing.isPresent()) {
							BookingEntityRepository.PaymentTxLookup tx = existing.get();
							if (!tx.getBookingId().equals(bookingId)) {
								status.setRollbackOnly();
								rollbackOnly[0] = true;
								return new PayOutcome.IdempotencyKeyConflict();
							}
							if ("REFUNDED".equals(tx.getStatus())) {
								return new PayOutcome.NotPayable(BookingStatus.EXPIRED);
							}
							result = reloadBookingForReplay(customerId, bookingId, now);
							rollbackOnly[0] = status.isRollbackOnly();
							bodyEnd[0] = System.nanoTime();
							return result;
						}
					}

					if (preloadedBooking != null) {
						int updated = metrics.timeDbQuery("pay", "confirm_update", strategy.value(),
							() -> jpa.confirm(bookingId, customerId, now));
						if (updated == 1) {
							Booking confirmed = preloadedBooking.confirmed();
							metrics.timeDbQuery("pay", "record_payment_tx", strategy.value(),
								() -> jpa.insertPaymentTransaction(UUID.randomUUID(), confirmed.id(), customerId,
									"PAYMENT", "COMPLETED", confirmed.totalPrice(), idempotencyKey, now));
							result = new PayOutcome.Paid(confirmed);
						} else {
							result = fallbackNotPaid(customerId, bookingId, now, idempotencyKey);
						}
					} else if (jdbc != null) {
						UUID txId = UUID.randomUUID();
						OffsetDateTime nowOffset = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
						List<PaidBookingRow> rows = metrics.timeDbQuery("pay", "confirm_update", strategy.value(),
							() -> jdbc.sql(PAY_CONFIRM_AND_INSERT_SQL)
								.param(1, bookingId)
								.param(2, customerId)
								.param(3, nowOffset)
								.param(4, bookingId)
								.param(5, txId)
								.param(6, idempotencyKey)
								.param(7, nowOffset)
								.query(JpaSeatInventory::mapPaidBookingRow)
								.list());

						if (!rows.isEmpty()) {
							PaidBookingRow first = rows.get(0);
							List<BookedSeat> seats = rows.stream()
								.filter(r -> r.seatId() != null)
								.map(r -> new BookedSeat(r.seatId(), r.price() != null ? r.price() : 0L))
								.toList();
							Booking confirmed = new Booking(first.id(), first.customerId(), first.showtimeId(),
								BookingStatus.CONFIRMED, first.createdAt(), first.expiresAt(), seats);
							result = new PayOutcome.Paid(confirmed);
						} else {
							result = fallbackNotPaid(customerId, bookingId, now, idempotencyKey);
						}
					} else {
						int updated = metrics.timeDbQuery("pay", "confirm_update", strategy.value(),
							() -> jpa.confirm(bookingId, customerId, now));
						if (updated == 1) {
							Optional<Booking> confirmedOpt = metrics.timeDbQuery("pay", "reload_booking", strategy.value(),
								() -> jpa.findByIdWithSeats(bookingId)).map(BookingEntity::toBooking);
							if (confirmedOpt.isEmpty()) {
								result = new PayOutcome.Gone();
							} else {
								Booking confirmed = confirmedOpt.get();
								metrics.timeDbQuery("pay", "record_payment_tx", strategy.value(),
									() -> jpa.insertPaymentTransaction(UUID.randomUUID(), confirmed.id(), customerId,
										"PAYMENT", "COMPLETED", confirmed.totalPrice(), idempotencyKey, now));
								result = new PayOutcome.Paid(confirmed);
							}
						} else {
							result = fallbackNotPaid(customerId, bookingId, now, idempotencyKey);
						}
					}
					rollbackOnly[0] = status.isRollbackOnly();
					bodyEnd[0] = System.nanoTime();
					return result;
				});
				if (bodyEnd[0] > 0 && !rollbackOnly[0]) {
					metrics.recordCommit("pay", strategy.value(), System.nanoTime() - bodyEnd[0]);
				}
			} catch (DataIntegrityViolationException e) {
				if (idempotencyKey != null && hasConstraint(e, "uq_payment_tx_customer_idempotency_key")) {
					outcome = resolveIdempotencyRace(customerId, bookingId, idempotencyKey, now);
				} else if (hasConstraint(e, "uq_payment_tx_booking_payment")) {
					outcome = reloadBookingForReplay(customerId, bookingId, now);
				} else {
					throw e;
				}
			}
			return outcome;
		} finally {
			metrics.recordOperation("pay", strategy.value(), payOutcomeTag(outcome), System.nanoTime() - start);
		}
	}

	private PayOutcome fallbackNotPaid(UUID customerId, UUID bookingId, Instant now, String idempotencyKey) {
		Optional<Booking> currentOpt = metrics.timeDbQuery("pay", "reload_booking", strategy.value(),
			() -> jpa.findByIdWithSeats(bookingId)).map(BookingEntity::toBooking);
		if (currentOpt.isEmpty() || !currentOpt.get().customerId().equals(customerId)) {
			return new PayOutcome.Gone();
		}
		Booking current = currentOpt.get();
		BookingStatus currentStatus = current.statusAt(now);
		if (currentStatus == BookingStatus.CONFIRMED) {
			return new PayOutcome.AlreadyPaid(current);
		}
		if (currentStatus == BookingStatus.EXPIRED) {
			String lateKey = idempotencyKey != null ? idempotencyKey
				: "late-refund:" + current.id();
			metrics.timeDbQuery("pay", "record_late_refund_tx", strategy.value(),
				() -> jpa.insertLatePaymentRefund(UUID.randomUUID(), current.id(), customerId,
					current.totalPrice(), lateKey, now));
		}
		return new PayOutcome.NotPayable(currentStatus);
	}

	private PayOutcome resolveIdempotencyRace(UUID customerId, UUID bookingId, String idempotencyKey, Instant now) {
		Optional<BookingEntityRepository.PaymentTxLookup> existing =
			jpa.findPaymentByIdempotencyKey(customerId, idempotencyKey);
		if (existing.isPresent()) {
			BookingEntityRepository.PaymentTxLookup tx = existing.get();
			if (!tx.getBookingId().equals(bookingId)) {
				return new PayOutcome.IdempotencyKeyConflict();
			}
			if ("REFUNDED".equals(tx.getStatus())) {
				return new PayOutcome.NotPayable(BookingStatus.EXPIRED);
			}
		}
		return reloadBookingForReplay(customerId, bookingId, now);
	}

	private PayOutcome reloadBookingForReplay(UUID customerId, UUID bookingId, Instant now) {
		return metrics.timeDbQuery("pay", "reload_booking", strategy.value(),
			() -> jpa.findByIdWithSeats(bookingId)).map(BookingEntity::toBooking)
			.<PayOutcome>map(current -> {
				if (!current.customerId().equals(customerId)) {
					return new PayOutcome.Gone();
				}
				BookingStatus currentStatus = current.statusAt(now);
				return currentStatus == BookingStatus.CONFIRMED ? new PayOutcome.AlreadyPaid(current)
					: new PayOutcome.NotPayable(currentStatus);
			}).orElseGet(PayOutcome.Gone::new);
	}

	/** Like {@link #pay}, the row is read again in the same transaction when nothing was deleted. */
	@Override
	public AbandonOutcome abandon(UUID customerId, UUID bookingId) {
		long start = System.nanoTime();
		long[] bodyEnd = {-1L};
		boolean[] rollbackOnly = {false};
		AbandonOutcome outcome = null;
		try {
			outcome = transaction.execute(status -> {
				AbandonOutcome result;
				int deleted = metrics.timeDbQuery("abandon", "delete_pending", strategy.value(),
					() -> jpa.deletePending(bookingId, customerId));
				if (deleted == 1) {
					result = new AbandonOutcome.Abandoned();
				} else {
					result = metrics.timeDbQuery("abandon", "reload_booking", strategy.value(),
						() -> jpa.findById(bookingId)).map(BookingEntity::toBooking)
						.<AbandonOutcome>map(current -> {
							if (!current.customerId().equals(customerId)) {
								return new AbandonOutcome.Gone();
							}
							return new AbandonOutcome.NotPending(current.status());
						}).orElseGet(AbandonOutcome.Gone::new);
				}
				rollbackOnly[0] = status.isRollbackOnly();
				bodyEnd[0] = System.nanoTime();
				return result;
			});
			if (bodyEnd[0] > 0 && !rollbackOnly[0]) {
				metrics.recordCommit("abandon", strategy.value(), System.nanoTime() - bodyEnd[0]);
			}
			return outcome;
		} finally {
			metrics.recordOperation("abandon", strategy.value(), abandonOutcomeTag(outcome), System.nanoTime() - start);
		}
	}

	@Override
	public AbandonOutcome abandon(Booking booking) {
		return abandon(booking.customerId(), booking.id());
	}

	/** Only rows that pass {@code HoldSql.OCCUPIES} come back: a Pending one is a live Hold, the others were sold. */
	@Override
	public Map<UUID, SeatStatus> statuses(UUID showtimeId, Instant now) {
		return metrics.timeDbQuery("statuses", "find_occupied_seats", strategy.value(),
			() -> {
				if (jdbc == null) {
					return jpa.findOccupiedSeats(showtimeId, now).stream().collect(Collectors.toMap(
						BookingEntityRepository.OccupiedSeat::getSeatId,
						seat -> BookingStatus.valueOf(seat.getStatus()) == BookingStatus.PENDING ? SeatStatus.HELD
							: SeatStatus.SOLD));
				}
				Map<UUID, SeatStatus> map = new HashMap<>();
				jdbc.sql(FIND_OCCUPIED_SEATS_SQL)
					.param(1, showtimeId)
					.param(2, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
					.query(JpaSeatInventory::mapOccupiedSeat)
					.forEach(row -> map.put(row.seatId(),
						"PENDING".equals(row.status()) ? SeatStatus.HELD : SeatStatus.SOLD));
				return map;
			});
	}

	/** The order of ADR 0005; see it before changing anything here. */
	@Override
	@Transactional
	public CancelOutcome cancel(UUID showtimeId, Cancellation cancellation, Instant now) {
		return cancel(null, showtimeId, cancellation, now);
	}

	@Override
	@Transactional
	public CancelOutcome cancel(UUID actorId, UUID showtimeId, Cancellation cancellation, Instant now) {
		if (jpa.cancelShowtime(showtimeId, cancellation.reason().name(), cancellation.note(), now) == 0) {
			return jpa.existsShowtime(showtimeId) ? new CancelOutcome.NotCancellable() : new CancelOutcome.NotFound();
		}
		// Delete before refunding: a payment that commits in between is then refunded, never left Confirmed.
		int deletedPending = jpa.deletePendingBookings(showtimeId);
		int refundedConfirmed = jpa.refundConfirmedBookings(showtimeId);
		jpa.insertRefundTransactionsForShowtime(showtimeId, now);
		if (actorId != null) {
			String details = AuditDetails.builder()
				.put("reason", cancellation.reason().name())
				.put("note", cancellation.note())
				.put("deletedPendingBookings", deletedPending)
				.put("refundedConfirmedBookings", refundedConfirmed)
				.toJson();
			auditLogs.append(AuditLog.of(actorId, AuditAction.SHOWTIME_CANCELLED, AuditTargetType.SHOWTIME,
				showtimeId, details, now));
		}
		return new CancelOutcome.Cancelled();
	}

	/** Not {@code @Transactional}: the failed transaction is rolled back before the outcome is returned. */
	@Override
	public DeleteOutcome delete(UUID showtimeId, Instant now) {
		return delete(null, showtimeId, now);
	}

	@Override
	public DeleteOutcome delete(UUID actorId, UUID showtimeId, Instant now) {
		try {
			return transaction.execute(status -> {
				int purgedLapsedHolds = jpa.deleteLapsedHoldsOfShowtime(showtimeId, now);
				int rows = jpa.deleteShowtime(showtimeId);
				if (rows == 0) {
					return new DeleteOutcome.NotFound();
				}
				if (actorId != null) {
					String details = AuditDetails.builder()
						.put("purgedLapsedHolds", purgedLapsedHolds)
						.toJson();
					auditLogs.append(AuditLog.of(actorId, AuditAction.SHOWTIME_DELETED, AuditTargetType.SHOWTIME,
						showtimeId, details, now));
				}
				return new DeleteOutcome.Deleted();
			});
		} catch (DataIntegrityViolationException e) {
			if (hasConstraint(e, "fk_booking_showtime")) {
				return new DeleteOutcome.HasBookings();
			}
			throw e;
		}
	}

	private static String holdOutcomeTag(HoldOutcome outcome) {
		if (outcome == null) {
			return "error";
		}
		return switch (outcome) {
			case HoldOutcome.Held ignored -> "held";
			case HoldOutcome.SeatsTaken ignored -> "seats_taken";
			case HoldOutcome.ShowtimeCancelled ignored -> "showtime_cancelled";
			case HoldOutcome.ShowtimeGone ignored -> "showtime_gone";
			case HoldOutcome.CustomerHasPending ignored -> "customer_has_pending";
			case HoldOutcome.Busy ignored -> "busy";
		};
	}

	private static String payOutcomeTag(PayOutcome outcome) {
		if (outcome == null) {
			return "error";
		}
		return switch (outcome) {
			case PayOutcome.Paid ignored -> "paid";
			case PayOutcome.AlreadyPaid ignored -> "already_paid";
			case PayOutcome.NotPayable ignored -> "not_payable";
			case PayOutcome.IdempotencyKeyConflict ignored -> "idempotency_key_conflict";
			case PayOutcome.Gone ignored -> "gone";
		};
	}

	private static String abandonOutcomeTag(AbandonOutcome outcome) {
		if (outcome == null) {
			return "error";
		}
		return switch (outcome) {
			case AbandonOutcome.Abandoned ignored -> "abandoned";
			case AbandonOutcome.NotPending ignored -> "not_pending";
			case AbandonOutcome.Gone ignored -> "gone";
		};
	}
}
