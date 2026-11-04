package ph.edu.ici.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final StaffRepository staff;
    private final TokenService tokens;
    public JwtAuthenticationFilter(StaffRepository staff,TokenService tokens){this.staff=staff;this.tokens=tokens;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getServletPath().equals("/api/auth/login");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String authorization=request.getHeader("Authorization");
        if(authorization!=null) {
            try {
                if(!authorization.startsWith("Bearer ")||authorization.length()>8192)throw new IllegalArgumentException();
                Claims claims=tokens.verify(authorization.substring(7));
                UUID tokenId=UUID.fromString(claims.getId());
                var account=staff.find(Long.parseLong(claims.getSubject())).orElseThrow(IllegalArgumentException::new);
                if(!account.active()||staff.revoked(tokenId))throw new IllegalArgumentException();
                var grants=staff.grants(account.id());
                var identity=new StaffIdentity(account.id(),account.username(),account.fullName(),grants,tokenId,claims.getExpiration().toInstant());
                var authorities=grants.stream().map(StaffIdentity.Grant::role).distinct().map(SimpleGrantedAuthority::new).toList();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,authorities));
            }catch(JwtException|IllegalArgumentException exception){
                SecurityContextHolder.clearContext();SecurityConfiguration.unauthorized(response);return;
            }
        }
        chain.doFilter(request,response);
    }
}
