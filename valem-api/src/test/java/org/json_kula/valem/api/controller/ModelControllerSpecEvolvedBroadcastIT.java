package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A WS subscriber receives a discriminated {@code "spec-evolved"} event after
 * {@code POST /models/{id}/spec/evolve}, and mutation events still carry their own
 * {@code "mutation"} discriminator (back-compat with pre-existing subscribers).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModelControllerSpecEvolvedBroadcastIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();

    private String httpUrl(String path) {
        return "http://localhost:" + port + path;
    }

    private String wsUrl(String path) {
        return "ws://localhost:" + port + path;
    }

    private BlockingQueue<String> subscribe(String modelId) throws Exception {
        BlockingQueue<String> frames = new ArrayBlockingQueue<>(10);
        StandardWebSocketClient client = new StandardWebSocketClient();
        client.doHandshake(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                frames.offer(message.getPayload());
            }
        }, wsUrl("/models/" + modelId + "/subscribe")).get(5, TimeUnit.SECONDS);
        return frames;
    }

    private ResponseEntity<Map> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(httpUrl(path), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    void evolve_broadcastsSpecEvolvedEvent() throws Exception {
        String id = "spec-evolved-broadcast-1";
        ResponseEntity<Map> create = post("/models",
                "{\"id\":\"" + id + "\",\"version\":\"1.0.0\",\"schema\":{}}");
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        BlockingQueue<String> frames = subscribe(id);

        ResponseEntity<Map> evolve = post("/models/" + id + "/spec/evolve", """
                {"upsertDerivations":[{"path":"$.doubled","expr":"1 * 2"}]}
                """);
        assertThat(evolve.getStatusCode()).isEqualTo(HttpStatus.OK);

        String frame = frames.poll(5, TimeUnit.SECONDS);
        assertThat(frame).isNotNull();
        JsonNode event = mapper.readTree(frame);
        assertThat(event.path("kind").asText()).isEqualTo("spec-evolved");
        assertThat(event.path("modelId").asText()).isEqualTo(id);
        assertThat(event.path("version").asText()).isNotBlank();
    }

    @Test
    void mutate_stillBroadcastsMutationKindEvent() throws Exception {
        String id = "spec-evolved-broadcast-2";
        ResponseEntity<Map> create = post("/models",
                "{\"id\":\"" + id + "\",\"version\":\"1.0.0\",\"schema\":{}}");
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        BlockingQueue<String> frames = subscribe(id);

        ResponseEntity<Map> mutate = post("/models/" + id + "/mutations", "{\"$.x\":1}");
        assertThat(mutate.getStatusCode()).isEqualTo(HttpStatus.OK);

        String frame = frames.poll(5, TimeUnit.SECONDS);
        assertThat(frame).isNotNull();
        JsonNode event = mapper.readTree(frame);
        assertThat(event.path("kind").asText()).isEqualTo("mutation");
        assertThat(event.path("modelId").asText()).isEqualTo(id);
    }
}
