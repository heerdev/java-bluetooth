
package com.example.tvremote;

import com.google.protobuf.ByteString;
import remote.Remotemessage.RemoteConfigure;
import remote.Remotemessage.RemoteDeviceInfo;
import remote.Remotemessage.RemoteKeyCode;
import remote.Remotemessage.RemoteKeyInject;
import remote.Remotemessage.RemoteMessage;
import remote.Remotemessage.RemotePingRequest;
import remote.Remotemessage.RemotePingResponse;
import remote.Remotemessage.RemoteSetActive;
import remote.Remotemessage.RemoteDirection;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.KeyManagerFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TvRemote {

    private static final String TV_HOST = "192.168.0.94";
    private static final int TV_PORT = 6466;

    private static final String TRUST_STORE = "tv-truststore.p12";
    private static final char[] TRUST_PASSWORD =
            System.getenv("TV_TRUSTSTORE_PASSWORD") == null
                    ? new char[0]
                    : System.getenv("TV_TRUSTSTORE_PASSWORD").toCharArray();

    private static final String CLIENT_STORE = "tv-client.p12";
    private static final char[] CLIENT_PASSWORD =
            System.getenv("TV_CLIENT_PASSWORD") == null
                    ? new char[0]
                    : System.getenv("TV_CLIENT_PASSWORD").toCharArray();

    private static final int FEATURE_PING = 1;
    private static final int FEATURE_KEY = 2;
    private static final int FEATURE_POWER = 32;
    private static final int FEATURE_VOLUME = 64;
    private static final int FEATURE_APP_LINK = 512;

    private static final int CLIENT_FEATURES =
            FEATURE_PING | FEATURE_KEY | FEATURE_POWER
                    | FEATURE_VOLUME | FEATURE_APP_LINK;

    private static final CountDownLatch configured = new CountDownLatch(1);
    private static final AtomicInteger activeFeatures = new AtomicInteger(0);

    private static volatile boolean connected;
    private static volatile OutputStream output;

    private static final class Button {
        final String label;
        final int keyCode;

        Button(String label, int keyCode) {
            this.label = label;
            this.keyCode = keyCode;
        }
    }

    private static final Button[] BUTTONS = {
            new Button("UP", 19),
            new Button("DOWN", 20),
            new Button("LEFT", 21),
            new Button("RIGHT", 22),
            new Button("OK / SELECT", 23),
            new Button("HOME", 3),
            new Button("BACK", 4),
            new Button("VOLUME UP", 24),
            new Button("VOLUME DOWN", 25),
            new Button("MUTE / UNMUTE", 164)
    };

    public static void main(String[] args) throws Exception {
        if (CLIENT_PASSWORD.length == 0 || TRUST_PASSWORD.length == 0) {
            System.err.println(
                    "Set TV_CLIENT_PASSWORD and TV_TRUSTSTORE_PASSWORD " +
                            "environment variables before running.");
            System.err.println(
                    "The trust store must be a PKCS12 store containing " +
                            "the TV certificate.");
            return;
        }

        SSLContext sslContext = createSslContext();

        try (SSLSocket socket =
                     (SSLSocket) sslContext.getSocketFactory()
                             .createSocket(TV_HOST, TV_PORT)) {

            socket.setKeepAlive(true);
            socket.startHandshake();

            System.out.println("TLS connected: " +
                    socket.getSession().getProtocol());

            InputStream input = socket.getInputStream();
            output = socket.getOutputStream();
            connected = true;

            Thread receiver = new Thread(
                    () -> receiveMessages(input),
                    "tv-remote-receiver");
            receiver.setDaemon(true);
            receiver.start();

            // Preserve the feature-negotiation approach that worked.
           /* RemoteDeviceInfo deviceInfo = RemoteDeviceInfo.newBuilder()
                    .setModel("Java TV Remote")
                    .setVendor("Java")
                    .setUnknown1(1)
                    .setUnknown2("1")
                    .setPackageName("atvremote")
                    .setAppVersion("1.0.0")
                    .build();

            RemoteConfigure configure = RemoteConfigure.newBuilder()
                    .setCode1(CLIENT_FEATURES)
                    .setDeviceInfo(deviceInfo)
                    .build();

            send(RemoteMessage.newBuilder()
                    .setRemoteConfigure(configure)
                    .build());*/

            System.out.println("Configuration sent.");

            if (!configured.await(10, TimeUnit.SECONDS)) {
                System.err.println(
                        "Timed out waiting for the TV configuration response.");
                return;
            }

            runMenu();

            connected = false;
            System.out.println("Remote disconnected.");
        }
    }

    private static SSLContext createSslContext() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(TRUST_STORE))) {
            trustStore.load(in, TRUST_PASSWORD);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        KeyStore clientStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(CLIENT_STORE))) {
            clientStore.load(in, CLIENT_PASSWORD);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientStore, CLIENT_PASSWORD);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return context;
    }


    private static void receiveMessages(InputStream input) {
        try {
            while (connected) {
                byte[] frame = ProtoFraming.readFrame(input);

                if (frame == null) {
                    break;
                }

                RemoteMessage message = RemoteMessage.parseFrom(frame);

                if (message.hasRemoteConfigure()) {
                    RemoteConfigure tvConfig = message.getRemoteConfigure();

                    System.out.println("TV model: "
                            + tvConfig.getDeviceInfo().getModel());
                    System.out.println("TV vendor: "
                            + tvConfig.getDeviceInfo().getVendor());
                    System.out.println("TV feature flags: "
                            + tvConfig.getCode1());

                    int negotiated = tvConfig.getCode1() & CLIENT_FEATURES;
                    activeFeatures.set(negotiated);

                    RemoteDeviceInfo deviceInfo = RemoteDeviceInfo.newBuilder()
                            .setModel("Java TV Remote")
                            .setVendor("Java")
                            .setUnknown1(1)
                            .setUnknown2("1")
                            .setPackageName("atvremote")
                            .setAppVersion("1.0.0")
                            .build();

                    RemoteConfigure response = RemoteConfigure.newBuilder()
                            .setCode1(negotiated)
                            .setDeviceInfo(deviceInfo)
                            .build();

                    send(RemoteMessage.newBuilder()
                            .setRemoteConfigure(response)
                            .build());

                    System.out.println(
                            "Configuration response sent; active features: "
                                    + negotiated);

                    configured.countDown();

                } else if (message.hasRemoteSetActive()) {
                    RemoteSetActive tvActive = message.getRemoteSetActive();

                    System.out.println("TV active status: "
                            + tvActive.getActive());

                    send(RemoteMessage.newBuilder()
                            .setRemoteSetActive(
                                    RemoteSetActive.newBuilder()
                                            .setActive(activeFeatures.get())
                                            .build())
                            .build());

                } else if (message.hasRemotePingRequest()) {
                    RemotePingRequest ping = message.getRemotePingRequest();

                    RemotePingResponse response =
                            RemotePingResponse.newBuilder()
                                    .setVal1(ping.getVal1())
                                    .build();

                    send(RemoteMessage.newBuilder()
                            .setRemotePingResponse(response)
                            .build());

                } else if (message.hasRemoteStart()) {
                    System.out.println(
                            "TV remote-start notification received.");

                } else if (message.hasRemoteError()) {
                    System.err.println("TV reported a protocol error: "
                            + message.getRemoteError());

                } else {
                    System.out.println("Received an unhandled TV message.");
                }
            }
        } catch (Exception e) {
            if (connected) {
                System.err.println(
                        "TV receive loop ended: " + e.getMessage());
            }
        } finally {
            configured.countDown();
        }
    }


    private static synchronized void send(RemoteMessage message)
            throws Exception {
        if (!connected || output == null) {
            throw new IllegalStateException("TV connection is not active.");
        }

        ProtoFraming.writeFrame(output, message.toByteArray());
    }

    private static void sendKey(int keyCode) throws Exception {
        RemoteKeyCode key = RemoteKeyCode.forNumber(keyCode);

        if (key == null || key == RemoteKeyCode.UNRECOGNIZED) {
            System.err.println("Unsupported key code in generated protobuf: " +
                    keyCode);
            return;
        }

        RemoteKeyInject press = RemoteKeyInject.newBuilder()
                .setKeyCode(key)
                .setDirection(RemoteDirection.SHORT)
                .build();

        send(RemoteMessage.newBuilder()
                .setRemoteKeyInject(press)
                .build());

        System.out.println("Sent: " + key.name());
    }

    private static void runMenu() throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in))) {

            while (connected) {
                System.out.println();
                System.out.println("=== Sharp TV Remote ===");

                for (int i = 0; i < BUTTONS.length; i++) {
                    System.out.printf("%2d. %s%n",
                            i + 1, BUTTONS[i].label);
                }

                System.out.println(" 0. Quit");
                System.out.print("Choose a button: ");

                String line = reader.readLine();
                if (line == null || line.trim().equals("0")) {
                    break;
                }

                final int choice;
                try {
                    choice = Integer.parseInt(line.trim());
                } catch (NumberFormatException e) {
                    System.out.println("Enter a number from 0 to 10.");
                    continue;
                }

                if (choice < 1 || choice > BUTTONS.length) {
                    System.out.println("Enter a number from 0 to 10.");
                    continue;
                }

                sendKey(BUTTONS[choice - 1].keyCode);
            }
        }
    }
}
