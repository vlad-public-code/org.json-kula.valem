package org.json_kula.valem.persistence.s3;

import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.persistence.spi.Concern;
import org.json_kula.valem.persistence.spi.PersistenceProvider;
import org.json_kula.valem.persistence.spi.ProviderContext;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.util.EnumSet;
import java.util.Set;

/**
 * S3/MinIO-backed {@link PersistenceProvider}. Serves {@link Concern#BLOB} only. Owns the
 * {@link S3Client} it builds from {@code valem.storage.s3.*} properties and closes it in
 * {@link #close()}.
 */
public final class S3PersistenceProvider implements PersistenceProvider {

    private S3Client s3;

    @Override
    public String type() {
        return "s3";
    }

    @Override
    public Set<Concern> concerns() {
        return EnumSet.of(Concern.BLOB);
    }

    @Override
    public BlobStore blobStore(ProviderContext ctx) {
        String bucket = ctx.require("valem.storage.s3.bucket");
        return new S3BlobStore(s3(ctx), bucket);
    }

    private synchronized S3Client s3(ProviderContext ctx) {
        if (s3 == null) {
            String region    = ctx.get("valem.storage.s3.region", "us-east-1");
            String endpoint  = ctx.get("valem.storage.s3.endpoint", "");
            String accessKey = ctx.get("valem.storage.s3.access-key", "");
            String secretKey = ctx.get("valem.storage.s3.secret-key", "");

            S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
            if (!endpoint.isBlank()) {
                builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
            }
            if (!accessKey.isBlank()) {
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)));
            } else {
                builder.credentialsProvider(DefaultCredentialsProvider.create());
            }
            s3 = builder.build();
        }
        return s3;
    }

    @Override
    public synchronized void close() {
        if (s3 != null) {
            s3.close();
        }
    }
}
