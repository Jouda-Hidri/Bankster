package bankster.client.psd2;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

import javax.net.ssl.KeyManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import reactor.netty.http.client.HttpClient;

/**
 * Builds the mutually-authenticated {@link WebClient} used for every XS2A call.
 *
 * <p>NextGenPSD2 requires the TPP to identify itself with an eIDAS QWAC presented
 * as a TLS client certificate; the ASPSP derives the TPP identity from the
 * certificate subject. Without it the sandbox answers {@code TOKEN_INVALID} even
 * when a valid bearer token is supplied.
 */
@Configuration
@EnableConfigurationProperties(Psd2Properties.class)
public class Psd2Config {

    private static final Logger log = LoggerFactory.getLogger(Psd2Config.class);

    /** OID 2.5.4.97 — organizationIdentifier, carries the TPP authorisation number. */
    private static final String ORGANIZATION_IDENTIFIER_OID = "2.5.4.97";

    @Bean
    public KeyStore psd2KeyStore(Psd2Properties properties) throws Exception {
        Resource resource = new DefaultResourceLoader().getResource(properties.getKeystore());
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "PSD2 keystore not found at '" + properties.getKeystore()
                            + "'. Run scripts/generate-sandbox-cert.sh to create one.");
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = resource.getInputStream()) {
            keyStore.load(in, properties.getKeystorePassword().toCharArray());
        }
        return keyStore;
    }

    /**
     * Resolves the OAuth2 client id, preferring the configured value and otherwise
     * falling back to the TPP authorisation number embedded in the certificate.
     */
    @Bean
    public Psd2TppIdentity psd2TppIdentity(KeyStore keyStore, Psd2Properties properties) throws Exception {
        String configured = properties.getClientId();
        if (configured != null && !configured.isBlank()) {
            return new Psd2TppIdentity(configured);
        }
        String fromCertificate = organizationIdentifier(keyStore);
        log.info("PSD2 client id taken from transport certificate: {}", fromCertificate);
        return new Psd2TppIdentity(fromCertificate);
    }

    @Bean
    public WebClient psd2WebClient(Psd2Properties properties, KeyStore keyStore) throws Exception {
        KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, properties.getKeystorePassword().toCharArray());

        SslContext sslContext = SslContextBuilder.forClient()
                .keyManager(keyManagerFactory)
                .build();

        HttpClient httpClient = HttpClient.create()
                .secure(spec -> spec.sslContext(sslContext))
                // The consent and OAuth endpoints answer with 302s that must be
                // inspected by the caller, so redirects are deliberately not followed.
                .followRedirect(false);

        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    private static String organizationIdentifier(KeyStore keyStore) throws Exception {
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (!(keyStore.getCertificate(alias) instanceof X509Certificate certificate)) {
                continue;
            }
            String subject = certificate.getSubjectX500Principal().getName();
            for (String part : subject.split(",")) {
                String trimmed = part.trim();
                // Java renders unknown OIDs as "OID.2.5.4.97=..." or "2.5.4.97=#..."
                if (trimmed.startsWith("OID." + ORGANIZATION_IDENTIFIER_OID + "=")) {
                    return decodeIfDer(trimmed.substring(trimmed.indexOf('=') + 1));
                }
                if (trimmed.startsWith(ORGANIZATION_IDENTIFIER_OID + "=")) {
                    return decodeIfDer(trimmed.substring(trimmed.indexOf('=') + 1));
                }
            }
        }
        throw new IllegalStateException(
                "Certificate has no organizationIdentifier (OID 2.5.4.97); set psd2.client-id explicitly.");
    }

    /**
     * A subject value Java cannot render as text comes back DER-encoded and hex
     * prefixed with '#'. For the PrintableString the sandbox issues, the payload
     * starts two bytes in (tag + length).
     */
    private static String decodeIfDer(String value) {
        if (!value.startsWith("#")) {
            return value;
        }
        byte[] der = new byte[(value.length() - 1) / 2];
        for (int i = 0; i < der.length; i++) {
            der[i] = (byte) Integer.parseInt(value.substring(1 + i * 2, 3 + i * 2), 16);
        }
        return new String(der, 2, der.length - 2, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The TPP authorisation number used as the OAuth2 {@code client_id}. */
    public record Psd2TppIdentity(String clientId) {
    }
}
