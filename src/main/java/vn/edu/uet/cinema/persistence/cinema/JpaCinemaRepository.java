package vn.edu.uet.cinema.persistence.cinema;

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
import vn.edu.uet.cinema.business.cinema.Cinema;
import vn.edu.uet.cinema.business.cinema.CinemaRepository;

@Repository
class JpaCinemaRepository implements CinemaRepository {

	private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"));

	private final CinemaEntityRepository jpa;
	private final AuditLogRepository auditLogs;

	JpaCinemaRepository(CinemaEntityRepository jpa) {
		this(jpa, AuditLogRepository.noop());
	}

	@Autowired
	JpaCinemaRepository(CinemaEntityRepository jpa, ObjectProvider<AuditLogRepository> auditLogsProvider) {
		this(jpa, auditLogsProvider != null ? auditLogsProvider.getIfAvailable(AuditLogRepository::noop)
			: AuditLogRepository.noop());
	}

	JpaCinemaRepository(CinemaEntityRepository jpa, AuditLogRepository auditLogs) {
		this.jpa = jpa;
		this.auditLogs = auditLogs != null ? auditLogs : AuditLogRepository.noop();
	}

	@Override
	public List<Cinema> findAll() {
		return jpa.findAll().stream().map(CinemaEntity::toCinema).toList();
	}

	@Override
	public PageSlice<Cinema> findAll(PageQuery query) {
		Page<CinemaEntity> page = jpa.findAll(PageRequest.of(query.page(), query.size(), DEFAULT_SORT));
		return new PageSlice<>(page.getContent().stream().map(CinemaEntity::toCinema).toList(),
			query.page(), query.size(), page.getTotalElements());
	}

	@Override
	public Optional<Cinema> findById(UUID id) {
		return jpa.findById(id).map(CinemaEntity::toCinema);
	}

	@Override
	public Cinema save(Cinema cinema) {
		return jpa.save(CinemaEntity.from(cinema)).toCinema();
	}

	@Override
	@Transactional
	public Cinema save(Cinema cinema, AuditLog auditLog) {
		Cinema saved = jpa.saveAndFlush(CinemaEntity.from(cinema)).toCinema();
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
			if (hasConstraint(e, "fk_auditorium_cinema")) {
				throw new ConflictException("Cinema has Auditoriums");
			}
			throw e;
		}
	}
}
