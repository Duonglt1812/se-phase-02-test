package vn.edu.uet.cinema.business.audit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;

/** In-memory append-only {@link AuditLogRepository} for unit tests. */
public class InMemoryAuditLogs implements AuditLogRepository {

	private final List<AuditLog> entries = new ArrayList<>();

	@Override
	public synchronized AuditLog append(AuditLog entry) {
		entries.add(entry);
		return entry;
	}

	@Override
	public synchronized PageSlice<AuditLog> findAll(PageQuery query, AuditLogFilter filter) {
		AuditLogFilter f = filter != null ? filter : AuditLogFilter.NONE;
		List<AuditLog> matching = entries.stream()
			.filter(e -> f.action() == null || e.action() == f.action())
			.filter(e -> f.targetType() == null || e.targetType() == f.targetType())
			.filter(e -> f.targetId() == null || e.targetId().equals(f.targetId()))
			.filter(e -> f.actorId() == null || e.actorId().equals(f.actorId()))
			.sorted(Comparator.comparing(AuditLog::createdAt).reversed()
				.thenComparing(AuditLog::id, Comparator.reverseOrder()))
			.toList();
		int fromIndex = Math.min(query.offset(), matching.size());
		int toIndex = Math.min(fromIndex + query.size(), matching.size());
		return new PageSlice<>(matching.subList(fromIndex, toIndex), query.page(), query.size(), matching.size());
	}

	public synchronized List<AuditLog> all() {
		return List.copyOf(entries);
	}
}
