package ph.edu.ici.imports;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Text normalization shared by header resolution, value maps and field cleanup. */
final class ImportText {
    private ImportText(){}
    private static final Pattern NON_ALNUM=Pattern.compile("[^\\p{L}\\p{N}]+"),SPACES=Pattern.compile("\\s+"),CONTROL=Pattern.compile("[\\p{Cc}&&[^\\t\\n\\r]]");
    /** Header/alias key: NFKC, lower-case, trim, each run of non-alphanumerics becomes one underscore, edge underscores dropped. */
    static String header(String value){
        String s=Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
        return NON_ALNUM.matcher(s).replaceAll("_").replaceAll("^_+|_+$","");
    }
    /** Value-map key: NFKC, lower-case, trimmed, whitespace collapsed. */
    static String value(String value){return SPACES.matcher(Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim()).replaceAll(" ");}
    /** Stored text: NFC, trimmed, whitespace runs collapsed to one space; null when blank. */
    static String clean(String value){
        if(value==null)return null;String s=SPACES.matcher(Normalizer.normalize(value,Normalizer.Form.NFC).trim()).replaceAll(" ");return s.isEmpty()?null:s;
    }
    static boolean hasControl(String value){return CONTROL.matcher(value).find();}
    /** Short, control-free excerpt of a non-personal code-like value for messages. */
    static String excerpt(String value){String s=CONTROL.matcher(value==null?"":value).replaceAll("");return s.length()>40?s.substring(0,40)+"…":s;}
}
