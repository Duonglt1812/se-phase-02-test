package vn.edu.uet.cinema.persistence.auditorium;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuditoriumEntityRepository extends JpaRepository<AuditoriumEntity, UUID> {

	boolean existsByCinemaId(UUID cinemaId);

	boolean existsByCinemaIdAndName(UUID cinemaId, String name);
}
