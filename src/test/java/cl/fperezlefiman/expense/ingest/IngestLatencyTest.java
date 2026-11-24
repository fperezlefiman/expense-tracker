package cl.fperezlefiman.expense.ingest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate of phase 1: the webhook's p95 under 50 concurrent deliveries. Runs against a real socket and a
 * real Postgres, so the number is comparable between runs (not to Cloud Run, which adds its own hop).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtConfig.class)
class IngestLatencyTest extends cl.fperezlefiman.expense.AbstractPostgresTest {

    private static final int CONCURRENCY = 50;
    private static final int PER_WORKER = 10;

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Test
    void p95UnderFiftyConcurrentDeliveries() throws Exception {
        jdbc.sql("delete from raw_documents").update();
        jdbc.sql("insert into accounts (id, email) values (gen_random_uuid(), :email) on conflict do nothing")
                .param("email", "me@example.com")
                .update();

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < CONCURRENCY; worker++) {
                int w = worker;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < PER_WORKER; i++) {
                        String body = GmailNotificationTest.push("msg-" + w + "-" + i, String.valueOf(i));
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + port + "/gmail/notifications"))
                                .header("Authorization", "Bearer good")
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build();
                        long start = System.nanoTime();
                        int status;
                        try {
                            status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                        } catch (java.io.IOException | InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                        latencies.add((System.nanoTime() - start) / 1_000);
                        assertThat(status).isEqualTo(200);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }

        List<Long> sorted = latencies.stream().sorted().toList();
        long p95 = sorted.get((int) Math.floor(sorted.size() * 0.95) - 1);
        long p50 = sorted.get(sorted.size() / 2);
        System.out.printf("%nINGEST p50=%d us  p95=%d us  n=%d%n", p50, p95, sorted.size());

        assertThat(sorted.size()).isEqualTo(CONCURRENCY * PER_WORKER);
        // Loose on purpose: this catches a regression, not a slow CI machine.
        assertThat(p95).isLessThan(1_000_000L);
    }
}
