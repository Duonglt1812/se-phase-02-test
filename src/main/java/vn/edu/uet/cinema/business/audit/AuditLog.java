package vn.edu.uet.cinema.business.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable append-only audit entry capturing an administrative mutation (S4). */
public record AuditLog(
	UUID id,
	UUID actorId,
	AuditAction action,
	AuditTargetType targetType,
	UUID targetId,
	String details,
	Instant createdAt) {

	public AuditLog {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(actorId, "actorId must not be null");
		Objects.requireNonNull(action, "action must not be null");
		Objects.requireNonNull(targetType, "targetType must not be null");
		Objects.requireNonNull(targetId, "targetId must not be null");
		details = (details == null || details.isBlank()) ? AuditDetails.emptyJson() : details;
		Objects.requireNonNull(createdAt, "createdAt must not be null");
	}

	public static AuditLog of(UUID actorId, AuditAction action, AuditTargetType targetType, UUID targetId,
		String details, Instant createdAt) {
		return new AuditLog(UUID.randomUUID(), actorId, action, targetType, targetId, details, createdAt);
	}
}
