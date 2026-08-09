package org.json_kula.valem.api.error;

import org.json_kula.valem.api.dto.ValidationErrorResponse;
import org.json_kula.valem.core.blob.NoSuchBlobException;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.engine.SchemaViolationException;
import org.json_kula.valem.service.BlobNotReferencedException;
import org.json_kula.valem.service.InvalidPatchException;
import org.json_kula.valem.service.HistoryNotFoundException;
import org.json_kula.valem.service.ModelAlreadyExistsException;
import org.json_kula.valem.service.ModelNotFoundException;
import org.json_kula.valem.service.ModelValidationException;
import org.json_kula.valem.service.ModelLimitExceededException;
import org.json_kula.valem.service.MutationQueueFullException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.Locale;
import java.util.Map;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/**
 * Maps service-layer and core exceptions to RFC 7807 Problem Detail responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ── Service-layer exceptions ───────────────────────────────────────────────

    @ExceptionHandler(ModelNotFoundException.class)
    public ProblemDetail handleModelNotFound(ModelNotFoundException ex) {
        log.warn("Model not found: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ModelAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleModelAlreadyExists(ModelAlreadyExistsException ex) {
        log.warn("Duplicate model: {}", ex.getMessage());
        return ResponseEntity.status(CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(org.json_kula.valem.service.SpecVersionConflictException.class)
    public ProblemDetail handleVersionConflict(
            org.json_kula.valem.service.SpecVersionConflictException ex) {
        log.warn("Spec evolution version conflict: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(org.json_kula.valem.service.SchemaStateIncompatibleException.class)
    public ProblemDetail handleSchemaStateIncompatible(
            org.json_kula.valem.service.SchemaStateIncompatibleException ex) {
        log.warn("Spec evolution stranded state: {} incompatibility(ies)", ex.incompatibilities().size());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("incompatibilities", ex.incompatibilities().stream()
                .map(i -> Map.of("path", i.path(), "message", i.message())).toList());
        return pd;
    }

    @ExceptionHandler(ModelValidationException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidation(ModelValidationException ex) {
        log.warn("Spec validation failed: {} errors", ex.validationResult().errors().size());
        return ResponseEntity.status(UNPROCESSABLE_ENTITY)
                .body(ValidationErrorResponse.of(ex.validationResult()));
    }

    @ExceptionHandler(org.json_kula.valem.api.composition.CompositionException.class)
    public ProblemDetail handleComposition(
            org.json_kula.valem.api.composition.CompositionException ex) {
        log.warn("Composition topology invalid: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(org.json_kula.valem.api.reference.ReferenceException.class)
    public ProblemDetail handleReference(org.json_kula.valem.api.reference.ReferenceException ex) {
        log.warn("Reference resolution failed: {}", ex.getMessage());
        HttpStatus status = switch (ex) {
            case org.json_kula.valem.api.reference.ReferenceException.PromotionClosureFailure p -> CONFLICT;
            case org.json_kula.valem.api.reference.ReferenceException.ReferenceLocalityViolation v -> CONFLICT;
            case org.json_kula.valem.api.reference.ReferenceException.DemoteUnsupported d -> HttpStatus.METHOD_NOT_ALLOWED;
            default -> UNPROCESSABLE_ENTITY;
        };
        return ProblemDetail.forStatusAndDetail(status, ex.getMessage());
    }

    @ExceptionHandler(HistoryNotFoundException.class)
    public ProblemDetail handleHistoryNotFound(HistoryNotFoundException ex) {
        log.warn("History not found: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(BlobNotReferencedException.class)
    public ProblemDetail handleBlobNotReferenced(BlobNotReferencedException ex) {
        log.warn("Blob not referenced: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(InvalidPatchException.class)
    public ProblemDetail handleInvalidPatch(InvalidPatchException ex) {
        log.warn("Invalid patch: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(NoSuchBlobException.class)
    public ProblemDetail handleNoSuchBlob(NoSuchBlobException ex) {
        log.warn("Blob not found: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ModelLimitExceededException.class)
    public ProblemDetail handleModelLimitExceeded(ModelLimitExceededException ex) {
        log.warn("Model limit exceeded: limit={}", ex.limit());
        ProblemDetail pd = ProblemDetail.forStatus(TOO_MANY_REQUESTS);
        pd.setTitle("Model Limit Exceeded");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(MutationQueueFullException.class)
    public ProblemDetail handleMutationQueueFull(MutationQueueFullException ex) {
        log.warn("Mutation queue full: modelId={} capacity={}", ex.modelId(), ex.queueCapacity());
        ProblemDetail pd = ProblemDetail.forStatus(TOO_MANY_REQUESTS);
        pd.setTitle("Mutation Queue Full");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    // ── Core engine exceptions ─────────────────────────────────────────────────

    @ExceptionHandler(ConstraintEvaluator.ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(
            ConstraintEvaluator.ConstraintViolationException ex) {
        log.warn("Constraint violation: {} violation(s)", ex.violations().size());
        return ResponseEntity.status(CONFLICT)
                .body(Map.of("error", "Constraint violation", "violations", ex.violations()));
    }

    @ExceptionHandler(SchemaViolationException.class)
    public ResponseEntity<Map<String, Object>> handleSchemaViolation(SchemaViolationException ex) {
        log.warn("Schema violation: {} violation(s)", ex.violations().size());
        return ResponseEntity.status(UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "Schema violation", "violations", ex.violations()));
    }

    @ExceptionHandler(org.json_kula.valem.core.state.StateLimitExceededException.class)
    public ProblemDetail handleStateLimitExceeded(
            org.json_kula.valem.core.state.StateLimitExceededException ex) {
        log.warn("State limit exceeded: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    // ── Generic fallbacks ──────────────────────────────────────────────────────

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        // Log the parser detail server-side; return a generic message so Jackson/library internals
        // (class names, source locations) are not disclosed to the client (audit SEC-7).
        log.warn("Request body not readable (400): {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        log.warn("Request error: {} {}", ex.getStatusCode(), ex.getReason());
        return ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        // Unknown path / static resource — a client-side 404, not a server error.
        log.debug("No resource: {}", ex.getResourcePath());
        return ProblemDetail.forStatusAndDetail(NOT_FOUND, "Not found");
    }

    @ExceptionHandler(org.springframework.web.servlet.NoHandlerFoundException.class)
    public ProblemDetail handleNoHandlerFound(
            org.springframework.web.servlet.NoHandlerFoundException ex) {
        log.debug("No handler: {} {}", ex.getHttpMethod(), ex.getRequestURL());
        return ProblemDetail.forStatusAndDetail(NOT_FOUND, "Not found");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * The client went away before the response could be written.
     *
     * <p>This is not a server fault, and there is nothing useful to return: the socket is already
     * closed, so writing a Problem Detail only fails again on the way out. It is entirely routine on
     * long-lived and streaming connections — an agent cancelling a tool call, a browser navigating
     * away mid-request, a proxy timing out.
     *
     * <p>It used to fall through to {@link #handleIo} and be logged as {@code ERROR} with a full
     * ~140-frame stack trace. On the hosted MCP endpoint, where clients disconnect constantly, that
     * buried real failures in noise: a service being restarted by its platform emitted pages of these
     * as its connections dropped, which read like the cause and were only the consequence. Returning
     * {@code void} tells Spring the exchange is handled and that nothing is to be written.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException ex) {
        log.debug("Client disconnected before the response was written: {}", ex.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public ProblemDetail handleIo(IOException ex) {
        // Container-specific aborts (Tomcat's ClientAbortException, a broken pipe on a reset
        // connection) are the same non-event as above but arrive as a plain IOException. Match on the
        // cause chain rather than importing a servlet-container class into this layer.
        if (isClientDisconnect(ex)) {
            log.debug("Client disconnected before the response was written: {}", ex.getMessage());
        } else {
            log.error("I/O error", ex);
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Storage error");
    }

    /**
     * True when an I/O failure is the peer hanging up rather than storage misbehaving.
     *
     * <p>Walks a bounded number of links rather than to the end of the chain. {@code initCause}
     * forbids an exception causing itself, but nothing prevents two exceptions causing each other, and
     * a cycle here would spin a request thread forever — a much worse outcome than mislabelling a log
     * line. Real chains are a few frames deep.
     */
    static boolean isClientDisconnect(Throwable ex) {
        int remaining = 20;
        for (Throwable t = ex; t != null && remaining-- > 0; t = t.getCause()) {
            if (t instanceof ClosedChannelException) return true;
            String name = t.getClass().getSimpleName();
            if (name.equals("ClientAbortException") || name.equals("AsyncRequestNotUsableException")) {
                return true;
            }
            String message = t.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("broken pipe") || lower.contains("connection reset")) return true;
            }
        }
        return false;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error");
    }
}
