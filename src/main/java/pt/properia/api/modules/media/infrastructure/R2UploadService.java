package pt.properia.api.modules.media.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

@Service
public class R2UploadService {

    private static final Logger log = LoggerFactory.getLogger(R2UploadService.class);

    private final R2Properties props;
    private final String cdnBaseUrl;

    public R2UploadService(R2Properties props,
                           @Value("${properia.media.cdn-base-url:}") String cdnBaseUrl) {
        this.props = props;
        this.cdnBaseUrl = cdnBaseUrl.endsWith("/") ? cdnBaseUrl.substring(0, cdnBaseUrl.length() - 1) : cdnBaseUrl;
    }

    public boolean isConfigured() {
        return props.isConfigured();
    }

    public record PresignedUpload(String uploadUrl, String publicUrl, String objectKey) {}

    public PresignedUpload createPresignedUpload(String objectKey, String contentType) {
        var endpoint = URI.create("https://" + props.getAccountId() + ".r2.cloudflarestorage.com");
        var credentials = AwsBasicCredentials.create(props.getAccessKeyId(), props.getSecretAccessKey());

        try (var presigner = S3Presigner.builder()
                .region(Region.of("auto"))
                .endpointOverride(endpoint)
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build()) {

            var putRequest = PutObjectRequest.builder()
                .bucket(props.getBucket())
                .key(objectKey)
                .contentType(contentType)
                .build();

            var presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .putObjectRequest(putRequest)
                .build();

            var presigned = presigner.presignPutObject(presignRequest);
            var publicUrl = cdnBaseUrl.isBlank()
                ? "https://" + props.getAccountId() + ".r2.cloudflarestorage.com/" + props.getBucket() + "/" + objectKey
                : cdnBaseUrl + "/" + objectKey;

            log.debug("R2 presigned upload created: objectKey={}", objectKey);
            return new PresignedUpload(presigned.url().toString(), publicUrl, objectKey);
        }
    }
}
