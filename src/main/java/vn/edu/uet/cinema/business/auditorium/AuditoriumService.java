package vn.edu.uet.cinema.business.auditorium;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.audit.AuditAction;
import vn.edu.uet.cinema.business.audit.AuditDetails;
import vn.edu.uet.cinema.business.audit.AuditLog;
import vn.edu.uet.cinema.business.audit.AuditTargetType;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.showtime.ShowtimeRepository;

public class AuditoriumService {

	private final CinemaService cinemas;
	private final AuditoriumRepository auditoriums;
	private final ShowtimeRepository showtimes;

	public AuditoriumService(CinemaService cinemas, AuditoriumRepository auditoriums, ShowtimeRepository showtimes) {
		this.cinemas = cinemas;
		this.auditoriums = auditoriums;
		this.showtimes = showtimes;
	}

	public Auditorium create(UUID cinemaId, String name, int rows, int seatsPerRow, List<String> vipRows,
		List<String> coupleRows) {
		return create(null, cinemaId, name, rows, seatsPerRow, vipRows, coupleRows, Instant.now());
	}

	public Auditorium create(UUID actorId, UUID cinemaId, String name, int rows, int seatsPerRow, List<String> vipRows,
		List<String> coupleRows, Instant now) {
		cinemas.get(cinemaId);
		if (auditoriums.existsByCinemaIdAndName(cinemaId, name)) {
			throw new AuditoriumNameTakenException();
		}
		Auditorium auditorium = new Auditorium(UUID.randomUUID(), cinemaId, name, rows, seatsPerRow,
			SeatLayout.generate(rows, seatsPerRow, vipRows, coupleRows));
		if (actorId == null) {
			return auditoriums.save(auditorium);
		}
		String details = AuditDetails.builder()
			.put("cinemaId", cinemaId)
			.put("name", name)
			.put("rows", rows)
			.put("seatsPerRow", seatsPerRow)
			.toJson();
		return auditoriums.save(auditorium, AuditLog.of(actorId, AuditAction.AUDITORIUM_CREATED,
			AuditTargetType.AUDITORIUM, auditorium.id(), details, now != null ? now : Instant.now()));
	}

	public List<Auditorium> list(UUID cinemaId) {
		cinemas.get(cinemaId);
		return auditoriums.findByCinemaId(cinemaId);
	}

	public Auditorium get(UUID id) {
		return auditoriums.findById(id).orElseThrow(() -> new NotFoundException("Auditorium not found"));
	}

	public void delete(UUID id) {
		delete(null, id, Instant.now());
	}

	public void delete(UUID actorId, UUID id, Instant now) {
		Auditorium existing = get(id);
		if (showtimes.existsByAuditoriumId(id)) {
			throw new ConflictException("Auditorium has Showtimes");
		}
		if (actorId == null) {
			auditoriums.deleteById(id);
			return;
		}
		String details = AuditDetails.builder()
			.put("cinemaId", existing.cinemaId())
			.put("name", existing.name())
			.toJson();
		auditoriums.deleteById(id, AuditLog.of(actorId, AuditAction.AUDITORIUM_DELETED, AuditTargetType.AUDITORIUM,
			id, details, now != null ? now : Instant.now()));
	}
}
