package vn.edu.uet.cinema.persistence.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.uet.cinema.business.account.Role;

interface AccountEntityRepository extends JpaRepository<AccountEntity, UUID> {

	Optional<AccountEntity> findByEmail(String email);

	boolean existsByEmail(String email);

	Page<AccountEntity> findByRole(Role role, Pageable pageable);
}
