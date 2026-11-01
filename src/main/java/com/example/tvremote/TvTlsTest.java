
package com.example.tvremote;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public final class TvTlsTest {

    private static final String TV_IP = "192.168.0.94";
    private static final int TV_PORT = 6467;

    public static void main(String[] args) throws Exception {
        Path certificatePath = Path.of("tv-certificate.pem");

        CertificateFactory certificateFactory =
                CertificateFactory.getInstance("X.509");

        X509Certificate tvCertificate;

        try (FileInputStream input =
                     new FileInputStream(certificatePath.toFile())) {
            tvCertificate = (X509Certificate)
                    certificateFactory.generateCertificate(input);
        }

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("sharp-tv", tvCertificate);

        TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());

        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(
                null,
                trustManagerFactory.getTrustManagers(),
                null
        );

        try (SSLSocket socket = (SSLSocket)
                sslContext.getSocketFactory()
                        .createSocket(TV_IP, TV_PORT)) {

            socket.setSoTimeout(5000);
            socket.startHandshake();

            System.out.println("TLS handshake succeeded.");
            System.out.println("Protocol: "
                    + socket.getSession().getProtocol());
            System.out.println("Cipher: "
                    + socket.getSession().getCipherSuite());
            System.out.println("TV certificate subject: "
                    + tvCertificate.getSubjectX500Principal());
        }
    }
}
