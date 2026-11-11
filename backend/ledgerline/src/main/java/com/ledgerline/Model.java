package com.ledgerline;
import java.util.*;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

enum Role { ADMIN, ACCOUNT_HOLDER, AUDITOR }

record Actor(UUID id, String username, Role role) {
  static Actor from(Jwt j) { return new Actor(UUID.fromString(j.getSubject()), j.getClaimAsString("username"), Role.valueOf(j.getClaimAsString("role"))); }
}

class ApiException extends RuntimeException {
  private final HttpStatus status; private final String code; private final boolean denial;
  ApiException(HttpStatus s, String code, String msg, boolean denial) { super(msg); status = s; this.code = code; this.denial = denial; }
  HttpStatus status() { return status; } String code() { return code; } boolean denial() { return denial; }
  static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to do this", true); }
  static ApiException hidden() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", true); } // same answer as "missing"
  static ApiException notFound(String m) { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", m, false); }
  static ApiException bad(String c, String m) { return new ApiException(HttpStatus.BAD_REQUEST, c, m, false); }
  static ApiException unprocessable(String c, String m) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, c, m, false); }
}

@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, String>> api(ApiException e) { return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage())); }
  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, String>> other(Exception e) {
    log.error("Unhandled error", e); // details stay in the server log, never in the response
    return ResponseEntity.status(500).body(Map.of("code", "INTERNAL_ERROR", "message", "Something went wrong")); }
}
