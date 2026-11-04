package ph.edu.ici;

import java.util.Map;
import org.springframework.boot.SpringApplication;

/** Optional development server with a disposable PostgreSQL database. */
public final class LocalPostgresApplication {
    public static void main(String[] args) {
        var application=new SpringApplication(IciLocalApplication.class);
        Map.of(
            "spring.datasource.url",PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"),
            "spring.datasource.username","postgres","spring.datasource.password","",
            "ici.auth.secret",PostgresSupport.SECRET).forEach(System::setProperty);
        application.run(args);
    }
}
