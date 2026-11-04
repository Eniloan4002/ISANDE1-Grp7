package ph.edu.ici;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;

public final class PostgresSupport {
    public static final EmbeddedPostgres DATABASE=start();
    public static final String SECRET=java.util.Base64.getEncoder().encodeToString("test-only-key-ici-32-bytes-long-123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static EmbeddedPostgres start(){try{return EmbeddedPostgres.builder().setPort(0).start();}catch(IOException exception){throw new ExceptionInInitializerError(exception);}}
    private PostgresSupport(){}
}
