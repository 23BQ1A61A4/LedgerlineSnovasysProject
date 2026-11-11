# Threats
| Threat | Defence | Test |
|---|---|---|
| Retry / double-click pays twice | Idempotency key claimed in same DB transaction | sameIdempotencyKeyPaysOnlyOnce |
| Same key, different amount | Request-hash comparison, 422 | sameKeyDifferentAmountIsRejected |
| Concurrent withdrawals overdraw | Row locks in id order + CHECK constraint | concurrentWithdrawalsNeverOverdraw |
| Reading/spending another user's account (IDOR) | Policy with ownership from DB; 404/403; audited | holderCannotReadOrSpendAnotherHoldersAccount |
| Privilege escalation | Role checks in Policy; deny-by-default URLs | rolesEnforcedAndNoTokenIs401 |
| Zero/negative amounts | Validation + DB CHECK | overdraftRejectedBalanceUnchanged (add a negative-amount test) |
| Editing/deleting history | Append-only DB triggers | ledgerAndAuditAreAppendOnly |
| Audit tampering | Hash chain + /api/audit/verify | ledgerAndAuditAreAppendOnly |
| Money created/destroyed | Zero-sum deferred trigger + /api/audit/reconcile | concurrentWithdrawalsNeverOverdraw |
| Password guessing | BCrypt + lockout after 5 fails | TODO: write test |
| Account enumeration | Same 404 / same login error | TODO: write test |
| XSS steals token | 30-min token in sessionStorage (residual risk) | none - stated honestly |
