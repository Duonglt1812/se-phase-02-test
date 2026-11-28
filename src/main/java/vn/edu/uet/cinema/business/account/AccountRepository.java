package vn.edu.uet.cinema.business.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.audit.AuditLog;

public interface AccountRepository {

	Optional<Account> findById(UUID id);

	Optional<Account> findByEmail(String email);

	boolean existsByEmail(String email);

	default PageSlice<Account> findByRole(Role role, PageQuery query) {
		return new PageSlice<>(List.of(), query.page(), query.size(), 0);
	}

	/** @throws EmailTakenException if the email is already taken */
	Account save(Account account);

	/** Saves the account and appends the audit entry inside the same database transaction (S4). */
	default Account save(Account account, AuditLog auditLog) {
		return save(account);
	}
}
