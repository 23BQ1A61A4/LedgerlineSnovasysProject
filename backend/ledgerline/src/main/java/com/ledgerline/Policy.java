package com.ledgerline;
import com.ledgerline.LedgerService.AccountRow;
import com.ledgerline.LedgerService.MoveType;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/** EVERY authorisation decision lives here. Ownership always comes from DB rows, never from the request. */
@Component
public class Policy {
  void requireRole(Actor a, Role... allowed) { if (!Arrays.asList(allowed).contains(a.role())) throw ApiException.forbidden(); }
  private boolean isCustomer(AccountRow acc) { return "CUSTOMER".equals(acc.kind()); }
  private boolean owns(Actor a, AccountRow acc) { return isCustomer(acc) && a.id().equals(acc.ownerId()); }
  boolean canRead(Actor a, AccountRow acc) {
    return switch (a.role()) { case ADMIN, AUDITOR -> true; case ACCOUNT_HOLDER -> owns(a, acc); };
  }
  /** debit = money leaves, credit = money arrives. Denied unless a rule below allows it. */
  void requireCanMove(Actor a, MoveType type, AccountRow debit, AccountRow credit) {
    boolean ok = switch (type) {
      case DEPOSIT -> isCustomer(credit) && (a.role() == Role.ADMIN || (a.role() == Role.ACCOUNT_HOLDER && owns(a, credit)));
      case WITHDRAWAL -> a.role() == Role.ACCOUNT_HOLDER && owns(a, debit);
      case TRANSFER -> a.role() == Role.ACCOUNT_HOLDER && owns(a, debit) && isCustomer(credit);
    };
    if (!ok) throw ApiException.forbidden();
  }
}
