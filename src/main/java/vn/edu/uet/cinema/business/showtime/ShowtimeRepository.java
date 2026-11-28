package vn.edu.uet.cinema.business.showtime;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.uet.cinema.business.audit.AuditLog;

public interface ShowtimeRepository {

	Showtime save(Showtime showtime);

	/** Saves the showtime and appends the audit entry inside the same database transaction (S4). */
	default Showtime save(Showtime showtime, AuditLog auditLog) {
		return save(showtime);
	}

	Optional<Showtime> findById(UUID id);

	List<Showtime> findByStartsAtBetween(Instant fromInclusive, Instant toExclusive, UUID cinemaId, UUID movieId);

	boolean existsOverlapping(UUID auditoriumId, Instant startsAt, Instant endsAt);

	boolean existsByMovieId(UUID movieId);

	boolean existsByAuditoriumId(UUID auditoriumId);
}
