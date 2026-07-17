package org.json_kula.valem.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.service.ModelService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A lightweight, self-contained in-memory audit trail for the <b>embedded</b> CLI mode (MCP + console),
 * plugged into {@link ModelService} as both its {@link ModelService.AuditSink} (write) and
 * {@link ModelService.AuditReader} (query/verify). It gives the embedded {@code get_audit}/
 * {@code verify_audit} tools real data without dragging the persistence modules into the CLI dependency
 * tree — the records mirror the durable {@code AuditRecord} JSON shape the REST API and Java SDK serve,
 * so audit output is identical across embedded and remote modes.
 *
 * <p>It is deliberately <em>not</em> tamper-evident: embedded state is ephemeral and single-process, so
 * there is no hash chain to verify (unlike the durable {@code FilesystemAuditStore}/{@code PostgresAuditStore}
 * behind {@code valem-web}). {@link #verify} reports a valid, chain-less result.
 */
final class InMemoryCliAudit implements ModelService.AuditSink, ModelService.AuditReader {

    /** Newest-first cap applied when the caller passes no positive limit (mirrors {@code AuditQuery}). */
    private static final int DEFAULT_LIMIT = 100;

    private final ObjectMapper mapper;
    private final Map<String, List<ObjectNode>> byModel = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    InMemoryCliAudit(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ── Write (AuditSink) ───────────────────────────────────────────────────────

    @Override
    public void record(String modelId, String modelVersion, String source,
                       Map<String, JsonNode> mutations,
                       ModelRuntime.MutationResult result, Instant at) {
        long sequence = sequences.computeIfAbsent(modelId, k -> new AtomicLong()).getAndIncrement();
        ObjectNode rec = mapper.createObjectNode();
        rec.put("modelId", modelId);
        rec.put("sequence", sequence);
        rec.put("timestamp", at == null ? null : at.toString());
        rec.put("modelVersion", modelVersion);
        rec.put("source", source);
        rec.set("mutations", mapper.valueToTree(mutations));
        rec.set("derivedUpdated", mapper.valueToTree(result.derivedUpdated()));
        rec.set("flaggedConstraints", mapper.valueToTree(
                result.flaggedConstraints().stream()
                        .map(ConstraintEvaluator.Violation::constraintId).toList()));
        rec.set("dispatchedEffects", mapper.valueToTree(
                result.dispatchedEffects().stream().map(EffectRequest::effectId).toList()));
        rec.set("traces", mapper.valueToTree(result.traces()));
        rec.putNull("prevHash");
        rec.putNull("hash");
        byModel.computeIfAbsent(modelId, k -> Collections.synchronizedList(new ArrayList<>())).add(rec);
    }

    // ── Read (AuditReader) ──────────────────────────────────────────────────────

    @Override
    public JsonNode query(String modelId, String pathPrefix, Instant from, Instant to, int limit) {
        List<ObjectNode> all = byModel.getOrDefault(modelId, List.of());
        int cap = limit > 0 ? limit : DEFAULT_LIMIT;
        ArrayNode out = mapper.createArrayNode();
        synchronized (all) {
            for (int i = all.size() - 1; i >= 0 && out.size() < cap; i--) {   // newest-first
                ObjectNode rec = all.get(i);
                if (touchesPath(rec, pathPrefix) && inWindow(rec, from, to)) {
                    out.add(rec);
                }
            }
        }
        return out;
    }

    @Override
    public JsonNode verify(String modelId) {
        long count = byModel.getOrDefault(modelId, List.of()).size();
        ObjectNode node = mapper.createObjectNode();
        node.put("valid", true);
        node.put("recordsChecked", count);
        node.putNull("firstBrokenSequence");
        node.put("detail", "ok (embedded in-memory audit; no tamper-evident hash chain)");
        return node;
    }

    private static boolean touchesPath(ObjectNode rec, String prefix) {
        if (prefix == null || prefix.isBlank()) return true;
        for (Iterator<String> it = rec.path("mutations").fieldNames(); it.hasNext(); ) {
            if (it.next().startsWith(prefix)) return true;
        }
        for (JsonNode d : rec.path("derivedUpdated")) {
            if (d.asText().startsWith(prefix)) return true;
        }
        for (JsonNode t : rec.path("traces")) {
            if (t.path("targetPath").asText("").startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean inWindow(ObjectNode rec, Instant from, Instant to) {
        if (from == null && to == null) return true;
        String ts = rec.path("timestamp").asText(null);
        if (ts == null) return false;
        Instant t;
        try {
            t = Instant.parse(ts);
        } catch (RuntimeException e) {
            return false;
        }
        if (from != null && t.isBefore(from)) return false;
        return to == null || t.isBefore(to);
    }
}
