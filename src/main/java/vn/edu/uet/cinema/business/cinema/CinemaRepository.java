package vn.edu.uet.cinema.business.cinema;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.audit.AuditLog;

public interface CinemaRepository {

	List<Cinema> findAll();

	PageSlice<Cinema> findAll(PageQuery query);

	Optional<Cinema> findById(UUID id);

	Cinema save(Cinema cinema);

	/** Saves the cinema and appends the audit entry inside the same database transaction (S4). */
	default Cinema save(Cinema cinema, AuditLog auditLog) {
		return save(cinema);
	}

	void deleteById(UUID id);

	/** Deletes the cinema and appends the audit entry inside the same database transaction (S4). */
	default void deleteById(UUID id, AuditLog auditLog) {
		deleteById(id);
	}
}
