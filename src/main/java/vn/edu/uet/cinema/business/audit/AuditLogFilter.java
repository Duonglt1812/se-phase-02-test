package vn.edu.uet.cinema.business.audit;

import java.util.UUID;

/** Optional filter criteria for querying {@link AuditLog} entries. */
public record AuditLogFilter(
	AuditAction action,
	AuditTargetType targetType,
	UUID targetId,
	UUID actorId) {

	public static final AuditLogFilter NONE = new AuditLogFilter(null, null, null, null);
}
