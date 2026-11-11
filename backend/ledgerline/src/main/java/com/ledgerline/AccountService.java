package com.ledgerline;
import com.ledgerline.LedgerService.AccountRow;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  private static final String COLS = "SELECT id, owner_id, kind, name, balance_minor FROM account ";
  private final JdbcTemplate jdbc; private final Policy policy; private final PasswordEncoder enc; private final AuditService audit;
  public AccountService(JdbcTemplate j, Policy p, PasswordEncoder e, AuditService a) { jdbc = j; policy = p; enc = e; audit = a; }

  @Transactional
  UUID createUser(Actor actor, String username, String password, Role role, String ip) {
    policy.requireRole(actor, Role.ADMIN);
    UUID id = UUID.randomUUID();
    try { jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (?,?,?,?)", id, username, enc.encode(password), role.name()); }
    catch (DuplicateKeyException e) { throw ApiException.bad("USERNAME_TAKEN", "Username already exists"); }
    audit.record(actor.id(), actor.username(), "CREATE_USER", id.toString(), "SUCCESS", "role=" + role, ip);
    return id;
  }
  @Transactional
  UUID createAccount(Actor actor, UUID ownerId, String name, String ip) {
    policy.requireRole(actor, Role.ADMIN);
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ? AND role = 'ACCOUNT_HOLDER'", Integer.class, ownerId);
    if (n == null || n == 0) throw ApiException.bad("OWNER_INVALID", "Owner must be an existing account holder");
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, owner_id, kind, name) VALUES (?,?, 'CUSTOMER', ?)", id, ownerId, name);
    audit.record(actor.id(), actor.username(), "CREATE_ACCOUNT", id.toString(), "SUCCESS", "owner=" + ownerId, ip);
    return id;
  }
  @Transactional(readOnly = true)
  List<AccountRow> list(Actor actor) {
    if (actor.role() == Role.ACCOUNT_HOLDER) return jdbc.query(COLS + "WHERE kind = 'CUSTOMER' AND owner_id = ? ORDER BY created_at", LedgerService.ACCOUNT, actor.id());
    return jdbc.query(COLS + "WHERE kind = 'CUSTOMER' ORDER BY created_at", LedgerService.ACCOUNT);
  }
  @Transactional(readOnly = true)
  AccountRow get(Actor actor, UUID id) {
    List<AccountRow> r = jdbc.query(COLS + "WHERE id = ?", LedgerService.ACCOUNT, id);
    if (r.isEmpty()) throw ApiException.notFound("Not found");
    if (!policy.canRead(actor, r.get(0))) throw ApiException.hidden(); // identical 404: no account enumeration
    return r.get(0);
  }
  @Transactional(readOnly = true)
  List<Map<String, Object>> statement(Actor actor, UUID id, int limit) {
    get(actor, id);
    return jdbc.queryForList("SELECT e.id AS entry_id, e.transaction_id, t.type, e.amount_minor, t.created_at FROM ledger_entry e JOIN ledger_transaction t ON t.id = e.transaction_id WHERE e.account_id = ? ORDER BY e.id DESC LIMIT ?", id, Math.max(1, Math.min(limit, 200)));
  }
}
