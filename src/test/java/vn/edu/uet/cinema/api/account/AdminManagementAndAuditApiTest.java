package vn.edu.uet.cinema.api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.uet.cinema.TestcontainersConfiguration;

/**
 * HTTP API integration tests for database-managed Admins, live password rotation without restart,
 * and append-only administrative audit logs (Issue #18 / S4).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminManagementAndAuditApiTest {

	@Autowired
	MockMvc mvc;

	@Test
	void adminCreatesSecondAdminListsAdminsAndEnforcesRoleAccess() throws Exception {
		Tokens rootAdmin = login("admin@cinema.local", "admin12345");
		String secondEmail = "ops-admin-" + UUID.randomUUID() + "@cinema.local";

		// Unauthenticated and Customer callers are rejected on /api/admin/**.
		mvc.perform(get("/api/admin/accounts")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/admin/audit-logs")).andExpect(status().isUnauthorized());

		String customerEmail = "s4-cust-" + UUID.randomUUID() + "@example.com";
		registerCustomer(customerEmail, "password123");
		Tokens customer = login(customerEmail, "password123");

		mvc.perform(get("/api/admin/accounts").header("Authorization", "Bearer " + customer.accessToken()))
			.andExpect(status().isForbidden())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
		mvc.perform(post("/api/admin/accounts").header("Authorization", "Bearer " + customer.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"adminpass123\",\"fullName\":\"Forbidden\"}".formatted(secondEmail)))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/audit-logs").header("Authorization", "Bearer " + customer.accessToken()))
			.andExpect(status().isForbidden());

		// Root Admin creates a second Admin account in the database.
		String createdBody = mvc.perform(post("/api/admin/accounts")
				.header("Authorization", "Bearer " + rootAdmin.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"opsPassword123\",\"fullName\":\"Ops Admin\"}"
					.formatted(secondEmail)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value(secondEmail))
			.andExpect(jsonPath("$.fullName").value("Ops Admin"))
			.andExpect(jsonPath("$.role").value("ADMIN"))
			.andReturn().getResponse().getContentAsString();
		String secondAdminId = JsonPath.read(createdBody, "$.id");

		// Duplicate email returns 409 Conflict.
		mvc.perform(post("/api/admin/accounts")
				.header("Authorization", "Bearer " + rootAdmin.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"opsPassword123\",\"fullName\":\"Dup\"}"
					.formatted(secondEmail.toUpperCase())))
			.andExpect(status().isConflict())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

		// Admin listing includes both bootstrap and newly created Admin with pagination headers.
		mvc.perform(get("/api/admin/accounts").param("page", "0").param("size", "50")
				.header("Authorization", "Bearer " + rootAdmin.accessToken()))
			.andExpect(status().isOk())
			.andExpect(header().exists("X-Total-Count"))
			.andExpect(jsonPath("$[*].email").value(hasItem("admin@cinema.local")))
			.andExpect(jsonPath("$[*].email").value(hasItem(secondEmail)));

		// Audit log recorded ADMIN_CREATED without leaking passwords.
		String auditBody = mvc.perform(get("/api/admin/audit-logs")
				.param("action", "ADMIN_CREATED")
				.param("targetId", secondAdminId)
				.header("Authorization", "Bearer " + rootAdmin.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].action").value("ADMIN_CREATED"))
			.andExpect(jsonPath("$[0].targetType").value("ACCOUNT"))
			.andExpect(jsonPath("$[0].targetId").value(secondAdminId))
			.andExpect(jsonPath("$[0].details.email").value(secondEmail))
			.andReturn().getResponse().getContentAsString();
		assertThat(auditBody).doesNotContain("opsPassword123");
	}

	@Test
	void adminRotatesPasswordWithoutRestartAndInvalidatesOldTokens() throws Exception {
		Tokens rootAdmin = login("admin@cinema.local", "admin12345");
		String rotatedEmail = "rotate-admin-" + UUID.randomUUID() + "@cinema.local";
		String createdBody = mvc.perform(post("/api/admin/accounts")
				.header("Authorization", "Bearer " + rootAdmin.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"initialPass123\",\"fullName\":\"Rotatable Admin\"}"
					.formatted(rotatedEmail)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		String adminId = JsonPath.read(createdBody, "$.id");

		Tokens before = login(rotatedEmail, "initialPass123");

		// Wrong current password returns 401; overlong newPassword (> 72 UTF-8 bytes) returns 400 (never 500).
		mvc.perform(post("/api/me/password")
				.header("Authorization", "Bearer " + before.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"wrong-pass\",\"newPassword\":\"newPassword123\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

		mvc.perform(post("/api/me/password")
				.header("Authorization", "Bearer " + before.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"initialPass123\",\"newPassword\":\"%s\"}".formatted("p".repeat(73))))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

		// Rotate password at runtime without restarting.
		mvc.perform(post("/api/me/password")
				.header("Authorization", "Bearer " + before.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"initialPass123\",\"newPassword\":\"rotatedPass999\"}"))
			.andExpect(status().isNoContent());

		// Old access token and refresh token are immediately invalid, and old password stops working.
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + before.accessToken()))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"%s\"}".formatted(before.refreshToken())))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"initialPass123\"}".formatted(rotatedEmail)))
			.andExpect(status().isUnauthorized());

		// New password works immediately and ADMIN_PASSWORD_CHANGED is audited.
		Tokens after = login(rotatedEmail, "rotatedPass999");
		mvc.perform(get("/api/admin/audit-logs")
				.param("action", "ADMIN_PASSWORD_CHANGED")
				.param("targetId", adminId)
				.header("Authorization", "Bearer " + after.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].actorId").value(adminId))
			.andExpect(jsonPath("$[0].targetType").value("ACCOUNT"));
	}

	@Test
	void showtimeCreateCancelDeleteAndCatalogueMutationsRecordAuditLogs() throws Exception {
		Tokens admin = login("admin@cinema.local", "admin12345");
		String adminId = JsonPath.read(mvc.perform(get("/api/me")
				.header("Authorization", "Bearer " + admin.accessToken()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");

		String cinemaId = JsonPath.read(mvc.perform(post("/api/cinemas")
				.header("Authorization", "Bearer " + admin.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Audit API Cinema %s\",\"address\":\"10 Main St\"}".formatted(UUID.randomUUID())))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

		mvc.perform(put("/api/cinemas/" + cinemaId)
				.header("Authorization", "Bearer " + admin.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Audit API Cinema Updated\",\"address\":\"20 Main St\"}"))
			.andExpect(status().isOk());

		String auditoriumId = JsonPath.read(mvc.perform(post("/api/cinemas/" + cinemaId + "/auditoriums")
				.header("Authorization", "Bearer " + admin.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Screen Audit\",\"rows\":2,\"seatsPerRow\":2,\"vipRows\":[],\"coupleRows\":[]}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

		String movieId = JsonPath.read(mvc.perform(post("/api/movies")
				.header("Authorization", "Bearer " + admin.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Audit Film\",\"description\":\"Desc\",\"durationMinutes\":110,\"genre\":\"Drama\","
					+ "\"ageRating\":\"P\",\"releaseDate\":\"2026-10-01\",\"posterUrl\":\"https://example.com/a.jpg\"}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

		Instant start1 = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		String showtime1Id = JsonPath.read(mvc.perform(post("/api/showtimes")
				.header("Authorization", "Bearer " + admin.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"movieId\":\"%s\",\"auditoriumId\":\"%s\",\"startsAt\":\"%s\",\"basePrice\":100000}"
					.formatted(movieId, auditoriumId, start1)))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

		mvc.perform(post("/api/showtimes/" + showtime1Id + "/cancellation")
				.header("Authorization", "Bearer " + admin.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"EQUIPMENT_FAILURE\",\"note\":\"Projector fault\"}"))
			.andExpect(status().isOk());

		Instant start2 = start1.plus(5, ChronoUnit.HOURS);
		String showtime2Id = JsonPath.read(mvc.perform(post("/api/showtimes")
				.header("Authorization", "Bearer " + admin.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"movieId\":\"%s\",\"auditoriumId\":\"%s\",\"startsAt\":\"%s\",\"basePrice\":120000}"
					.formatted(movieId, auditoriumId, start2)))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

		mvc.perform(delete("/api/showtimes/" + showtime2Id)
				.header("Authorization", "Bearer " + admin.accessToken()))
			.andExpect(status().isNoContent());

		// Verify SHOWTIME_CREATED + SHOWTIME_CANCELLED for showtime1.
		mvc.perform(get("/api/admin/audit-logs")
				.param("targetType", "SHOWTIME")
				.param("targetId", showtime1Id)
				.header("Authorization", "Bearer " + admin.accessToken()))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Total-Count", "2"))
			.andExpect(jsonPath("$[0].action").value("SHOWTIME_CANCELLED"))
			.andExpect(jsonPath("$[0].actorId").value(adminId))
			.andExpect(jsonPath("$[0].details.reason").value("EQUIPMENT_FAILURE"))
			.andExpect(jsonPath("$[0].details.note").value("Projector fault"))
			.andExpect(jsonPath("$[1].action").value("SHOWTIME_CREATED"))
			.andExpect(jsonPath("$[1].details.basePrice").value(100_000));

		// Verify SHOWTIME_CREATED + SHOWTIME_DELETED for hard-deleted showtime2.
		mvc.perform(get("/api/admin/audit-logs")
				.param("targetType", "SHOWTIME")
				.param("targetId", showtime2Id)
				.header("Authorization", "Bearer " + admin.accessToken()))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Total-Count", "2"))
			.andExpect(jsonPath("$[0].action").value("SHOWTIME_DELETED"))
			.andExpect(jsonPath("$[0].details.purgedLapsedHolds").value(0))
			.andExpect(jsonPath("$[1].action").value("SHOWTIME_CREATED"));

		// Verify cinema audit logs (CREATED + UPDATED).
		mvc.perform(get("/api/admin/audit-logs")
				.param("targetType", "CINEMA")
				.param("targetId", cinemaId)
				.header("Authorization", "Bearer " + admin.accessToken()))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Total-Count", "2"))
			.andExpect(jsonPath("$[0].action").value("CINEMA_UPDATED"))
			.andExpect(jsonPath("$[1].action").value("CINEMA_CREATED"));
	}

	private void registerCustomer(String email, String password) throws Exception {
		mvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\",\"fullName\":\"S4 Customer\"}".formatted(email, password)))
			.andExpect(status().isCreated());
	}

	private Tokens login(String email, String password) throws Exception {
		String body = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return new Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
	}

	private record Tokens(String accessToken, String refreshToken) {
	}
}
