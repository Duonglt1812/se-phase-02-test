package vn.edu.uet.cinema.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uet.cinema.business.movie.AgeRating;

@WebMvcTest(controllers = JacksonScalarCoercionTest.ProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonConfig.class, JacksonScalarCoercionTest.ProbeController.class})
class JacksonScalarCoercionTest {

	@Autowired
	MockMvc mvc;

	@Test
	void rejectsBooleanScalarForStringField() throws Exception {
		for (String value : List.of("true", "false")) {
			mvc.perform(post("/test/cinemas").contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\":\"Cinema Alpha\",\"address\":" + value + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("Request body contains an invalid value."))
				.andExpect(jsonPath("$.errors[0].field").value("address"))
				.andExpect(jsonPath("$.errors[0].message").value("Must be a string."));
		}
	}

	@Test
	void rejectsIntegerAndFloatScalarsForStringField() throws Exception {
		for (String value : List.of("123", "12.5")) {
			mvc.perform(post("/test/movies").contentType(MediaType.APPLICATION_JSON)
					.content("{\"title\":" + value + ",\"ageRating\":\"P\",\"releaseDate\":\"2026-01-15\","
						+ "\"id\":\"550e8400-e29b-41d4-a716-446655440010\",\"durationMinutes\":120,"
						+ "\"tags\":[\"drama\"]}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errors[0].field").value("title"))
				.andExpect(jsonPath("$.errors[0].message").value("Must be a string."));
		}
	}

	@Test
	void rejectsScalarInsideStringList() throws Exception {
		mvc.perform(post("/test/movies").contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Valid Title\",\"ageRating\":\"P\",\"releaseDate\":\"2026-01-15\","
					+ "\"id\":\"550e8400-e29b-41d4-a716-446655440010\",\"durationMinutes\":120,"
					+ "\"tags\":[\"valid\",42]}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.errors[0].field").value("tags[1]"))
			.andExpect(jsonPath("$.errors[0].message").value("Must be a string."));
	}

	@Test
	void preservesLoginPrivacyWhenScalarSentToLogin() throws Exception {
		String secret = "LOGIN_SCALAR_CANARY";
		String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":true,\"password\":\"" + secret + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value("Invalid request."))
			.andExpect(jsonPath("$.errors").doesNotExist())
			.andReturn().getResponse().getContentAsString();

		assertThat(response).doesNotContain(secret);
	}

	@Test
	void acceptsValidStringsEnumsUuidsDatesAndNumbers() throws Exception {
		mvc.perform(post("/test/movies").contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Valid Title\",\"ageRating\":\"P\",\"releaseDate\":\"2026-01-15\","
					+ "\"id\":\"550e8400-e29b-41d4-a716-446655440010\",\"durationMinutes\":120,"
					+ "\"tags\":[\"drama\",\"action\"]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Valid Title"))
			.andExpect(jsonPath("$.ageRating").value("P"))
			.andExpect(jsonPath("$.releaseDate").value("2026-01-15"))
			.andExpect(jsonPath("$.id").value("550e8400-e29b-41d4-a716-446655440010"))
			.andExpect(jsonPath("$.durationMinutes").value(120))
			.andExpect(jsonPath("$.tags[0]").value("drama"))
			.andExpect(jsonPath("$.tags[1]").value("action"));
	}

	@Test
	void nulCharacterInStringRemainsBadRequestWithoutTypeMismatchMessage() throws Exception {
		mvc.perform(post("/test/movies").contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"\\u0000x\",\"ageRating\":\"P\",\"releaseDate\":\"2026-01-15\","
					+ "\"id\":\"550e8400-e29b-41d4-a716-446655440010\",\"durationMinutes\":120,"
					+ "\"tags\":[\"drama\"]}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.errors[0].field").value("title"))
			.andExpect(jsonPath("$.errors[0].message").value("Invalid value."));
	}

	@RestController
	static class ProbeController {

		@PostMapping("/test/cinemas")
		CinemaPayload echoCinema(@RequestBody CinemaPayload payload) {
			return payload;
		}

		@PostMapping("/test/movies")
		MoviePayload echoMovie(@RequestBody MoviePayload payload) {
			return payload;
		}

		@PostMapping("/api/auth/login")
		LoginPayload echoLogin(@RequestBody LoginPayload payload) {
			return payload;
		}

		record CinemaPayload(String name, String address) {
		}

		record MoviePayload(String title, AgeRating ageRating, LocalDate releaseDate, UUID id, int durationMinutes,
			List<String> tags) {
		}

		record LoginPayload(String email, String password) {
		}
	}
}
