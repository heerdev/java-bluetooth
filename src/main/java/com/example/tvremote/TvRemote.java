
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
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final CountDownLatch disconnected = new CountDownLatch(1);
    private static final AtomicInteger activeFeatures = new AtomicInteger(0);
    private static final ExecutorService commandExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "tv-remote-commands");
                thread.setDaemon(true);
                return thread;
            });

    private static volatile boolean connected;
    private static volatile OutputStream output;
    private static volatile SSLSocket remoteSocket;
    private static JLabel statusLabel;
    private static JFrame window;

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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            createWindow();
            Thread connectionThread = new Thread(
                    TvRemote::connectToTv, "tv-remote-connection");
            connectionThread.setDaemon(true);
            connectionThread.start();
        });
    }

    private static void createWindow() {
        window = new JFrame("Sharp TV Remote");
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                quit();
            }
        });

        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JLabel title = new JLabel("Sharp TV Remote", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        content.add(title, BorderLayout.NORTH);

        JPanel controls = new JPanel(new GridLayout(0, 3, 8, 8));
        controls.setBorder(BorderFactory.createEmptyBorder(16, 0, 12, 0));
        addControl(controls, 0);
        addControl(controls, 1);
        addControl(controls, 2);
        addControl(controls, 3);
        addControl(controls, 4);
        addControl(controls, 5);
        addControl(controls, 6);
        addControl(controls, 7);
        addControl(controls, 8);
        addControl(controls, 9);
        content.add(controls, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 8));
        statusLabel = new JLabel("Starting TV remote...");
        JButton quitButton = new JButton("0. Quit");
        quitButton.addActionListener(event -> quit());
        footer.add(statusLabel, BorderLayout.CENTER);
        footer.add(quitButton, BorderLayout.EAST);
        content.add(footer, BorderLayout.SOUTH);

        window.setContentPane(content);
        window.setSize(420, 470);
        window.setLocationByPlatform(true);
        window.setResizable(false);
        window.setVisible(true);
    }

    private static void addControl(JPanel controls, int index) {
        Button button = BUTTONS[index];
        JButton control = new JButton((index + 1) + ". " + button.label);
        control.addActionListener(event -> commandExecutor.execute(() -> {
            try {
                sendKey(button.keyCode);
                setStatus("Sent " + button.label);
            } catch (Exception e) {
                setStatus("Could not send " + button.label + ": "
                        + e.getMessage());
            }
        }));
        controls.add(control);
    }

    private static void connectToTv() {
        if (CLIENT_PASSWORD.length == 0 || TRUST_PASSWORD.length == 0) {
            setStatus("Set TV_CLIENT_PASSWORD and TV_TRUSTSTORE_PASSWORD "
                    + "to connect.");
            return;
        }

        try {
            SSLContext sslContext = createSslContext();
            try (SSLSocket socket =
                         (SSLSocket) sslContext.getSocketFactory()
                                 .createSocket(TV_HOST, TV_PORT)) {
                remoteSocket = socket;
                socket.setKeepAlive(true);
                socket.startHandshake();

                InputStream input = socket.getInputStream();
                output = socket.getOutputStream();
                connected = true;
                setStatus("TLS connected; waiting for TV configuration...");

                Thread receiver = new Thread(
                        () -> receiveMessages(input),
                        "tv-remote-receiver");
                receiver.setDaemon(true);
                receiver.start();

                if (!configured.await(10, TimeUnit.SECONDS)) {
                    setStatus("Timed out waiting for TV configuration.");
                    return;
                }

                setStatus("Connected to Sharp TV.");
                disconnected.await();
            }
        } catch (Exception e) {
            setStatus("Connection failed: " + e.getMessage());
        } finally {
            connected = false;
            output = null;
            remoteSocket = null;
        }
    }

    private static void setStatus(String message) {
        SwingUtilities.invokeLater(() -> {
            if (statusLabel != null) {
                statusLabel.setText(message);
            }
        });
    }

    private static void quit() {
        connected = false;
        commandExecutor.shutdownNow();
        SSLSocket socket = remoteSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception e) {
                System.err.println("Could not close TV connection: "
                        + e.getMessage());
            }
        }
        disconnected.countDown();
        window.dispose();
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
                setStatus("TV connection ended: " + e.getMessage());
            }
        } finally {
            connected = false;
            disconnected.countDown();
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

}
