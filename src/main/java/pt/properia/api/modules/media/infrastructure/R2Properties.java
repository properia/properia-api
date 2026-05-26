package pt.properia.api.modules.media.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("properia.media.r2")
public class R2Properties {

    private String accountId = "";
    private String accessKeyId = "";
    private String secretAccessKey = "";
    private String bucket = "";

    public boolean isConfigured() {
        return !accountId.isBlank()
            && !accessKeyId.isBlank()
            && !secretAccessKey.isBlank()
            && !bucket.isBlank();
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }

    public String getSecretAccessKey() { return secretAccessKey; }
    public void setSecretAccessKey(String secretAccessKey) { this.secretAccessKey = secretAccessKey; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
}
