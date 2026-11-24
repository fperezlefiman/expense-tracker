package cl.fperezlefiman.expense.ingest;

import java.util.List;

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

import cl.fperezlefiman.expense.AbstractPostgresTest;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestJwtConfig.class)
class GmailNotificationTest extends AbstractPostgresTest {

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
    void storesTwoDeliveriesOfTheSameHistorySeparately() throws Exception {
        // Deliberate: the row is a delivery, not a mailbox state. The same email can appear under several
        // historyIds, so the duplicate is removed in phase 2, keyed by the Gmail message id — not here.
        for (String messageId : List.of("msg-1", "msg-2")) {
            mvc.perform(post("/gmail/notifications")
                            .header("Authorization", "Bearer good")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(push(messageId, "1000")))
                    .andExpect(status().isOk());
        }

        assertThat(count()).isEqualTo(2);
    }

    @Test
    void rejectsExpiredTokenWithoutStoring() throws Exception {
        mvc.perform(post("/gmail/notifications")
                        .header("Authorization", "Bearer expired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(push("msg-4", "1003")))
                .andExpect(status().isUnauthorized());

        assertThat(count()).isZero();
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
}
