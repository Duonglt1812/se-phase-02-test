package vn.edu.uet.cinema.business.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.uet.cinema.business.ConflictException;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.audit.AuditAction;
import vn.edu.uet.cinema.business.audit.AuditTargetType;
import vn.edu.uet.cinema.business.audit.InMemoryAuditLogs;

class AccountServiceTest {

	private final InMemoryAuditLogs auditLogs = new InMemoryAuditLogs();
	private final InMemoryAccounts accounts = new InMemoryAccounts(auditLogs);
	private final AccountService service = new AccountService(accounts, new PrefixHasher());

	@Test
	void registersCustomerWithNormalizedEmailAndHashedPassword() {
		Account account = service.registerCustomer("  Alice@Example.COM ", "secret123", " Alice ");

		assertThat(account.email()).isEqualTo("alice@example.com");
		assertThat(account.fullName()).isEqualTo("Alice");
		assertThat(account.role()).isEqualTo(Role.CUSTOMER);
		assertThat(account.passwordHash()).isEqualTo("hashed:secret123");
	}

	@Test
	void rejectsDuplicateEmailRegardlessOfCase() {
		service.registerCustomer("alice@example.com", "secret123", "Alice");

		assertThatThrownBy(() -> service.registerCustomer("ALICE@example.com", "other1234", "Alice 2"))
			.isInstanceOf(ConflictException.class);
	}

	@Test
	void authenticatesWithCorrectPasswordOnly() {
		Account registered = service.registerCustomer("alice@example.com", "secret123", "Alice");

		assertThat(service.authenticate("Alice@example.com", "secret123")).isEqualTo(registered);
		assertThatThrownBy(() -> service.authenticate("alice@example.com", "wrong"))
			.isInstanceOf(InvalidCredentialsException.class);
		assertThatThrownBy(() -> service.authenticate("nobody@example.com", "secret123"))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void ensureAdminSeedsBootstrapAdminOnceWithoutOverwritingExistingPassword() {
		service.ensureAdmin("Admin@Cinema.local", "admin-pass");
		service.ensureAdmin("admin@cinema.local", "different-env-pass");

		assertThat(accounts.size()).isEqualTo(1);
		assertThat(service.authenticate("admin@cinema.local", "admin-pass").role()).isEqualTo(Role.ADMIN);
		assertThatThrownBy(() -> service.authenticate("admin@cinema.local", "different-env-pass"))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void createAdminCreatesDatabaseAdminAndRecordsAuditEntry() {
		service.ensureAdmin("root@cinema.local", "root-pass");
		Account root = service.authenticate("root@cinema.local", "root-pass");
		Instant now = Instant.parse("2026-11-27T10:00:00Z");

		Account created = service.createAdmin(root.id(), " Ops@Cinema.LOCAL ", "ops-secret", " Ops Admin ", now);

		assertThat(created.email()).isEqualTo("ops@cinema.local");
		assertThat(created.fullName()).isEqualTo("Ops Admin");
		assertThat(created.role()).isEqualTo(Role.ADMIN);
		assertThat(service.authenticate("ops@cinema.local", "ops-secret")).isEqualTo(created);
		assertThat(auditLogs.all()).hasSize(1);
		assertThat(auditLogs.all().get(0).actorId()).isEqualTo(root.id());
		assertThat(auditLogs.all().get(0).action()).isEqualTo(AuditAction.ADMIN_CREATED);
		assertThat(auditLogs.all().get(0).targetType()).isEqualTo(AuditTargetType.ACCOUNT);
		assertThat(auditLogs.all().get(0).targetId()).isEqualTo(created.id());
		assertThat(auditLogs.all().get(0).details()).contains("\"email\":\"ops@cinema.local\"")
			.doesNotContain("ops-secret");

		assertThatThrownBy(() -> service.createAdmin(root.id(), "OPS@cinema.local", "other-pass", "Dup", now))
			.isInstanceOf(EmailTakenException.class);
		assertThat(auditLogs.all()).hasSize(1);
	}

	@Test
	void changePasswordRotatesWithoutRestartAndAuditsAdminsOnly() {
		service.ensureAdmin("root@cinema.local", "old-pass");
		Account admin = service.authenticate("root@cinema.local", "old-pass");
		Account customer = service.registerCustomer("bob@example.com", "cust-old", "Bob");
		Instant now = Instant.parse("2026-11-27T10:05:00Z");

		assertThatThrownBy(() -> service.changePassword(admin.id(), "wrong-old", "new-pass123", now))
			.isInstanceOf(InvalidCredentialsException.class);
		assertThat(auditLogs.all()).isEmpty();

		service.changePassword(admin.id(), "old-pass", "new-pass123", now);
		assertThat(service.authenticate("root@cinema.local", "new-pass123").id()).isEqualTo(admin.id());
		assertThatThrownBy(() -> service.authenticate("root@cinema.local", "old-pass"))
			.isInstanceOf(InvalidCredentialsException.class);
		// Subsequent startup bootstrap must not revert the rotated password.
		service.ensureAdmin("root@cinema.local", "old-pass");
		assertThat(service.authenticate("root@cinema.local", "new-pass123").id()).isEqualTo(admin.id());
		assertThat(auditLogs.all()).hasSize(1);
		assertThat(auditLogs.all().get(0).action()).isEqualTo(AuditAction.ADMIN_PASSWORD_CHANGED);
		assertThat(auditLogs.all().get(0).targetId()).isEqualTo(admin.id());

		service.changePassword(customer.id(), "cust-old", "cust-new123", now);
		assertThat(service.authenticate("bob@example.com", "cust-new123").id()).isEqualTo(customer.id());
		assertThat(auditLogs.all()).hasSize(1);
	}

	@Test
	void listAdminsReturnsOnlyAdminAccountsOrderedByEmail() {
		service.ensureAdmin("z-admin@cinema.local", "pass12345");
		Account root = service.authenticate("z-admin@cinema.local", "pass12345");
		service.createAdmin(root.id(), "a-admin@cinema.local", "pass12345", "A Admin", Instant.now());
		service.registerCustomer("customer@example.com", "pass12345", "Customer");

		PageSlice<Account> admins = service.listAdmins(new PageQuery(0, 10));

		assertThat(admins.totalElements()).isEqualTo(2);
		assertThat(admins.items()).extracting(Account::email)
			.containsExactly("a-admin@cinema.local", "z-admin@cinema.local");
	}

	@Test
	void ensureAdminRefusesToPromoteACustomer() {
		service.registerCustomer("boss@example.com", "secret123", "Boss");

		assertThatThrownBy(() -> service.ensureAdmin("boss@example.com", "admin-pass"))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void inMemoryAccountsEnforcesUniqueEmailOnSave() {
		Account first = new Account(UUID.randomUUID(), "user@cinema.local", "hash1", "User 1", Role.CUSTOMER);
		accounts.save(first);

		// Updating the same account is allowed
		Account updated = new Account(first.id(), "user@cinema.local", "hash_new", "User Updated", Role.CUSTOMER);
		assertThat(accounts.save(updated).fullName()).isEqualTo("User Updated");

		// Saving a different account with the same email throws EmailTakenException
		Account duplicate = new Account(UUID.randomUUID(), "USER@CINEMA.LOCAL", "hash2", "User 2", Role.CUSTOMER);
		assertThatThrownBy(() -> accounts.save(duplicate))
			.isInstanceOf(EmailTakenException.class);
	}

	@Test
	void inMemoryAccountsEnforcesNotNullFieldsOnSave() {
		UUID id = UUID.randomUUID();
		assertThatThrownBy(() -> accounts.save(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> accounts.save(new Account(null, "e@c.l", "h", "fn", Role.CUSTOMER))).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> accounts.save(new Account(id, null, "h", "fn", Role.CUSTOMER))).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> accounts.save(new Account(id, "e@c.l", null, "fn", Role.CUSTOMER))).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> accounts.save(new Account(id, "e@c.l", "h", null, Role.CUSTOMER))).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> accounts.save(new Account(id, "e@c.l", "h", "fn", null))).isInstanceOf(NullPointerException.class);
	}

	@Test
	void inMemoryAccountsSupportsCaseInsensitiveLookup() {
		Account account = new Account(UUID.randomUUID(), "alice@cinema.local", "hash", "Alice", Role.CUSTOMER);
		accounts.save(account);

		assertThat(accounts.findByEmail("ALICE@CINEMA.LOCAL")).contains(account);
		assertThat(accounts.existsByEmail("Alice@Cinema.Local")).isTrue();
		assertThat(accounts.findByEmail(null)).isEmpty();
	}

	@Test
	void ensureAdminHandlesConcurrentCreationRace() {
		// Mock a repository where findByEmail returns empty (both instances find nothing initially),
		// but save() fails with EmailTakenException for the losing instance.
		AccountRepository racingAccounts = new AccountRepository() {
			private boolean first = true;
			@Override public java.util.Optional<Account> findById(UUID id) { return java.util.Optional.empty(); }
			@Override public java.util.Optional<Account> findByEmail(String email) { return java.util.Optional.empty(); }
			@Override public boolean existsByEmail(String email) { return false; }
			@Override public Account save(Account account) {
				if (!first) {
					throw new EmailTakenException();
				}
				first = false;
				return account;
			}
		};

		AccountService racingService = new AccountService(racingAccounts, new PrefixHasher());
		racingService.ensureAdmin("admin@cinema.local", "pass"); // First succeeds
		racingService.ensureAdmin("admin@cinema.local", "pass"); // Second catches EmailTakenException and finishes cleanly
	}

	@Test
	void unknownEmailStillCostsAHashComparison() {
		CountingHasher hasher = new CountingHasher();
		AccountService counted = new AccountService(new InMemoryAccounts(), hasher);

		assertThatThrownBy(() -> counted.authenticate("nobody@example.com", "secret123"))
			.isInstanceOf(InvalidCredentialsException.class);
		assertThat(hasher.matchCalls).isEqualTo(1);
	}

	private static class PrefixHasher implements PasswordHasher {

		public String hash(String rawPassword) {
			return "hashed:" + rawPassword;
		}

		public boolean matches(String rawPassword, String hash) {
			return hash.equals(hash(rawPassword));
		}
	}

	private static class CountingHasher extends PrefixHasher {

		int matchCalls;

		@Override
		public boolean matches(String rawPassword, String hash) {
			matchCalls++;
			return super.matches(rawPassword, hash);
		}
	}
}
