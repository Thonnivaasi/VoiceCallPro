package com.voicecallpro.app;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;
public class CallService extends Service {
    public static final String ACTION_END_CALL = "com.voicecallpro.END_CALL";
    private static final String CHANNEL_ID = "voicecall_channel";
    private static final int NOTIF_ID = 1;
    private static final int SAMPLE_RATE = 8000;
    private static final int BUFFER_SIZE = 256;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    public static final int PORT_AUDIO = 50005;
    public static final int PORT_DISCOVERY = 50006;
    private final IBinder binder = new LocalBinder();
    public enum CallMode { WIFI_ONLY, WIFI_BT_AUDIO, BT_ONLY, BT_BT_MIC }
    private boolean muted = false;
    private InetAddress peerAddress;
    private BluetoothCallHelper btHelper;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private AudioManager audioManager;
    private DatagramSocket audioSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    public class LocalBinder extends android.os.Binder {
        public CallService getService() { return CallService.this; }
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_END_CALL.equals(intent.getAction())) {
            stopCall(); stopSelf(); return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("Connecting..."));
        return START_STICKY;
    }
    public void setMuted(boolean m) { this.muted = m; }
    public void setSpeakerOn(boolean on) { audioManager.setSpeakerphoneOn(on); }
    public void startWifiCall(InetAddress peer, boolean useBtAudio) {
        this.peerAddress = peer;
        running.set(true);
        setupAudio(useBtAudio ? CallMode.WIFI_BT_AUDIO : CallMode.WIFI_ONLY);
        startWifiSend();
        startWifiReceive();
        startKeepAlive();
        updateNotification("In call (WiFi" + (useBtAudio ? " + BT Audio)" : ")"));
    }
    public void startBtCall(boolean useBtMic) {
        running.set(true);
        setupAudio(useBtMic ? CallMode.BT_BT_MIC : CallMode.BT_ONLY);
        updateNotification("In call (BT" + (useBtMic ? " + BT Mic)" : ")"));
    }
    public void setBtHelper(BluetoothCallHelper helper) {
        this.btHelper = helper;
        helper.setAudioCallback(
            data -> { if (audioTrack != null) audioTrack.write(data, 0, data.length); },
            this::readMicBuffer
        );
    }
    public void stopCall() {
        running.set(false);
        stopAudio();
        try { if (audioSocket != null) audioSocket.close(); } catch (Exception e) {}
        if (btHelper != null) { btHelper.close(); btHelper = null; }
        stopForeground(true);
    }
    private void setupAudio(CallMode mode) {
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if (mode == CallMode.WIFI_BT_AUDIO || mode == CallMode.BT_BT_MIC) {
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
        }
        // Use minimum buffer size to reduce AudioRecord internal buffering lag
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT);
        int micSrc = (mode == CallMode.BT_BT_MIC || mode == CallMode.WIFI_BT_AUDIO)
                ? MediaRecorder.AudioSource.DEFAULT : MediaRecorder.AudioSource.MIC;
        audioRecord = new AudioRecord(micSrc, SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT, minBuf);
        int minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT);
        audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE, CHANNEL_OUT,
                AUDIO_FORMAT, minTrack, AudioTrack.MODE_STREAM);
        audioRecord.startRecording();
        audioTrack.play();
    }
    private void stopAudio() {
        try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); audioRecord = null; } } catch (Exception e) {}
        try { if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); audioTrack = null; } } catch (Exception e) {}
        audioManager.setBluetoothScoOn(false);
        audioManager.stopBluetoothSco();
        audioManager.setMode(AudioManager.MODE_NORMAL);
    }
    private byte[] readMicBuffer() {
        byte[] buf = new byte[BUFFER_SIZE];
        if (audioRecord != null && !muted) audioRecord.read(buf, 0, buf.length);
        return buf;
    }
    private void startWifiSend() {
        new Thread(() -> {
            try {
                audioSocket = new DatagramSocket();
                audioSocket.setSoTimeout(0);
                byte[] buf = new byte[BUFFER_SIZE];
                while (running.get()) {
                    if (audioRecord == null || muted || peerAddress == null) { Thread.sleep(10); continue; }
                    int read = audioRecord.read(buf, 0, buf.length);
                    if (read > 0) audioSocket.send(new DatagramPacket(buf, read, peerAddress, PORT_AUDIO));
                }
            } catch (Exception e) {}
        }).start();
    }
    private void startWifiReceive() {
        new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket(PORT_AUDIO)) {
                s.setSoTimeout(0);
                byte[] buf = new byte[BUFFER_SIZE * 2];
                while (running.get()) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    s.receive(pkt);
                    if (peerAddress == null) peerAddress = pkt.getAddress();
                    byte[] data = new byte[pkt.getLength()];
                    System.arraycopy(pkt.getData(), 0, data, 0, data.length);
                    if (audioTrack != null) audioTrack.write(data, 0, data.length);
                }
            } catch (Exception e) {}
        }).start();
    }
    private void startKeepAlive() {
        new Thread(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(3000);
                    if (peerAddress != null && audioSocket != null) {
                        byte[] ping = "PING".getBytes();
                        audioSocket.send(new DatagramPacket(ping, ping.length, peerAddress, PORT_DISCOVERY));
                    }
                } catch (Exception e) {}
            }
        }).start();
    }
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "VoiceCall Pro", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
    private Notification buildNotification(String text) {
        Intent ei = new Intent(this, CallService.class);
        ei.setAction(ACTION_END_CALL);
        PendingIntent pi = PendingIntent.getService(this, 0, ei, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, openApp, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VoiceCall Pro").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(openPi)
                .setSilent(true).setOngoing(true)
                .addAction(android.R.drawable.ic_delete, "End Call", pi)
                .build();
    }
    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(text));
    }
    @Override public void onDestroy() { stopCall(); super.onDestroy(); }
}
