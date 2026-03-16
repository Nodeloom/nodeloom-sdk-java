# NodeLoom Java SDK

Java SDK for instrumenting AI agents and sending telemetry to [NodeLoom](https://nodeloom.io).

## Features

- Fire-and-forget telemetry that never blocks or crashes your application
- Zero external runtime dependencies (only Java standard library)
- Automatic batching and retry with exponential backoff
- `AutoCloseable` support for try-with-resources
- Thread-safe client with builder pattern configuration
- Bounded in-memory queue prevents unbounded memory growth
- Built-in JSON serialization

## Requirements

- Java 11+
- Maven 3.x

## Installation

### Maven

```xml
<dependency>
    <groupId>io.nodeloom</groupId>
    <artifactId>nodeloom-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.nodeloom:nodeloom-sdk:0.1.0'
```

## Quick Start

```java
import io.nodeloom.sdk.*;
import java.util.Map;

try (NodeLoom client = NodeLoom.builder()
        .apiKey("sdk_your_api_key")
        .build()) {

    try (Trace trace = client.trace("my-agent")
            .input(Map.of("query", "What is NodeLoom?"))
            .start()) {

        try (Span span = trace.span("llm-call", SpanType.LLM)) {
            span.setInput(Map.of("prompt", "What is NodeLoom?"));
            span.setOutput(Map.of("response", "NodeLoom is an AI agent operations platform."));
            span.setTokenUsage(15, 20, "gpt-4o");
        }
    }
}
```

## Traces and Spans

A **trace** represents a single end-to-end agent execution. A **span** represents a unit of work within a trace.

### Trace Lifecycle

```java
// Create a trace (not yet started)
Trace trace = client.trace("my-agent");

// Set input before starting
trace.input(Map.of("query", "hello"));

// Start the trace (emits trace_start event)
trace.start();

// Create spans...
Span span = trace.span("operation", SpanType.LLM);
span.setOutput(Map.of("result", "..."));
span.end();

// End the trace (emits trace_end event)
trace.end(TraceStatus.SUCCESS, Map.of("result", "done"), null);
```

### Span Types

| Type | Description |
|------|-------------|
| `SpanType.LLM` | Language model call |
| `SpanType.TOOL` | Tool or function invocation |
| `SpanType.RETRIEVAL` | Vector search or data retrieval |
| `SpanType.CHAIN` | Pipeline or chain of steps |
| `SpanType.AGENT` | Sub-agent invocation |
| `SpanType.CUSTOM` | User-defined operation |

### Nested Spans

```java
try (Span parent = trace.span("agent-step", SpanType.AGENT)) {
    try (Span child = parent.span("llm-call", SpanType.LLM)) {
        child.setInput(Map.of("prompt", "Hello"));
        child.setOutput(Map.of("response", "Hi there!"));
        child.setTokenUsage(10, 20, "gpt-4o");
    }
}
```

Child spans are automatically ended when their parent span closes.

### Error Handling

```java
try (Span span = trace.span("risky-call", SpanType.TOOL)) {
    span.setError("Connection timeout");
    // span status is automatically set to "error"
}

trace.end(TraceStatus.ERROR, null, "Agent execution failed");
```

Using `setError()` on a trace makes the auto-close mark it as `ERROR`:

```java
try (Trace trace = client.trace("my-agent").start()) {
    try {
        // ... agent logic ...
    } catch (Exception e) {
        trace.setError(e.getMessage());
        throw e;
    }
}
// trace automatically ends with ERROR status
```

## Configuration

```java
NodeLoom client = NodeLoom.builder()
    .apiKey("sdk_your_api_key")        // required
    .endpoint("https://api.nodeloom.io") // default
    .environment("production")          // default
    .agentVersion("1.0.0")             // optional
    .maxQueueSize(10_000)              // default
    .maxBatchSize(100)                 // default
    .flushIntervalMs(5_000)            // default
    .maxRetries(3)                     // default
    .baseRetryDelayMs(1_000)           // default
    .httpTimeoutMs(10_000)             // default
    .build();
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `apiKey` | *required* | SDK API key (starts with `sdk_`) |
| `endpoint` | `https://api.nodeloom.io` | NodeLoom API base URL |
| `environment` | `production` | Deployment environment label |
| `agentVersion` | `null` | Version string for the agent |
| `maxQueueSize` | `10,000` | Max queued events before dropping |
| `maxBatchSize` | `100` | Max events per batch |
| `flushIntervalMs` | `5,000` | Milliseconds between automatic flushes |
| `maxRetries` | `3` | Retry attempts for failed requests |
| `baseRetryDelayMs` | `1,000` | Base delay for exponential backoff |
| `httpTimeoutMs` | `10,000` | HTTP request timeout |

## Building

```bash
mvn clean install
```

## Running Tests

```bash
mvn test
```

## License

MIT
