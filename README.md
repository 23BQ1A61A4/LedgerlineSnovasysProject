# LedgerLine
Money movement with a ledger you can prove: double-entry postings, idempotent requests, row-locked concurrency, server-enforced roles, hash-chained audit log.
**Status: written by AI assistants (ChatGPT, Claude). Update this section with what YOU have actually run and tested.**

## Run locally (Windows CMD)
1. PostgreSQL: create `ledgerline_db` and user `ledgerline_app` (owner of the DB; also `GRANT ALL ON SCHEMA public TO ledgerline_app;`).
2. Backend:
```
cd backend\ledgerline
set "DB_PASSWORD=YOUR_DB_PASSWORD"
set "JWT_SECRET=any-random-string-of-48+-characters"
set "SEED_DEMO=true"
mvnw.cmd spring-boot:run
```
3. Frontend (second window): `cd frontend` then `npm install` then `npm run dev` -> http://localhost:5173
4. Tests: create `ledgerline_test` DB, then `mvnw.cmd test`.

## Demo accounts (demo only, SEED_DEMO=true)
admin / Admin#Demo2026, auditor / Auditor#Demo2026, alice / Alice#Demo2026, bob / Bob#Demo2026!!

## Roles
ADMIN creates users/accounts and deposits. ACCOUNT_HOLDER moves money only from own accounts. AUDITOR read-only incl. audit log, hash-chain verify, reconcile.

## Known limitations (edit honestly)
Single currency; every deposit/withdrawal locks the SYSTEM account row (serialises); no rate limit beyond login lockout; JWT role changes apply at token expiry; Render free tier sleeps ~50s.
