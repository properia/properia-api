package pt.properia.api.modules.auth.infrastructure;

import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.stereotype.Service;
import pt.properia.api.shared.domain.DomainException;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Password hashing using scrypt with parameters matching the Next.js backend:
 * N=16384, r=8, p=1, keyLen=64
 * Format: scrypt$<16-byte-salt-hex>$<64-byte-derived-key-hex>
 */
@Service
public class PasswordService {

    private static final int SCRYPT_N = 16384;
    private static final int SCRYPT_R = 8;
    private static final int SCRYPT_P = 1;
    private static final int KEY_LEN = 64;
    // Alinhado com o formulário de registo do FE (≥12). Frases longas são mais seguras
    // e fáceis de lembrar. Só se aplica a definir/alterar password (registo, reset) —
    // nunca ao login, por isso contas antigas com passwords mais curtas continuam a entrar.
    private static final int MIN_LENGTH = 12;

    private final SecureRandom rng = new SecureRandom();
    private final HexFormat hex = HexFormat.of();

    public String hash(String password) {
        byte[] salt = new byte[16];
        rng.nextBytes(salt);
        byte[] dk = SCrypt.generate(password.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_LEN);
        return "scrypt$" + hex.formatHex(salt) + "$" + hex.formatHex(dk);
    }

    public boolean verify(String password, String storedHash) {
        if (storedHash == null) return false;

        String[] parts = storedHash.split("\\$");
        if (parts.length != 3 || !"scrypt".equals(parts[0])) return false;

        try {
            byte[] salt = hex.parseHex(parts[1]);
            byte[] expected = hex.parseHex(parts[2]);
            int keyLen = expected.length;

            byte[] actual = SCrypt.generate(
                password.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, keyLen
            );

            return constantTimeEquals(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    public void assertPolicy(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new DomainException("VALIDATION_ERROR",
                "A palavra-passe deve ter pelo menos " + MIN_LENGTH + " caracteres.");
        }
    }

    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
