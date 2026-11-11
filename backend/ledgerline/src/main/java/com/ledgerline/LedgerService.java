package com.ledgerline;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
  public static final UUID SYSTEM_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
  public enum MoveType { DEPOSIT, WITHDRAWAL, TRANSFER }
  public record AccountRow(UUID id, UUID ownerId, String kind, String name, long balanceMinor) {}
  public record TxResult(UUID transactionId, boolean replayed) {}
  private record Prior(String hash, UUID txId) {}
  static final RowMapper<AccountRow> ACCOUNT = (rs, i) -> new AccountRow(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getString("kind"), rs.getString("name"), rs.getLong("balance_minor"));
  private final JdbcTemplate jdbc; private final Policy policy; private final AuditService audit;
  public LedgerService(JdbcTemplate j, Policy p, AuditService a) { jdbc = j; policy = p; audit = a; }

  /** One routine for deposit, withdrawal and transfer: one place to get right. */
  @Transactional
  public TxResult move(Actor actor, MoveType type, UUID debitId, UUID creditId, long amount, String idemKey, String ip) {
    if (amount <= 0) throw ApiException.bad("INVALID_AMOUNT", "Amount must be positive");
    if (debitId.equals(creditId)) throw ApiException.bad("SAME_ACCOUNT", "Accounts must differ");
    String reqHash = sha256(type + "|" + debitId + "|" + creditId + "|" + amount);
    // 1. Idempotency: claim the key inside THIS transaction. A concurrent duplicate blocks until we commit, then replays our result.
    int claimed = jdbc.update("INSERT INTO idempotency_key (user_id, idem_key, request_hash) VALUES (?,?,?) ON CONFLICT (user_id, idem_key) DO NOTHING", actor.id(), idemKey, reqHash);
    if (claimed == 0) {
      Prior p = jdbc.queryForObject("SELECT request_hash, transaction_id FROM idempotency_key WHERE user_id = ? AND idem_key = ?", (rs, i) -> new Prior(rs.getString(1), rs.getObject(2, UUID.class)), actor.id(), idemKey);
      if (!p.hash().equals(reqHash)) throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED", "This key was already used for a different request");
      return new TxResult(p.txId(), true);
    }
    // 2. Lock both accounts in id order => no deadlocks, no lost updates
    List<AccountRow> rows = jdbc.query("SELECT id, owner_id, kind, name, balance_minor FROM account WHERE id IN (?, ?) ORDER BY id FOR UPDATE", ACCOUNT, debitId, creditId);
    if (rows.size() != 2) throw ApiException.notFound("Account not found");
    AccountRow debit = rows.stream().filter(r -> r.id().equals(debitId)).findFirst().orElseThrow();
    AccountRow credit = rows.stream().filter(r -> r.id().equals(creditId)).findFirst().orElseThrow();
    // 3. Authorise using DB facts, never request fields
    policy.requireCanMove(actor, type, debit, credit);
    // 4. Funds check on the locked row (the SYSTEM account may go negative: it mirrors customers' money)
    if ("CUSTOMER".equals(debit.kind()) && debit.balanceMinor() < amount) throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "Insufficient funds");
    // 5. Post two entries that sum to zero + update balances, all in one transaction
    UUID txId = UUID.randomUUID();
    jdbc.update("INSERT INTO ledger_transaction (id, type, initiated_by, amount_minor) VALUES (?,?,?,?)", txId, type.name(), actor.id(), amount);
    jdbc.update("INSERT INTO ledger_entry (transaction_id, account_id, amount_minor) VALUES (?,?,?)", txId, debitId, -amount);
    jdbc.update("INSERT INTO ledger_entry (transaction_id, account_id, amount_minor) VALUES (?,?,?)", txId, creditId, amount);
    jdbc.update("UPDATE account SET balance_minor = balance_minor - ? WHERE id = ?", amount, debitId);
    jdbc.update("UPDATE account SET balance_minor = balance_minor + ? WHERE id = ?", amount, creditId);
    jdbc.update("UPDATE idempotency_key SET transaction_id = ? WHERE user_id = ? AND idem_key = ?", txId, actor.id(), idemKey);
    audit.record(actor.id(), actor.username(), type.name(), txId.toString(), "SUCCESS", "amount=" + amount + " debit=" + debitId + " credit=" + creditId, ip);
    return new TxResult(txId, false);
  }

  /** Proof that the books balance, from one consistent snapshot. */
  @Transactional(readOnly = true)
  public Map<String, Object> reconcile() {
    Map<String, Object> m = new LinkedHashMap<>(jdbc.queryForMap("""
        SELECT (SELECT COUNT(*) FROM (SELECT 1 FROM ledger_entry GROUP BY transaction_id HAVING SUM(amount_minor) <> 0) x) AS unbalanced_transactions,
               (SELECT COUNT(*) FROM account a WHERE a.balance_minor <> COALESCE((SELECT SUM(e.amount_minor) FROM ledger_entry e WHERE e.account_id = a.id), 0)) AS balance_mismatches,
               (SELECT COALESCE(SUM(balance_minor), 0) FROM account) AS total_of_all_balances"""));
    m.put("ok", ((Number) m.get("unbalanced_transactions")).longValue() == 0 && ((Number) m.get("balance_mismatches")).longValue() == 0 && ((Number) m.get("total_of_all_balances")).longValue() == 0);
    return m;
  }
  static String sha256(String s) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); }
  }
}
