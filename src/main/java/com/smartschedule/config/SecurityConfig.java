package com.smartschedule.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Cấu hình bảo mật (SEC-01, SEC-02, SEC-03, SEC-04, SEC-06, SEC-17).
 *
 * - Phiên đăng nhập: session phía server, cookie HttpOnly (không để token trong localStorage).
 * - Mặc định MỌI API yêu cầu đăng nhập; API quản trị yêu cầu ROLE_ADMIN.
 * - CSRF bật: token nằm ở cookie XSRF-TOKEN, frontend gửi lại qua header X-XSRF-TOKEN.
 * - Không cấu hình CORS: giao diện và API cùng origin (do Spring phục vụ) nên không cần (SEC-06).
 * - Security header: CSP (script/style chỉ từ 'self'), X-Frame-Options, X-Content-Type-Options, Referrer-Policy.
 * - Giới hạn số phiên đồng thời mỗi người (app.security.max-sessions), khóa tài khoản khi sai mật khẩu nhiều lần.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // ---------- Giới hạn số phiên đăng nhập đồng thời mỗi người (C4) ----------

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Để SessionRegistry biết khi phiên bị hủy/hết hạn mà dọn đi. */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * Chiến lược phiên cho đăng nhập thủ công ở AuthController: quá số phiên cho phép thì phiên cũ nhất bị hết hạn;
     * đổi id phiên (chống session fixation); đăng ký phiên mới vào registry.
     */
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(
            SessionRegistry registry, @Value("${app.security.max-sessions:3}") int maxSessions) {
        ConcurrentSessionControlAuthenticationStrategy concurrent =
                new ConcurrentSessionControlAuthenticationStrategy(registry);
        concurrent.setMaximumSessions(maxSessions);
        concurrent.setExceptionIfMaximumExceeded(false);   // đăng nhập mới thắng, phiên cũ bị đá ra
        return new CompositeSessionAuthenticationStrategy(List.of(
                concurrent,
                new ChangeSessionIdAuthenticationStrategy(),
                new RegisterSessionAuthenticationStrategy(registry)));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SessionRegistry sessionRegistry,
                                                   @Value("${app.security.max-sessions:3}") int maxSessions) throws Exception {
        // CSRF cho SPA: cookie đọc được bằng JS, token gửi lại ở header
        XorCsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();
        delegate.setCsrfRequestAttributeName("_csrf");
        CsrfTokenRequestHandler requestHandler = delegate::handle;

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(requestHandler))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a
                // Trang giao diện (chỉ là vỏ HTML, không chứa dữ liệu) + tài nguyên tĩnh
                .requestMatchers("/", "/dashboard", "/css/**", "/js/**", "/favicon.ico", "/error").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                // API quản trị
                .requestMatchers(HttpMethod.GET, "/api/shifts").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/shifts").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/shifts/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/shifts/*/candidates").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/shifts/*/assign").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/employees").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/employees/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/employees/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/branches/**", "/api/skills/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/branches/**", "/api/skills/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/shifts/*/late").hasRole("ADMIN")
                // Còn lại: phải đăng nhập (quyền theo chủ sở hữu kiểm bằng @PreAuthorize ở controller)
                .anyRequest().authenticated())
            .sessionManagement(sm -> sm
                .maximumSessions(maxSessions)
                .sessionRegistry(sessionRegistry))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .logout(l -> l
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpStatus.OK.value()))
                .deleteCookies("JSESSIONID"))
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives(
                    "default-src 'self'; "
                  + "script-src 'self'; "
                  // C3: không còn thuộc tính style="..." trong markup và không dùng font ngoài, nên KHÔNG cần 'unsafe-inline'
                  + "style-src 'self'; "
                  + "font-src 'self'; "
                  + "img-src 'self' data:; "
                  + "connect-src 'self'; "
                  + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"))
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)));

        return http.build();
    }

    /** Ép Spring ghi cookie XSRF-TOKEN ở mọi response (token mặc định được nạp lười). */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
