package com.voicecallpro.app;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
public class WifiDiscoveryHelper {
    private static final int PORT = 50006;
    private static final int TIMEOUT_MS = 8000;
    private final AtomicBoolean running = new AtomicBoolean(false);
    public interface DiscoveryListener {
        void onHostFound(InetAddress address);
        void onTimeout();
        void onError(String msg);
    }
    public void startHostBeacon(String roomCode) {
        running.set(true);
        new Thread(() -> {
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setBroadcast(true);
                String msg = "VCPHOST:" + roomCode;
                byte[] data = msg.getBytes();
                while (running.get()) {
                    Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
                    while (ifaces != null && ifaces.hasMoreElements()) {
                        NetworkInterface iface = ifaces.nextElement();
                        if (iface.isLoopback() || !iface.isUp()) continue;
                        for (java.net.InterfaceAddress ia : iface.getInterfaceAddresses()) {
                            InetAddress broadcast = ia.getBroadcast();
                            if (broadcast == null) continue;
                            sock.send(new DatagramPacket(data, data.length, broadcast, PORT));
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e) {}
        }).start();
    }
    public void searchForHost(String roomCode, DiscoveryListener listener) {
        running.set(true);
        new Thread(() -> {
            try (DatagramSocket sock = new DatagramSocket(PORT)) {
                sock.setBroadcast(true);
                sock.setSoTimeout(TIMEOUT_MS);
                byte[] buf = new byte[256];
                long start = System.currentTimeMillis();
                while (running.get()) {
                    try {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        sock.receive(pkt);
                        String msg = new String(pkt.getData(), 0, pkt.getLength());
                        if (msg.equals("VCPHOST:" + roomCode)) {
                            listener.onHostFound(pkt.getAddress()); return;
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        if (System.currentTimeMillis() - start > TIMEOUT_MS) {
                            listener.onTimeout(); return;
                        }
                    }
                }
            } catch (Exception e) { listener.onError(e.getMessage()); }
        }).start();
    }
    public void stop() { running.set(false); }
}
