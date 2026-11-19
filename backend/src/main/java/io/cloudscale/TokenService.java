package io.cloudscale;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access tokens: short-lived signed JWTs (HS256 via Nimbus). Refresh tokens: random 256-bit opaque values, stored only
 * as SHA-256 hashes, rotated on every use. Presenting an already-used refresh token revokes the whole token family
 * (reuse detection), so a stolen token that races the legitimate client invalidates both.
 */
@Service
public class TokenService {
    public record Tokens(String accessToken, String refreshToken, String tokenType, long expiresIn) {}

    private final JwtEncoder encoder;
    private final JdbcTemplate jdbc;
    private final Props props;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JwtEncoder encoder, JdbcTemplate jdbc, Props props) {
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.props = props;
    }

    public Tokens issue(UUID userId, String email, UUID familyId) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.jwt().accessTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(SecurityConfig.ISSUER).subject(userId.toString())
                .issuedAt(now).expiresAt(exp).id(UUID.randomUUID().toString()).claim("email", email).build();
        String access = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.update("INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, expires_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), userId, familyId, sha256(refresh), java.sql.Timestamp.from(now.plus(props.jwt().refreshTtl())));
        return new Tokens(access, refresh, "Bearer", props.jwt().accessTtl().toSeconds());
    }

    /** noRollbackFor: the family revocation below must COMMIT even though this method ends by throwing 401. */
    @Transactional(noRollbackFor = ApiException.class)
    public Tokens rotate(String refreshToken) {
        String hash = sha256(refreshToken);
        // Single atomic statement: only one concurrent caller can consume a given refresh token.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > now() RETURNING user_id, family_id", hash);
        if (rows.isEmpty()) {
            // Already-used token presented again: treat as theft and revoke the whole family.
            jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE revoked_at IS NULL AND family_id = "
                    + "(SELECT family_id FROM refresh_tokens WHERE token_hash = ? AND revoked_at IS NOT NULL)", hash);
            throw ApiException.unauthorized("Refresh token invalid, expired or already used");
        }
        UUID userId = (UUID) rows.get(0).get("user_id");
        UUID family = (UUID) rows.get(0).get("family_id");
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        return issue(userId, email, family);
    }

    public void revoke(String refreshToken) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = ? AND revoked_at IS NULL", sha256(refreshToken));
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
