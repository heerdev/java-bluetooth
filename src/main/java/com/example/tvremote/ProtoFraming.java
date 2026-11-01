
package com.example.tvremote;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class ProtoFraming {

    private static final int MAX_MESSAGE_SIZE = 1_048_576;

    private ProtoFraming() {
    }

    public static void writeFrame(
            OutputStream output, byte[] message) throws IOException {

        if (message.length > MAX_MESSAGE_SIZE) {
            throw new IOException("Message exceeds maximum size");
        }

        int length = message.length;

        while ((length & ~0x7F) != 0) {
            output.write((length & 0x7F) | 0x80);
            length >>>= 7;
        }

        output.write(length);
        output.write(message);
        output.flush();
    }

    public static byte[] readFrame(InputStream input)
            throws IOException {

        DataInputStream data = new DataInputStream(input);
        int length = readVarint(data);

        if (length < 0 || length > MAX_MESSAGE_SIZE) {
            throw new IOException("Invalid message length: " + length);
        }

        byte[] message = new byte[length];
        data.readFully(message);
        return message;
    }

    private static int readVarint(InputStream input)
            throws IOException {

        int result = 0;

        for (int shift = 0; shift < 35; shift += 7) {
            int value = input.read();

            if (value == -1) {
                throw new EOFException(
                        "Connection closed while reading frame length");
            }

            if (shift == 28 && (value & 0xF0) != 0) {
                throw new IOException("Invalid frame length prefix");
            }

            result |= (value & 0x7F) << shift;

            if ((value & 0x80) == 0) {
                return result;
            }
        }

        throw new IOException("Malformed frame length prefix");
    }
}
