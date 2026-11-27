package vn.edu.uet.cinema.business.movie;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.audit.AuditAction;
import vn.edu.uet.cinema.business.audit.AuditDetails;
import vn.edu.uet.cinema.business.audit.AuditLog;
import vn.edu.uet.cinema.business.audit.AuditTargetType;
import vn.edu.uet.cinema.business.showtime.ShowtimeRepository;

public class MovieService {

	private final MovieRepository movies;
	private final ShowtimeRepository showtimes;

	public MovieService(MovieRepository movies, ShowtimeRepository showtimes) {
		this.movies = movies;
		this.showtimes = showtimes;
	}

	public Movie create(String title, String description, int durationMinutes, String genre, AgeRating ageRating,
		LocalDate releaseDate, String posterUrl) {
		return create(null, title, description, durationMinutes, genre, ageRating, releaseDate, posterUrl,
			Instant.now());
	}

	public Movie create(UUID actorId, String title, String description, int durationMinutes, String genre,
		AgeRating ageRating, LocalDate releaseDate, String posterUrl, Instant now) {
		Movie movie = new Movie(UUID.randomUUID(), title, description, durationMinutes, genre, ageRating,
			releaseDate, posterUrl);
		if (actorId == null) {
			return movies.save(movie);
		}
		String details = AuditDetails.builder()
			.put("title", title)
			.put("durationMinutes", durationMinutes)
			.put("genre", genre)
			.put("ageRating", ageRating)
			.toJson();
		return movies.save(movie, AuditLog.of(actorId, AuditAction.MOVIE_CREATED, AuditTargetType.MOVIE,
			movie.id(), details, now != null ? now : Instant.now()));
	}

	public List<Movie> list() {
		return movies.findAll();
	}

	public PageSlice<Movie> list(PageQuery query) {
		return movies.findAll(query);
	}

	public Movie get(UUID id) {
		return movies.findById(id).orElseThrow(() -> new NotFoundException("Movie not found"));
	}

	public Movie update(UUID id, String title, String description, int durationMinutes, String genre,
		AgeRating ageRating, LocalDate releaseDate, String posterUrl) {
		return update(null, id, title, description, durationMinutes, genre, ageRating, releaseDate, posterUrl,
			Instant.now());
	}

	public Movie update(UUID actorId, UUID id, String title, String description, int durationMinutes, String genre,
		AgeRating ageRating, LocalDate releaseDate, String posterUrl, Instant now) {
		get(id);
		Movie updated = new Movie(id, title, description, durationMinutes, genre, ageRating, releaseDate, posterUrl);
		if (actorId == null) {
			return movies.save(updated);
		}
		String details = AuditDetails.builder()
			.put("title", title)
			.put("durationMinutes", durationMinutes)
			.put("genre", genre)
			.put("ageRating", ageRating)
			.toJson();
		return movies.save(updated, AuditLog.of(actorId, AuditAction.MOVIE_UPDATED, AuditTargetType.MOVIE,
			id, details, now != null ? now : Instant.now()));
	}

	public void delete(UUID id) {
		delete(null, id, Instant.now());
	}

	public void delete(UUID actorId, UUID id, Instant now) {
		Movie existing = get(id);
		if (showtimes.existsByMovieId(id)) {
			throw new ConflictException("Movie has Showtimes");
		}
		if (actorId == null) {
			movies.deleteById(id);
			return;
		}
		String details = AuditDetails.builder()
			.put("title", existing.title())
			.toJson();
		movies.deleteById(id, AuditLog.of(actorId, AuditAction.MOVIE_DELETED, AuditTargetType.MOVIE,
			id, details, now != null ? now : Instant.now()));
	}
}
