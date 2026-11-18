package dev.packetflow.dpi;

/**
 * Minimal TLS ClientHello parser: enough to find the SNI hostname inside the
 * first client record. Kept as a convenient helper for custom engines.
 */
final class Tls {

    private Tls() {
    }

    /** Record header is intact and the handshake type is client_hello. */
    static boolean isClientHello(byte[] b, int len) {
        return len > 9
                && (b[0] & 0xFF) == 0x16
                && (b[5] & 0xFF) == 0x01
                && u16(b, 3) > 0;
    }

    /** Offset of the first byte of the SNI hostname, or -1. */
    static int sniStart(byte[] b, int len) {
        return sni(b, len, false);
    }

    /** Offset right after the SNI hostname, or -1. */
    static int sniEnd(byte[] b, int len) {
        return sni(b, len, true);
    }

    private static int sni(byte[] b, int len, boolean end) {
        try {
            if (!isClientHello(b, len)) return -1;

            int p = 9 + 2 + 32;                    // handshake header + version + random
            int sidLen = b[p] & 0xFF;              // session id
            p += 1 + sidLen;
            int csLen = u16(b, p);                 // cipher suites
            p += 2 + csLen;
            int compLen = b[p] & 0xFF;             // compression methods
            p += 1 + compLen;
            if (p + 2 > len) return -1;

            int extTotal = u16(b, p);
            p += 2;
            int extEnd = Math.min(len, p + extTotal);

            while (p + 4 <= extEnd) {
                int type = u16(b, p);
                int elen = u16(b, p + 2);
                int data = p + 4;
                int next = data + elen;
                if (next > len) next = len;

                if (type == 0x0000 && data + 2 <= len) {          // server_name
                    int listLen = u16(b, data);
                    int q = data + 2;
                    int listEnd = Math.min(len, q + listLen);
                    while (q + 3 <= listEnd) {
                        int nameType = b[q] & 0xFF;
                        int nlen = u16(b, q + 1);
                        int nstart = q + 3;
                        if (nameType == 0) {
                            int nend = Math.min(len, nstart + nlen);
                            return end ? nend : nstart;
                        }
                        q = nstart + nlen;
                    }
                }
                if (next <= p) break;
                p = next;
            }
        } catch (RuntimeException ignored) {
            // truncated / malformed hello: no SNI for you
        }
        return -1;
    }

    static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }
}
