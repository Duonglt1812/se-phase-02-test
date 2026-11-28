package vn.edu.uet.cinema.business.audit;

import java.util.List;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;

/**
 * Append-only business port for administrative audit logs (ADR 0001, S4).
 * Intentionally exposes no update or delete methods.
 */
public interface AuditLogRepository {

	/** Appends a single audit entry, participating in the caller's active database transaction when present. */
	AuditLog append(AuditLog entry);

	/** Lists audit entries newest-first matching the optional filter criteria. */
	PageSlice<AuditLog> findAll(PageQuery query, AuditLogFilter filter);

	static AuditLogRepository noop() {
		return Noop.INSTANCE;
	}

	enum Noop implements AuditLogRepository {
		INSTANCE;

		@Override
		public AuditLog append(AuditLog entry) {
			return entry;
		}

		@Override
		public PageSlice<AuditLog> findAll(PageQuery query, AuditLogFilter filter) {
			return new PageSlice<>(List.of(), query.page(), query.size(), 0);
		}
	}
}
