
package com.example.tvremote;

import com.google.protobuf.MessageLite;
import remote.Remotemessage;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.Console;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

public final class TvRemote_bckup {

    private static final String HOST = "192.168.0.94";
    private static final int PORT = 6466;

    // Remote protocol feature flags.
    private static final int FEATURE_PING = 1;
    private static final int FEATURE_KEY = 2;
    private static final int FEATURE_POWER = 32;
    private static final int FEATURE_VOLUME = 64;
    private static final int FEATURE_APP_LINK = 512;
    private static final int CLIENT_FEATURES =
            FEATURE_PING
                    | FEATURE_KEY
                    | FEATURE_POWER
                    | FEATURE_VOLUME
                    | FEATURE_APP_LINK;

    private TvRemote_bckup() {
    }

    private static void send(OutputStream out, MessageLite message)
            throws Exception {
        ProtoFraming.writeFrame(out, message.toByteArray());
        out.flush();
    }

    private static Remotemessage.RemoteMessage receive(InputStream in)
            throws Exception {
        byte[] frame = ProtoFraming.readFrame(in);

        if (frame == null) {
            throw new EOFException("TV closed the connection");
        }

        Remotemessage.RemoteMessage message =
                Remotemessage.RemoteMessage.parseFrom(frame);

        System.out.println("Received: " + message.getAllFields().keySet());

        return message;
    }

    private static SSLContext createTlsContext(char[] password)
            throws Exception {

        KeyStore clientStore = KeyStore.getInstance("PKCS12");

        try (InputStream in =
                     Files.newInputStream(Path.of("tv-client.p12"))) {
            clientStore.load(in, password);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientStore, password);

        KeyStore trustStore =
                KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);

        try (InputStream in =
                     Files.newInputStream(Path.of("tv-certificate.pem"))) {

            X509Certificate tvCertificate = (X509Certificate)
                    CertificateFactory.getInstance("X.509")
                            .generateCertificate(in);

            trustStore.setCertificateEntry("tv", tvCertificate);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(
                kmf.getKeyManagers(),
                tmf.getTrustManagers(),
                new SecureRandom()
        );

        return context;
    }

    public static void main(String[] args) throws Exception {

        Console console = System.console();

        if (console == null) {
            throw new IllegalStateException(
                    "Run this program from Terminal so it can read the keystore password."
            );
        }

        char[] password =
                console.readPassword("Client keystore password: ");

        try {
            SSLContext context = createTlsContext(password);

            try (SSLSocket socket = (SSLSocket)
                    context.getSocketFactory().createSocket(HOST, PORT)) {

                socket.setSoTimeout(10000);
                socket.startHandshake();

                System.out.println(
                        "TLS connected: " + socket.getSession().getProtocol()
                );

                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                boolean configured = false;
                boolean remoteStarted = false;
                boolean keySent = false;

                int activeFeatures = 0;

                while (true) {
                    Remotemessage.RemoteMessage message;

                    try {
                        message = receive(in);
                    } catch (SocketTimeoutException e) {
                        System.out.println(
                                "No message received; connection remains open."
                        );
                        continue;
                    }

                    if (message.hasRemoteConfigure()) {

                        Remotemessage.RemoteConfigure tvConfig =
                                message.getRemoteConfigure();

                        System.out.println(
                                "TV model: "
                                        + tvConfig.getDeviceInfo().getModel()
                        );

                        System.out.println(
                                "TV vendor: "
                                        + tvConfig.getDeviceInfo().getVendor()
                        );

                        System.out.println(
                                "TV feature flags: " + tvConfig.getCode1()
                        );

                        activeFeatures = tvConfig.getCode1()
                                & CLIENT_FEATURES;

                        Remotemessage.RemoteDeviceInfo clientInfo =
                                Remotemessage.RemoteDeviceInfo.newBuilder()
                                        .setUnknown1(1)
                                        .setUnknown2("1")
                                        .setPackageName("atvremote")
                                        .setAppVersion("1.0.0")
                                        .build();

                        Remotemessage.RemoteConfigure responseConfig =
                                Remotemessage.RemoteConfigure.newBuilder()
                                        .setCode1(activeFeatures)
                                        .setDeviceInfo(clientInfo)
                                        .build();

                        send(
                                out,
                                Remotemessage.RemoteMessage.newBuilder()
                                        .setRemoteConfigure(responseConfig)
                                        .build()
                        );

                        configured = true;

                        System.out.println(
                                "Configuration response sent. Negotiated flags: "
                                        + activeFeatures
                        );

                    } else if (message.hasRemoteSetActive()) {

                        int tvActive =
                                message.getRemoteSetActive().getActive();

                        System.out.println(
                                "TV active status: " + tvActive
                        );

                        /*
                         * Reply using the negotiated feature flags,
                         * not a hard-coded value.
                         */
                        send(
                                out,
                                Remotemessage.RemoteMessage.newBuilder()
                                        .setRemoteSetActive(
                                                Remotemessage.RemoteSetActive
                                                        .newBuilder()
                                                        .setActive(activeFeatures)
                                                        .build()
                                        )
                                        .build()
                        );

                        System.out.println(
                                "Replied to remote_set_active with flags "
                                        + activeFeatures
                        );

                    } else if (message.hasRemoteStart()) {

                        System.out.println(
                                "Received remote_start: "
                                        + message.getRemoteStart()
                        );

                        /*
                         * Only treat this as ready if the generated
                         * schema exposes a positive started value.
                         */
                        remoteStarted = true;

                        if (configured && remoteStarted && !keySent) {

                            if ((activeFeatures & FEATURE_KEY) == 0) {
                                System.out.println(
                                        "Key injection was not negotiated; "
                                                + "not sending Back."
                                );
                                continue;
                            }
                            if (!keySent && (activeFeatures & FEATURE_KEY) != 0) {
                                Remotemessage.RemoteKeyInject key =
                                        Remotemessage.RemoteKeyInject.newBuilder()
                                                .setKeyCode(
                                                        Remotemessage.RemoteKeyCode.KEYCODE_BACK)
                                                .setDirection(
                                                        Remotemessage.RemoteDirection.SHORT)
                                                .build();

                                send(out, Remotemessage.RemoteMessage.newBuilder()
                                        .setRemoteKeyInject(key)
                                        .build());

                                keySent = true;
                                System.out.println("Sent Back key.");
                            }
                        }

                    } else if (message.hasRemotePingRequest()) {

                        Remotemessage.RemotePingRequest ping =
                                message.getRemotePingRequest();

                        send(
                                out,
                                Remotemessage.RemoteMessage.newBuilder()
                                        .setRemotePingResponse(
                                                Remotemessage.RemotePingResponse
                                                        .newBuilder()
                                                        .setVal1(ping.getVal1())
                                                        .build()
                                        )
                                        .build()
                        );

                        System.out.println("Replied to TV ping.");

                    } else if (message.hasRemoteError()) {

                        System.out.println(
                                "TV reported a remote protocol error: "
                                        + message.getRemoteError()
                        );

                    } else {
                        System.out.println(
                                "Unhandled message: " + message
                        );
                    }
                }
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
