package vn.edu.uet.cinema.business.auditorium;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.uet.cinema.business.audit.AuditLog;

public interface AuditoriumRepository {

	List<Auditorium> findByCinemaId(UUID cinemaId);

	Optional<Auditorium> findById(UUID id);

	boolean existsByCinemaId(UUID cinemaId);

	boolean existsByCinemaIdAndName(UUID cinemaId, String name);

	Auditorium save(Auditorium auditorium);

	/** Saves the auditorium and appends the audit entry inside the same database transaction (S4). */
	default Auditorium save(Auditorium auditorium, AuditLog auditLog) {
		return save(auditorium);
	}

	void deleteById(UUID id);

	/** Deletes the auditorium and appends the audit entry inside the same database transaction (S4). */
	default void deleteById(UUID id, AuditLog auditLog) {
		deleteById(id);
	}
}
