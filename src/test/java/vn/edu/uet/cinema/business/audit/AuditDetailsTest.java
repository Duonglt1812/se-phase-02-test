package vn.edu.uet.cinema.business.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;

class AuditDetailsTest {

	@Test
	void buildsEscapedDeterministicJsonUsingJdkOnly() {
		assertThat(AuditDetails.builder().toJson()).isEqualTo("{}");

		String json = AuditDetails.builder()
			.put("reason", "OTHER")
			.put("note", "Line 1\n\"Quoted\" \\ path\t\u0007")
			.put("ignored", (String) null)
			.put("count", 3)
			.put("basePrice", 120_000L)
			.toJson();

		assertThat(json).isEqualTo(
			"{\"reason\":\"OTHER\",\"note\":\"Line 1\\n\\\"Quoted\\\" \\\\ path\\t\\u0007\",\"count\":3,\"basePrice\":120000}");
	}

	@Test
	void auditLogServiceFiltersAndPaginatesNewestFirst() {
		InMemoryAuditLogs repo = new InMemoryAuditLogs();
		AuditLogService service = new AuditLogService(repo);
		UUID adminA = UUID.randomUUID();
		UUID adminB = UUID.randomUUID();
		UUID showtimeId = UUID.randomUUID();
		Instant t1 = Instant.parse("2026-11-27T10:00:00Z");
		Instant t2 = Instant.parse("2026-11-27T10:01:00Z");
		Instant t3 = Instant.parse("2026-11-27T10:02:00Z");

		AuditLog first = repo.append(AuditLog.of(adminA, AuditAction.SHOWTIME_CREATED, AuditTargetType.SHOWTIME,
			showtimeId, "{\"basePrice\":100000}", t1));
		AuditLog second = repo.append(AuditLog.of(adminB, AuditAction.SHOWTIME_CANCELLED, AuditTargetType.SHOWTIME,
			showtimeId, "{\"reason\":\"POWER_OUTAGE\"}", t2));
		AuditLog third = repo.append(AuditLog.of(adminA, AuditAction.ADMIN_CREATED, AuditTargetType.ACCOUNT,
			adminB, "{\"email\":\"b@cinema.local\"}", t3));

		PageSlice<AuditLog> all = service.list(new PageQuery(0, 2), AuditLogFilter.NONE);
		assertThat(all.totalElements()).isEqualTo(3);
		assertThat(all.items()).containsExactly(third, second);

		PageSlice<AuditLog> byTarget = service.list(PageQuery.DEFAULT,
			new AuditLogFilter(null, AuditTargetType.SHOWTIME, showtimeId, null));
		assertThat(byTarget.items()).containsExactly(second, first);

		PageSlice<AuditLog> byActorAndAction = service.list(PageQuery.DEFAULT,
			new AuditLogFilter(AuditAction.SHOWTIME_CREATED, null, null, adminA));
		assertThat(byActorAndAction.items()).containsExactly(first);
	}
}
