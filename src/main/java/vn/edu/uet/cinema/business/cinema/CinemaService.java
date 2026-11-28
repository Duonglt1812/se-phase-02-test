package vn.edu.uet.cinema.business.cinema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.audit.AuditAction;
import vn.edu.uet.cinema.business.audit.AuditDetails;
import vn.edu.uet.cinema.business.audit.AuditLog;
import vn.edu.uet.cinema.business.audit.AuditTargetType;
import vn.edu.uet.cinema.business.auditorium.AuditoriumRepository;

public class CinemaService {

	private final CinemaRepository cinemas;
	private final AuditoriumRepository auditoriums;

	public CinemaService(CinemaRepository cinemas, AuditoriumRepository auditoriums) {
		this.cinemas = cinemas;
		this.auditoriums = auditoriums;
	}

	public Cinema create(String name, String address) {
		return create(null, name, address, Instant.now());
	}

	public Cinema create(UUID actorId, String name, String address, Instant now) {
		Cinema cinema = new Cinema(UUID.randomUUID(), name, address);
		if (actorId == null) {
			return cinemas.save(cinema);
		}
		String details = AuditDetails.builder()
			.put("name", name)
			.put("address", address)
			.toJson();
		return cinemas.save(cinema, AuditLog.of(actorId, AuditAction.CINEMA_CREATED, AuditTargetType.CINEMA,
			cinema.id(), details, now != null ? now : Instant.now()));
	}

	public List<Cinema> list() {
		return cinemas.findAll();
	}

	public PageSlice<Cinema> list(PageQuery query) {
		return cinemas.findAll(query);
	}

	public Cinema get(UUID id) {
		return cinemas.findById(id).orElseThrow(() -> new NotFoundException("Cinema not found"));
	}

	public Cinema update(UUID id, String name, String address) {
		return update(null, id, name, address, Instant.now());
	}

	public Cinema update(UUID actorId, UUID id, String name, String address, Instant now) {
		get(id);
		Cinema updated = new Cinema(id, name, address);
		if (actorId == null) {
			return cinemas.save(updated);
		}
		String details = AuditDetails.builder()
			.put("name", name)
			.put("address", address)
			.toJson();
		return cinemas.save(updated, AuditLog.of(actorId, AuditAction.CINEMA_UPDATED, AuditTargetType.CINEMA,
			id, details, now != null ? now : Instant.now()));
	}

	public void delete(UUID id) {
		delete(null, id, Instant.now());
	}

	public void delete(UUID actorId, UUID id, Instant now) {
		Cinema existing = get(id);
		if (auditoriums.existsByCinemaId(id)) {
			throw new ConflictException("Cinema has Auditoriums");
		}
		if (actorId == null) {
			cinemas.deleteById(id);
			return;
		}
		String details = AuditDetails.builder()
			.put("name", existing.name())
			.toJson();
		cinemas.deleteById(id, AuditLog.of(actorId, AuditAction.CINEMA_DELETED, AuditTargetType.CINEMA,
			id, details, now != null ? now : Instant.now()));
	}
}
