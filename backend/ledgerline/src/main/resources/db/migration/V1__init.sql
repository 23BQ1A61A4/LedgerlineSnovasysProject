CREATE TABLE app_user (
  id UUID PRIMARY KEY, username VARCHAR(50) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN','ACCOUNT_HOLDER','AUDITOR')),
  failed_attempts INT NOT NULL DEFAULT 0, locked_until TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE account (
  id UUID PRIMARY KEY, owner_id UUID REFERENCES app_user(id),
  kind VARCHAR(10) NOT NULL CHECK (kind IN ('CUSTOMER','SYSTEM')), name VARCHAR(60) NOT NULL,
  balance_minor BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT customer_has_owner CHECK (kind = 'SYSTEM' OR owner_id IS NOT NULL),
  CONSTRAINT no_negative_customer_balance CHECK (kind = 'SYSTEM' OR balance_minor >= 0));
INSERT INTO account (id, kind, name) VALUES ('00000000-0000-0000-0000-000000000001','SYSTEM','Bank cash');
CREATE TABLE ledger_transaction (
  id UUID PRIMARY KEY, type VARCHAR(12) NOT NULL CHECK (type IN ('DEPOSIT','WITHDRAWAL','TRANSFER')),
  initiated_by UUID NOT NULL REFERENCES app_user(id), amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE ledger_entry (
  id BIGSERIAL PRIMARY KEY, transaction_id UUID NOT NULL REFERENCES ledger_transaction(id),
  account_id UUID NOT NULL REFERENCES account(id), amount_minor BIGINT NOT NULL CHECK (amount_minor <> 0));
CREATE INDEX ix_entry_account ON ledger_entry(account_id, id DESC);
CREATE INDEX ix_entry_tx ON ledger_entry(transaction_id);
CREATE TABLE idempotency_key (
  user_id UUID NOT NULL, idem_key VARCHAR(100) NOT NULL, request_hash VARCHAR(64) NOT NULL,
  transaction_id UUID REFERENCES ledger_transaction(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, idem_key));
CREATE TABLE audit_log (
  id BIGSERIAL PRIMARY KEY, created_at TIMESTAMPTZ NOT NULL, actor_id UUID, actor_name VARCHAR(50),
  action VARCHAR(40) NOT NULL, target VARCHAR(100),
  outcome VARCHAR(10) NOT NULL CHECK (outcome IN ('SUCCESS','DENIED','FAILED')),
  detail VARCHAR(300), ip VARCHAR(64), prev_hash VARCHAR(64) NOT NULL, hash VARCHAR(64) NOT NULL);

CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION '% on % is not allowed (append-only)', TG_OP, TG_TABLE_NAME; END $$ LANGUAGE plpgsql;
CREATE TRIGGER audit_no_change BEFORE UPDATE OR DELETE ON audit_log FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER audit_no_truncate BEFORE TRUNCATE ON audit_log FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER entry_no_change BEFORE UPDATE OR DELETE ON ledger_entry FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER entry_no_truncate BEFORE TRUNCATE ON ledger_entry FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER tx_no_change BEFORE UPDATE OR DELETE ON ledger_transaction FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER tx_no_truncate BEFORE TRUNCATE ON ledger_transaction FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

CREATE FUNCTION check_zero_sum() RETURNS trigger AS $$
BEGIN
  IF (SELECT COALESCE(SUM(amount_minor),0) FROM ledger_entry WHERE transaction_id = NEW.transaction_id) <> 0 THEN
    RAISE EXCEPTION 'ledger transaction % does not balance', NEW.transaction_id; END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER trg_zero_sum AFTER INSERT ON ledger_entry
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_zero_sum();
