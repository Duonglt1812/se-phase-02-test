package vn.edu.uet.cinema.persistence.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.audit.AuditAction;
import vn.edu.uet.cinema.business.audit.AuditLog;
import vn.edu.uet.cinema.business.audit.AuditLogFilter;
import vn.edu.uet.cinema.business.audit.AuditLogRepository;
import vn.edu.uet.cinema.business.audit.AuditTargetType;

/**
 * Append-only PostgreSQL adapter for {@link AuditLogRepository} (S4).
 * Uses {@link JdbcClient} so writes execute inside the caller's active Spring transaction and persist
 * structured {@code jsonb} details directly.
 */
@Repository
public class JpaAuditLogRepository implements AuditLogRepository {

	private static final String INSERT_SQL = """
		insert into audit_log (id, actor_id, action, target_type, target_id, details, created_at)
		values (?, ?, ?, ?, ?, cast(? as jsonb), ?)
		""";

	private final JdbcClient jdbc;

	public JpaAuditLogRepository(JdbcClient jdbc) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
	}

	@Override
	@Transactional
	public AuditLog append(AuditLog entry) {
		Objects.requireNonNull(entry, "entry must not be null");
		jdbc.sql(INSERT_SQL)
			.param(entry.id())
			.param(entry.actorId())
			.param(entry.action().name())
			.param(entry.targetType().name())
			.param(entry.targetId())
			.param(entry.details())
			.param(OffsetDateTime.ofInstant(entry.createdAt(), ZoneOffset.UTC))
			.update();
		return entry;
	}

	@Override
	@Transactional(readOnly = true)
	public PageSlice<AuditLog> findAll(PageQuery query, AuditLogFilter filter) {
		AuditLogFilter f = filter != null ? filter : AuditLogFilter.NONE;
		StringBuilder where = new StringBuilder(" where 1 = 1");
		List<Object> params = new ArrayList<>();
		if (f.action() != null) {
			where.append(" and action = ?");
			params.add(f.action().name());
		}
		if (f.targetType() != null) {
			where.append(" and target_type = ?");
			params.add(f.targetType().name());
		}
		if (f.targetId() != null) {
			where.append(" and target_id = ?");
			params.add(f.targetId());
		}
		if (f.actorId() != null) {
			where.append(" and actor_id = ?");
			params.add(f.actorId());
		}

		JdbcClient.StatementSpec countSpec = jdbc.sql("select count(*) from audit_log" + where);
		for (Object param : params) {
			countSpec = countSpec.param(param);
		}
		long total = countSpec.query(Long.class).single();
		if (total == 0) {
			return new PageSlice<>(List.of(), query.page(), query.size(), 0);
		}

		String selectSql = """
			select id, actor_id, action, target_type, target_id, details::text as details, created_at
			from audit_log""" + where + " order by created_at desc, id desc limit ? offset ?";
		JdbcClient.StatementSpec selectSpec = jdbc.sql(selectSql);
		for (Object param : params) {
			selectSpec = selectSpec.param(param);
		}
		selectSpec = selectSpec.param(query.size()).param(query.offset());
		List<AuditLog> items = selectSpec.query(JpaAuditLogRepository::mapRow).list();
		return new PageSlice<>(items, query.page(), query.size(), total);
	}

	private static AuditLog mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new AuditLog(
			rs.getObject("id", UUID.class),
			rs.getObject("actor_id", UUID.class),
			AuditAction.valueOf(rs.getString("action")),
			AuditTargetType.valueOf(rs.getString("target_type")),
			rs.getObject("target_id", UUID.class),
			rs.getString("details"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}
}
