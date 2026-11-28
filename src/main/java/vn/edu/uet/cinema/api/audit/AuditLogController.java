package vn.edu.uet.cinema.api.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import vn.edu.uet.cinema.api.PaginationHeaders;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.audit.AuditAction;
import vn.edu.uet.cinema.business.audit.AuditLog;
import vn.edu.uet.cinema.business.audit.AuditLogFilter;
import vn.edu.uet.cinema.business.audit.AuditLogService;
import vn.edu.uet.cinema.business.audit.AuditTargetType;
import vn.edu.uet.cinema.config.OpenApiConfig;

@RestController
@Tag(name = "Audit Logs", description = "Append-only audit trail of administrative operations.")
class AuditLogController {

	private static final ObjectMapper JSON = JsonMapper.builder().build();
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

	private final AuditLogService auditLogs;

	AuditLogController(AuditLogService auditLogs) {
		this.auditLogs = auditLogs;
	}

	@GetMapping("/api/admin/audit-logs")
	@Operation(summary = "List audit logs", description = "Returns a page of append-only administrative audit entries "
		+ "ordered newest first. Optionally filters by action, targetType, targetId, and actorId. Totals are returned in "
		+ "X-Total-Count, X-Page, X-Page-Size, and X-Total-Pages headers. Requires ADMIN.")
	@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	ResponseEntity<List<AuditLogResponse>> list(
		@Parameter(description = "Zero-based page index.", example = "0",
			schema = @Schema(minimum = "0", maximum = "100000", defaultValue = "0"))
		@RequestParam(defaultValue = "0") @Min(value = 0, message = "Page must be 0 or greater")
		@Max(value = PageQuery.MAX_PAGE, message = "Page must be at most 100000") int page,
		@Parameter(description = "Maximum number of items per page (1-100).", example = "20",
			schema = @Schema(minimum = "1", maximum = "100", defaultValue = "20"))
		@RequestParam(defaultValue = "20") @Min(value = 1, message = "Size must be at least 1")
		@Max(value = PageQuery.MAX_SIZE, message = "Size must be at most 100") int size,
		@Parameter(description = "Optional audit action filter.", example = "SHOWTIME_CANCELLED")
		@RequestParam(required = false) AuditAction action,
		@Parameter(description = "Optional target entity type filter.", example = "SHOWTIME")
		@RequestParam(required = false) AuditTargetType targetType,
		@Parameter(description = "Optional target resource UUID filter.",
			example = "550e8400-e29b-41d4-a716-446655440010")
		@RequestParam(required = false) UUID targetId,
		@Parameter(description = "Optional acting admin UUID filter.",
			example = "550e8400-e29b-41d4-a716-446655440000")
		@RequestParam(required = false) UUID actorId) {
		AuditLogFilter filter = new AuditLogFilter(action, targetType, targetId, actorId);
		return PaginationHeaders.ok(auditLogs.list(new PageQuery(page, size), filter), AuditLogResponse::from);
	}

	record AuditLogResponse(
		@Schema(description = "Audit log entry identifier.", example = "550e8400-e29b-41d4-a716-446655440099") UUID id,
		@Schema(description = "Acting admin account identifier.", example = "550e8400-e29b-41d4-a716-446655440000")
		UUID actorId,
		@Schema(description = "Audited administrative action.", example = "SHOWTIME_CANCELLED") AuditAction action,
		@Schema(description = "Target domain resource type.", example = "SHOWTIME") AuditTargetType targetType,
		@Schema(description = "Target domain resource identifier.", example = "550e8400-e29b-41d4-a716-446655440010")
		UUID targetId,
		@Schema(description = "Structured action metadata captured at the time of the operation.")
		Map<String, Object> details,
		@Schema(description = "Timestamp when the audited action was recorded.", example = "2026-05-01T12:00:00Z")
		Instant createdAt) {

		static AuditLogResponse from(AuditLog entry) {
			return new AuditLogResponse(entry.id(), entry.actorId(), entry.action(), entry.targetType(),
				entry.targetId(), parseDetails(entry.details()), entry.createdAt());
		}

		private static Map<String, Object> parseDetails(String json) {
			if (json == null || json.isBlank()) {
				return Map.of();
			}
			try {
				Map<String, Object> parsed = JSON.readValue(json, MAP_TYPE);
				return parsed != null ? parsed : Map.of();
			} catch (RuntimeException e) {
				return Map.of();
			}
		}
	}
}
