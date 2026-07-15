# valem-client

Thin, standalone **Java** client for the Valem REST + WebSocket API. Depends only on the JDK
(`java.net.http`) and `jackson-databind` — it does **not** pull in the runtime engine, so a consumer
app stays lightweight.

## Add the dependency

```xml
<dependency>
    <groupId>io.github.vlad-public-code</groupId>
    <artifactId>valem-client</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Usage

```java
import org.json_kula.valem.client.*;
import org.json_kula.valem.client.ValemTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;

var mapper = new ObjectMapper();
try (var client = new ValemClient("http://localhost:8080", System.getenv("VALEM_KEY"))) {

    // Create a model from a spec.
    client.createModel(mapper.readTree(specJson));

    // Mutate by canonical address map (primary path).
    MutationResponse res = client.mutate("insurance-quote", Map.of(
            "$.quote.applicant.age", 35,
            "$.quote.coverage", 100000));
    System.out.println(res.derivedUpdated());   // [$.quote.annualPremium, ...]

    // Or set a single field via JSON Patch (pointer conversion handled).
    client.setField("insurance-quote", "$.quote.applicant.smoker", mapper.getNodeFactory().booleanNode(true));

    // Read state, explain a value, query the durable audit trail.
    var state = client.getState("insurance-quote");
    var why   = client.explain("insurance-quote", "$.quote.annualPremium");
    var trail  = client.audit("insurance-quote", new AuditQuery("$.quote", null, null, 50));
    var intact = client.verifyAudit("insurance-quote").valid();  // tamper-evident hash chain

    // Subscribe to live changes — reconnects automatically until closed.
    Subscription sub = client.subscribe("insurance-quote", new ChangeListener() {
        @Override public void onEvent(ChangeEvent e) {
            System.out.println("changed: " + e.mutatedPaths() + " -> " + e.derivedUpdated());
        }
    }, List.of("$.quote"));
    // ...later
    sub.close();
}
```

## Notes

- Every REST method returns a parsed DTO (see `ValemTypes`) and throws `ValemException`
  (with `.status()` and `.body()`) on any non-2xx response. Freeform JSON (specs, state, payloads) is
  returned as Jackson `JsonNode`.
- `subscribe(...)` uses the JDK `java.net.http.WebSocket`. It reconnects with exponential backoff
  (0.5s → 8s) after an unexpected close/error and stops permanently once you call `Subscription.close()`
  (or `ValemClient.close()`). The API key is sent as `?token=`.
- DTO records are `@JsonIgnoreProperties(ignoreUnknown = true)`, so a newer server never breaks an
  older client.
