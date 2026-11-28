package vn.edu.uet.cinema.business.movie;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.audit.AuditLog;

public interface MovieRepository {

	List<Movie> findAll();

	PageSlice<Movie> findAll(PageQuery query);

	Optional<Movie> findById(UUID id);

	Movie save(Movie movie);

	/** Saves the movie and appends the audit entry inside the same database transaction (S4). */
	default Movie save(Movie movie, AuditLog auditLog) {
		return save(movie);
	}

	void deleteById(UUID id);

	/** Deletes the movie and appends the audit entry inside the same database transaction (S4). */
	default void deleteById(UUID id, AuditLog auditLog) {
		deleteById(id);
	}
}
