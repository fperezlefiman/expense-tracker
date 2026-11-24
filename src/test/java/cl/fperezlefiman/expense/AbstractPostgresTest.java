package cl.fperezlefiman.expense;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** One Postgres per test class, started by JUnit and shared through the service connection. */
@Testcontainers
public abstract class AbstractPostgresTest {

    protected static final String EMAIL = "me@example.com";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    /** Builds one Pub/Sub push envelope carrying a Gmail notification payload. */
    protected static String push(String messageId, String historyId) {
        String json = "{\"emailAddress\":\"" + EMAIL + "\",\"historyId\":\"" + historyId + "\"}";
        String data = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return "{\"message\":{\"data\":\"" + data + "\",\"messageId\":\"" + messageId + "\"},"
                + "\"subscription\":\"projects/p/subscriptions/s\"}";
    }
}
