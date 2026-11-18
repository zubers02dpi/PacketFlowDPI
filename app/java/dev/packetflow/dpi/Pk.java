package dev.packetflow.dpi;

/** IPv4 / TCP / UDP packet building and parsing. */
final class Pk {

    private Pk() {
    }

    static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    static long u32(byte[] b, int i) {
        return ((long) (b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16)
                | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    static void p16(byte[] b, int i, int v) {
        b[i] = (byte) ((v >> 8) & 0xFF);
        b[i + 1] = (byte) (v & 0xFF);
    }

    static void p32(byte[] b, int i, long v) {
        b[i] = (byte) ((v >> 24) & 0xFF);
        b[i + 1] = (byte) ((v >> 16) & 0xFF);
        b[i + 2] = (byte) ((v >> 8) & 0xFF);
        b[i + 3] = (byte) (v & 0xFF);
    }

    /** Build IPv4 + TCP, return total length. */
    static int tcp(byte[] out, byte[] srcIp, byte[] dstIp, int srcPort, int dstPort,
                   long seq, long ack, int flags, int window,
                   byte[] payload, int payOff, int payLen) {
        int total = 40 + payLen;
        out[0] = 0x45;
        out[1] = 0;
        p16(out, 2, total);
        p16(out, 4, 0);
        p16(out, 6, 0x4000);            // don't fragment
        out[8] = 64;
        out[9] = 6;
        p16(out, 10, 0);
        System.arraycopy(srcIp, 0, out, 12, 4);
        System.arraycopy(dstIp, 0, out, 16, 4);
        p16(out, 10, inetChecksum(out, 0, 20));

        p16(out, 20, srcPort);
        p16(out, 22, dstPort);
        p32(out, 24, seq);
        p32(out, 28, ack);
        out[32] = (byte) (5 << 4);
        out[33] = (byte) flags;
        p16(out, 34, window);
        p16(out, 36, 0);
        p16(out, 38, 0);
        if (payLen > 0 && payload != null) {
            System.arraycopy(payload, payOff, out, 40, payLen);
        }
        p16(out, 36, tcpChecksum(out, 20, 20 + payLen, srcIp, dstIp));
        return total;
    }

    /** Build IPv4 + UDP (checksum 0, legal for IPv4), return total length. */
    static int udp(byte[] out, byte[] srcIp, byte[] dstIp, int srcPort, int dstPort,
                   byte[] payload, int payOff, int payLen) {
        int total = 28 + payLen;
        out[0] = 0x45;
        out[1] = 0;
        p16(out, 2, total);
        p16(out, 4, 0);
        p16(out, 6, 0);
        out[8] = 64;
        out[9] = 17;
        p16(out, 10, 0);
        System.arraycopy(srcIp, 0, out, 12, 4);
        System.arraycopy(dstIp, 0, out, 16, 4);
        p16(out, 10, inetChecksum(out, 0, 20));

        p16(out, 20, srcPort);
        p16(out, 22, dstPort);
        p16(out, 24, 8 + payLen);
        p16(out, 26, 0);
        if (payLen > 0 && payload != null) {
            System.arraycopy(payload, payOff, out, 28, payLen);
        }
        return total;
    }

    static int inetChecksum(byte[] b, int off, int len) {
        long sum = 0;
        int i = 0;
        for (; i + 1 < len; i += 2) sum += u16(b, off + i);
        if ((len & 1) != 0) sum += (b[off + len - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    static int tcpChecksum(byte[] b, int off, int tcpLen, byte[] src, byte[] dst) {
        long sum = 0;
        sum += u16(src, 0) + u16(src, 2);
        sum += u16(dst, 0) + u16(dst, 2);
        sum += 6;
        sum += tcpLen;
        int i = 0;
        for (; i + 1 < tcpLen; i += 2) sum += u16(b, off + i);
        if ((tcpLen & 1) != 0) sum += (b[off + tcpLen - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }
}
