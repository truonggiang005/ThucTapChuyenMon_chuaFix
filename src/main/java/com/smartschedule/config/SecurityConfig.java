package com.smartschedule.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Cấu hình bảo mật (SEC-01, SEC-02, SEC-03, SEC-04, SEC-06, SEC-17).
 *
 * - Phiên đăng nhập: session phía server, cookie HttpOnly (không để token trong localStorage).
 * - Mặc định MỌI API yêu cầu đăng nhập; API quản trị yêu cầu ROLE_ADMIN.
 * - CSRF bật: token nằm ở cookie XSRF-TOKEN, frontend gửi lại qua header X-XSRF-TOKEN.
 * - Không cấu hình CORS: giao diện và API cùng origin (do Spring phục vụ) nên không cần (SEC-06).
 * - Security header: CSP, X-Frame-Options, X-Content-Type-Options, Referrer-Policy.
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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
                // Còn lại: phải đăng nhập (quyền theo chủ sở hữu kiểm bằng @PreAuthorize ở controller)
                .anyRequest().authenticated())
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
                  // style-src có 'unsafe-inline' vì giao diện hiện còn thuộc tính style="..." (không cho phép với script)
                  + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
                  + "font-src 'self' https://fonts.gstatic.com; "
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
