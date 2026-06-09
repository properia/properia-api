package pt.properia.api.modules.advertiser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pt.properia.api.modules.advertiser.application.GoogleCalendarService;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GoogleCalendarService.
 * Tests AES-256-GCM encrypt/decrypt round-trips and edge cases.
 * No Spring context needed — tested in isolation.
 */
class GoogleCalendarServiceTest {

    private GoogleCalendarService buildService(String keyB64) throws Exception {
        var service = new GoogleCalendarService(new ObjectMapper());
        var keyField = GoogleCalendarService.class.getDeclaredField("encryptionKeyB64");
        keyField.setAccessible(true);
        keyField.set(service, keyB64);

        // Also inject empty client-id/secret so isConfigured() returns false (irrelevant here)
        for (var fieldName : new String[]{"clientId", "clientSecret"}) {
            var f = GoogleCalendarService.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(service, "");
        }
        return service;
    }

    private static String randomKeyB64() {
        var key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void encryptDecryptRoundTrip() throws Exception {
        var svc = buildService(randomKeyB64());
        var plaintext = "ya29.a0AfH6SMCtest-access-token-value";
        var ciphertext = svc.encrypt(plaintext);
        assertNotNull(ciphertext);
        assertNotEquals(plaintext, ciphertext);
        assertEquals(plaintext, svc.decrypt(ciphertext));
    }

    @Test
    void encryptIsDeterministicallyUnique() throws Exception {
        // AES-GCM uses a random nonce per encrypt call, so same plaintext ≠ same ciphertext
        var svc = buildService(randomKeyB64());
        var pt = "refresh_token_value";
        assertNotEquals(svc.encrypt(pt), svc.encrypt(pt));
    }

    @Test
    void encryptNullReturnsNull() throws Exception {
        var svc = buildService(randomKeyB64());
        assertNull(svc.encrypt(null));
        assertNull(svc.decrypt(null));
    }

    @Test
    void encryptWithNoKeyReturnsPlaintext() throws Exception {
        // When encryption key is blank, values are stored as-is (dev mode)
        var svc = buildService("");
        var pt = "plain_token";
        assertEquals(pt, svc.encrypt(pt));
        assertEquals(pt, svc.decrypt(pt));
    }

    @Test
    void decryptTamperedCiphertextThrows() throws Exception {
        var svc = buildService(randomKeyB64());
        var ciphertext = svc.encrypt("some_token");
        // Flip a byte in the ciphertext to simulate tampering
        var raw = Base64.getDecoder().decode(ciphertext);
        raw[raw.length - 1] ^= 0xFF;
        var tampered = Base64.getEncoder().encodeToString(raw);
        assertThrows(RuntimeException.class, () -> svc.decrypt(tampered));
    }

    @Test
    void wrongKeyDecryptThrows() throws Exception {
        var svc1 = buildService(randomKeyB64());
        var svc2 = buildService(randomKeyB64());
        var ciphertext = svc1.encrypt("token_encrypted_with_key1");
        // svc2 has a different key — decryption must fail
        assertThrows(RuntimeException.class, () -> svc2.decrypt(ciphertext));
    }

    @Test
    void encryptDecryptLongToken() throws Exception {
        var svc = buildService(randomKeyB64());
        // Google refresh tokens are typically ~200 chars
        var longToken = "1//0g" + "x".repeat(190);
        assertEquals(longToken, svc.decrypt(svc.encrypt(longToken)));
    }
}
