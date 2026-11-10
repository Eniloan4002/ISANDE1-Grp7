package ph.edu.ici;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.*;
import ph.edu.ici.auth.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AccessPolicyRequestTest {
    JdbcTemplate jdbc;AccessPolicy policy;
    @BeforeEach void setup(){jdbc=mock(JdbcTemplate.class);policy=new AccessPolicy(jdbc);var user=new StaffIdentity(1,"test","Test",List.of(),UUID.randomUUID(),Instant.now().plusSeconds(60),false);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));newRequest();}
    void newRequest(){RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));}
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();SecurityContextHolder.clearContext();}
    @Test void repeatedScopeChecksShareOneLookupButNextRequestSeesRevocation(){
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(1L),eq("enrollment.manage"))).thenReturn(List.of(3L),List.of());
        assertThat(policy.hasPermission("enrollment.manage")).isTrue();assertThat(policy.campuses("enrollment.manage")).containsExactly(3L);
        verify(jdbc,times(1)).queryForList(anyString(),eq(Long.class),eq(1L),eq("enrollment.manage"));
        newRequest();assertThat(policy.hasPermission("enrollment.manage")).isFalse();verify(jdbc,times(2)).queryForList(anyString(),eq(Long.class),eq(1L),eq("enrollment.manage"));
    }
    @Test void postLockRefreshRechecksPermissionsInsteadOfUsingEarlierAuthorization(){
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(1L),eq("users.manage"))).thenReturn(List.of(3L),List.of());
        assertThat(policy.hasPermission("users.manage")).isTrue();policy.refresh();assertThat(policy.hasPermission("users.manage")).isFalse();
    }
}
