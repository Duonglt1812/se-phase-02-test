package vn.edu.uet.cinema.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import vn.edu.uet.cinema.TestcontainersConfiguration;

/**
 * Verifies that errors handled outside Spring MVC (dispatched to {@code /error}) return RFC 9457
 * {@code application/problem+json} responses without leaking stack traces or internal details (B6 / ST105).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ProblemErrorControllerApiTest {

	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	@Value("${local.server.port}")
	int port;

	@AfterEach
	void closeClient() {
		http.close();
	}

	@Test
	void malformedEncodedPathRejectedBeforeMvcReturnsProblemJson() throws Exception {
		HttpResponse<String> response = http.send(
			HttpRequest.newBuilder(uri("/api/showtimes/%25")).GET().build(),
			BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
		assertProblemJson(response, 400);
		assertThat(response.body()).doesNotContain("\"errors\"").doesNotContain("trace");
	}

	@Test
	void emptyQueryParameterNameReturnsProblemJson() throws Exception {
		HttpResponse<String> response = http.send(
			HttpRequest.newBuilder(uri("/api/movies?=null")).GET().build(),
			BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
		assertProblemJson(response, 400);
		assertThat(response.body()).doesNotContain("\"errors\"").doesNotContain("trace");
	}

	@Test
	void directErrorDispatchReturnsProblemJsonWithoutStackTrace() throws Exception {
		HttpResponse<String> response = http.send(
			HttpRequest.newBuilder(uri("/error")).GET().build(),
			BodyHandlers.ofString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(500);
		assertProblemJson(response, 500);
		assertThat(response.body()).doesNotContain("trace").doesNotContain("Exception");
	}

	private static void assertProblemJson(HttpResponse<String> response, int expectedStatus) {
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
			assertThat(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(MediaType.parseMediaType(type))).isTrue());
		assertThat(JsonPath.<String>read(response.body(), "$.type")).isEqualTo("about:blank");
		assertThat(JsonPath.<Integer>read(response.body(), "$.status")).isEqualTo(expectedStatus);
		assertThat(JsonPath.<String>read(response.body(), "$.title")).isNotBlank();
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}
}
