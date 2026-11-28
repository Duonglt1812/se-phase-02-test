package vn.edu.uet.cinema.persistence.auditorium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import vn.edu.uet.cinema.business.auditorium.Auditorium;

class JpaAuditoriumRepositoryTest {

	@Test
	@SuppressWarnings("unchecked")
	void findByCinemaIdUsesSingleSummaryQueryWithoutTouchingSeatsOrJpa() {
		AuditoriumEntityRepository jpa = mock(AuditoriumEntityRepository.class);
		JdbcClient jdbc = mock(JdbcClient.class);
		StatementSpec statement = mock(StatementSpec.class);
		MappedQuerySpec<Auditorium> mapped = mock(MappedQuerySpec.class);

		UUID cinemaId = UUID.randomUUID();
		Auditorium summary = new Auditorium(UUID.randomUUID(), cinemaId, "Hall 1", 10, 12, List.of());

		when(jdbc.sql(anyString())).thenReturn(statement);
		when(statement.param(cinemaId)).thenReturn(statement);
		when(statement.query(any(RowMapper.class))).thenReturn(mapped);
		when(mapped.list()).thenReturn(List.of(summary));

		JpaAuditoriumRepository repository = new JpaAuditoriumRepository(jpa, jdbc);
		List<Auditorium> result = repository.findByCinemaId(cinemaId);

		assertThat(result).containsExactly(summary);
		assertThat(result.getFirst().seats()).isEmpty();
		verify(jdbc).sql("select id, cinema_id, name, rows, seats_per_row from auditorium where cinema_id = ? order by name, id");
		verifyNoInteractions(jpa);
	}
}
