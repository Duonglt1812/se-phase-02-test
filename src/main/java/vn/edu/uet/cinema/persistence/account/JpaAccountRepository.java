package vn.edu.uet.cinema.persistence.account;

import static vn.edu.uet.cinema.persistence.ConstraintViolations.hasConstraint;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.account.Account;
import vn.edu.uet.cinema.business.account.AccountRepository;
import vn.edu.uet.cinema.business.account.EmailTakenException;
import vn.edu.uet.cinema.business.account.Role;
import vn.edu.uet.cinema.business.audit.AuditLog;
import vn.edu.uet.cinema.business.audit.AuditLogRepository;

@Repository
class JpaAccountRepository implements AccountRepository {

	private static final Sort ADMIN_SORT = Sort.by(Sort.Order.asc("email"), Sort.Order.asc("id"));
	private static final String SELECT_BY_ID = """
		select id, email, password_hash, full_name, role from account where id = ?
		""";
	private static final String SELECT_BY_EMAIL = """
		select id, email, password_hash, full_name, role from account where email = ?
		""";
	private static final String EXISTS_BY_EMAIL = """
		select exists(select 1 from account where email = ?)
		""";

	private final AccountEntityRepository jpa;
	private final JdbcClient jdbc;
	private final AuditLogRepository auditLogs;

	JpaAccountRepository(AccountEntityRepository jpa) {
		this(jpa, null, AuditLogRepository.noop());
	}

	JpaAccountRepository(AccountEntityRepository jpa, JdbcClient jdbc) {
		this(jpa, jdbc, AuditLogRepository.noop());
	}

	@Autowired
	JpaAccountRepository(AccountEntityRepository jpa, JdbcClient jdbc, AuditLogRepository auditLogs) {
		this.jpa = jpa;
		this.jdbc = jdbc;
		this.auditLogs = auditLogs != null ? auditLogs : AuditLogRepository.noop();
	}

	@Override
	public Optional<Account> findById(UUID id) {
		return jdbc.sql(SELECT_BY_ID).param(id).query(JpaAccountRepository::mapAccount).optional();
	}

	@Override
	public Optional<Account> findByEmail(String email) {
		return jdbc.sql(SELECT_BY_EMAIL).param(email).query(JpaAccountRepository::mapAccount).optional();
	}

	@Override
	public boolean existsByEmail(String email) {
		return Boolean.TRUE.equals(jdbc.sql(EXISTS_BY_EMAIL).param(email).query(Boolean.class).single());
	}

	@Override
	@Transactional(readOnly = true)
	public PageSlice<Account> findByRole(Role role, PageQuery query) {
		Page<AccountEntity> page = jpa.findByRole(role, PageRequest.of(query.page(), query.size(), ADMIN_SORT));
		return new PageSlice<>(page.getContent().stream().map(AccountEntity::toAccount).toList(),
			query.page(), query.size(), page.getTotalElements());
	}

	@Override
	public Account save(Account account) {
		try {
			return jpa.saveAndFlush(AccountEntity.from(account)).toAccount();
		} catch (DataIntegrityViolationException e) {
			// Two registrations with the same email raced past existsByEmail; the unique constraint decides.
			if (hasConstraint(e, "account_email_key")) {
				throw new EmailTakenException();
			}
			throw e;
		}
	}

	@Override
	@Transactional
	public Account save(Account account, AuditLog auditLog) {
		Account saved = save(account);
		if (auditLog != null) {
			auditLogs.append(auditLog);
		}
		return saved;
	}

	private static Account mapAccount(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new Account(
			rs.getObject("id", UUID.class),
			rs.getString("email"),
			rs.getString("password_hash"),
			rs.getString("full_name"),
			Role.valueOf(rs.getString("role")));
	}
}
