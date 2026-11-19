package ph.edu.ici.enrollment;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * ICI2-13 identity matching rules, shared by duplicate adjudication and legacy imports.
 * Operates on rows keyed like the students/applications tables: lrn, birth_date, first_name, last_name.
 * Rules: exact non-null LRN, or same birth date with last-name and first-given-name similarity of at least 0.8.
 */
public final class IdentityMatcher {
    public static final double NAME_THRESHOLD=0.8;
    public static final String LRN_EXACT="LRN_EXACT",NAME_DOB="NAME_DOB";
    private IdentityMatcher(){}

    public static String normalize(Object value){return Normalizer.normalize(Objects.toString(value,""),Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^a-z\\s]","").trim().replaceAll("\\s+"," ");}
    public static double similarity(Object left,Object right){String a=normalize(left),b=normalize(right);if(a.isEmpty()||b.isEmpty())return 0;int[] prev=new int[b.length()+1];for(int j=0;j<prev.length;j++)prev[j]=j;for(int i=1;i<=a.length();i++){int[] next=new int[b.length()+1];next[0]=i;for(int j=1;j<=b.length();j++)next[j]=Math.min(Math.min(next[j-1]+1,prev[j]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));prev=next;}return 1.0-(double)prev[b.length()]/Math.max(a.length(),b.length());}
    /** First given-name token after normalization (middle names and second given names are ignored). */
    public static String firstToken(Object firstName){return normalize(firstName).split(" ")[0];}
    public static boolean sameLrn(Map<String,Object> a,Map<String,Object> b){return a.get("lrn")!=null&&a.get("lrn").equals(b.get("lrn"));}
    public static boolean sameNameDob(Map<String,Object> a,Map<String,Object> b){return Objects.equals(a.get("birth_date"),b.get("birth_date"))&&similarity(a.get("last_name"),b.get("last_name"))>=NAME_THRESHOLD&&similarity(firstToken(a.get("first_name")),firstToken(b.get("first_name")))>=NAME_THRESHOLD;}
    /** The lower of the last-name and first-given-name similarities, recorded as case evidence. */
    public static double nameScore(Map<String,Object> a,Map<String,Object> b){return Math.min(similarity(a.get("last_name"),b.get("last_name")),similarity(firstToken(a.get("first_name")),firstToken(b.get("first_name"))));}
    public static boolean matches(Map<String,Object> a,Map<String,Object> b){return sameLrn(a,b)||sameNameDob(a,b);}
    /** Evidence type used by duplicate cases: LRN wins when both rules match. Null when the records do not match. */
    public static String rule(Map<String,Object> a,Map<String,Object> b){return sameLrn(a,b)?LRN_EXACT:sameNameDob(a,b)?NAME_DOB:null;}
}
