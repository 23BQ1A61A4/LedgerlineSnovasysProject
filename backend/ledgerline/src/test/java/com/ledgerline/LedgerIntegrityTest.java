package com.ledgerline;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.jayway.jsonpath.JsonPath;
import com.ledgerline.LedgerService.MoveType;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class LedgerIntegrityTest {
  static final String PW = "Test#Passw0rd1";
  @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired LedgerService ledger; @Autowired AuditService audit; @Autowired PasswordEncoder enc;

  Actor user(Role role) {
    UUID id = UUID.randomUUID(); String n = role.name().toLowerCase() + "_" + id.toString().substring(0, 8);
    jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (?,?,?,?)", id, n, enc.encode(PW), role.name()); return new Actor(id, n, role); }
  UUID account(Actor o) { UUID id = UUID.randomUUID(); jdbc.update("INSERT INTO account (id, owner_id, kind, name) VALUES (?,?, 'CUSTOMER', 't')", id, o.id()); return id; }
  void fund(UUID acc, long amt) { ledger.move(user(Role.ADMIN), MoveType.DEPOSIT, LedgerService.SYSTEM_ACCOUNT, acc, amt, UUID.randomUUID().toString(), "t"); }
  long balance(UUID acc) { return jdbc.queryForObject("SELECT balance_minor FROM account WHERE id = ?", Long.class, acc); }
  String token(Actor a) throws Exception {
    String b = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + a.username() + "\",\"password\":\"" + PW + "\"}"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return JsonPath.read(b, "$.token"); }

  @Test void concurrentWithdrawalsNeverOverdraw() throws Exception {
    Actor alice = user(Role.ACCOUNT_HOLDER); UUID acc = account(alice); fund(acc, 1000);
    ExecutorService pool = Executors.newFixedThreadPool(20); List<Future<Boolean>> fs = new ArrayList<>();
    for (int i = 0; i < 20; i++) fs.add(pool.submit(() -> { try { ledger.move(alice, MoveType.WITHDRAWAL, acc, LedgerService.SYSTEM_ACCOUNT, 100, UUID.randomUUID().toString(), "t"); return true; } catch (ApiException e) { return false; } }));
    int ok = 0; for (var f : fs) if (f.get()) ok++; pool.shutdown();
    assertEquals(10, ok); assertEquals(0L, balance(acc)); assertEquals(true, ledger.reconcile().get("ok")); }

  @Test void sameIdempotencyKeyPaysOnlyOnce() throws Exception {
    Actor alice = user(Role.ACCOUNT_HOLDER); UUID acc = account(alice); fund(acc, 1000); String key = UUID.randomUUID().toString();
    ExecutorService pool = Executors.newFixedThreadPool(10); List<Future<LedgerService.TxResult>> fs = new ArrayList<>();
    for (int i = 0; i < 10; i++) fs.add(pool.submit(() -> ledger.move(alice, MoveType.WITHDRAWAL, acc, LedgerService.SYSTEM_ACCOUNT, 100, key, "t")));
    Set<UUID> ids = new HashSet<>(); for (var f : fs) ids.add(f.get().transactionId()); pool.shutdown();
    assertEquals(1, ids.size()); assertEquals(900L, balance(acc)); }

  @Test void sameKeyDifferentAmountIsRejected() {
    Actor alice = user(Role.ACCOUNT_HOLDER); UUID acc = account(alice); fund(acc, 1000); String key = UUID.randomUUID().toString();
    ledger.move(alice, MoveType.WITHDRAWAL, acc, LedgerService.SYSTEM_ACCOUNT, 100, key, "t");
    ApiException e = assertThrows(ApiException.class, () -> ledger.move(alice, MoveType.WITHDRAWAL, acc, LedgerService.SYSTEM_ACCOUNT, 200, key, "t"));
    assertEquals("IDEMPOTENCY_KEY_REUSED", e.code()); }

  @Test void overdraftRejectedBalanceUnchanged() {
    Actor alice = user(Role.ACCOUNT_HOLDER); UUID acc = account(alice); fund(acc, 500);
    ApiException e = assertThrows(ApiException.class, () -> ledger.move(alice, MoveType.WITHDRAWAL, acc, LedgerService.SYSTEM_ACCOUNT, 501, UUID.randomUUID().toString(), "t"));
    assertEquals("INSUFFICIENT_FUNDS", e.code()); assertEquals(500L, balance(acc)); }

  @Test void holderCannotReadOrSpendAnotherHoldersAccount() throws Exception {
    Actor alice = user(Role.ACCOUNT_HOLDER), bob = user(Role.ACCOUNT_HOLDER); UUID a = account(alice), b = account(bob); fund(a, 5000); String bt = token(bob);
    mvc.perform(get("/api/accounts/" + a).header("Authorization", "Bearer " + bt)).andExpect(status().isNotFound());
    mvc.perform(post("/api/transactions/transfer").header("Authorization", "Bearer " + bt).header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content("{\"fromAccountId\":\"" + a + "\",\"toAccountId\":\"" + b + "\",\"amountMinor\":1000}")).andExpect(status().isForbidden());
    assertEquals(5000L, balance(a));
    assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE actor_id = ? AND outcome = 'DENIED'", Integer.class, bob.id()) >= 2); }

  @Test void rolesEnforcedAndNoTokenIs401() throws Exception {
    Actor holder = user(Role.ACCOUNT_HOLDER), auditor = user(Role.AUDITOR); UUID acc = account(holder); fund(acc, 1000);
    String h = "Bearer " + token(holder), au = "Bearer " + token(auditor);
    mvc.perform(get("/api/audit").header("Authorization", h)).andExpect(status().isForbidden());
    mvc.perform(post("/api/admin/users").header("Authorization", h).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"evil\",\"password\":\"Passw0rd!!!!\",\"role\":\"ADMIN\"}")).andExpect(status().isForbidden());
    mvc.perform(post("/api/transactions/withdraw").header("Authorization", au).header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":\"" + acc + "\",\"amountMinor\":10}")).andExpect(status().isForbidden());
    mvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized()); }

  @Test void ledgerAndAuditAreAppendOnly() {
    Actor alice = user(Role.ACCOUNT_HOLDER); fund(account(alice), 100);
    assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM ledger_entry"));
    assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE audit_log SET outcome = 'SUCCESS'"));
    assertEquals(true, audit.verifyChain().get("ok")); }
}
