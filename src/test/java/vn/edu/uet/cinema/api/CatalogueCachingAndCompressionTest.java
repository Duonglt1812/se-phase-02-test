package vn.edu.uet.cinema.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.account.AccountService;
import vn.edu.uet.cinema.business.account.RefreshTokenService;
import vn.edu.uet.cinema.business.auditorium.Auditorium;
import vn.edu.uet.cinema.business.auditorium.AuditoriumService;
import vn.edu.uet.cinema.business.auditorium.Seat;
import vn.edu.uet.cinema.business.auditorium.SeatType;
import vn.edu.uet.cinema.business.booking.BookingService;
import vn.edu.uet.cinema.business.cinema.Cinema;
import vn.edu.uet.cinema.business.cinema.CinemaService;
import vn.edu.uet.cinema.business.movie.AgeRating;
import vn.edu.uet.cinema.business.movie.Movie;
import vn.edu.uet.cinema.business.movie.MovieService;
import vn.edu.uet.cinema.business.showtime.PricedSeat;
import vn.edu.uet.cinema.business.showtime.SeatMapEntry;
import vn.edu.uet.cinema.business.showtime.ShowtimeService;
import vn.edu.uet.cinema.business.showtime.inventory.SeatInventory;
import vn.edu.uet.cinema.business.showtime.inventory.SeatStatus;
import vn.edu.uet.cinema.config.CatalogueCachingConfig;
import vn.edu.uet.cinema.config.OpenApiConfig;
import vn.edu.uet.cinema.config.SecurityConfig;

/**
 * Verifies HTTP GZIP compression on JSON responses >= 1 KB and Cache-Control / ETag headers on public catalogue reads
 * against the real embedded Tomcat container (P9).
 */
@SpringBootTest(classes = CatalogueCachingAndCompressionTest.TestApplication.class,
	webEnvironment = WebEnvironment.RANDOM_PORT,
	properties = "app.jwt.secret=issue47-test-secret-with-at-least-32-bytes")
class CatalogueCachingAndCompressionTest {

	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	@Value("${local.server.port}")
	int port;

	@MockitoBean
	AccountService accounts;
	@MockitoBean
	RefreshTokenService refreshTokens;
	@MockitoBean
	CinemaService cinemas;
	@MockitoBean
	AuditoriumService auditoriums;
	@MockitoBean
	MovieService movies;
	@MockitoBean
	ShowtimeService showtimes;
	@MockitoBean
	SeatInventory inventory;
	@MockitoBean
	BookingService bookings;

	@AfterEach
	void closeClient() {
		http.close();
	}

	@Test
	void largeJsonResponseIsGzipCompressedWhenAccepted() throws Exception {
		List<Movie> largePage = IntStream.range(0, 40)
			.mapToObj(i -> new Movie(UUID.randomUUID(), "Movie Title " + i,
				"Detailed synopsis for compression verification " + i, 120, "Drama", AgeRating.P,
				LocalDate.of(2026, 5, 1), "https://example.com/poster-" + i + ".jpg"))
			.toList();
		when(movies.list(any(PageQuery.class))).thenReturn(new PageSlice<>(largePage, 0, 40, 40));

		HttpResponse<byte[]> response = http.send(
			HttpRequest.newBuilder(uri("/api/movies?size=40")).header("Accept-Encoding", "gzip").GET().build(),
			BodyHandlers.ofByteArray());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Encoding")).as("headers=" + response.headers().map()).contains("gzip");
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
			String decompressed = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
			assertThat(decompressed).contains("Movie Title 0").contains("Movie Title 39");
		}
	}

	@Test
	void publicCatalogueEndpointsExposeCacheControlAndEtag() throws Exception {
		UUID cinemaId = UUID.randomUUID();
		when(cinemas.list(any(PageQuery.class))).thenReturn(new PageSlice<>(
			List.of(new Cinema(cinemaId, "CGV Vincom", "191 Ba Trieu")), 0, 20, 1));
		when(auditoriums.list(cinemaId)).thenReturn(List.of(
			new Auditorium(UUID.randomUUID(), cinemaId, "Hall 1", 10, 12, List.of())));

		HttpResponse<String> first = http.send(HttpRequest.newBuilder(uri("/api/cinemas")).GET().build(),
			BodyHandlers.ofString());
		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(first.headers().firstValue("Cache-Control")).hasValueSatisfying(value ->
			assertThat(value).contains("public").contains("max-age=60"));
		String etag = first.headers().firstValue("ETag").orElseThrow();

		HttpResponse<String> notModified = http.send(
			HttpRequest.newBuilder(uri("/api/cinemas")).header("If-None-Match", etag).GET().build(),
			BodyHandlers.ofString());
		assertThat(notModified.statusCode()).isEqualTo(304);

		HttpResponse<String> halls = http.send(
			HttpRequest.newBuilder(uri("/api/cinemas/" + cinemaId + "/auditoriums")).GET().build(),
			BodyHandlers.ofString());
		assertThat(halls.statusCode()).isEqualTo(200);
		assertThat(halls.headers().firstValue("Cache-Control")).hasValueSatisfying(value ->
			assertThat(value).contains("public").contains("max-age=60"));
		assertThat(halls.headers().firstValue("ETag")).isPresent();
		assertThat(halls.body()).contains("Hall 1").doesNotContain("\"seats\"");
	}

	@Test
	void realTimeSeatMapIsGzippedAndNeverPubliclyCached() throws Exception {
		UUID showtimeId = UUID.randomUUID();
		List<SeatMapEntry> seats = IntStream.range(0, 60)
			.mapToObj(i -> new SeatMapEntry(
				new PricedSeat(new Seat(UUID.randomUUID(), "A", i + 1, SeatType.STANDARD), 100_000L),
				SeatStatus.AVAILABLE))
			.toList();
		when(showtimes.seatMap(org.mockito.ArgumentMatchers.eq(showtimeId), any())).thenReturn(seats);

		HttpResponse<byte[]> response = http.send(
			HttpRequest.newBuilder(uri("/api/showtimes/" + showtimeId + "/seats"))
				.header("Accept-Encoding", "gzip").GET().build(),
			BodyHandlers.ofByteArray());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
		assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value ->
			assertThat(value).contains("no-store").doesNotContain("public"));
		assertThat(response.headers().firstValue("ETag")).isEmpty();
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(excludeName = {
		"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
		"org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
	@ComponentScan(basePackages = {
		"vn.edu.uet.cinema.api.account",
		"vn.edu.uet.cinema.api.auditorium",
		"vn.edu.uet.cinema.api.booking",
		"vn.edu.uet.cinema.api.cinema",
		"vn.edu.uet.cinema.api.movie",
		"vn.edu.uet.cinema.api.showtime"})
	@Import({ApiExceptionHandler.class, CatalogueCachingConfig.class, OpenApiConfig.class, SecurityConfig.class})
	static class TestApplication {
	}
}
