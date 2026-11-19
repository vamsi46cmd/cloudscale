package io.cloudscale;

import io.cloudscale.Entities.AppUser;
import io.cloudscale.TokenService.Tokens;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Authentication")
public class AuthController {
    public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(min = 10, max = 72) String password) {}

    public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {}

    public record UserView(UUID id, String email, Instant createdAt) {}

    private final Repos.UserRepo users;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final String dummyHash;

    public AuthController(Repos.UserRepo users, PasswordEncoder encoder, TokenService tokens) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.dummyHash = encoder.encode("not-a-real-password");   // compared when the email is unknown, to even out timing
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Tokens register(@Valid @RequestBody RegisterRequest req) {
        AppUser u = new AppUser();
        u.id = UUID.randomUUID();
        u.email = req.email().trim().toLowerCase();
        u.passwordHash = encoder.encode(req.password());
        try {
            users.saveAndFlush(u);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("Email already registered");
        }
        return tokens.issue(u.id, u.email, UUID.randomUUID());
    }

    @PostMapping("/auth/login")
    public Tokens login(@Valid @RequestBody LoginRequest req) {
        AppUser u = users.findByEmailIgnoreCase(req.email().trim()).orElse(null);
        boolean ok = encoder.matches(req.password(), u == null ? dummyHash : u.passwordHash);
        if (u == null || !ok) {
            throw ApiException.unauthorized("Invalid credentials");   // same answer for unknown email and wrong password
        }
        return tokens.issue(u.id, u.email, UUID.randomUUID());
    }

    @PostMapping("/auth/refresh")
    public Tokens refresh(@Valid @RequestBody RefreshRequest req) {
        return tokens.rotate(req.refreshToken());
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest req) {
        tokens.revoke(req.refreshToken());
    }

    @GetMapping("/users/me")
    public UserView me(@AuthenticationPrincipal Jwt jwt) {
        AppUser u = users.findById(UUID.fromString(jwt.getSubject())).orElseThrow(() -> ApiException.unauthorized("Unknown user"));
        return new UserView(u.id, u.email, u.createdAt);
    }
}
