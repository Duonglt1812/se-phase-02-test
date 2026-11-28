package vn.edu.uet.cinema.business.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds deterministic JSON object strings for {@link AuditLog#details()} using JDK types only (ADR 0001).
 */
public final class AuditDetails {

	private final Map<String, Object> fields = new LinkedHashMap<>();

	private AuditDetails() {
	}

	public static AuditDetails builder() {
		return new AuditDetails();
	}

	public static String emptyJson() {
		return "{}";
	}

	public AuditDetails put(String key, String value) {
		if (key != null && !key.isBlank() && value != null) {
			fields.put(key, value);
		}
		return this;
	}

	public AuditDetails put(String key, long value) {
		if (key != null && !key.isBlank()) {
			fields.put(key, value);
		}
		return this;
	}

	public AuditDetails put(String key, int value) {
		if (key != null && !key.isBlank()) {
			fields.put(key, value);
		}
		return this;
	}

	public AuditDetails put(String key, Object value) {
		if (key != null && !key.isBlank() && value != null) {
			fields.put(key, value.toString());
		}
		return this;
	}

	public String toJson() {
		if (fields.isEmpty()) {
			return "{}";
		}
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, Object> entry : fields.entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			appendQuoted(sb, entry.getKey());
			sb.append(':');
			Object value = entry.getValue();
			if (value instanceof Number) {
				sb.append(value);
			} else {
				appendQuoted(sb, value.toString());
			}
		}
		sb.append('}');
		return sb.toString();
	}

	private static void appendQuoted(StringBuilder sb, String value) {
		sb.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
	}
}
