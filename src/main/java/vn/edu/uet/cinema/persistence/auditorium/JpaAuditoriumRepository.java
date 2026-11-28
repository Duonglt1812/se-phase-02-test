package vn.edu.uet.cinema.persistence.auditorium;

import static vn.edu.uet.cinema.persistence.ConstraintViolations.hasConstraint;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.audit.AuditLog;
import vn.edu.uet.cinema.business.audit.AuditLogRepository;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumNameTakenException;
import vn.edu.uet.cinema.business.auditorium.AuditoriumRepository;

@Repository
class JpaAuditoriumRepository implements AuditoriumRepository {

	private final AuditoriumEntityRepository jpa;
	private final JdbcClient jdbc;
	private final AuditLogRepository auditLogs;

	JpaAuditoriumRepository(AuditoriumEntityRepository jpa, JdbcClient jdbc) {
		this(jpa, jdbc, AuditLogRepository.noop());
	}

	@Autowired
	JpaAuditoriumRepository(AuditoriumEntityRepository jpa, JdbcClient jdbc,
		ObjectProvider<AuditLogRepository> auditLogsProvider) {
		this(jpa, jdbc, auditLogsProvider != null ? auditLogsProvider.getIfAvailable(AuditLogRepository::noop)
			: AuditLogRepository.noop());
	}

	JpaAuditoriumRepository(AuditoriumEntityRepository jpa, JdbcClient jdbc, AuditLogRepository auditLogs) {
		this.jpa = jpa;
		this.jdbc = jdbc;
		this.auditLogs = auditLogs != null ? auditLogs : AuditLogRepository.noop();
	}

	@Override
	public List<Auditorium> findByCinemaId(UUID cinemaId) {
		return jdbc.sql("select id, cinema_id, name, rows, seats_per_row from auditorium where cinema_id = ? order by name, id")
			.param(cinemaId)
			.query((rs, rowNum) -> new Auditorium(
				rs.getObject("id", UUID.class),
				rs.getObject("cinema_id", UUID.class),
				rs.getString("name"),
				rs.getInt("rows"),
				rs.getInt("seats_per_row"),
				List.of()))
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Auditorium> findById(UUID id) {
		return jpa.findById(id).map(AuditoriumEntity::toAuditorium);
	}

	@Override
	public boolean existsByCinemaId(UUID cinemaId) {
		return jpa.existsByCinemaId(cinemaId);
	}

	@Override
	public boolean existsByCinemaIdAndName(UUID cinemaId, String name) {
		return jpa.existsByCinemaIdAndName(cinemaId, name);
	}

	@Override
	@Transactional
	public Auditorium save(Auditorium auditorium) {
		return save(auditorium, null);
	}

	@Override
	@Transactional
	public Auditorium save(Auditorium auditorium, AuditLog auditLog) {
		try {
			Auditorium saved = jpa.saveAndFlush(AuditoriumEntity.from(auditorium)).toAuditorium();
			if (auditLog != null) {
				auditLogs.append(auditLog);
			}
			return saved;
		} catch (DataIntegrityViolationException e) {
			if (hasConstraint(e, "uq_auditorium_cinema_name")) {
				throw new AuditoriumNameTakenException();
			}
			throw e;
		}
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
			if (hasConstraint(e, "fk_showtime_auditorium")) {
				throw new ConflictException("Auditorium has Showtimes");
			}
			throw e;
		}
	}
}
