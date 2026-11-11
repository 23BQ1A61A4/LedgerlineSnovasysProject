package com.ledgerline;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Demo-only users. Enabled with SEED_DEMO=true. Opening balances go through the real ledger. */
@Component
class DemoDataSeeder implements CommandLineRunner {
  private final JdbcTemplate jdbc; private final PasswordEncoder enc; private final LedgerService ledger;
  @Value("${app.seed-demo}") boolean enabled;
  DemoDataSeeder(JdbcTemplate j, PasswordEncoder e, LedgerService l) { jdbc = j; enc = e; ledger = l; }
  @Override public void run(String... args) {
    if (!enabled || jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class) > 0) return;
    Actor admin = user("admin", "Admin#Demo2026", Role.ADMIN);
    user("auditor", "Auditor#Demo2026", Role.AUDITOR);
    for (Actor h : new Actor[]{user("alice", "Alice#Demo2026", Role.ACCOUNT_HOLDER), user("bob", "Bob#Demo2026!!", Role.ACCOUNT_HOLDER)}) {
      UUID acc = UUID.randomUUID();
      jdbc.update("INSERT INTO account (id, owner_id, kind, name) VALUES (?,?, 'CUSTOMER', 'Savings')", acc, h.id());
      ledger.move(admin, LedgerService.MoveType.DEPOSIT, LedgerService.SYSTEM_ACCOUNT, acc, 100_000, UUID.randomUUID().toString(), "seed");
    }
  }
  private Actor user(String n, String pw, Role r) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (?,?,?,?)", id, n, enc.encode(pw), r.name());
    return new Actor(id, n, r);
  }
}
