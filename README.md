# Higgsfield Java SDK

An unofficial Higgsfield Java SDK. Requires Java 25. This project is not affiliated with Higgsfield.

The latest release coordinates are `io.github.mityushin:higgsfield-java-sdk:0.1.0`.

General-purpose Higgsfield generation client, independent of Spring. The asynchronous methods start immediately and return `CompletableFuture`. Close the client when finished. The credentials are `key-id:key-secret`; `fromEnvironment()` reads `HF_CREDENTIALS`, `HF_KEY`, or `HF_API_KEY` and `HF_API_SECRET`.

```java
try (var client = HiggsfieldClient.fromEnvironment()) {
    var request = client.submit("bytedance/seedance-2.0/text-to-video", Map.of(
        "prompt", "A cinematic scene at sunset", "duration", 5,
        "resolution", "720p", "aspect_ratio", "16:9", "generate_audio", true));
    // Persist request.requestId() before waiting; it can be restored with client.request(id).
    var result = request.waitForResult(PollingOptions.defaults());
    if (result.isCompleted()) System.out.println(result.body().path("video").path("url"));
}
```

Supply `SubmitOptions` for a webhook URL or an idempotency key. Mutating calls are not automatically retried. Status GET requests retry transient transport errors, HTTP 429, 500, 502, 503 and 504 up to twice by default; configure `statusMaxRetries` on the builder. `Retry-After` is respected. A timeout or cancellation of local polling does not cancel the remote generation; use `request.cancel()` explicitly. Terminal failure states are returned as `RequestStatus`, and the caller must inspect them. Status and output fields remain accessible as raw JSON. The example requires account access and consumes credits.

## Agents

`client.agents()` provides synchronous and `CompletableFuture` methods for creating sessions, sending messages, fetching message slices, interrupting turns, and uploading media. `run` / `runAsync` send a turn and poll until completion, failure, or a question; an optional handler can answer questions. A local timeout or future cancellation does not interrupt the server-side turn. Agent availability and credit requirements depend on account access.

## Webhooks and retention

`WebhookVerifier` verifies Standard Webhooks HMAC-SHA256 signatures over the original raw HTTP body, validates timestamps, and accepts multiple secrets during rotation. The caller is responsible for checking the event envelope, durably recording events, and deduplicating deliveries. `SubmitOptions` optionally configures the webhook URL, idempotency key, and `Hf-Expires-After` retention. `client.delete(requestId)` deletes a finished request's server-side data; use only when deletion is intended.

## Bundled model catalog

Each SDK release contains an offline snapshot in `higgsfield/models/`. It is not a live catalog and does not verify that your account has access. Currently it covers eight Seedance 2.0/2.5 video endpoints, two Kling video endpoints and four image endpoints. Models absent from the catalog can still be called using their endpoint string and request JSON.

```java
import io.github.mityushin.higgsfield.catalog.HiggsfieldModels;
import io.github.mityushin.higgsfield.catalog.ModelType;

var videoModels = client.models().list(ModelType.VIDEO);
var model = client.models().get(HiggsfieldModels.SEEDANCE_2_0_TEXT_TO_VIDEO);
System.out.println(model.schema());
var request = client.submit(model, Map.of("prompt", "A cinematic sunset"));
```

Catalog schemas currently contain selected verified fields only (`schemaComplete() == false`). They are informational and are **not** automatically enforced during submission: optional fields and endpoint capabilities can change independently of an SDK release. Use `documentation()` for the model-specific reference and `find(id)` to check for absent entries. The returned schema is a defensive copy.

Licensed under the MIT License; see [LICENSE](LICENSE).

## Formatting

Java source and tests follow Spring Java Format. `mvn verify` checks formatting as part of the build. To apply the Java formatter and check the POM, run:

```bash
mvn io.spring.javaformat:spring-javaformat-maven-plugin:0.0.46:apply
python3 tools/format_pom.py --write
python3 tools/format_pom.py --check
```

CI checks the POM format separately; the POM formatter requires Python 3.
