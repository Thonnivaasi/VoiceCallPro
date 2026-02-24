package com.voicecallpro.app;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
public class BluetoothCallHelper {
    public static final UUID SERVICE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int CHUNK_SIZE = 128;
    private BluetoothSocket socket;
    private BluetoothServerSocket serverSocket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Consumer<byte[]> onAudioReceived;
    private Supplier<byte[]> readMicBuffer;
    public interface ConnectionListener {
        void onConnected();
        void onError(String msg);
    }
    public void setAudioCallback(Consumer<byte[]> onReceived, Supplier<byte[]> micReader) {
        this.onAudioReceived = onReceived;
        this.readMicBuffer = micReader;
    }
    public void startAsHost(BluetoothAdapter adapter, ConnectionListener listener) {
        new Thread(() -> {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("VoiceCallPro", SERVICE_UUID);
                socket = serverSocket.accept();
                try { serverSocket.close(); } catch (IOException e) {}
                serverSocket = null;
                setupStreams();
                startAudioThreads();
                listener.onConnected();
            } catch (IOException e) {
                listener.onError("Host error: " + e.getMessage());
            }
        }).start();
    }
    public void connectToDevice(BluetoothDevice device, ConnectionListener listener) {
        new Thread(() -> {
            try {
                socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
                socket.connect();
                setupStreams();
                startAudioThreads();
                listener.onConnected();
            } catch (IOException e) {
                listener.onError("Connect error: " + e.getMessage());
            }
        }).start();
    }
    private void setupStreams() throws IOException {
        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();
        running.set(true);
    }
    private void startAudioThreads() {
        new Thread(() -> {
            while (running.get()) {
                try {
                    byte[] buf = readMicBuffer.get();
                    if (buf == null || outputStream == null) continue;
                    int offset = 0;
                    while (offset < buf.length) {
                        int end = Math.min(offset + CHUNK_SIZE, buf.length);
                        outputStream.write(buf, offset, end - offset);
                        outputStream.flush();
                        offset = end;
                    }
                } catch (IOException e) { running.set(false); }
            }
        }).start();
        new Thread(() -> {
            byte[] buf = new byte[CHUNK_SIZE];
            while (running.get()) {
                try {
                    int read = inputStream.read(buf);
                    if (read > 0 && onAudioReceived != null) {
                        byte[] chunk = new byte[read];
                        System.arraycopy(buf, 0, chunk, 0, read);
                        onAudioReceived.accept(chunk);
                    }
                } catch (IOException e) { running.set(false); }
            }
        }).start();
    }
    public boolean isConnected() { return socket != null && socket.isConnected() && running.get(); }
    public void close() {
        running.set(false);
        try { if (socket != null) socket.close(); } catch (IOException e) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException e) {}
    }
}
