package com.example.tvremote;

import com.google.protobuf.ByteString;
import pairing.Pairingmessage;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.Console;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Scanner;

public final class TvPairing {
    private static final String TV_IP = "192.168.0.94";
    private static final int TV_PORT = 6467;
    private static final String CLIENT_KEYSTORE = "tv-client.p12";
    private static final String TV_CERTIFICATE = "tv-certificate.pem";

    private TvPairing() {}

    public static void main(String[] args) throws Exception {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException(
                    "Run this program from a terminal so passwords can be entered securely.");
        }

        char[] password = console.readPassword("Client keystore password: ");

        try {
            SSLContext context = createSslContext(password);

            try (SSLSocket socket = (SSLSocket) context.getSocketFactory()
                    .createSocket(TV_IP, TV_PORT)) {

                socket.setSoTimeout(15_000);
                socket.startHandshake();

                System.out.println("TLS connected: "
                        + socket.getSession().getProtocol());

                X509Certificate serverCertificate =
                        (X509Certificate) socket.getSession()
                                .getPeerCertificates()[0];

                KeyStore clientStore = KeyStore.getInstance("PKCS12");
                try (var in = java.nio.file.Files.newInputStream(
                        Path.of(CLIENT_KEYSTORE))) {
                    clientStore.load(in, password);
                }

                Certificate clientCertificate =
                        clientStore.getCertificate("tvremote-client");

                if (!(clientCertificate instanceof X509Certificate clientX509)) {
                    throw new IllegalStateException(
                            "tvremote-client certificate not found in the keystore.");
                }

                startPairing(socket, clientX509, serverCertificate);
            }
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static SSLContext createSslContext(char[] password)
            throws Exception {
        KeyStore clientStore = KeyStore.getInstance("PKCS12");
        try (var in = java.nio.file.Files.newInputStream(
                Path.of(CLIENT_KEYSTORE))) {
            clientStore.load(in, password);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientStore, password);

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate tvCertificate;
        try (var in = java.nio.file.Files.newInputStream(
                Path.of(TV_CERTIFICATE))) {
            tvCertificate = (X509Certificate) cf.generateCertificate(in);
        }

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("sharp-tv", tvCertificate);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return context;
    }

    private static void startPairing(
            SSLSocket socket,
            X509Certificate clientCertificate,
            X509Certificate serverCertificate) throws Exception {

        var input = socket.getInputStream();
        var output = socket.getOutputStream();

        Pairingmessage.PairingRequest request =
                Pairingmessage.PairingRequest.newBuilder()
                        .setServiceName("atvremote")
                        .setClientName("Java TV Remote")
                        .build();

        send(output, Pairingmessage.PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(Pairingmessage.PairingMessage.Status.STATUS_OK)
                .setPairingRequest(request)
                .build());

        boolean sentOptions = false;
        boolean sentConfiguration = false;
        boolean sentSecret = false;

        while (true) {
            Pairingmessage.PairingMessage message =
                    Pairingmessage.PairingMessage.parseFrom(
                            ProtoFraming.readFrame(input));

            if (message.getStatus()
                    != Pairingmessage.PairingMessage.Status.STATUS_OK) {
                throw new IOException(
                        "TV returned pairing status: " + message.getStatus());
            }

            if (message.hasPairingRequestAck()) {
                System.out.println("TV acknowledged pairing request: "
                        + message.getPairingRequestAck().getServerName());

                var encoding = Pairingmessage.PairingEncoding.newBuilder()
                        .setType(Pairingmessage.PairingEncoding.EncodingType
                                .ENCODING_TYPE_HEXADECIMAL)
                        .setSymbolLength(6)
                        .build();

                var options = Pairingmessage.PairingOption.newBuilder()
                        .setPreferredRole(Pairingmessage.RoleType.ROLE_TYPE_INPUT)
                        .addInputEncodings(encoding)
                        .build();

                send(output, baseMessage().setPairingOption(options).build());
                sentOptions = true;

            } else if (message.hasPairingOption()) {
                if (!sentOptions) {
                    throw new IOException("Received pairing options unexpectedly.");
                }

                var encoding = Pairingmessage.PairingEncoding.newBuilder()
                        .setType(Pairingmessage.PairingEncoding.EncodingType
                                .ENCODING_TYPE_HEXADECIMAL)
                        .setSymbolLength(6)
                        .build();

                var configuration =
                        Pairingmessage.PairingConfiguration.newBuilder()
                                .setEncoding(encoding)
                                .setClientRole(Pairingmessage.RoleType.ROLE_TYPE_INPUT)
                                .build();

                send(output, baseMessage()
                        .setPairingConfiguration(configuration).build());
                sentConfiguration = true;

            } else if (message.hasPairingConfigurationAck()) {
                if (!sentConfiguration) {
                    throw new IOException(
                            "Received configuration acknowledgement unexpectedly.");
                }

                System.out.println("Pairing configuration accepted.");
                System.out.println(
                        "Look at the TV and enter its six-character hexadecimal code.");

                String code = readPairingCode();
                byte[] secret = calculateSecret(
                        clientCertificate, serverCertificate, code);

                var pairingSecret = Pairingmessage.PairingSecret.newBuilder()
                        .setSecret(ByteString.copyFrom(secret))
                        .build();

                send(output, baseMessage()
                        .setPairingSecret(pairingSecret).build());
                sentSecret = true;

            } else if (message.hasPairingSecretAck()) {
                if (!sentSecret) {
                    throw new IOException(
                            "Received secret acknowledgement unexpectedly.");
                }

                System.out.println("TV acknowledged the pairing secret.");
                System.out.println(
                        "Pairing exchange completed. Test a remote session next.");
                return;

            } else {
                throw new IOException(
                        "Unexpected pairing message received from TV.");
            }
        }
    }

    private static Pairingmessage.PairingMessage.Builder baseMessage() {
        return Pairingmessage.PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(Pairingmessage.PairingMessage.Status.STATUS_OK);
    }

    private static void send(
            java.io.OutputStream output,
            Pairingmessage.PairingMessage message) throws IOException {
        ProtoFraming.writeFrame(output, message.toByteArray());
    }

    private static String readPairingCode() {
        Console console = System.console();
        if (console != null) {
            return console.readLine("Pairing code: ")
                    .trim().toUpperCase(Locale.ROOT);
        }
        return new Scanner(System.in).nextLine()
                .trim().toUpperCase(Locale.ROOT);
    }

    private static byte[] calculateSecret(
            X509Certificate clientCertificate,
            X509Certificate serverCertificate,
            String code) throws Exception {

        if (!code.matches("[0-9A-F]{6}")) {
            throw new IllegalArgumentException(
                    "The pairing code must be exactly six hexadecimal characters.");
        }

        RSAPublicKey clientKey = requireRsa(clientCertificate.getPublicKey());
        RSAPublicKey serverKey = requireRsa(serverCertificate.getPublicKey());

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(unsignedBytes(clientKey.getModulus()));
        digest.update(unsignedBytes(clientKey.getPublicExponent()));
        digest.update(unsignedBytes(serverKey.getModulus()));
        digest.update(unsignedBytes(serverKey.getPublicExponent()));
        digest.update(HexFormat.of().parseHex(code.substring(2)));

        byte[] hash = digest.digest();
        int expectedFirstByte = Integer.parseInt(code.substring(0, 2), 16);

        if ((hash[0] & 0xff) != expectedFirstByte) {
            throw new SecurityException(
                    "Pairing code validation failed. Check the code displayed on the TV.");
        }

        return hash;
    }

    private static RSAPublicKey requireRsa(PublicKey key) {
        if (!(key instanceof RSAPublicKey rsaKey)) {
            throw new IllegalArgumentException(
                    "The TV Remote protocol requires RSA certificates.");
        }
        return rsaKey;
    }

    private static byte[] unsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();

        // BigInteger may prepend 0x00 to represent a positive number.
        if (bytes.length > 1 && bytes[0] == 0) {
            return java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }
}
