package vn.edu.uet.cinema.business.audit;

import java.util.Objects;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;

/** Read-only query service for administrative audit logs (S4). */
public class AuditLogService {

	private final AuditLogRepository auditLogs;

	public AuditLogService(AuditLogRepository auditLogs) {
		this.auditLogs = Objects.requireNonNull(auditLogs, "auditLogs must not be null");
	}

	public PageSlice<AuditLog> list(PageQuery query, AuditLogFilter filter) {
		PageQuery effectiveQuery = query != null ? query : PageQuery.DEFAULT;
		AuditLogFilter effectiveFilter = filter != null ? filter : AuditLogFilter.NONE;
		return auditLogs.findAll(effectiveQuery, effectiveFilter);
	}
}
