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
                var session=staff.session(Long.parseLong(claims.getSubject()),tokenId).orElseThrow(IllegalArgumentException::new);
                var account=session.account();
                Object credentialVersion=claims.getOrDefault("tokenVersion",0);if(!(credentialVersion instanceof Number))throw new IllegalArgumentException();
                if(!account.active()||session.revoked()||account.tokenVersion()!=((Number)credentialVersion).intValue())throw new IllegalArgumentException();
                if(account.passwordChangeRequired()&&!java.util.Set.of("/api/auth/me","/api/auth/logout","/api/auth/change-password").contains(request.getServletPath())){
                    response.setStatus(403);response.setContentType("application/problem+json");response.getWriter().write("{\"status\":403,\"detail\":\"Change your temporary password before continuing.\"}");return;
                }
                var grants=session.grants();
                var identity=new StaffIdentity(account.id(),account.username(),account.fullName(),grants,tokenId,claims.getExpiration().toInstant(),account.passwordChangeRequired());
                var authorities=grants.stream().map(StaffIdentity.Grant::role).distinct().map(SimpleGrantedAuthority::new).toList();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,authorities));
            }catch(JwtException|IllegalArgumentException exception){
                SecurityContextHolder.clearContext();SecurityConfiguration.unauthorized(response);return;
            }
        }
        chain.doFilter(request,response);
    }
}
