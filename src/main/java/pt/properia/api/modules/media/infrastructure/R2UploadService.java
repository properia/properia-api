package pt.properia.api.modules.media.infrastructure;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Service
public class R2UploadService {

    private static final Logger log = LoggerFactory.getLogger(R2UploadService.class);

    private final R2Properties props;
    private final String cdnBaseUrl;
    private final List<String> corsAllowedOrigins;

    // O S3Client/S3Presigner do AWS SDK são thread-safe e CAROS de construir
    // (pools HTTP, threads, providers). Criar um por upload provocava churn de
    // memória/threads e OOM (instância reiniciava a meio do upload → 502 no FE).
    // São agora criados UMA vez e reutilizados. Init preguiçoso e thread-safe.
    private volatile S3Client s3Client;
    private volatile S3Presigner s3Presigner;

    public R2UploadService(R2Properties props,
                           @Value("${properia.media.cdn-base-url:}") String cdnBaseUrl,
                           @Value("${properia.media.cors-allowed-origins:*}") String corsAllowedOrigins) {
        this.props = props;
        this.cdnBaseUrl = cdnBaseUrl.endsWith("/") ? cdnBaseUrl.substring(0, cdnBaseUrl.length() - 1) : cdnBaseUrl;
        this.corsAllowedOrigins = Arrays.stream(corsAllowedOrigins.split(","))
            .map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    /**
     * Aplica a política de CORS ao bucket R2 no arranque (best-effort). Sem isto, o PUT
     * presigned directo do browser para o R2 é bloqueado pelo CORS e TUDO cai no fallback
     * do servidor — o que provocava a pressão de memória/OOM. Configurar o CORS faz as
     * imagens irem browser→R2 e o servidor deixa de as tocar.
     *
     * Requer permissão s3:PutBucketCors no token R2; se não a tiver, apenas regista um aviso.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void configureBucketCors() {
        if (!isConfigured()) return;
        try {
            var rule = CORSRule.builder()
                .allowedOrigins(corsAllowedOrigins.isEmpty() ? List.of("*") : corsAllowedOrigins)
                .allowedMethods("PUT", "GET", "HEAD")
                .allowedHeaders("*")
                .exposeHeaders("ETag")
                .maxAgeSeconds(3600)
                .build();
            s3().putBucketCors(PutBucketCorsRequest.builder()
                .bucket(props.getBucket())
                .corsConfiguration(CORSConfiguration.builder().corsRules(rule).build())
                .build());
            log.info("R2 CORS aplicado ao bucket '{}' (origens={}) — upload directo browser→R2 ativo.",
                props.getBucket(), corsAllowedOrigins.isEmpty() ? "*" : corsAllowedOrigins);
        } catch (Exception e) {
            log.warn("Não foi possível aplicar CORS ao bucket R2 (o token precisa de s3:PutBucketCors). "
                + "Configura-o no dashboard da Cloudflare para o upload directo funcionar. Detalhe: {}", e.getMessage());
        }
    }

    public boolean isConfigured() {
        return props.isConfigured();
    }

    public record PresignedUpload(String uploadUrl, String publicUrl, String objectKey) {}

    // ── Clientes partilhados (singletons preguiçosos) ───────────────────────────

    private S3Client s3() {
        var client = s3Client;
        if (client == null) {
            synchronized (this) {
                client = s3Client;
                if (client == null) {
                    client = S3Client.builder()
                        .region(Region.of("auto"))
                        .endpointOverride(endpoint())
                        .credentialsProvider(credentials())
                        .build();
                    s3Client = client;
                    log.info("R2 S3Client inicializado (singleton reutilizado).");
                }
            }
        }
        return client;
    }

    private S3Presigner presigner() {
        var presigner = s3Presigner;
        if (presigner == null) {
            synchronized (this) {
                presigner = s3Presigner;
                if (presigner == null) {
                    presigner = S3Presigner.builder()
                        .region(Region.of("auto"))
                        .endpointOverride(endpoint())
                        .credentialsProvider(credentials())
                        .build();
                    s3Presigner = presigner;
                    log.info("R2 S3Presigner inicializado (singleton reutilizado).");
                }
            }
        }
        return presigner;
    }

    private URI endpoint() {
        return URI.create("https://" + props.getAccountId() + ".r2.cloudflarestorage.com");
    }

    private StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(props.getAccessKeyId(), props.getSecretAccessKey()));
    }

    private String publicUrlFor(String objectKey) {
        return cdnBaseUrl.isBlank()
            ? "https://" + props.getAccountId() + ".r2.cloudflarestorage.com/" + props.getBucket() + "/" + objectKey
            : cdnBaseUrl + "/" + objectKey;
    }

    // ── Operações ───────────────────────────────────────────────────────────────

    /** Direct server-side upload to R2 — used when browser cannot do presigned PUT (CORS). */
    public String uploadBytes(String objectKey, byte[] bytes, String contentType) {
        var ct = (contentType != null && !contentType.isBlank()) ? contentType : "image/jpeg";
        s3().putObject(
            PutObjectRequest.builder()
                .bucket(props.getBucket())
                .key(objectKey)
                .contentType(ct)
                .build(),
            RequestBody.fromBytes(bytes)
        );

        var publicUrl = publicUrlFor(objectKey);
        log.info("R2 direct upload: objectKey={} size={} publicUrl={}", objectKey, bytes.length, publicUrl);
        return publicUrl;
    }

    /**
     * Upload em STREAMING para o R2 — a imagem NÃO é carregada inteira em memória.
     * Preferir a uploadBytes no caminho de multipart: o ficheiro é lido do disco temporário
     * do multipart em pedaços, evitando o pico de heap (byte[] + cópia) que fazia a instância
     * do Render rebentar por OOM sob uploads concorrentes.
     */
    public String uploadStream(String objectKey, java.io.InputStream in, long contentLength, String contentType) {
        var ct = (contentType != null && !contentType.isBlank()) ? contentType : "image/jpeg";
        s3().putObject(
            PutObjectRequest.builder()
                .bucket(props.getBucket())
                .key(objectKey)
                .contentType(ct)
                .contentLength(contentLength)
                .build(),
            RequestBody.fromInputStream(in, contentLength)
        );

        var publicUrl = publicUrlFor(objectKey);
        log.info("R2 stream upload: objectKey={} size={} publicUrl={}", objectKey, contentLength, publicUrl);
        return publicUrl;
    }

    public PresignedUpload createPresignedUpload(String objectKey, String contentType) {
        var putRequest = PutObjectRequest.builder()
            .bucket(props.getBucket())
            .key(objectKey)
            .contentType(contentType)
            .build();

        var presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(15))
            .putObjectRequest(putRequest)
            .build();

        var presigned = presigner().presignPutObject(presignRequest);
        var publicUrl = publicUrlFor(objectKey);

        log.debug("R2 presigned upload created: objectKey={}", objectKey);
        return new PresignedUpload(presigned.url().toString(), publicUrl, objectKey);
    }

    @PreDestroy
    public void shutdown() {
        try { if (s3Client != null) s3Client.close(); } catch (Exception ignored) {}
        try { if (s3Presigner != null) s3Presigner.close(); } catch (Exception ignored) {}
    }
}
