package com.paymentsledger.identity;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class CredentialTokens {
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String dummy = encoder.encode("dummy-credential-for-missing-record-check");

    public record Parsed(UUID id, String secret) {
        @Override
        public String toString() {
            return "Parsed[redacted]";
        }
    }

    public Parsed parse(String token, String prefix) {
        if (token == null || !token.startsWith(prefix) || token.length() != prefix.length() + 80)
            throw TenantAccessGuard.unauthorized();
        String selector = token.substring(prefix.length(), prefix.length() + 36);
        String secret = token.substring(prefix.length() + 37);
        try {
            UUID id = UUID.fromString(selector);
            if (!id.toString().equals(selector)
                    || token.charAt(prefix.length() + 36) != '.'
                    || !SECRET.matcher(secret).matches()) throw TenantAccessGuard.unauthorized();
            return new Parsed(id, secret);
        } catch (IllegalArgumentException ex) {
            throw TenantAccessGuard.unauthorized();
        }
    }

    public boolean matches(Parsed token, String hash) {
        return encoder.matches(token.secret(), hash);
    }

    public void rejectUnknown(Parsed token) {
        encoder.matches(token.secret(), dummy);
        throw TenantAccessGuard.unauthorized();
    }

    public String hash(Parsed token) {
        return encoder.encode(token.secret());
    }
}
