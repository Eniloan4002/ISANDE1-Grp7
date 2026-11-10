package ph.edu.ici;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import ph.edu.ici.auth.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class ReadQueryBudgetTest {
    @Test void fullAccountPageUsesOneGrantQueryAndPreservesScopeAndVersions(){
        var db=mock(NamedParameterJdbcTemplate.class);var policy=mock(AccessPolicy.class);
        when(policy.campuses("users.manage")).thenReturn(List.of(7L));
        when(db.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(25L);
        var rows=new ArrayList<Map<String,Object>>();var grants=new ArrayList<Map<String,Object>>();
        for(long id=1;id<=25;id++){
            rows.add(new LinkedHashMap<>(Map.of("id",id,"username","staff"+id,"updated_at",Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")))));
            grants.add(new LinkedHashMap<>(Map.of("user_id",id,"role","ROLE_TEACHER","campusId",7L,"campus","Campus")));
        }
        when(db.queryForList(anyString(),anyMap())).thenAnswer(call->{
            String sql=call.getArgument(0);Map<?,?> params=call.getArgument(1);
            if(sql.contains("FROM users u")){
                assertThat(sql).contains("g.campus_id NOT IN (:scope)");assertThat(params.get("scope")).isEqualTo(List.of(7L));return rows;
            }
            assertThat(sql).contains("g.user_id IN (:ids)");assertThat((List<?>)params.get("ids")).hasSize(25);return grants;
        });
        var page=new StaffAccountController(db,mock(PasswordEncoder.class),policy).list("",0);
        assertThat(page.get("total")).isEqualTo(25L);
        for(var row:rows){assertThat(row).containsEntry("version","2026-01-01T00:00:00Z").doesNotContainKey("updated_at");assertThat((List<?>)row.get("grants")).hasSize(1);}
        verify(db,times(2)).queryForList(anyString(),anyMap());verify(db,times(1)).queryForObject(anyString(),anyMap(),eq(Long.class));
    }
    @Test void queueTotalTravelsWithRowsWithoutASeparateCount(){
        var db=mock(NamedParameterJdbcTemplate.class);var policy=mock(AccessPolicy.class);
        when(policy.campuses("enrollment.manage")).thenReturn(List.of(7L));
        var row=new LinkedHashMap<String,Object>(Map.of("id",1L,"_total",42L));
        when(db.queryForList(anyString(),anyMap())).thenReturn(List.of(row));
        var service=new ph.edu.ici.enrollment.EnrollmentService(db,mock(ph.edu.ici.requirements.RequirementService.class),policy);
        var page=service.applications("",null,null,null,"","","","",0,25);
        assertThat(page.get("total")).isEqualTo(42L);assertThat(row).doesNotContainKey("_total");
        verify(db,times(1)).queryForList(contains("count(*) OVER()"),anyMap());
        verify(db,never()).queryForObject(anyString(),anyMap(),eq(Long.class));
    }
    @Test void emptyDeepQueuePageStillReturnsExactFilteredTotal(){
        var db=mock(NamedParameterJdbcTemplate.class);var policy=mock(AccessPolicy.class);
        when(policy.campuses("enrollment.manage")).thenReturn(List.of(7L));
        when(db.queryForList(anyString(),anyMap())).thenReturn(List.of());
        when(db.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(3L);
        var service=new ph.edu.ici.enrollment.EnrollmentService(db,mock(ph.edu.ici.requirements.RequirementService.class),policy);
        var page=service.applications("",null,null,null,"","","","",4,25);
        assertThat(page.get("total")).isEqualTo(3L);assertThat((List<?>)page.get("items")).isEmpty();
        verify(db,times(1)).queryForObject(anyString(),anyMap(),eq(Long.class));
    }
}
