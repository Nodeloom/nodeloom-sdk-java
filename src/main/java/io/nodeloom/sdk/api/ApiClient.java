package io.nodeloom.sdk.api;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * REST API client for NodeLoom.
 *
 * <p>SDK tokens can authenticate against all NodeLoom API endpoints.
 * This client provides methods for common operations like listing workflows,
 * triggering executions, and querying results.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * NodeLoom client = NodeLoom.builder().apiKey("sdk_...").build();
 * String workflows = client.api().request("GET", "/api/workflows?teamId=...");
 * }</pre>
 */
public class ApiClient {

    private final HttpClient httpClient;
    private final String endpoint;
    private final String apiKey;

    public ApiClient(String apiKey, String endpoint) {
        this.apiKey = apiKey;
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Make an authenticated API request.
     *
     * @param method HTTP method (GET, POST, PUT, DELETE, PATCH)
     * @param path   API path with optional query string (e.g., "/api/workflows?teamId=...")
     * @return Response body as a string (JSON)
     * @throws ApiException if the request fails with a non-2xx status code
     * @throws IOException  if a network error occurs
     */
    public String request(String method, String path) throws ApiException, IOException {
        return request(method, path, null);
    }

    /**
     * Make an authenticated API request with a JSON body.
     *
     * @param method HTTP method (GET, POST, PUT, DELETE, PATCH)
     * @param path   API path with optional query string (e.g., "/api/workflows?teamId=...")
     * @param body   Optional JSON request body (pass null for no body)
     * @return Response body as a string (JSON)
     * @throws ApiException if the request fails with a non-2xx status code
     * @throws IOException  if a network error occurs
     */
    public String request(String method, String path, String body) throws ApiException, IOException {
        String url = endpoint + path;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(30));

        if (body != null && !body.isEmpty()) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            builder.method(method, HttpRequest.BodyPublishers.ofString("{}"));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        try {
            HttpResponse<String> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new ApiException(status, response.body());
            }

            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    // -- Convenience Methods --------------------------------------------------

    /** List all workflows for a team. */
    public String listWorkflows(String teamId) throws ApiException, IOException {
        return request("GET", "/api/workflows?teamId=" + encode(teamId));
    }

    /** Get a workflow by ID. */
    public String getWorkflow(String workflowId) throws ApiException, IOException {
        return request("GET", "/api/workflows/" + encode(workflowId));
    }

    /** Execute a workflow. */
    public String executeWorkflow(String workflowId, String inputJson) throws ApiException, IOException {
        return request("POST", "/api/workflows/" + encode(workflowId) + "/execute", inputJson);
    }

    /** List executions for a team. */
    public String listExecutions(String teamId, int page, int size) throws ApiException, IOException {
        return request("GET", "/api/executions?teamId=" + encode(teamId) + "&page=" + page + "&size=" + size);
    }

    /** Get an execution by ID. */
    public String getExecution(String executionId) throws ApiException, IOException {
        return request("GET", "/api/executions/" + encode(executionId));
    }

    /** List credentials for a team. */
    public String listCredentials(String teamId) throws ApiException, IOException {
        return request("GET", "/api/credentials?teamId=" + encode(teamId));
    }

    /** Run guardrail checks on text content. */
    public String checkGuardrails(String teamId, String requestBodyJson) throws ApiException, IOException {
        return request("POST", "/api/guardrails/check?teamId=" + encode(teamId), requestBodyJson);
    }

    // -- Feedback --

    public String submitFeedback(String bodyJson) throws ApiException, IOException {
        return request("POST", "/api/sdk/v1/feedback", bodyJson);
    }

    public String listFeedback(String queryParams) throws ApiException, IOException {
        String path = "/api/sdk/v1/feedback";
        if (queryParams != null && !queryParams.isEmpty()) path += "?" + queryParams;
        return request("GET", path);
    }

    // -- Sentiment --

    public String analyzeSentiment(String bodyJson) throws ApiException, IOException {
        return request("POST", "/api/sdk/v1/sentiment", bodyJson);
    }

    // -- Costs --

    public String getCosts(String queryParams) throws ApiException, IOException {
        String path = "/api/sdk/v1/costs";
        if (queryParams != null && !queryParams.isEmpty()) path += "?" + queryParams;
        return request("GET", path);
    }

    // -- Webhooks --

    public String registerWebhook(String bodyJson) throws ApiException, IOException {
        return request("POST", "/api/sdk/v1/alerts/webhooks", bodyJson);
    }

    public String listWebhooks() throws ApiException, IOException {
        return request("GET", "/api/sdk/v1/alerts/webhooks");
    }

    public String deleteWebhook(String webhookId) throws ApiException, IOException {
        return request("DELETE", "/api/sdk/v1/alerts/webhooks/" + encode(webhookId));
    }

    // -- Prompts --

    public String createPrompt(String bodyJson) throws ApiException, IOException {
        return request("POST", "/api/sdk/v1/prompts", bodyJson);
    }

    public String getPrompt(String name, String queryParams) throws ApiException, IOException {
        String path = "/api/sdk/v1/prompts/" + encode(name);
        if (queryParams != null && !queryParams.isEmpty()) path += "?" + queryParams;
        return request("GET", path);
    }

    public String listPrompts() throws ApiException, IOException {
        return request("GET", "/api/sdk/v1/prompts");
    }

    // -- Red Team --

    public String startRedTeamScan(String bodyJson) throws ApiException, IOException {
        return request("POST", "/api/sdk/v1/redteam/scan", bodyJson);
    }

    public String getRedTeamScan(String scanId) throws ApiException, IOException {
        return request("GET", "/api/sdk/v1/redteam/scan/" + encode(scanId));
    }

    // -- Evaluation --

    public String triggerEvaluation(String bodyJson) throws ApiException, IOException {
        return request("POST", "/api/sdk/v1/evaluate", bodyJson);
    }

    // -- Metrics --

    public String getMetrics(String queryParams) throws ApiException, IOException {
        String path = "/api/sdk/v1/metrics";
        if (queryParams != null && !queryParams.isEmpty()) path += "?" + queryParams;
        return request("GET", path);
    }

    // -- Agent Callback --

    public String setCallbackUrl(String agentName, String bodyJson) throws ApiException, IOException {
        return request("POST", "/api/sdk/v1/agents/" + encode(agentName) + "/callback", bodyJson);
    }

    public String removeCallbackUrl(String agentName) throws ApiException, IOException {
        return request("DELETE", "/api/sdk/v1/agents/" + encode(agentName) + "/callback");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
