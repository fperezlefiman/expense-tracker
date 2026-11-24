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
 * Gate of phase 1: the webhook's p95 under ten concurrent deliveries — ten times the expected load
 * (one mailbox), after a warm-up that is not measured. Runs against a real socket and a real Postgres,
 * so the number is comparable between runs (not to Cloud Run, which adds its own hop).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtConfig.class)
class IngestLatencyTest extends cl.fperezlefiman.expense.AbstractPostgresTest {

    private static final int CONCURRENCY = 10;
    private static final int PER_WORKER = 10;
    private static final int WARMUP = 50;

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Test
    void p95UnderTenConcurrentDeliveries() throws Exception {
        jdbc.sql("delete from raw_documents").update();
        jdbc.sql("insert into accounts (id, email) values (gen_random_uuid(), :email) on conflict do nothing")
                .param("email", "me@example.com")
                .update();

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        // Warm-up, not measured: without it p95 is dominated by JIT and connection setup, and varied
        // between 190 and 460 ms across runs of the same binary.
        for (int i = 0; i < WARMUP; i++) {
            send(client, "warmup-" + i);
        }

        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < CONCURRENCY; worker++) {
                int w = worker;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < PER_WORKER; i++) {
                        try {
                            latencies.add(post(client, push("msg-" + w + "-" + i, String.valueOf(w * PER_WORKER + i))));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
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
        // Gate after warm-up: p95 of 44–58 ms locally across three runs, so 150 ms catches a real
        // regression without flaking on a shared CI box.
        assertThat(p95).isLessThan(150_000L);
    }

    private void send(HttpClient client, String key) throws Exception {
        post(client, push(key, key));
    }

    /** Posts one notification and returns its latency in microseconds. */
    private long post(HttpClient client, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/gmail/notifications"))
                .header("Authorization", "Bearer good")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        long start = System.nanoTime();
        int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        long micros = (System.nanoTime() - start) / 1_000;
        assertThat(status).isEqualTo(200);
        return micros;
    }
}
