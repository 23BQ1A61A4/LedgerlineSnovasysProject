package com.ledgerline;
import com.ledgerline.LedgerService.MoveType;
import com.ledgerline.LedgerService.TxResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/accounts")
class AccountController {
  private final AccountService svc; private final AuditedRunner runner;
  AccountController(AccountService s, AuditedRunner r) { svc = s; runner = r; }
  @GetMapping Object list(@AuthenticationPrincipal Jwt jwt) { return svc.list(Actor.from(jwt)); }
  @GetMapping("/{id}") Object get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest req) {
    Actor a = Actor.from(jwt); return runner.run(a, "VIEW_ACCOUNT", id.toString(), req, () -> svc.get(a, id)); }
  @GetMapping("/{id}/statement") Object statement(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam(defaultValue = "50") int limit, HttpServletRequest req) {
    Actor a = Actor.from(jwt); return runner.run(a, "VIEW_STATEMENT", id.toString(), req, () -> svc.statement(a, id, limit)); }
}

@RestController @RequestMapping("/api/admin")
class AdminController {
  record NewUser(@NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "[A-Za-z0-9_.-]+") String username, @NotBlank @Size(min = 10, max = 100) String password, @NotNull Role role) {}
  record NewAccount(@NotNull UUID ownerId, @NotBlank @Size(max = 60) String name) {}
  private final AccountService svc; private final AuditedRunner runner;
  AdminController(AccountService s, AuditedRunner r) { svc = s; runner = r; }
  @PostMapping("/users") Map<String, Object> user(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewUser r, HttpServletRequest req) {
    Actor a = Actor.from(jwt); return runner.run(a, "CREATE_USER", r.username(), req, () -> Map.of("id", svc.createUser(a, r.username(), r.password(), r.role(), req.getRemoteAddr()))); }
  @PostMapping("/accounts") Map<String, Object> account(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewAccount r, HttpServletRequest req) {
    Actor a = Actor.from(jwt); return runner.run(a, "CREATE_ACCOUNT", r.ownerId().toString(), req, () -> Map.of("id", svc.createAccount(a, r.ownerId(), r.name(), req.getRemoteAddr()))); }
}

@RestController @RequestMapping("/api/transactions")
class LedgerController {
  record AccountMove(@NotNull UUID accountId, @Positive @Max(100_000_000_000L) long amountMinor) {}
  record Transfer(@NotNull UUID fromAccountId, @NotNull UUID toAccountId, @Positive @Max(100_000_000_000L) long amountMinor) {}
  private final LedgerService ledger; private final AuditedRunner runner;
  LedgerController(LedgerService l, AuditedRunner r) { ledger = l; runner = r; }
  private static String requireKey(String k) {
    if (k == null || k.length() < 8 || k.length() > 100) throw ApiException.bad("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header (8-100 chars) is required"); return k; }
  private static ResponseEntity<Map<String, Object>> respond(TxResult r) {
    return ResponseEntity.status(r.replayed() ? 200 : 201).header("Idempotent-Replay", String.valueOf(r.replayed())).body(Map.of("transactionId", r.transactionId(), "replayed", r.replayed())); }
  @PostMapping("/deposit") ResponseEntity<Map<String, Object>> deposit(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody AccountMove r, HttpServletRequest req) {
    Actor a = Actor.from(jwt); return respond(runner.run(a, "DEPOSIT", r.accountId().toString(), req, () -> ledger.move(a, MoveType.DEPOSIT, LedgerService.SYSTEM_ACCOUNT, r.accountId(), r.amountMinor(), requireKey(key), req.getRemoteAddr()))); }
  @PostMapping("/withdraw") ResponseEntity<Map<String, Object>> withdraw(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody AccountMove r, HttpServletRequest req) {
    Actor a = Actor.from(jwt); return respond(runner.run(a, "WITHDRAWAL", r.accountId().toString(), req, () -> ledger.move(a, MoveType.WITHDRAWAL, r.accountId(), LedgerService.SYSTEM_ACCOUNT, r.amountMinor(), requireKey(key), req.getRemoteAddr()))); }
  @PostMapping("/transfer") ResponseEntity<Map<String, Object>> transfer(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody Transfer r, HttpServletRequest req) {
    Actor a = Actor.from(jwt); return respond(runner.run(a, "TRANSFER", r.fromAccountId().toString(), req, () -> ledger.move(a, MoveType.TRANSFER, r.fromAccountId(), r.toAccountId(), r.amountMinor(), requireKey(key), req.getRemoteAddr()))); }
}

@RestController @RequestMapping("/api/audit")
class AuditController {
  private final AuditService audit; private final LedgerService ledger; private final Policy policy; private final AuditedRunner runner;
  AuditController(AuditService a, LedgerService l, Policy p, AuditedRunner r) { audit = a; ledger = l; policy = p; runner = r; }
  @GetMapping Object list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "100") int limit, HttpServletRequest req) {
    Actor a = Actor.from(jwt); return runner.run(a, "VIEW_AUDIT", null, req, () -> { policy.requireRole(a, Role.AUDITOR); return audit.latest(limit); }); }
  @GetMapping("/verify") Object verify(@AuthenticationPrincipal Jwt jwt, HttpServletRequest req) {
    Actor a = Actor.from(jwt); return runner.run(a, "VERIFY_AUDIT", null, req, () -> { policy.requireRole(a, Role.AUDITOR); return audit.verifyChain(); }); }
  @GetMapping("/reconcile") Object reconcile(@AuthenticationPrincipal Jwt jwt, HttpServletRequest req) {
    Actor a = Actor.from(jwt); return runner.run(a, "RECONCILE", null, req, () -> { policy.requireRole(a, Role.AUDITOR); return ledger.reconcile(); }); }
}
