package com.ledgerline;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
  static final String GENESIS = "0".repeat(64);
  public record Row(long id, Instant at, UUID actorId, String actorName, String action, String target, String outcome, String detail, String ip, String prevHash, String hash) {}
  private final JdbcTemplate jdbc;
  public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  /** Joins the caller's transaction if any, else starts its own. */
  @Transactional
  public void record(UUID actorId, String actorName, String action, String target, String outcome, String detail, String ip) {
    jdbc.execute("SELECT pg_advisory_xact_lock(727001)"); // one writer at a time => the chain cannot fork
    String prev = jdbc.query("SELECT hash FROM audit_log ORDER BY id DESC LIMIT 1", (rs, i) -> rs.getString(1)).stream().findFirst().orElse(GENESIS);
    Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS); // Postgres keeps microseconds
    String d = detail != null && detail.length() > 300 ? detail.substring(0, 300) : detail;
    jdbc.update("INSERT INTO audit_log (created_at, actor_id, actor_name, action, target, outcome, detail, ip, prev_hash, hash) VALUES (?,?,?,?,?,?,?,?,?,?)",
        Timestamp.from(at), actorId, actorName, action, target, outcome, d, ip, prev, hash(prev, at, actorId, actorName, action, target, outcome, d, ip));
  }
  static String hash(String prev, Instant at, UUID actorId, String an, String action, String target, String outcome, String d, String ip) {
    String p = String.join("|", prev, at.toString(), Objects.toString(actorId, ""), Objects.toString(an, ""), action, Objects.toString(target, ""), outcome, Objects.toString(d, ""), Objects.toString(ip, ""));
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(p.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); }
  }
  private List<Row> all(String sql, Object... args) {
    return jdbc.query(sql, (rs, i) -> new Row(rs.getLong("id"), rs.getTimestamp("created_at").toInstant(), rs.getObject("actor_id", UUID.class), rs.getString("actor_name"),
        rs.getString("action"), rs.getString("target"), rs.getString("outcome"), rs.getString("detail"), rs.getString("ip"), rs.getString("prev_hash"), rs.getString("hash")), args); }
  @Transactional(readOnly = true)
  public List<Row> latest(int limit) { return all("SELECT * FROM audit_log ORDER BY id DESC LIMIT ?", Math.max(1, Math.min(limit, 500))); }
  @Transactional(readOnly = true)
  public Map<String, Object> verifyChain() {
    String prev = GENESIS; List<Row> rows = all("SELECT * FROM audit_log ORDER BY id");
    for (Row r : rows) {
      if (!r.prevHash().equals(prev) || !r.hash().equals(hash(prev, r.at(), r.actorId(), r.actorName(), r.action(), r.target(), r.outcome(), r.detail(), r.ip())))
        return Map.of("ok", false, "firstBadId", r.id());
      prev = r.hash();
    }
    return Map.of("ok", true, "entries", rows.size());
  }
}

/** Records every denied/failed attempt AFTER the business transaction has rolled back. */
@Component
class AuditedRunner {
  private final AuditService audit;
  AuditedRunner(AuditService a) { audit = a; }
  <T> T run(Actor a, String action, String target, HttpServletRequest req, Supplier<T> op) {
    try { return op.get(); }
    catch (ApiException e) { audit.record(a.id(), a.username(), action, target, e.denial() ? "DENIED" : "FAILED", e.code(), req.getRemoteAddr()); throw e; }
    catch (RuntimeException e) { audit.record(a.id(), a.username(), action, target, "FAILED", "INTERNAL", req.getRemoteAddr()); throw e; }
  }
}
