package cl.fperezlefiman.expense.ingest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestJwtConfig.class)
class GmailNotificationTest extends cl.fperezlefiman.expense.AbstractPostgresTest {

    private static final String EMAIL = "me@example.com";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void seed() {
        jdbc.sql("delete from raw_documents").update();
        jdbc.sql("delete from accounts").update();
        jdbc.sql("insert into accounts (id, email) values (gen_random_uuid(), :email)")
                .param("email", EMAIL)
                .update();
    }

    @Test
    void storesTheNotificationOnce() throws Exception {
        mvc.perform(post("/gmail/notifications")
                        .header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(push("msg-1", "1000")))
                .andExpect(status().isOk());

        assertThat(count()).isEqualTo(1);
    }

    @Test
    void sameDeliveryTwiceStillStoresOneRow() throws Exception {
        String body = push("msg-1", "1000");
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/gmail/notifications")
                            .header("Authorization", "Bearer good")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        assertThat(count()).isEqualTo(1);
    }

    @Test
    void rejectsRequestWithoutToken() throws Exception {
        mvc.perform(post("/gmail/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(push("msg-2", "1001")))
                .andExpect(status().isUnauthorized());

        assertThat(count()).isZero();
    }

    @Test
    void rejectsTokenFromAnotherAudience() throws Exception {
        mvc.perform(post("/gmail/notifications")
                        .header("Authorization", "Bearer foreign-audience")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(push("msg-3", "1002")))
                .andExpect(status().isUnauthorized());

        assertThat(count()).isZero();
    }

    @Test
    void rejectsMalformedPayloadWithoutStoring() throws Exception {
        mvc.perform(post("/gmail/notifications")
                        .header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":{}}"))
                .andExpect(status().isBadRequest());

        assertThat(count()).isZero();
    }

    private long count() {
        return jdbc.sql("select count(*) from raw_documents").query(Long.class).single();
    }

    static String push(String messageId, String historyId) {
        String json = "{\"emailAddress\":\"" + EMAIL + "\",\"historyId\":\"" + historyId + "\"}";
        String data = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return "{\"message\":{\"data\":\"" + data + "\",\"messageId\":\"" + messageId + "\"},"
                + "\"subscription\":\"projects/p/subscriptions/s\"}";
    }
}
