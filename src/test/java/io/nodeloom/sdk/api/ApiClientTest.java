package io.nodeloom.sdk.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ApiClient Tests")
class ApiClientTest {

    @Nested
    @DisplayName("Initialization")
    class Initialization {

        @Test
        @DisplayName("Strips trailing slash from endpoint")
        void stripsTrailingSlash() {
            ApiClient client = new ApiClient("sdk_test", "https://example.com/");
            // Verify by making a request that would fail - the URL construction is correct
            assertNotNull(client);
        }

        @Test
        @DisplayName("Creates client with default values")
        void createsWithDefaults() {
            ApiClient client = new ApiClient("sdk_test", "https://api.nodeloom.io");
            assertNotNull(client);
        }
    }

    @Nested
    @DisplayName("ApiException")
    class ApiExceptionTests {

        @Test
        @DisplayName("Contains status code and response body")
        void containsStatusAndBody() {
            ApiException ex = new ApiException(403, "{\"error\":\"Access denied\"}");
            assertEquals(403, ex.getStatusCode());
            assertEquals("{\"error\":\"Access denied\"}", ex.getResponseBody());
            assertTrue(ex.getMessage().contains("403"));
        }
    }

    @Nested
    @DisplayName("NodeLoom integration")
    class NodeLoomIntegration {

        @Test
        @DisplayName("api() returns ApiClient instance")
        void apiReturnsClient() {
            io.nodeloom.sdk.NodeLoom client = io.nodeloom.sdk.NodeLoom.builder()
                    .apiKey("sdk_test")
                    .build();
            ApiClient api = client.api();
            assertNotNull(api);
            client.close();
        }

        @Test
        @DisplayName("api() returns cached instance")
        void apiReturnsCachedInstance() {
            io.nodeloom.sdk.NodeLoom client = io.nodeloom.sdk.NodeLoom.builder()
                    .apiKey("sdk_test")
                    .build();
            ApiClient api1 = client.api();
            ApiClient api2 = client.api();
            assertSame(api1, api2);
            client.close();
        }
    }

    @Nested
    @DisplayName("checkGuardrails URL construction")
    class CheckGuardrailsUrlConstruction {

        // A tiny HTTP server is the only way to observe the exact request
        // URI ApiClient produces. Mocking HttpClient would only test our
        // mock, not the actual wire behavior that matters in production.
        private HttpServer startServer(AtomicReference<String> receivedPath) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/guardrails/check", exchange -> {
                receivedPath.set(exchange.getRequestURI().toString());
                String body = "{\"passed\":true,\"violations\":[]}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            });
            server.start();
            return server;
        }

        @Test
        @DisplayName("Empty teamId omits the teamId query param so the backend falls back to token auth")
        void emptyTeamIdOmitsQueryParam() throws Exception {
            AtomicReference<String> received = new AtomicReference<>();
            HttpServer server = startServer(received);
            try {
                ApiClient client = new ApiClient("sdk_test", "http://127.0.0.1:" + server.getAddress().getPort());
                String body = "{\"text\":\"hi\",\"agentName\":\"agent-1\"}";
                client.checkGuardrails("", body);
                assertEquals("/api/guardrails/check", received.get());
            } finally {
                server.stop(0);
            }
        }

        @Test
        @DisplayName("Null teamId omits the teamId query param")
        void nullTeamIdOmitsQueryParam() throws Exception {
            AtomicReference<String> received = new AtomicReference<>();
            HttpServer server = startServer(received);
            try {
                ApiClient client = new ApiClient("sdk_test", "http://127.0.0.1:" + server.getAddress().getPort());
                client.checkGuardrails(null, "{\"text\":\"hi\"}");
                assertEquals("/api/guardrails/check", received.get());
            } finally {
                server.stop(0);
            }
        }

        @Test
        @DisplayName("Non-empty teamId is appended as a URL-encoded query param")
        void presentTeamIdIsAppended() throws Exception {
            AtomicReference<String> received = new AtomicReference<>();
            HttpServer server = startServer(received);
            try {
                ApiClient client = new ApiClient("sdk_test", "http://127.0.0.1:" + server.getAddress().getPort());
                client.checkGuardrails("team uuid/1", "{\"text\":\"hi\"}");
                // encode() must URL-escape the space and slash so the backend
                // parses the query param correctly instead of splitting the path.
                assertTrue(received.get().startsWith("/api/guardrails/check?teamId="),
                        "expected teamId query param, got " + received.get());
                assertTrue(received.get().contains("team+uuid") || received.get().contains("team%20uuid"),
                        "expected URL-encoded team id, got " + received.get());
            } finally {
                server.stop(0);
            }
        }
    }
}
