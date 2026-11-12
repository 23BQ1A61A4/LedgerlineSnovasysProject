package com.ledgerline;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.cors.*;

@Configuration
public class SecurityConfig {
  @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
    http.csrf(c -> c.disable()) // stateless bearer token, no cookies
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a
            .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
            .requestMatchers("/api/health", "/api/auth/login", "/error").permitAll()
            .requestMatchers("/api/**").authenticated() // role decisions are made in Policy
            .anyRequest().denyAll())                    // deny by default
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
    return http.build();
  }
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
  @Bean SecretKey jwtKey(@Value("${app.jwt-secret}") String s) {
    if (s == null || s.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalStateException("JWT_SECRET must be at least 32 characters");
    return new SecretKeySpec(s.getBytes(StandardCharsets.UTF_8), "HmacSHA256"); }
  @Bean JwtDecoder jwtDecoder(SecretKey k) { return NimbusJwtDecoder.withSecretKey(k).macAlgorithm(MacAlgorithm.HS256).build(); }
  @Bean JwtEncoder jwtEncoder(SecretKey k) { return new NimbusJwtEncoder(new ImmutableSecret<>(k)); }
  @Bean CorsConfigurationSource cors(@Value("${app.cors-origin}") String origin) {
    CorsConfiguration c = new CorsConfiguration();
    c.setAllowedOrigins(List.of(origin)); c.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
    UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource(); s.registerCorsConfiguration("/api/**", c); return s; }
}

@Service
class AuthService {
  private static final int MAX_FAILS = 5; private static final Duration LOCK = Duration.ofMinutes(15);
  record LoginResult(String token, UUID userId, String username, Role role) {}
  private record U(UUID id, String hash, Role role, int fails, Timestamp lockedUntil) {}
  private final JdbcTemplate jdbc; private final PasswordEncoder enc; private final JwtEncoder jwt; private final String dummy;
  AuthService(JdbcTemplate j, PasswordEncoder e, JwtEncoder jwt) { jdbc = j; enc = e; this.jwt = jwt; dummy = e.encode("not-a-real-password"); }
  private ApiException invalid() { return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password", true); }

  @Transactional(noRollbackFor = ApiException.class) // the failed-attempt counter must be saved
  LoginResult login(String username, String password) {
    var rows = jdbc.query("SELECT id, password_hash, role, failed_attempts, locked_until FROM app_user WHERE username = ? FOR UPDATE",
        (rs, i) -> new U(rs.getObject("id", UUID.class), rs.getString("password_hash"), Role.valueOf(rs.getString("role")),
            rs.getInt("failed_attempts"), rs.getTimestamp("locked_until")), username);
    if (rows.isEmpty()) { enc.matches(password, dummy); throw invalid(); } // same timing + same error for unknown users
    U u = rows.get(0);
    if (u.lockedUntil() != null && u.lockedUntil().toInstant().isAfter(Instant.now())) throw invalid();
    if (!enc.matches(password, u.hash())) {
      int f = u.fails() + 1; boolean lock = f >= MAX_FAILS;
      jdbc.update("UPDATE app_user SET failed_attempts = ?, locked_until = ? WHERE id = ?", lock ? 0 : f, lock ? Timestamp.from(Instant.now().plus(LOCK)) : null, u.id());
      throw invalid();
    }
    jdbc.update("UPDATE app_user SET failed_attempts = 0, locked_until = NULL WHERE id = ?", u.id());
    Instant now = Instant.now();
    JwtClaimsSet c = JwtClaimsSet.builder().subject(u.id().toString()).claim("username", username).claim("role", u.role().name())
        .issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(30))).build();
    return new LoginResult(jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), c)).getTokenValue(), u.id(), username, u.role());
  }
}

@RestController
class AuthController {
  record LoginRequest(@NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 100) String password) {}
  private final AuthService auth; private final AuditService audit;
  AuthController(AuthService a, AuditService au) { auth = a; audit = au; }
  @GetMapping("/api/health") Map<String, String> health() { return Map.of("status", "UP"); }
  @PostMapping("/api/auth/login")
  Map<String, Object> login(@Valid @RequestBody LoginRequest r, HttpServletRequest req) {
    try {
      var res = auth.login(r.username(), r.password());
      audit.record(res.userId(), res.username(), "LOGIN", null, "SUCCESS", null, req.getRemoteAddr());
      return Map.of("token", res.token(), "role", res.role().name(), "username", res.username());
    } catch (ApiException e) {
      audit.record(null, r.username(), "LOGIN", null, "DENIED", "bad credentials or locked", req.getRemoteAddr()); throw e; }
  }
}
