package org.json_kula.valem.service;

/**
 * Thrown when a mutation request arrives for a model whose pending-mutation queue
 * has reached {@code mutationQueueCapacity} (the total of executing + waiting requests).
 *
 * <p>Mapped to HTTP 429 Too Many Requests by the API layer's global exception handler.
 */
public class MutationQueueFullException extends RuntimeException {

    private final String modelId;
    private final int queueCapacity;

    public MutationQueueFullException(String modelId, int queueCapacity) {
        super("Mutation queue full for model '" + modelId + "' (capacity: " + queueCapacity + ")");
        this.modelId = modelId;
        this.queueCapacity = queueCapacity;
    }

    public String modelId()      { return modelId; }
    public int    queueCapacity() { return queueCapacity; }
}
