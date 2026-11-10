package ph.edu.ici.auth;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StaffIdentity(long userId, String username, String fullName, List<Grant> grants,
                            UUID tokenId, Instant expiresAt,boolean passwordChangeRequired) implements Principal {
    public record Grant(long campusId, String campusCode, String role) {}
    @Override public String getName() { return username; }
}
