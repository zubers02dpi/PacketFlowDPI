package dev.packetflow.dpi;

import android.app.Service;
import android.content.Intent;
import android.net.VpnService;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Our own VPN: grabs every packet of the device, terminates TCP and UDP here,
 * and re-creates the connections with our strategy applied to the first payload.
 */
public class TunService extends VpnService {

    static final String TAG = "PACKETFLOW";

    interface Ui {
        void onLog(String line);
    }

    static volatile Ui ui;
    static volatile TunService current;
    static volatile String strategySpec = Strategy.DEFAULT;

    private static volatile long bytesUp, bytesDown, connections;

    static boolean running() {
        return current != null;
    }

    static long bytesUp() {
        return bytesUp;
    }

    static long bytesDown() {
        return bytesDown;
    }

    static long connections() {
        return connections;
    }

    static void log(String s) {
        Log.i(TAG, s);
        Ui u = ui;
        if (u != null) {
            try {
                u.onLog(s);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Switch the strategy live (new connections only). */
    static void setStrategy(String spec) {
        strategySpec = spec;
        TunService s = current;
        if (s != null) s.strategy = new Strategy(spec);
    }

    private ParcelFileDescriptor tun;
    private FileInputStream tin;
    private FileOutputStream tout;
    private final Object writeLock = new Object();
    private final Map<String, TcpFlow> flows = new ConcurrentHashMap<>();
    private final Map<String, UdpFlow> udp = new ConcurrentHashMap<>();
    private volatile boolean stop;
    private volatile Strategy strategy = new Strategy(strategySpec);

    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        strategy = new Strategy(strategySpec);
        if (tun == null) startVpn();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    Strategy strategy() {
        return strategy;
    }

    boolean protectSocket(Socket s) {
        try {
            return protect(s);
        } catch (Throwable t) {
            return false;
        }
    }

    private volatile boolean warnedProtect;

    boolean warnedProtect() {
        if (warnedProtect) return true;
        warnedProtect = true;
        return false;
    }

    private void startVpn() {
        try {
            Builder b = new Builder();
            b.setSession("PacketFlowDPI");
            b.addAddress("10.111.0.2", 32);
            try {
                // our own sockets must never re-enter the tunnel
                b.addDisallowedApplication(getPackageName());
            } catch (Throwable ignored) {
            }
            b.addDnsServer("1.1.1.1");
            b.addDnsServer("8.8.8.8");
            b.addRoute("0.0.0.0", 0);
            try {
                b.addRoute("::", 0);
            } catch (Throwable ignored) {
            }
            b.setMtu(1500);
            b.setBlocking(true);
            tun = b.establish();
        } catch (Throwable t) {
            log("не удалось поднять VPN: " + t);
            tun = null;
        }
        if (tun == null) {
            stopSelf();
            return;
        }
        tin = new FileInputStream(tun.getFileDescriptor());
        tout = new FileOutputStream(tun.getFileDescriptor());

        Thread r = new Thread(this::readLoop, "tun-read");
        r.setDaemon(true);
        r.start();
        Thread h = new Thread(this::housekeeping, "tun-house");
        h.setDaemon(true);
        h.start();

        log("VPN поднят, стратегия: " + strategy.spec);
    }

    // ------------------------------------------------------------------ tun input

    private void readLoop() {
        byte[] buf = new byte[4096];
        while (!stop) {
            int n;
            try {
                n = tin.read(buf);
            } catch (IOException e) {
                break;
            }
            if (n <= 0) continue;
            bytesDown += n;
            try {
                handlePacket(buf, n);
            } catch (Throwable t) {
                // never kill the tunnel because of one bad packet
            }
        }
        log("tun закрыт");
    }

    private void handlePacket(byte[] b, int n) {
        int ver = (b[0] & 0xF0) >> 4;
        if (ver != 4) return;                       // IPv6: no route for it, OS falls back to v4

        int ihl = (b[0] & 0x0F) * 4;
        int total = Pk.u16(b, 2);
        if (total <= ihl || total > n) total = n;

        int frag = Pk.u16(b, 6);
        if ((frag & 0x1FFF) != 0) return;           // fragments: not supported

        int proto = b[9] & 0xFF;
        byte[] src = Arrays.copyOfRange(b, 12, 16);
        byte[] dst = Arrays.copyOfRange(b, 16, 20);

        if (proto == 6) handleTcp(b, ihl, total, src, dst);
        else if (proto == 17) handleUdp(b, ihl, total, src, dst);
    }

    private void handleTcp(byte[] b, int off, int total, byte[] src, byte[] dst) {
        if (off + 20 > total) return;
        int sport = Pk.u16(b, off);
        int dport = Pk.u16(b, off + 2);
        long seq = Pk.u32(b, off + 4);
        long ack = Pk.u32(b, off + 8);
        int doff = ((b[off + 12] & 0xF0) >> 4) * 4;
        int flags = b[off + 13] & 0xFF;
        int wnd = Pk.u16(b, off + 14);
        int dataOff = off + doff;
        int len = Math.max(0, total - dataOff);

        String key = key(src, sport, dst, dport);
        boolean isSyn = (flags & TcpFlow.SYN) != 0;

        if (isSyn) {
            if (flows.size() > 300) return;
            TcpFlow flow = new TcpFlow(this, src, sport, dst, dport);
            TcpFlow old = flows.put(key, flow);
            if (old != null) old.close();
            connections++;
            flow.onSyn(seq, wnd);
            return;
        }

        TcpFlow flow = flows.get(key);
        if (flow == null) {
            sendRst(src, sport, dst, dport, seq, ack, len);
            return;
        }
        flow.onSegment(seq, ack, flags, wnd, b, dataOff, len);
    }

    private void sendRst(byte[] src, int sport, byte[] dst, int dport,
                         long seq, long ack, int len) {
        byte[] pkt = new byte[40];
        int n = Pk.tcp(pkt, dst, src, dport, sport,
                ack, (seq + len) & 0xFFFFFFFFL, TcpFlow.RST | TcpFlow.ACK, 0, null, 0, 0);
        writeTun(pkt, n);
    }

    private void handleUdp(byte[] b, int off, int total, byte[] src, byte[] dst) {
        if (off + 8 > total) return;
        int sport = Pk.u16(b, off);
        int dport = Pk.u16(b, off + 2);
        int len = total - (off + 8);
        if (len <= 0) return;

        String key = key(src, sport, dst, dport);
        UdpFlow f = udp.get(key);
        if (f == null) {
            try {
                DatagramSocket s = new DatagramSocket();
                if (!protect(s)) {
                    s.close();
                    return;
                }
                s.connect(InetAddress.getByAddress(dst), dport);
                f = new UdpFlow(this, s, src, sport, dst, dport);
                udp.put(key, f);
                f.start();
            } catch (Exception e) {
                return;
            }
        }
        f.last = System.currentTimeMillis();
        try {
            f.sock.send(new DatagramPacket(b, off + 8, len));
        } catch (Exception ignored) {
        }
    }

    void writeTun(byte[] pkt, int len) {
        if (tout == null || stop) return;
        try {
            synchronized (writeLock) {
                tout.write(pkt, 0, len);
            }
            bytesUp += len;
        } catch (IOException ignored) {
        }
    }

    void removeFlow(TcpFlow f) {
        flows.remove(key(f.srcIp, f.srcPort, f.dstIp, f.dstPort), f);
    }

    void countConnection(String peer) {
        if (peer.endsWith(":853")) return;      // private DNS chatter: keep the log readable
        log("→ " + peer);
    }

    private static String key(byte[] srcIp, int sport, byte[] dstIp, int dport) {
        return TcpFlow.ip2s(srcIp) + ":" + sport + ">" + TcpFlow.ip2s(dstIp) + ":" + dport;
    }

    // ------------------------------------------------------------------ housekeeping

    private void housekeeping() {
        while (!stop) {
            try {
                Thread.sleep(15000);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            for (TcpFlow f : new ArrayList<>(flows.values())) {
                if (f.isClosed() || now - f.lastActive() > 180000) f.close();
            }
            for (Map.Entry<String, UdpFlow> e : new ArrayList<>(udp.entrySet())) {
                UdpFlow f = e.getValue();
                if (now - f.last > 120000) {
                    f.sock.close();
                    udp.remove(e.getKey());
                }
            }
        }
    }

    @Override
    public void onRevoke() {
        stopVpn();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    private void stopVpn() {
        stop = true;
        for (TcpFlow f : new ArrayList<>(flows.values())) f.close();
        flows.clear();
        for (UdpFlow f : new ArrayList<>(udp.values())) f.sock.close();
        udp.clear();
        try {
            if (tin != null) tin.close();
        } catch (IOException ignored) {
        }
        try {
            if (tout != null) tout.close();
        } catch (IOException ignored) {
        }
        try {
            if (tun != null) tun.close();
        } catch (IOException ignored) {
        }
        tun = null;
        if (current == this) current = null;
        log("VPN остановлен");
    }

    static String human(long bytes) {
        if (bytes >= 1048576) return String.format(Locale.US, "%.1f МБ", bytes / 1048576.0);
        if (bytes >= 1024) return String.format(Locale.US, "%.0f КБ", bytes / 1024.0);
        return bytes + " Б";
    }

    // ------------------------------------------------------------------ UDP

    static final class UdpFlow {
        final TunService svc;
        final DatagramSocket sock;
        final byte[] srcIp, dstIp;
        final int srcPort, dstPort;
        volatile long last = System.currentTimeMillis();

        UdpFlow(TunService svc, DatagramSocket sock, byte[] srcIp, int srcPort,
                byte[] dstIp, int dstPort) {
            this.svc = svc;
            this.sock = sock;
            this.srcIp = srcIp;
            this.srcPort = srcPort;
            this.dstIp = dstIp;
            this.dstPort = dstPort;
        }

        void start() {
            Thread t = new Thread(this::loop, "udp-flow");
            t.setDaemon(true);
            t.start();
        }

        private void loop() {
            byte[] buf = new byte[2048];
            while (!svc.stop) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    last = System.currentTimeMillis();
                    byte[] out = new byte[28 + p.getLength()];
                    int n = Pk.udp(out, dstIp, srcIp, dstPort, srcPort,
                            p.getData(), p.getOffset(), p.getLength());
                    svc.writeTun(out, n);
                } catch (Exception e) {
                    return;
                }
            }
        }
    }
}
