package ph.edu.ici.enrollment;

import java.sql.Date;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Pins the ICI2-13 matching rules that DuplicateService and the legacy import both use. */
class IdentityMatcherTest {
    Map<String,Object> person(String lrn,String first,String last,String birth){Map<String,Object> m=new HashMap<>();m.put("lrn",lrn);m.put("first_name",first);m.put("last_name",last);m.put("birth_date",Date.valueOf(birth));return m;}

    @Test void exactLrnMatchesEvenWhenNamesAndBirthDatesDiffer(){
        var a=person("123456789012","Ana","Santos","2009-01-01");var b=person("123456789012","Zed","Other","2005-05-05");
        assertThat(IdentityMatcher.matches(a,b)).isTrue();assertThat(IdentityMatcher.rule(a,b)).isEqualTo("LRN_EXACT");
    }
    @Test void missingLrnNeverMatchesOnLrn(){assertThat(IdentityMatcher.sameLrn(person(null,"A","B","2009-01-01"),person(null,"C","D","2009-01-01"))).isFalse();}
    @Test void nameAndBirthDateMatchIgnoresCaseAccentsAndMiddleNames(){
        var a=person(null,"José Maria","DELA CRUZ","2009-01-01");var b=person(null,"jose","dela cruz","2009-01-01");
        assertThat(IdentityMatcher.rule(a,b)).isEqualTo("NAME_DOB");assertThat(IdentityMatcher.nameScore(a,b)).isEqualTo(1.0);
    }
    @Test void similarNamesAtThresholdMatchButOnlyOnTheSameBirthDate(){
        var a=person(null,"Maria","Delacruz","2009-02-02");
        assertThat(IdentityMatcher.matches(a,person(null,"Maria","Delacruy","2009-02-02"))).isTrue();      // 1 edit in 8 characters = 0.875
        assertThat(IdentityMatcher.matches(a,person(null,"Maria","Delacruy","2009-02-03"))).isFalse();
        assertThat(IdentityMatcher.matches(a,person(null,"Maria","Delacxxx","2009-02-02"))).isFalse();     // 3 edits in 8 characters = 0.625
    }
    @Test void emptyOrNonLetterNamesNeverMatch(){assertThat(IdentityMatcher.similarity("123","123")).isZero();assertThat(IdentityMatcher.matches(person(null,"","","2009-01-01"),person(null,"","","2009-01-01"))).isFalse();}
}
