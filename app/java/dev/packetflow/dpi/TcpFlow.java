package dev.packetflow.dpi;

import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One TCP connection, terminated in userspace: the app talks to us through the
 * tun device, we talk to the real server with an ordinary (protected) socket.
 * That lets us reshuffle the first bytes of the stream however we want.
 */
final class TcpFlow {

    static final int FIN = 0x01, SYN = 0x02, RST = 0x04, PSH = 0x08, ACK = 0x10;
    private static final int MSS = 1400;
    private static final long SEQ_MASK = 0xFFFFFFFFL;

    final TunService svc;
    final byte[] srcIp;      // phone side
    final byte[] dstIp;      // server side
    final int srcPort;
    final int dstPort;
    final String peer;

    private final Object lock = new Object();
    private final List<byte[]> preConnect = new ArrayList<>();
    private int preSize;

    private Socket socket;
    private ParcelFileDescriptor pfd;
    private InputStream in;
    private OutputStream out;

    private long isn = -1;
    private long sndUna, sndNxt, rcvNxt;
    private int sndWnd = 65535;
    private boolean connected, synAcked, finSent, finRcvd, closed;
    private long lastActive = System.currentTimeMillis();

    /** The first TLS record is often written by the app in several chunks. */
    private final Object firstLock = new Object();
    private byte[] pend = new byte[4096];
    private int pendLen;
    private boolean firstDone;
    private boolean firstTimer;

    TcpFlow(TunService svc, byte[] srcIp, int srcPort, byte[] dstIp, int dstPort) {
        this.svc = svc;
        this.srcIp = srcIp;
        this.srcPort = srcPort;
        this.dstIp = dstIp;
        this.dstPort = dstPort;
        this.peer = ip2s(dstIp) + ":" + dstPort;
    }

    static String ip2s(byte[] a) {
        return (a[0] & 0xFF) + "." + (a[1] & 0xFF) + "." + (a[2] & 0xFF) + "." + (a[3] & 0xFF);
    }

    long lastActive() {
        return lastActive;
    }

    boolean isClosed() {
        return closed;
    }

    // ---------------------------------------------------------------- client side

    void onSyn(long clientSeq, int clientWnd) {
        synchronized (lock) {
            if (closed) return;
            if (isn >= 0) {
                sendPacket(SYN | ACK, null, 0, 0);   // duplicate SYN, repeat our answer
                return;
            }
            isn = 1 + (new Random().nextInt() & 0x3FFFFFFF);
            sndUna = isn;
            sndNxt = isn;
            rcvNxt = (clientSeq + 1) & SEQ_MASK;
            if (clientWnd > 0) sndWnd = clientWnd;
            sendPacket(SYN | ACK, null, 0, 0);
            sndNxt = (isn + 1) & SEQ_MASK;
        }
        Thread t = new Thread(this::connect, "tcp-open");
        t.setDaemon(true);
        t.start();
    }

    void onSegment(long seq, long ack, int flags, int wnd, byte[] d, int off, int len) {
        lastActive = System.currentTimeMillis();
        if ((flags & RST) != 0) {
            close();
            return;
        }

        if ((flags & ACK) != 0) {
            synchronized (lock) {
                if (wnd > 0) sndWnd = wnd;
                long diff = (ack - sndUna) & SEQ_MASK;
                long ahead = (sndNxt - ack) & SEQ_MASK;
                if (diff <= 0x40000000L && ahead <= 0x40000000L) {
                    sndUna = ack;
                }
                if (!synAcked && ack == ((isn + 1) & SEQ_MASK)) synAcked = true;
                lock.notifyAll();
            }
        }

        if (len > 0) {
            boolean inOrder;
            synchronized (lock) {
                inOrder = (seq == rcvNxt);
                if (inOrder) rcvNxt = (rcvNxt + len) & SEQ_MASK;
            }
            if (inOrder) deliver(d, off, len);
            ackOnly();
        }

        if ((flags & FIN) != 0) {
            boolean needAckNow = false;
            synchronized (lock) {
                if (!finRcvd) {
                    finRcvd = true;
                    needAckNow = true;
                }
            }
            if (needAckNow) {
                shutdownServerWrite();
                ackOnly();
            }
            boolean done;
            synchronized (lock) {
                done = finSent;
            }
            if (done) close();
        }
    }

    private void deliver(byte[] d, int off, int len) {
        byte[] copy = null;
        synchronized (lock) {
            if (!connected) {
                if (preSize + len > 256 * 1024) {
                    // refuse to buffer forever
                    out("слишком много данных до подключения: " + peer);
                    reset();
                    return;
                }
                copy = new byte[len];
                System.arraycopy(d, off, copy, 0, len);
                preConnect.add(copy);
                preSize += len;
            }
        }
        if (copy == null) writeToServer(d, off, len);
    }

    private void writeToServer(byte[] d, int off, int len) {
        if (out == null || len <= 0) return;
        synchronized (firstLock) {
            if (firstDone) {
                writePlain(d, off, len);
                return;
            }
            appendPending(d, off, len);
            if (!tryFlushFirst()) startFirstTimer();
        }
    }

    private void writePlain(byte[] d, int off, int len) {
        try {
            out.write(d, off, len);
        } catch (IOException e) {
            reset();
        }
    }

    private void appendPending(byte[] d, int off, int len) {
        if (pendLen + len > pend.length) {
            int want = Math.max(pend.length * 2, pendLen + len);
            pend = java.util.Arrays.copyOf(pend, Math.min(want, 128 * 1024));
        }
        int room = Math.min(len, pend.length - pendLen);
        System.arraycopy(d, off, pend, pendLen, room);
        pendLen += room;
    }

    /**
     * Sends the first record once it is complete. TLS headers carry the record
     * length, so we can wait for the whole ClientHello instead of desyncing a
     * truncated head (the SNI lives at the very end of it).
     */
    private boolean tryFlushFirst() {
        if (pendLen >= 5) {
            int type = pend[0] & 0xFF;
            int recLen = ((pend[3] & 0xFF) << 8) | (pend[4] & 0xFF);
            int need = 5 + recLen;
            if (type == 0x16 && recLen > 0 && need <= 65536) {
                if (pendLen >= need) {
                    flushFirst(need);
                    return true;
                }
                return false;                       // wait for the rest
            }
            flushFirst(pendLen);                    // not TLS: send as is
            return true;
        }
        if (pendLen >= 1024) {
            flushFirst(pendLen);
            return true;
        }
        return false;
    }

    /** Do not stall forever if the peer never finishes its first record. */
    private void startFirstTimer() {
        if (firstTimer) return;
        firstTimer = true;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(350);
            } catch (InterruptedException ignored) {
            }
            synchronized (firstLock) {
                if (!firstDone && pendLen > 0) flushFirst(pendLen);
            }
        }, "tcp-first");
        t.setDaemon(true);
        t.start();
    }

    private void flushFirst(int n) {
        if (firstDone) return;
        byte[] head = java.util.Arrays.copyOf(pend, n);
        byte[] tail = pendLen > n ? java.util.Arrays.copyOfRange(pend, n, pendLen) : null;
        pendLen = 0;
        firstDone = true;
        try {
            out("→ " + peer + " запрос " + head.length + " Б");
            Desync.writeFirst(pfd, out, head, 0, head.length, svc.strategy());
            if (tail != null) out.write(tail);
            out.flush();
        } catch (IOException e) {
            reset();
        }
    }

    private void shutdownServerWrite() {
        try {
            if (out != null) out.flush();
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) socket.shutdownOutput();
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- server side

    private void connect() {
        Socket s = new Socket();
        try {
            if (!svc.protectSocket(s) && !svc.warnedProtect()) {
                out("protect недоступен — работаю через исключение пакета из VPN");
            }
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), 15000);
            InputStream si = s.getInputStream();
            OutputStream so = s.getOutputStream();
            ParcelFileDescriptor p = ParcelFileDescriptor.fromSocket(s);

            List<byte[]> queued;
            synchronized (lock) {
                socket = s;
                in = si;
                out = so;
                pfd = p;
                connected = true;
                queued = new ArrayList<>(preConnect);
                preConnect.clear();
                preSize = 0;
                lock.notifyAll();
            }
            for (byte[] chunk : queued) writeToServer(chunk, 0, chunk.length);

            svc.countConnection(peer);
            pump(si);
        } catch (IOException e) {
            out("не подключиться " + peer + ": " + shortMsg(e));
            reset();
        }
    }

    private void pump(InputStream si) {
        byte[] buf = new byte[MSS];
        long total = 0;
        try {
            while (!closed) {
                int n = si.read(buf);
                if (n < 0) break;
                if (n > 0) {
                    total += n;
                    if (total == n) out("← " + peer + " ответ " + n + " Б");
                    sendData(buf, 0, n);
                }
            }
        } catch (IOException e) {
            out("← " + peer + " обрыв: " + shortMsg(e));
        }
        if (total == 0) out("← " + peer + " пусто, сервер закрыл");
        sendFin();
    }

    private void sendData(byte[] d, int off, int len) {
        int sent = 0;
        while (sent < len && !closed) {
            int chunk;
            synchronized (lock) {
                long inflight = (sndNxt - sndUna) & SEQ_MASK;
                if (inflight > 0x40000000L) inflight = 0;
                long room = (long) sndWnd - inflight;
                if (room <= 0) {
                    try {
                        lock.wait(300);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                chunk = (int) Math.min(Math.min(MSS, room), len - sent);
                sendPacket(ACK | PSH, d, off + sent, chunk);
                sndNxt = (sndNxt + chunk) & SEQ_MASK;
            }
            sent += chunk;
        }
    }

    private void sendFin() {
        boolean waitClient;
        synchronized (lock) {
            if (finSent || closed) return;
            sendPacket(ACK | FIN, null, 0, 0);
            sndNxt = (sndNxt + 1) & SEQ_MASK;
            finSent = true;
            waitClient = finRcvd;
        }
        if (waitClient) close();
    }

    private void ackOnly() {
        synchronized (lock) {
            if (closed) return;
            sendPacket(ACK, null, 0, 0);
        }
    }

    private void sendPacket(int flags, byte[] payload, int off, int len) {
        byte[] pkt = new byte[40 + len];
        int n = Pk.tcp(pkt, dstIp, srcIp, dstPort, srcPort, sndNxt, rcvNxt,
                flags, 65535, payload, off, len);
        svc.writeTun(pkt, n);
    }

    // ---------------------------------------------------------------- teardown

    void reset() {
        synchronized (lock) {
            if (closed) return;
            sendPacket(RST | ACK, null, 0, 0);
            closed = true;
            lock.notifyAll();
        }
        cleanup();
    }

    void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            lock.notifyAll();
        }
        cleanup();
    }

    private void cleanup() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        try {
            if (pfd != null) pfd.close();
        } catch (IOException ignored) {
        }
        svc.removeFlow(this);
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private void out(String s) {
        TunService.log(s);
    }
}
