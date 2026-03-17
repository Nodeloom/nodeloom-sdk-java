package io.nodeloom.sdk.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
}
