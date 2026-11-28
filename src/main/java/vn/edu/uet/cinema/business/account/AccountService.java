package vn.edu.uet.cinema.business.account;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import vn.edu.uet.cinema.business.NotFoundException;
import vn.edu.uet.cinema.business.PageQuery;
import vn.edu.uet.cinema.business.PageSlice;
import vn.edu.uet.cinema.business.audit.AuditAction;
import vn.edu.uet.cinema.business.audit.AuditDetails;
import vn.edu.uet.cinema.business.audit.AuditLog;
import vn.edu.uet.cinema.business.audit.AuditTargetType;

public class AccountService {

	private final AccountRepository accounts;
	private final PasswordHasher passwordHasher;
	/** Checked against when the email is unknown, so both failure paths cost one hash comparison. */
	private final String dummyHash;

	public AccountService(AccountRepository accounts, PasswordHasher passwordHasher) {
		this.accounts = accounts;
		this.passwordHasher = passwordHasher;
		this.dummyHash = passwordHasher.hash("not-a-real-password");
	}

	/** Self-registration always creates a Customer; Admins are bootstrapped or created by an existing Admin. */
	public Account registerCustomer(String email, String password, String fullName) {
		String normalizedEmail = normalize(email);
		if (accounts.existsByEmail(normalizedEmail)) {
			throw new EmailTakenException();
		}
		return accounts.save(new Account(UUID.randomUUID(), normalizedEmail, passwordHasher.hash(password),
			fullName.strip(), Role.CUSTOMER));
	}

	/**
	 * Seeds the initial bootstrap Admin from environment variables if it does not exist yet (S4).
	 * Once created, an existing Admin's password is managed in the database via {@link #changePassword}
	 * and is not overwritten on subsequent restarts.
	 */
	public void ensureAdmin(String email, String password) {
		String normalizedEmail = normalize(email);
		Optional<Account> existing = accounts.findByEmail(normalizedEmail);
		if (existing.isPresent()) {
			Account admin = existing.get();
			if (admin.role() != Role.ADMIN) {
				throw new IllegalStateException("Configured admin email belongs to a " + admin.role() + " account");
			}
			return;
		}
		try {
			accounts.save(new Account(UUID.randomUUID(), normalizedEmail, passwordHasher.hash(password),
				"Administrator", Role.ADMIN));
		} catch (EmailTakenException alreadyCreatedByAnotherInstance) {
			// Several instances starting together: whoever inserts first wins, that's fine.
		}
	}

	/** Creates an additional database-managed Admin account and records an atomic audit entry (S4). */
	public Account createAdmin(UUID actorId, String email, String password, String fullName, Instant now) {
		String normalizedEmail = normalize(email);
		if (accounts.existsByEmail(normalizedEmail)) {
			throw new EmailTakenException();
		}
		Account admin = new Account(UUID.randomUUID(), normalizedEmail, passwordHasher.hash(password),
			fullName.strip(), Role.ADMIN);
		if (actorId == null) {
			return accounts.save(admin);
		}
		Instant timestamp = now != null ? now : Instant.now();
		String details = AuditDetails.builder()
			.put("email", admin.email())
			.put("fullName", admin.fullName())
			.toJson();
		AuditLog auditLog = AuditLog.of(actorId, AuditAction.ADMIN_CREATED, AuditTargetType.ACCOUNT,
			admin.id(), details, timestamp);
		return accounts.save(admin, auditLog);
	}

	/** Lists database-managed Admin accounts with pagination (S4). */
	public PageSlice<Account> listAdmins(PageQuery query) {
		return accounts.findByRole(Role.ADMIN, query != null ? query : PageQuery.DEFAULT);
	}

	/**
	 * Changes an account's password at runtime without restarting the service (S4).
	 * Verifies {@code currentPassword} first; when the caller is an Admin, appends an atomic
	 * {@link AuditAction#ADMIN_PASSWORD_CHANGED} entry in the same transaction.
	 */
	public Account changePassword(UUID accountId, String currentPassword, String newPassword, Instant now) {
		Account account = get(accountId);
		if (!passwordHasher.matches(currentPassword, account.passwordHash())) {
			throw new InvalidCredentialsException("Invalid current password");
		}
		Account updated = new Account(account.id(), account.email(), passwordHasher.hash(newPassword),
			account.fullName(), account.role());
		if (account.role() == Role.ADMIN) {
			Instant timestamp = now != null ? now : Instant.now();
			AuditLog auditLog = AuditLog.of(account.id(), AuditAction.ADMIN_PASSWORD_CHANGED, AuditTargetType.ACCOUNT,
				account.id(), AuditDetails.emptyJson(), timestamp);
			return accounts.save(updated, auditLog);
		}
		return accounts.save(updated);
	}

	public Account authenticate(String email, String password) {
		Optional<Account> account = accounts.findByEmail(normalize(email));
		// Always hash-compare so an unknown email takes as long as a wrong password (no account enumeration by timing).
		boolean passwordMatches = passwordHasher.matches(password,
			account.map(Account::passwordHash).orElse(dummyHash));
		if (account.isEmpty() || !passwordMatches) {
			throw new InvalidCredentialsException();
		}
		return account.get();
	}

	public Account get(UUID id) {
		return accounts.findById(id).orElseThrow(() -> new NotFoundException("Account not found"));
	}

	private static String normalize(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}
}
