package ph.edu.ici;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude=UserDetailsServiceAutoConfiguration.class)
public class IciLocalApplication {
    public static void main(String[] args) {
        SpringApplication.run(IciLocalApplication.class, args);
    }
}
