package ph.edu.ici.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean PasswordEncoder passwords(){return new BCryptPasswordEncoder(12);}
    @Bean SecurityFilterChain security(HttpSecurity http,JwtAuthenticationFilter jwt) throws Exception {
        return http.csrf(csrf->csrf.disable())
            .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache->cache.disable()).formLogin(form->form.disable()).httpBasic(basic->basic.disable())
            .authorizeHttpRequests(auth->auth.requestMatchers("/api/auth/login","/error").permitAll().anyRequest().authenticated())
            .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,exception)->unauthorized(response))
                .accessDeniedHandler((request,response,exception)->{
                    response.setStatus(403);response.setContentType("application/problem+json");
                    response.getWriter().write("{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"Your account is not authorized for this action.\"}");
                }))
            .headers(headers->headers.cacheControl(cache->{}))
            .addFilterBefore(jwt,UsernamePasswordAuthenticationFilter.class).build();
    }
    static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(401);response.setHeader("WWW-Authenticate","Bearer");response.setHeader("Cache-Control","no-store");
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":401,\"title\":\"Unauthorized\",\"detail\":\"Sign in with a valid, unexpired token.\"}");
    }
}
