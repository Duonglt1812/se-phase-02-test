package vn.edu.uet.cinema.business.audit;

/** Audited administrative actions recorded in the append-only audit log (S4). */
public enum AuditAction {
	ADMIN_CREATED,
	ADMIN_PASSWORD_CHANGED,
	SHOWTIME_CREATED,
	SHOWTIME_CANCELLED,
	SHOWTIME_DELETED,
	CINEMA_CREATED,
	CINEMA_UPDATED,
	CINEMA_DELETED,
	AUDITORIUM_CREATED,
	AUDITORIUM_DELETED,
	MOVIE_CREATED,
	MOVIE_UPDATED,
	MOVIE_DELETED
}
