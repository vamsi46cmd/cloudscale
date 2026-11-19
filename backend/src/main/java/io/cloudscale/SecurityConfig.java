package io.cloudscale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    public static final String ISSUER = "cloudscale";

    private static void write(ObjectMapper mapper, HttpServletResponse res, HttpStatus status, String message, String path) throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), ApiExceptionHandler.ApiError.of(status, message, path, null));
    }

    private static SecretKey key(Props props) {
        byte[] secret = props.jwt().secret() == null ? new byte[0] : props.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be set and at least 32 bytes long");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(Props props) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(props)));
    }

    @Bean
    JwtDecoder jwtDecoder(Props props) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(key(props)).macAlgorithm(MacAlgorithm.HS256).build();
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER)));
        return d;
    }

    /** BCrypt (adaptive, salted). Cost 10 keeps login CPU reasonable; raise it for real deployments. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    RateLimitFilter rateLimitFilter(StringRedisTemplate redis, Props props, ObjectMapper mapper, RedisCircuit circuit, MeterRegistry registry) {
        return new RateLimitFilter(redis, props, mapper, circuit, registry);
    }

    /** The rate limiter runs inside the security chain (after authentication); stop Boot registering it a second time. */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitRegistration(RateLimitFilter f) {
        FilterRegistrationBean<RateLimitFilter> r = new FilterRegistrationBean<>(f);
        r.setEnabled(false);
        return r;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, RateLimitFilter rateLimit, ObjectMapper mapper) throws Exception {
        AuthenticationEntryPoint entry = (req, res, ex) -> write(mapper, res, HttpStatus.UNAUTHORIZED, "Authentication required or token invalid", req.getRequestURI());
        AccessDeniedHandler denied = (req, res, ex) -> write(mapper, res, HttpStatus.FORBIDDEN, "Access denied", req.getRequestURI());
        http.csrf(c -> c.disable())   // stateless bearer-token API: no cookies, so no CSRF surface
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                .requestMatchers("/api/v1/docs/**", "/api/v1/openapi/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/actuator/**").permitAll()   // served only on the separate management port (8081)
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()).authenticationEntryPoint(entry).accessDeniedHandler(denied))
            .exceptionHandling(e -> e.authenticationEntryPoint(entry).accessDeniedHandler(denied))
            .addFilterAfter(rateLimit, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
            .info(new Info().title("CloudScale API").version("1.0.0").description("Multi-tenant workspace, project and job API"))
            .components(new Components().addSecuritySchemes("bearerAuth",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
