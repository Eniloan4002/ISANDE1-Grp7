package ph.edu.ici.imports;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.io.ClassPathResource;

/** Loads the synthetic CSV fixtures, replacing placeholders so repeated runs never collide in the shared test database. */
final class ImportFixtures {
    private ImportFixtures(){}
    static String n(){return String.format("%08d",ThreadLocalRandom.current().nextInt(100_000_000));}
    /**
     * 12 lower-case letters derived from the numeric token. Names that differ only by this suffix must never look alike to the
     * ICI2-13 matcher (similarity >= 0.8) across the many fixtures sharing one database, so it uses the whole alphabet and enough length.
     */
    static String letters(String n){
        var random=new java.util.Random(Long.parseLong(n)*7919L+13);StringBuilder s=new StringBuilder();
        for(int i=0;i<12;i++)s.append((char)('a'+random.nextInt(26)));return s.toString();
    }
    static byte[] fixture(String name,String n){return fixture(name,n,StandardCharsets.UTF_8);}
    static byte[] fixture(String name,String n,Charset cs){
        try{
            String text=new String(new ClassPathResource("import/"+name).getContentAsByteArray(),cs);
            return text.replace("%N%",n).replace("%L%",letters(n)).replace("%U%",letters(n).toUpperCase()).getBytes(cs);
        }catch(IOException e){throw new IllegalStateException(e);}
    }
}
