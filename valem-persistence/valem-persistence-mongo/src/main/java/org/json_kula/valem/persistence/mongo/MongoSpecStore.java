package org.json_kula.valem.persistence.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.persistence.SpecStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * {@link SpecStore} backed by MongoDB collection {@code ss_specs}.
 *
 * <p>Document shape: {@code {_id: modelId, specJson: "...", savedAt: Date}}.
 * The spec is stored as a JSON string to avoid BSON key-naming restrictions
 * (MongoDB forbids dots in field names, which can appear in JSONata expressions).
 */
public final class MongoSpecStore implements SpecStore {

    private static final Logger log = LoggerFactory.getLogger(MongoSpecStore.class);
    private static final String COLLECTION = "ss_specs";

    private final MongoCollection<Document> collection;
    private final ObjectMapper              mapper;

    public MongoSpecStore(MongoDatabase db, ObjectMapper mapper) {
        this.collection = db.getCollection(COLLECTION);
        this.mapper     = mapper;
    }

    @Override
    public void saveSpec(String modelId, ModelSpec spec) throws IOException {
        Document doc = new Document("_id", modelId)
                .append("specJson", mapper.writeValueAsString(spec))
                .append("savedAt",  new Date());
        collection.replaceOne(Filters.eq("_id", modelId), doc, new ReplaceOptions().upsert(true));
        log.debug("Saved spec for model '{}'", modelId);
    }

    @Override
    public Optional<ModelSpec> loadSpec(String modelId) throws IOException {
        Document doc = collection.find(Filters.eq("_id", modelId)).first();
        if (doc == null) return Optional.empty();
        return Optional.of(mapper.readValue(doc.getString("specJson"), ModelSpec.class));
    }

    @Override
    public List<String> modelIds() throws IOException {
        List<String> ids = new ArrayList<>();
        collection.find().projection(new Document("_id", 1))
                .forEach(doc -> ids.add(doc.getString("_id")));
        ids.sort(String::compareTo);
        return ids;
    }

    @Override
    public void delete(String modelId) throws IOException {
        collection.deleteOne(Filters.eq("_id", modelId));
        log.debug("Deleted spec for model '{}'", modelId);
    }

    @Override
    public boolean isEnabled() { return true; }
}
