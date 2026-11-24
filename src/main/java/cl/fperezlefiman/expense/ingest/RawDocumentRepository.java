package cl.fperezlefiman.expense.ingest;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RawDocumentRepository {

    private final JdbcClient jdbc;

    public RawDocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> findAccountIdByEmail(String email) {
        return jdbc.sql("select id from accounts where email = :email")
                .param("email", email)
                .query(UUID.class)
                .optional();
    }

    /**
     * Idempotent by design: Pub/Sub delivers at least once, so a redelivered message must not create a
     * second row. The unique key is (account_id, kind, source_ref), and for a notification source_ref is
     * the Pub/Sub message id: the row records a delivery, not a mailbox state.
     */
    public void insertNotification(UUID accountId, String sourceRef, String payloadJson) {
        jdbc.sql("""
                        insert into raw_documents (id, account_id, kind, source_ref, payload)
                        values (gen_random_uuid(), :accountId, 'notification', :sourceRef, cast(:payload as jsonb))
                        on conflict (account_id, kind, source_ref) do nothing
                        """)
                .param("accountId", accountId)
                .param("sourceRef", sourceRef)
                .param("payload", payloadJson)
                .update();
    }
}
