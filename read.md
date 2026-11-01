# Java Sharp Android TV Remote

A Java/Maven project for experimenting with the Android TV Remote
protocol over Wi-Fi.

> **Status:** Network reachability and a TLS handshake with the TV have
> been tested. Pairing and remote-control operation are not yet
> confirmed.

## 1. Requirements

-   macOS (commands below use the macOS terminal)
-   JDK 17 or newer (the project compiles with Java release 17; JDK 24
    has been used during development)
-   Maven
-   Mac and Sharp Android TV connected to the same reachable network
-   OpenSSL and `keytool` (included with a JDK)

## 2. Project location and TV address

The current development project is located at:

``` bash
cd ~/Project/java-bluetooth
```

The TV used during development was reachable at `192.168.0.94`. Replace
this address with the TV's current IP address if DHCP changes it.

The Android TV Remote protocol commonly uses:

-   TCP `6467` --- pairing
-   TCP `6466` --- remote-control session

These ports being reachable does not, by itself, mean pairing has
succeeded.

## 3. Export the TV certificate: `tv-certificate.pem`

The Java TLS test uses the TV's certificate to verify the server
certificate. This is different from the Java client's own identity.

From the project root, run:

``` bash
openssl s_client \
  -connect 192.168.0.94:6467 \
  -showcerts </dev/null 2>/dev/null \
  | openssl x509 -outform PEM > tv-certificate.pem
```

Check that a certificate was written:

``` bash
openssl x509 -in tv-certificate.pem -noout -subject -issuer -fingerprint -sha256
```

You can also inspect the certificate's dates:

``` bash
openssl x509 -in tv-certificate.pem -noout -dates
```

If the command fails, check that the TV's IP address is correct and that
the pairing port is reachable. The command above exports the first
certificate presented by that TLS endpoint; compare its SHA-256
fingerprint with a trusted value when possible.

`tv-certificate.pem` contains a public certificate, not the client's
private key. It can still reveal device-identifying details, so consider
whether to publish it in a public repository.

## 4. Generate the Java client's PKCS#12 identity: `tv-client.p12`

The `.p12` file is a **client identity keystore**. It normally contains
a private key and its associated certificate. It is separate from
`tv-certificate.pem`, which is used to verify the TV.

From the project root, create a new RSA key pair and a self-signed
certificate:

``` bash
keytool -genkeypair \
  -alias tvremote-client \
  -keyalg RSA \
  -keysize 2048 \
  -validity 3650 \
  -storetype PKCS12 \
  -keystore tv-client.p12 \
  -dname "CN=Java TV Remote"
```

When prompted, choose a strong keystore password and keep it private.
`keytool` may ask you to enter the password twice. Do not put the
password in source code or commit it to Git.

### Verify the PKCS#12 file

``` bash
keytool -list -v \
  -storetype PKCS12 \
  -keystore tv-client.p12 \
  -alias tvremote-client
```

Enter the password when prompted. Confirm that the entry type is
`PrivateKeyEntry`.

You can inspect the public certificate stored in the keystore without
exporting the private key:

``` bash
keytool -exportcert \
  -rfc \
  -alias tvremote-client \
  -keystore tv-client.p12 \
  -file tv-client-public.pem
```

This creates `tv-client-public.pem`, which contains the public
certificate only. It is not the `.p12` file and does not contain the
private key. You do not need to create this extra file unless you want
to inspect or exchange the public certificate.

**Important:** Generating a `.p12` file only creates a client identity.
It does not pair the application with the TV, and it does not prove that
the TV accepts that identity. The Java pairing implementation must use
the certificate and private key in the way required by the TV's
protocol.

## 5. Keep private files out of Git

Create or update `.gitignore` in the project root with:

``` gitignore
# Private keys and client identity stores
tv-client.p12
*.p12
*.pfx
*.jks

# Local secrets
.env
secrets.properties
```

Check whether Git ignores the keystore:

``` bash
git check-ignore -v tv-client.p12
```

Check whether the file is already tracked:

``` bash
git ls-files tv-client.p12
```

If `git ls-files` prints `tv-client.p12`, the file is already tracked.
To stop tracking it while keeping the local file:

``` bash
git rm --cached tv-client.p12
git commit -m "Stop tracking local client keystore"
```

Removing a file in a later commit does **not** erase it from Git
history. If the keystore was pushed to a public repository or shared
with an untrusted party, treat its private key as exposed. Generate a
replacement and determine whether the TV needs to be re-paired or the
old identity revoked.

Be cautious about committing `tv-certificate.pem` too: it is public-key
material, but its subject may identify your TV.

## 6. Build the Maven project

From the project root:

``` bash
mvn clean compile
```

The protobuf Maven plugin generates Java source files from the `.proto`
files. Generated files should appear under:

``` text
target/generated-sources/protobuf/java/
├── pairing/Pairingmessage.java
└── remote/Remotemessage.java
```

These files live under `target/` and are generated by Maven; normally,
do not edit them by hand.

## 7. TLS test

The existing TLS test is:

``` text
src/main/java/com/example/tvremote/TvTlsTest.java
```

Run it using the project's configured Maven exec plugin if its
main-class configuration is set for that class, or run the class from
your IDE. The test expects `tv-certificate.pem` at the project root and
uses it to trust the TV certificate.

A successful TLS handshake confirms that a TLS connection was
established and the certificate was accepted by the test's trust
configuration. It does **not** confirm that the Android TV pairing
protocol or remote-control commands work.

## 8. Current protocol files

``` text
src/main/proto/pairingmessage.proto
src/main/proto/remotemessage.proto
src/main/java/com/example/tvremote/ProtoFraming.java
src/main/java/com/example/tvremote/TvTlsTest.java
```

`ProtoFraming.java` reads and writes protobuf messages with a varint
length prefix. Pairing and remote-session code must follow the
protocol's exact message fields, cryptographic checks, and TLS
requirements.

## 9. Security checklist

-   [ ] `tv-client.p12` is excluded from Git.
-   [ ] The keystore password is not stored in source code.
-   [ ] The private keystore is stored only where needed.
-   [ ] The TV certificate fingerprint has been checked.
-   [ ] Pairing success has been tested separately from the TLS
    handshake.
-   [ ] Any exposed client key has been replaced and the old identity's
    access reviewed.

## 10. Troubleshooting commands

Check the TV's pairing port:

``` bash
nc -vz -G 3 192.168.0.94 6467
```

Inspect the TV's TLS certificate:

``` bash
openssl s_client -connect 192.168.0.94:6467 -brief </dev/null
```

Inspect the local client keystore:

``` bash
keytool -list -keystore tv-client.p12
```

Build from a clean state:

``` bash
mvn clean compile
```

Replace the sample IP address in these commands with the TV's current
address when needed.
