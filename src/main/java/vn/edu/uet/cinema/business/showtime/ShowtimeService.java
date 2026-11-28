package vn.edu.uet.cinema.business.showtime;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.audit.AuditAction;
import vn.edu.uet.cinema.business.audit.AuditDetails;
import vn.edu.uet.cinema.business.audit.AuditLog;
import vn.edu.uet.cinema.business.audit.AuditTargetType;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.auditorium.Seat;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.inventory.CancelOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.Cancellation;
import vn.edu.uet.cinema.business.showtime.inventory.CancellationReason;
import vn.edu.uet.cinema.business.showtime.inventory.DeleteOutcome;
import vn.edu.uet.cinema.business.showtime.inventory.SeatInventory;
import vn.edu.uet.cinema.business.showtime.inventory.SeatStatus;

public class ShowtimeService {

	private static final int MAX_NOTE_LENGTH = 200;

	private final MovieService movies;
	private final AuditoriumService auditoriums;
	private final ShowtimeRepository showtimes;
	private final SeatInventory inventory;
	private final Map<UUID, List<Seat>> layouts = new ConcurrentHashMap<>();
	private final Map<UUID, Map<UUID, Seat>> seatsByIdCache = new ConcurrentHashMap<>();
	private final Map<UUID, Showtime> scheduledCache = new ConcurrentHashMap<>();

	public ShowtimeService(MovieService movies, AuditoriumService auditoriums, ShowtimeRepository showtimes,
		SeatInventory inventory) {
		this.movies = movies;
		this.auditoriums = auditoriums;
		this.showtimes = showtimes;
		this.inventory = inventory;
	}

	public Showtime create(UUID movieId, UUID auditoriumId, Instant startsAt, long basePrice) {
		return create(null, movieId, auditoriumId, startsAt, basePrice, Instant.now());
	}

	public Showtime create(UUID actorId, UUID movieId, UUID auditoriumId, Instant startsAt, long basePrice,
		Instant now) {
		if (basePrice <= 0) {
			throw new InvalidShowtimeException("Base price must be positive");
		}
		if (basePrice > SeatPricing.MAX_BASE_PRICE) {
			throw new InvalidShowtimeException("Base price must be at most " + SeatPricing.MAX_BASE_PRICE);
		}
		int durationMinutes = movies.get(movieId).durationMinutes();
		auditoriums.get(auditoriumId);
		Instant endsAt = startsAt.plusSeconds(((long) durationMinutes + 15) * 60);
		if (showtimes.existsOverlapping(auditoriumId, startsAt, endsAt)) {
			throw new ConflictException("Auditorium has an overlapping Showtime");
		}
		Showtime showtime = new Showtime(UUID.randomUUID(), movieId, auditoriumId, startsAt, endsAt, basePrice);
		if (actorId == null) {
			return showtimes.save(showtime);
		}
		Instant timestamp = now != null ? now : Instant.now();
		String details = AuditDetails.builder()
			.put("movieId", movieId)
			.put("auditoriumId", auditoriumId)
			.put("startsAt", startsAt)
			.put("endsAt", endsAt)
			.put("basePrice", basePrice)
			.toJson();
		AuditLog auditLog = AuditLog.of(actorId, AuditAction.SHOWTIME_CREATED, AuditTargetType.SHOWTIME,
			showtime.id(), details, timestamp);
		Showtime saved = showtimes.save(showtime, auditLog);
		if (saved.status() == ShowtimeStatus.SCHEDULED) {
			scheduledCache.put(saved.id(), saved);
		}
		return saved;
	}

	public Showtime get(UUID id) {
		Showtime cached = scheduledCache.get(id);
		if (cached != null) {
			return cached;
		}
		Showtime showtime = showtimes.findById(id).orElseThrow(() -> new NotFoundException("Showtime not found"));
		if (showtime.status() == ShowtimeStatus.SCHEDULED) {
			scheduledCache.putIfAbsent(id, showtime);
		}
		return showtime;
	}

	/** A Showtime whose Seats can still be chosen: a Cancelled one is a conflict. */
	public Showtime getScheduled(UUID id) {
		Showtime showtime = get(id);
		if (showtime.status() == ShowtimeStatus.CANCELLED) {
			scheduledCache.remove(id);
			throw new ConflictException("Showtime is Cancelled");
		}
		return showtime;
	}

	/** The Seats of the Showtime's Auditorium in layout order, each priced from its Seat Type, keyed by Seat id. */
	public Map<UUID, PricedSeat> pricedSeats(Showtime showtime) {
		Map<UUID, PricedSeat> priced = new LinkedHashMap<>();
		layout(showtime.auditoriumId()).forEach(seat -> priced.put(seat.id(),
			new PricedSeat(seat, SeatPricing.price(seat.type(), showtime.basePrice()))));
		return priced;
	}

	/** The Seats of an Auditorium keyed by Seat id, cached without allocating per-request pricing wrappers. */
	public Map<UUID, Seat> seatsById(UUID auditoriumId) {
		Map<UUID, Seat> byId = seatsByIdCache.get(auditoriumId);
		if (byId != null) {
			return byId;
		}
		Map<UUID, Seat> computed = new LinkedHashMap<>();
		for (Seat seat : layout(auditoriumId)) {
			computed.put(seat.id(), seat);
		}
		Map<UUID, Seat> immutable = Collections.unmodifiableMap(computed);
		Map<UUID, Seat> existing = seatsByIdCache.putIfAbsent(auditoriumId, immutable);
		return existing != null ? existing : immutable;
	}

	/**
	 * An Auditorium's Seats, read once (ADR 0008). Auditoriums are not edited, and one with a Showtime cannot be
	 * deleted (ADR 0006), so the layout behind a Showtime is fixed on every instance. A change that lets an
	 * Auditorium's Seats change must evict it here.
	 */
	private List<Seat> layout(UUID auditoriumId) {
		// get() never locks; computeIfAbsent() can lock its bin even for a key that is already there.
		List<Seat> seats = layouts.get(auditoriumId);
		return seats != null ? seats : layouts.computeIfAbsent(auditoriumId, id -> auditoriums.get(id).seats());
	}

	/** Every Seat of a Scheduled Showtime with its price and status at {@code now}. */
	public List<SeatMapEntry> seatMap(UUID id, Instant now) {
		Showtime showtime = getScheduled(id);
		Map<UUID, SeatStatus> statuses = inventory.statuses(id, now);
		long basePrice = showtime.basePrice();
		return layout(showtime.auditoriumId()).stream()
			.map(seat -> new SeatMapEntry(
				new PricedSeat(seat, SeatPricing.price(seat.type(), basePrice)),
				statuses.getOrDefault(seat.id(), SeatStatus.AVAILABLE)))
			.toList();
	}

	public List<Showtime> list(Instant fromInclusive, Instant toExclusive, UUID cinemaId, UUID movieId) {
		return showtimes.findByStartsAtBetween(fromInclusive, toExclusive, cinemaId, movieId);
	}

	/** Its Pending Bookings are deleted and its Confirmed ones become Refunded. A blank note counts as no note. */
	public Showtime cancel(UUID id, CancellationReason reason, String note, Instant now) {
		return cancel(null, id, reason, note, now);
	}

	/** Cancels the Showtime and records an atomic audit entry when {@code actorId} is non-null (S4). */
	public Showtime cancel(UUID actorId, UUID id, CancellationReason reason, String note, Instant now) {
		if (note != null && note.length() > MAX_NOTE_LENGTH) {
			throw new InvalidShowtimeException("A cancellation note has at most " + MAX_NOTE_LENGTH + " characters");
		}
		String given = note == null || note.isBlank() ? null : note;
		if (reason == CancellationReason.OTHER && given == null) {
			throw new InvalidShowtimeException("A Cancellation Reason of Other needs a note");
		}
		Cancellation cancellation = new Cancellation(reason, given);
		CancelOutcome outcome = actorId == null
			? inventory.cancel(id, cancellation, now)
			: inventory.cancel(actorId, id, cancellation, now);
		scheduledCache.remove(id);
		return switch (outcome) {
			case CancelOutcome.Cancelled cancelled -> {
				Showtime showtime = get(id);
				yield new Showtime(showtime.id(), showtime.movieId(),
					showtime.auditoriumId(), showtime.startsAt(), showtime.endsAt(), showtime.basePrice(),
					ShowtimeStatus.CANCELLED, cancellation);
			}
			case CancelOutcome.NotCancellable notCancellable ->
				throw new ConflictException("Showtime is already Cancelled or has ended");
			case CancelOutcome.NotFound notFound ->
				throw new NotFoundException("Showtime not found");
		};
	}

	public void delete(UUID id, Instant now) {
		delete(null, id, now);
	}

	public void delete(UUID actorId, UUID id, Instant now) {
		scheduledCache.remove(id);
		DeleteOutcome outcome = actorId == null
			? inventory.delete(id, now)
			: inventory.delete(actorId, id, now);
		switch (outcome) {
			case DeleteOutcome.Deleted deleted -> {
			}
			case DeleteOutcome.HasBookings hasBookings -> throw new ConflictException("Showtime has Bookings");
			case DeleteOutcome.NotFound notFound -> throw new NotFoundException("Showtime not found");
		}
	}
}
