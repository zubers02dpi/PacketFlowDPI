package dev.packetflow.dpi;

import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.io.OutputStream;

/**
 * First-payload writer.
 *
 * <p>In the production build this is where the desync engine lives. That code
 * is proprietary and is intentionally not published here. The open build is a
 * plain pass-through: the first payload is written to the socket unchanged, so
 * nothing bypasses DPI inspection.</p>
 *
 * <p>To plug in your own engine, replace the body of
 * {@link #writeFirst(ParcelFileDescriptor, OutputStream, byte[], int, int, Strategy)}
 * and use {@link Strategy#plan(byte[], int)} to decide what to send.</p>
 */
final class Desync {

    private Desync() {
    }

    /**
     * Writes the first payload of a connection.
     *
     * <p>Open implementation: one write, no transformation.</p>
     */
    static void writeFirst(ParcelFileDescriptor pfd, OutputStream out,
                           byte[] d, int off, int len, Strategy st) throws IOException {
        out.write(d, off, len);
        out.flush();
        TunService.log("desync: открытая сборка, движок не подключён");
    }
}
