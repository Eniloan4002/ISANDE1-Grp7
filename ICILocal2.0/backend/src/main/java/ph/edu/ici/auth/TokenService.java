package ph.edu.ici.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    public record Issued(String accessToken, Instant expiresAt) {}
    private final SecretKey key;
    private final String issuer;
    private final int ttlMinutes;
    public TokenService(@Value("${ici.auth.secret}") String secret,
                        @Value("${ici.auth.issuer}") String issuer,
                        @Value("${ici.auth.ttl-minutes}") int ttlMinutes) {
        try { this.key=Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret)); }
        catch (Exception exception) { throw new IllegalArgumentException("JWT_SECRET must be a Base64-encoded random key of at least 32 bytes."); }
        if(ttlMinutes<1||ttlMinutes>60) throw new IllegalArgumentException("JWT_TTL_MINUTES must be between 1 and 60.");
        this.issuer=issuer;this.ttlMinutes=ttlMinutes;
    }
    public Issued issue(long userId,List<StaffIdentity.Grant> grants) {
        Instant now=Instant.now(), expiry=now.plusSeconds(ttlMinutes*60L);
        List<Map<String,Object>> scopes=grants.stream().map(grant->Map.<String,Object>of("campusId",grant.campusId(),"campusCode",grant.campusCode(),"role",grant.role())).toList();
        String jwt=Jwts.builder().issuer(issuer).subject(Long.toString(userId)).id(UUID.randomUUID().toString())
            .issuedAt(Date.from(now)).expiration(Date.from(expiry))
            .claim("userId",userId).claim("roles",grants.stream().map(StaffIdentity.Grant::role).distinct().sorted().toList())
            .claim("campusScope",scopes).signWith(key,Jwts.SIG.HS256).compact();
        return new Issued(jwt,expiry);
    }
    public Claims verify(String token) {
        Claims claims=Jwts.parser().verifyWith(key).requireIssuer(issuer).build().parseSignedClaims(token).getPayload();
        if(claims.getExpiration()==null||claims.getIssuedAt()==null||claims.getId()==null||claims.getSubject()==null)
            throw new IllegalArgumentException("Required token claims are missing.");
        UUID.fromString(claims.getId());Long.parseLong(claims.getSubject());
        return claims;
    }
}
