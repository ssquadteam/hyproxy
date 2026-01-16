package town.kibty.hyproxy.util;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Base64;

@UtilityClass
public class CertificateUtil {
    public String computeCertificateFingerprint(X509Certificate certificate) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] certBytes = certificate.getEncoded();
            byte[] hash = sha256.digest(certBytes);
            return base64UrlEncode(hash);
        } catch (NoSuchAlgorithmException | CertificateEncodingException var4) {
            return null;
        }
    }

    public boolean validateCertificateBinding(@Nullable String jwtFingerprint, @Nullable X509Certificate clientCert) {
        if (jwtFingerprint == null || jwtFingerprint.isEmpty()) {
            return false;
        }

        if (clientCert == null) {
            return false;
        }

        String actualFingerprint = computeCertificateFingerprint(clientCert);
        if (actualFingerprint == null) {
            return false;
        }

        return timingSafeEquals(jwtFingerprint, actualFingerprint);
    }

    public String base64UrlEncode(byte[] input) {
        String base64 = Base64.getEncoder().encodeToString(input);
        return base64.replace('+', '-').replace('/', '_').replace("=", "");
    }

    public boolean timingSafeEquals(String a, String b) {
        if (a != null && b != null) {
            byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
            byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(aBytes, bBytes);
        } else {
            return a == b;
        }
    }
}
