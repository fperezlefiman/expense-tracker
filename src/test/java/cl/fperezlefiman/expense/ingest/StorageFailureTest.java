package cl.fperezlefiman.expense.ingest;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A storage failure must answer 5xx, or Pub/Sub stops retrying and the notification is lost.
 * The repository is mocked because a real outage cannot be produced with Testcontainers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestJwtConfig.class)
class StorageFailureTest extends cl.fperezlefiman.expense.AbstractPostgresTest {

    @MockitoBean
    RawDocumentRepository repository;

    @Autowired
    MockMvc mvc;

    @Test
    void answers503WhenTheDatabaseIsDown() throws Exception {
        given(repository.findAccountIdByEmail(anyString())).willReturn(Optional.of(UUID.randomUUID()));
        willThrow(new DataAccessResourceFailureException("database down"))
                .given(repository).insertNotification(any(), anyString(), anyString());

        mvc.perform(post("/gmail/notifications")
                        .header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GmailNotificationTest.push("msg-9", "9999")))
                .andExpect(status().isServiceUnavailable());
    }
}
