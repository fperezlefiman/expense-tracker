package cl.fperezlefiman.expense.ingest;

import java.util.Base64;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The webhook does not think (ADR 0003): it stores the notification and answers. The worker expands it
 * into messages in the next phase. Authentication is enforced by the security filter chain.
 */
@RestController
public class GmailNotificationController {

    private static final Logger log = LoggerFactory.getLogger(GmailNotificationController.class);

    private final RawDocumentRepository repository;
    private final ObjectMapper mapper;

    public GmailNotificationController(RawDocumentRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @PostMapping("/gmail/notifications")
    public ResponseEntity<Void> receive(@RequestBody JsonNode envelope) {
        JsonNode message = envelope.path("message");
        String messageId = message.path("messageId").asText("");
        String data = message.path("data").asText("");

        if (messageId.isBlank() || data.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        JsonNode payload;
        try {
            payload = mapper.readTree(Base64.getDecoder().decode(data));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }

        String email = payload.path("emailAddress").asText("");
        String historyId = payload.path("historyId").asText("");
        if (email.isBlank() || historyId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // An unknown mailbox is not retryable: answering 200 avoids an endless redelivery loop.
        var accountId = repository.findAccountIdByEmail(email);
        if (accountId.isEmpty()) {
            log.warn("notification for unknown mailbox, ignored");
            return ResponseEntity.ok().build();
        }

        repository.insertNotification(accountId.get(), messageId, payload.toString());
        return ResponseEntity.ok().build();
    }

    /** A storage failure is retryable, so it must answer 5xx for Pub/Sub to redeliver it. */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Void> onStorageFailure(DataAccessException e) {
        log.error("could not store notification", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
