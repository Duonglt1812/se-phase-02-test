package vn.edu.uet.cinema.persistence.movie;

import static vn.edu.uet.cinema.persistence.ConstraintViolations.hasConstraint;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.audit.AuditLog;
import vn.edu.uet.cinema.business.audit.AuditLogRepository;
import vn.edu.uet.cinema.business.movie.Movie;
import vn.edu.uet.cinema.business.movie.MovieRepository;

@Repository
class JpaMovieRepository implements MovieRepository {

	private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("title"), Sort.Order.asc("id"));

	private final MovieEntityRepository jpa;
	private final AuditLogRepository auditLogs;

	JpaMovieRepository(MovieEntityRepository jpa) {
		this(jpa, AuditLogRepository.noop());
	}

	@Autowired
	JpaMovieRepository(MovieEntityRepository jpa, ObjectProvider<AuditLogRepository> auditLogsProvider) {
		this(jpa, auditLogsProvider != null ? auditLogsProvider.getIfAvailable(AuditLogRepository::noop)
			: AuditLogRepository.noop());
	}

	JpaMovieRepository(MovieEntityRepository jpa, AuditLogRepository auditLogs) {
		this.jpa = jpa;
		this.auditLogs = auditLogs != null ? auditLogs : AuditLogRepository.noop();
	}

	@Override
	public List<Movie> findAll() {
		return jpa.findAll().stream().map(MovieEntity::toMovie).toList();
	}

	@Override
	public PageSlice<Movie> findAll(PageQuery query) {
		Page<MovieEntity> page = jpa.findAll(PageRequest.of(query.page(), query.size(), DEFAULT_SORT));
		return new PageSlice<>(page.getContent().stream().map(MovieEntity::toMovie).toList(),
			query.page(), query.size(), page.getTotalElements());
	}

	@Override
	public Optional<Movie> findById(UUID id) {
		return jpa.findById(id).map(MovieEntity::toMovie);
	}

	@Override
	public Movie save(Movie movie) {
		return jpa.save(MovieEntity.from(movie)).toMovie();
	}

	@Override
	@Transactional
	public Movie save(Movie movie, AuditLog auditLog) {
		Movie saved = jpa.saveAndFlush(MovieEntity.from(movie)).toMovie();
		if (auditLog != null) {
			auditLogs.append(auditLog);
		}
		return saved;
	}

	@Override
	@Transactional
	public void deleteById(UUID id) {
		deleteById(id, null);
	}

	@Override
	@Transactional
	public void deleteById(UUID id, AuditLog auditLog) {
		try {
			jpa.deleteById(id);
			jpa.flush();
			if (auditLog != null) {
				auditLogs.append(auditLog);
			}
		} catch (DataIntegrityViolationException e) {
			if (hasConstraint(e, "fk_showtime_movie")) {
				throw new ConflictException("Movie has Showtimes");
			}
			throw e;
		}
	}
}
