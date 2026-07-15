package org.json_kula.valem.persistence.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.core.blob.NoSuchBlobException;
import org.json_kula.valem.core.model.BlobRef;
import org.json_kula.valem.persistence.BlobSpooler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Content-addressed {@link BlobStore} backed by MongoDB GridFS ({@code ss_blobs} bucket)
 * plus a lightweight index collection ({@code ss_blob_index}) keyed by SHA-256.
 *
 * <p>Index document shape:
 * {@code {_id: "sha256:<hex>", gridfsFileId: ObjectId, byteCount: long}}.
 */
public final class MongoBlobStore implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(MongoBlobStore.class);
    private static final String INDEX_COLLECTION = "ss_blob_index";
    private static final String GRIDFS_BUCKET    = "ss_blobs";

    private final GridFSBucket               bucket;
    private final MongoCollection<Document>  index;

    public MongoBlobStore(MongoDatabase db) {
        this.bucket = GridFSBuckets.create(db, GRIDFS_BUCKET);
        this.index  = db.getCollection(INDEX_COLLECTION);
    }

    @Override
    public BlobRef store(InputStream data, String mediaType) throws IOException {
        // Spool to a temp file while hashing (bounded heap), then stream the file into GridFS —
        // GridFS chunks the upload, so the blob never lives wholly in heap on either side (F-T6).
        try (BlobSpooler spooled = BlobSpooler.spool(data)) {
            String blobId = spooled.blobId();
            long   size   = spooled.byteCount();

            // Content-addressed: skip upload if already stored
            if (index.find(Filters.eq("_id", blobId)).first() != null) {
                return new BlobRef(blobId, mediaType, size);
            }

            ObjectId fileId;
            try (InputStream in = spooled.openStream()) {
                fileId = bucket.uploadFromStream(blobId.substring("sha256:".length()), in);
            }
            index.insertOne(new Document("_id",         blobId)
                    .append("gridfsFileId", fileId)
                    .append("byteCount",    size));
            log.debug("Stored blob {} ({} bytes)", blobId, size);
            return new BlobRef(blobId, mediaType, size);
        }
    }

    @Override
    public InputStream load(String blobId) throws IOException {
        Document entry = index.find(Filters.eq("_id", blobId)).first();
        if (entry == null) throw new NoSuchBlobException(blobId);
        ObjectId fileId = entry.getObjectId("gridfsFileId");
        return bucket.openDownloadStream(fileId);
    }

    @Override
    public boolean exists(String blobId) {
        return index.find(Filters.eq("_id", blobId)).first() != null;
    }

    @Override
    public void delete(String blobId) {
        Document entry = index.find(Filters.eq("_id", blobId)).first();
        if (entry == null) return;
        ObjectId fileId = entry.getObjectId("gridfsFileId");
        bucket.delete(fileId);
        index.deleteOne(Filters.eq("_id", blobId));
        log.debug("Deleted blob {}", blobId);
    }
}
