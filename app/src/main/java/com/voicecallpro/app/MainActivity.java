package com.voicecallpro.app;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
public class MainActivity extends AppCompatActivity {
    private static final int PERM_REQUEST = 100;
    private SwitchMaterial switchWifiBt, switchBtMic;
    private TextView tvStatus, tvSignal, tvTimer, tvRoomCodeDisplay;
    private TextView tvWifiLabel, tvWifiBtLabel;
    private Button btnHost, btnCall, btnMute, btnSpeaker, btnEndCall;
    private TextInputEditText etRoomCode;
    private View layoutWifiCode, layoutBtDevices, layoutCallControls;
    private Spinner spinnerDevices;
    private boolean inCall = false, muted = false, speakerOn = false;
    private Handler timerHandler = new Handler(Looper.getMainLooper());
    private int callSeconds = 0;
    private Runnable timerRunnable;
    private CallService callService;
    private boolean serviceBound = false;
    private final ServiceConnection serviceConn = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
            callService = ((CallService.LocalBinder) b).getService();
            serviceBound = true;
        }
        public void onServiceDisconnected(ComponentName n) { serviceBound = false; }
    };
    private BluetoothAdapter btAdapter;
    private List<BluetoothDevice> pairedDevices = new ArrayList<>();
    private List<String> pairedNames = new ArrayList<>();
    private WifiDiscoveryHelper wifiDiscovery;
    private String currentRoomCode;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupBluetooth();
        requestPerms();
        setupListeners();
        bindService(new Intent(this, CallService.class), serviceConn, Context.BIND_AUTO_CREATE);
    }
    private void bindViews() {
        switchWifiBt = findViewById(R.id.switchWifiBt);
        switchBtMic = findViewById(R.id.switchBtMic);
        tvStatus = findViewById(R.id.tvStatus);
        tvSignal = findViewById(R.id.tvSignal);
        tvTimer = findViewById(R.id.tvTimer);
        tvRoomCodeDisplay = findViewById(R.id.tvRoomCodeDisplay);
        tvWifiLabel = findViewById(R.id.tvWifiLabel);
        tvWifiBtLabel = findViewById(R.id.tvWifiBtLabel);
        btnHost = findViewById(R.id.btnHost);
        btnCall = findViewById(R.id.btnCall);
        btnMute = findViewById(R.id.btnMute);
        btnSpeaker = findViewById(R.id.btnSpeaker);
        btnEndCall = findViewById(R.id.btnEndCall);
        etRoomCode = findViewById(R.id.etRoomCode);
        layoutWifiCode = findViewById(R.id.layoutWifiCode);
        layoutBtDevices = findViewById(R.id.layoutBtDevices);
        layoutCallControls = findViewById(R.id.layoutCallControls);
        spinnerDevices = findViewById(R.id.spinnerDevices);
        // Initial state - both toggle labels grey (off)
        tvWifiLabel.setTextColor(getColor(R.color.text_secondary));
        tvWifiBtLabel.setTextColor(getColor(R.color.text_secondary));
    }
    private void setupBluetooth() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) btAdapter = bm.getAdapter();
    }
    private void setupListeners() {
        switchWifiBt.setOnCheckedChangeListener((b, checked) -> {
            int color = checked ? R.color.accent : R.color.text_secondary;
            tvWifiLabel.setTextColor(getColor(color));
            tvWifiBtLabel.setTextColor(getColor(color));
        });
        switchBtMic.setOnCheckedChangeListener((b, checked) -> {
            layoutWifiCode.setVisibility(checked ? View.GONE : View.VISIBLE);
            layoutBtDevices.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked) loadPairedDevices();
        });
        btnHost.setOnClickListener(v -> onHostClicked());
        btnCall.setOnClickListener(v -> onCallClicked());
        btnMute.setOnClickListener(v -> toggleMute());
        btnSpeaker.setOnClickListener(v -> toggleSpeaker());
        btnEndCall.setOnClickListener(v -> endCall());
    }
    private void loadPairedDevices() {
        pairedDevices.clear();
        pairedNames.clear();
        if (btAdapter == null) { toast("Bluetooth not available"); return; }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            toast("BT Connect permission needed"); return;
        }
        Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                pairedDevices.add(d);
                pairedNames.add(d.getName() != null ? d.getName() : d.getAddress());
            }
        }
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, pairedNames);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevices.setAdapter(a);
        if (pairedNames.isEmpty()) toast("No paired BT devices found");
    }
    private void onHostClicked() {
        if (switchBtMic.isChecked()) {
            if (btAdapter == null) { toast("No BT"); return; }
            setStatus("Waiting for BT connection...");
            startSvc();
            BluetoothCallHelper helper = new BluetoothCallHelper();
            if (serviceBound) callService.setBtHelper(helper);
            helper.startAsHost(btAdapter, new BluetoothCallHelper.ConnectionListener() {
                public void onConnected() {
                    runOnUiThread(() -> {
                        if (helper.isConnected()) {
                            setStatus("Connected (BT)");
                            if (serviceBound) callService.startBtCall(true);
                            onCallStarted();
                        } else setStatus("Waiting for guest...");
                    });
                }
                public void onError(String msg) { runOnUiThread(() -> setStatus("BT Error: " + msg)); }
            });
        } else {
            currentRoomCode = String.format("%06d", new Random().nextInt(1000000));
            tvRoomCodeDisplay.setText("Room: " + currentRoomCode);
            tvRoomCodeDisplay.setVisibility(View.VISIBLE);
            setStatus("Hosting - code: " + currentRoomCode);
            if (wifiDiscovery != null) wifiDiscovery.stop();
            wifiDiscovery = new WifiDiscoveryHelper();
            wifiDiscovery.startHostBeacon(currentRoomCode);
            startSvc();
            if (serviceBound) callService.startWifiCall(null, switchWifiBt.isChecked());
            onCallStarted();
        }
    }
    private void onCallClicked() {
        if (switchBtMic.isChecked()) {
            if (pairedDevices.isEmpty()) { toast("No paired device selected"); return; }
            BluetoothDevice device = pairedDevices.get(spinnerDevices.getSelectedItemPosition());
            setStatus("Connecting to " + pairedNames.get(spinnerDevices.getSelectedItemPosition()));
            startSvc();
            BluetoothCallHelper helper = new BluetoothCallHelper();
            if (serviceBound) callService.setBtHelper(helper);
            helper.connectToDevice(device, new BluetoothCallHelper.ConnectionListener() {
                public void onConnected() {
                    runOnUiThread(() -> {
                        setStatus("Connected (BT)");
                        if (serviceBound) callService.startBtCall(true);
                        onCallStarted();
                    });
                }
                public void onError(String msg) { runOnUiThread(() -> setStatus("Error: " + msg)); }
            });
        } else {
            String code = etRoomCode.getText() != null ? etRoomCode.getText().toString().trim() : "";
            if (code.length() != 6) { toast("Enter 6-digit room code"); return; }
            setStatus("Searching...");
            if (wifiDiscovery != null) wifiDiscovery.stop();
            wifiDiscovery = new WifiDiscoveryHelper();
            wifiDiscovery.searchForHost(code, new WifiDiscoveryHelper.DiscoveryListener() {
                public void onHostFound(InetAddress addr) {
                    runOnUiThread(() -> {
                        setStatus("Connected (WiFi)");
                        startSvc();
                        if (serviceBound) callService.startWifiCall(addr, switchWifiBt.isChecked());
                        onCallStarted();
                    });
                }
                public void onTimeout() { runOnUiThread(() -> setStatus("Host not found")); }
                public void onError(String msg) { runOnUiThread(() -> setStatus("Error: " + msg)); }
            });
        }
    }
    private void startSvc() {
        Intent i = new Intent(this, CallService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }
    private void onCallStarted() {
        inCall = true;
        btnEndCall.setVisibility(View.VISIBLE);
        layoutCallControls.setVisibility(View.VISIBLE);
        tvTimer.setVisibility(View.VISIBLE);
        callSeconds = 0;
        startTimer();
    }
    private void endCall() {
        inCall = false;
        stopTimer();
        tvTimer.setVisibility(View.GONE);
        btnEndCall.setVisibility(View.GONE);
        layoutCallControls.setVisibility(View.GONE);
        tvRoomCodeDisplay.setVisibility(View.GONE);
        setStatus("Idle");
        tvSignal.setText("Signal: --");
        if (wifiDiscovery != null) { wifiDiscovery.stop(); wifiDiscovery = null; }
        if (serviceBound) callService.stopCall();
        stopService(new Intent(this, CallService.class));
    }
    private void toggleMute() {
        muted = !muted;
        btnMute.setText(muted ? "Unmute" : "Mute");
        if (serviceBound) callService.setMuted(muted);
    }
    private void toggleSpeaker() {
        speakerOn = !speakerOn;
        btnSpeaker.setText(speakerOn ? "Earpiece" : "Speaker");
        if (serviceBound) callService.setSpeakerOn(speakerOn);
    }
    private void startTimer() {
        timerRunnable = new Runnable() {
            public void run() {
                callSeconds++;
                tvTimer.setText(String.format("%02d:%02d", callSeconds/60, callSeconds%60));
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.postDelayed(timerRunnable, 1000);
    }
    private void stopTimer() {
        if (timerRunnable != null) timerHandler.removeCallbacks(timerRunnable);
        tvTimer.setText("00:00");
    }
    private void setStatus(String s) { tvStatus.setText(s); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void requestPerms() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.MODIFY_AUDIO_SETTINGS);
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> needed = new ArrayList<>();
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
        }
        if (!needed.isEmpty()) ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERM_REQUEST);
    }
    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
    }
    @Override
    protected void onDestroy() {
        if (serviceBound) unbindService(serviceConn);
        super.onDestroy();
    }
}
