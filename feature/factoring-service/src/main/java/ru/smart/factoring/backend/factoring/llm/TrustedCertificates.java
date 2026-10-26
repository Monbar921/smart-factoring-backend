package ru.smart.factoring.backend.factoring.llm;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * TLS для GigaChat: сертификаты серверов выданы НУЦ Минцифры («Russian Trusted Root CA»), которого нет в cacerts JDK.
 * Доверяем стандартным корневым сертификатам JDK и дополнительно - сертификатам из PEM-файла.
 */
final class TrustedCertificates {

    private TrustedCertificates() {
    }

    static SSLContext withExtraCa(final Path pemFile) {
        try {
            final KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType());
            extra.load(null, null);
            try (InputStream in = Files.newInputStream(pemFile)) {
                int index = 0;
                for (final Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    extra.setCertificateEntry("extra-ca-" + index++, certificate);
                }
                if (index == 0) {
                    throw new IllegalStateException("В " + pemFile + " нет сертификатов");
                }
            }
            final X509TrustManager defaults = trustManager(null);
            final X509TrustManager extras = trustManager(extra);
            final SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new EitherTrustManager(defaults, extras)}, null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось прочитать сертификаты " + pemFile + ": " + e.getMessage(), e);
        }
    }

    private static X509TrustManager trustManager(final KeyStore keyStore) throws GeneralSecurityException {
        final TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore);
        return Arrays.stream(factory.getTrustManagers())
                .filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Нет X509TrustManager"));
    }

    private record EitherTrustManager(X509TrustManager first, X509TrustManager second) implements X509TrustManager {

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
            first.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
            try {
                first.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                second.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return Stream.concat(Arrays.stream(first.getAcceptedIssuers()), Arrays.stream(second.getAcceptedIssuers()))
                    .toArray(X509Certificate[]::new);
        }
    }
}
