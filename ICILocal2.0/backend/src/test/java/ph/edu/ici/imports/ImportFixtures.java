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
    static String letters(String n){StringBuilder s=new StringBuilder();for(char c:n.toCharArray())s.append((char)('a'+(c-'0')));return s.toString();}
    static byte[] fixture(String name,String n){return fixture(name,n,StandardCharsets.UTF_8);}
    static byte[] fixture(String name,String n,Charset cs){
        try{
            String text=new String(new ClassPathResource("import/"+name).getContentAsByteArray(),cs);
            return text.replace("%N%",n).replace("%L%",letters(n)).replace("%U%",letters(n).toUpperCase()).getBytes(cs);
        }catch(IOException e){throw new IllegalStateException(e);}
    }
}
