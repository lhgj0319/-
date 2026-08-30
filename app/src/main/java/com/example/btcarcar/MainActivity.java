package com.example.btcarcar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.widget.EditText;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.StorageService;

/**
 * 蓝牙小车遥控器（横屏）
 *
 * 经典蓝牙 SPP 连接 ESP32（BluetoothSerial）/ HC-05 / HC-06 等蓝牙串口模块，
 * 按住方向键持续发送指令，松开可选自动发送停止指令。
 * 适配 ESP32 状态机固件（firmware/esp32_car/esp32_car.ino）：
 * 固件收到指令后一直执行直到收到 S，因此 App 在松开按键和断开连接时都会补发停止指令。
 */
public class MainActivity extends Activity {

    /** 经典蓝牙 SPP 串口服务 UUID（HC-05 / HC-06 / JDY-31 通用，不要改） */
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /** ── 指令字：按你的小车固件修改这里即可 ── */
    private static final String CMD_FORWARD = "F";
    private static final String CMD_BACKWARD = "B";
    private static final String CMD_LEFT = "L";
    private static final String CMD_RIGHT = "R";
    private static final String CMD_STOP = "S";

    /** 按住按键时的连发间隔（毫秒） */
    private static final long REPEAT_INTERVAL_MS = 120;

    private static final int REQUEST_ENABLE_BT = 1001;
    private static final int REQUEST_PERMISSIONS = 1002;

    private BluetoothAdapter mAdapter;
    private BluetoothSocket mSocket;
    private OutputStream mOut;
    private InputStream mIn;
    private volatile boolean mConnected;

    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final Handler mRepeat = new Handler(Looper.getMainLooper());
    private Runnable mRepeatTask;

    private TextView mStatusText;
    private TextView mDeviceText;
    private TextView mLogText;
    private Button mConnectBtn;
    private CheckBox mAutoStopCheck;
    private EditText mCommandInput;

    private Model mVoskModel;
    private Recognizer mVoskRecognizer;
    private android.media.AudioRecord mAudioRecord;
    private volatile boolean mListening;

    private final List<BluetoothDevice> mDiscovered = new ArrayList<>();
    private boolean mDiscovering;

    /** 蓝牙搜索广播接收器 */
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device;
                if (Build.VERSION.SDK_INT >= 33) {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                }
                if (device != null && device.getName() != null && !mDiscovered.contains(device)) {
                    mDiscovered.add(device);
                    appendLog("发现设备: " + device.getName() + "  " + device.getAddress());
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                mDiscovering = false;
                appendLog("搜索完成");
                showDeviceDialog();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 控制期间保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        mStatusText = findViewById(R.id.statusText);
        mDeviceText = findViewById(R.id.deviceText);
        mLogText = findViewById(R.id.logText);
        mConnectBtn = findViewById(R.id.connectBtn);
        mAutoStopCheck = findViewById(R.id.autoStopCheck);
        mCommandInput = findViewById(R.id.commandInput);
        findViewById(R.id.sendCmdBtn).setOnClickListener(v -> sendTypedCommand());
        findViewById(R.id.voiceBtn).setOnClickListener(v -> startVoiceInput());

        mConnectBtn.setOnClickListener(v -> onConnectClicked());
        findViewById(R.id.btnUp).setOnTouchListener(holdSend(CMD_FORWARD));
        findViewById(R.id.btnDown).setOnTouchListener(holdSend(CMD_BACKWARD));
        findViewById(R.id.btnLeft).setOnTouchListener(holdSend(CMD_LEFT));
        findViewById(R.id.btnRight).setOnTouchListener(holdSend(CMD_RIGHT));
        findViewById(R.id.btnStop).setOnTouchListener(holdSend(CMD_STOP));

        mAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mAdapter == null) {
            appendLog("此设备不支持蓝牙");
            mConnectBtn.setEnabled(false);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mReceiver, filter);
        }

        initOfflineVoice();
    }

    private void initOfflineVoice() {
        StorageService.unpack(this, "model", "model",
                model -> {
                    mVoskModel = model;
                    try {
                        mVoskRecognizer = new Recognizer(mVoskModel, 16000);
                        mUi.post(() -> appendLog("离线语音已就绪"));
                    } catch (Exception e) {
                        mUi.post(() -> appendLog("离线语音初始化失败: " + e.getMessage()));
                    }
                },
                exception -> mUi.post(() -> appendLog("离线语音模型加载失败: " + exception.getMessage())));
    }

    // ─────────────── 连接流程 ───────────────

    private void onConnectClicked() {
        if (mConnected) {
            disconnect();
            return;
        }
        if (mAdapter == null) {
            toast("此设备不支持蓝牙");
            return;
        }
        if (!ensurePermissions()) return;
        if (!mAdapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
            return;
        }
        showDeviceDialog();
    }

    /** 按系统版本申请蓝牙相关运行时权限 */
    private boolean ensurePermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+：新蓝牙权限
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            // Android 6~11：搜索蓝牙设备需要定位权限
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (need.isEmpty()) return true;
        requestPermissions(need.toArray(new String[0]), REQUEST_PERMISSIONS);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1003) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceInput();
            } else {
                toast("需要麦克风权限才能使用语音控制");
            }
            return;
        }
        if (requestCode != REQUEST_PERMISSIONS) return;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                toast("需要蓝牙权限才能连接");
                return;
            }
        }
        onConnectClicked();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK) {
            showDeviceDialog();
        }
    }

    /** 弹出设备选择列表：已配对设备 + 搜索到的新设备 + 搜索入口 */
    private void showDeviceDialog() {
        if (mAdapter == null) return;
        final List<BluetoothDevice> devices = new ArrayList<>();
        final List<String> labels = new ArrayList<>();

        Set<BluetoothDevice> bonded = mAdapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                devices.add(d);
                labels.add((d.getName() == null ? "未知设备" : d.getName()) + "\n" + d.getAddress());
            }
        }
        for (BluetoothDevice d : mDiscovered) {
            if (!devices.contains(d)) {
                devices.add(d);
                labels.add((d.getName() == null ? "未知设备" : d.getName())
                        + "\n" + d.getAddress() + "（未配对）");
            }
        }
        devices.add(null);
        labels.add("🔍 搜索新设备…");

        new AlertDialog.Builder(this)
                .setTitle("选择蓝牙设备")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    BluetoothDevice dev = devices.get(which);
                    if (dev == null) {
                        startDiscovery();
                    } else {
                        connect(dev);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void startDiscovery() {
        if (mDiscovering || mAdapter == null || !mAdapter.isEnabled()) return;
        mDiscovered.clear();
        mDiscovering = true;
        appendLog("开始搜索附近蓝牙设备…");
        mAdapter.startDiscovery();
    }

    /** 后台线程连接蓝牙串口 */
    private void connect(final BluetoothDevice device) {
        setConnecting(true);
        new Thread(() -> {
            BluetoothSocket socket = null;
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                // 连接前停止搜索，避免拖慢连接
                if (mAdapter != null && mAdapter.isDiscovering()) {
                    mAdapter.cancelDiscovery();
                }
                socket.connect(); // 阻塞直到连接成功或失败
                mSocket = socket;
                mOut = socket.getOutputStream();
                mIn = socket.getInputStream();
                mConnected = true;
                mUi.post(() -> {
                    setConnecting(false);
                    mDeviceText.setText("设备: " + (device.getName() == null ? "未知设备" : device.getName()));
                    mStatusText.setText("已连接");
                    mStatusText.setTextColor(0xFF2E9E5B);
                    mConnectBtn.setText("断开连接");
                    appendLog("== 已连接 " + device.getName() + " ==");
                });
                startReadLoop();
            } catch (final Exception e) {
                mConnected = false;
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
                closeQuietly();
                mUi.post(() -> {
                    setConnecting(false);
                    mStatusText.setText("未连接");
                    mStatusText.setTextColor(0xFFD64545);
                    mConnectBtn.setText("连接蓝牙");
                    appendLog("连接失败: " + e.getMessage());
                });
            }
        }).start();
    }

    /** 后台线程循环读取小车返回的数据 */
    private void startReadLoop() {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[256];
            InputStream in = mIn;
            try {
                int n;
                while (mConnected && in != null && (n = in.read(buf)) > 0) {
                    final String text = new String(buf, 0, n, StandardCharsets.UTF_8);
                    mUi.post(() -> appendLog("收: " + text));
                }
            } catch (IOException ignored) {
                // 断开时 read 会抛异常，正常退出即可
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ─────────────── 发送与按键 ───────────────

    private void sendTypedCommand() {
        String command = mCommandInput.getText().toString().trim();
        if (command.length() == 0) { toast("请输入命令"); return; }
        send(command);
        appendLog("发: " + command);
    }

    private void startVoiceInput() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1003);
            return;
        }
        if (mVoskRecognizer == null) {
            toast("离线语音模型尚未加载");
            return;
        }
        if (mListening) {
            stopOfflineListening();
        } else {
            startOfflineListening();
        }
    }

    private void startOfflineListening() {
        int sampleRate = 16000;
        int bufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize <= 0) bufferSize = 4096;
        mAudioRecord = new android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                Math.max(bufferSize, 4096));
        mListening = true;
        mAudioRecord.startRecording();
        appendLog("离线语音监听中...");

        new Thread(() -> {
            byte[] buffer = new byte[2048];
            try {
                while (mListening && mAudioRecord != null) {
                    int count = mAudioRecord.read(buffer, 0, buffer.length);
                    if (count > 0 && mVoskRecognizer.acceptWaveForm(buffer, count)) {
                        handleOfflineResult(mVoskRecognizer.getResult());
                    }
                }
            } catch (Exception e) {
                mUi.post(() -> toast("离线录音失败: " + e.getMessage()));
            }
        }).start();

        mUi.postDelayed(() -> {
            if (mListening) stopOfflineListening();
        }, 6000);
    }

    private void stopOfflineListening() {
        mListening = false;
        if (mAudioRecord != null) {
            try { mAudioRecord.stop(); } catch (Exception ignored) { }
            try { mAudioRecord.release(); } catch (Exception ignored) { }
            mAudioRecord = null;
        }
        if (mVoskRecognizer != null) handleOfflineResult(mVoskRecognizer.getFinalResult());
    }

    private void handleOfflineResult(String json) {
        try {
            JSONObject object = new JSONObject(json);
            String text = object.optString("text", "").trim();
            if (text.length() == 0) return;
            mUi.post(() -> {
                mCommandInput.setText(text);
                send(text);
                appendLog("离线语音发: " + text);
            });
        } catch (Exception ignored) { }
    }

    private void send(String cmd) {
        if (!mConnected || mOut == null) {
            toast("请先连接蓝牙小车");
            return;
        }
        try {
            String payload = cmd.length() == 1 ? cmd : cmd + "\n";
            mOut.write(payload.getBytes(StandardCharsets.UTF_8));
            mOut.flush();
        } catch (IOException e) {
            toast("发送失败，连接已断开");
            disconnect();
        }
    }

    /** 按住连发、松开（可选）自动停止 的触摸监听 */
    private View.OnTouchListener holdSend(final String cmd) {
        return (v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    send(cmd);
                    mRepeatTask = () -> {
                        send(cmd);
                        mRepeat.postDelayed(mRepeatTask, REPEAT_INTERVAL_MS);
                    };
                    mRepeat.postDelayed(mRepeatTask, REPEAT_INTERVAL_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    stopRepeat();
                    if (mAutoStopCheck.isChecked()) send(CMD_STOP);
                    return true;
                default:
                    return false;
            }
        };
    }

    private void stopRepeat() {
        if (mRepeatTask != null) {
            mRepeat.removeCallbacks(mRepeatTask);
            mRepeatTask = null;
        }
    }

    // ─────────────── 断开与收尾 ───────────────

    private void disconnect() {
        stopRepeat();
        // 固件是状态机：不收到新指令就一直执行当前动作，
        // 断开前必须补发停止指令，否则小车会继续跑
        sendStopBeforeClose();
        mConnected = false;
        closeQuietly();
        mUi.post(() -> {
            mStatusText.setText("未连接");
            mStatusText.setTextColor(0xFFD64545);
            mDeviceText.setText("未选择设备");
            mConnectBtn.setText("连接蓝牙");
            appendLog("== 已断开 ==");
        });
    }

    /** 关闭连接前补发一次停止指令（针对“保持最后指令”型固件） */
    private void sendStopBeforeClose() {
        if (mOut == null) return;
        try {
            mOut.write(CMD_STOP.getBytes(StandardCharsets.UTF_8));
            mOut.flush();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly() {
        try {
            if (mIn != null) mIn.close();
        } catch (IOException ignored) {
        }
        try {
            if (mOut != null) mOut.close();
        } catch (IOException ignored) {
        }
        try {
            if (mSocket != null) mSocket.close();
        } catch (IOException ignored) {
        }
        mIn = null;
        mOut = null;
        mSocket = null;
    }

    private void setConnecting(boolean connecting) {
        mConnectBtn.setEnabled(!connecting);
        mConnectBtn.setText(connecting ? "连接中…" : (mConnected ? "断开连接" : "连接蓝牙"));
        if (connecting) {
            mStatusText.setText("连接中…");
            mStatusText.setTextColor(0xFFD9A406);
        }
    }

    /** 只在主线程调用 */
    private void appendLog(String line) {
        mLogText.append(line + "\n");
        String t = mLogText.getText().toString();
        if (t.length() > 3000) {
            mLogText.setText(t.substring(t.length() - 3000));
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopOfflineListening();
        if (mVoskRecognizer != null) {
            mVoskRecognizer.close();
            mVoskRecognizer = null;
        }
        if (mVoskModel != null) {
            mVoskModel.close();
            mVoskModel = null;
        }
        try {
            unregisterReceiver(mReceiver);
        } catch (Exception ignored) {
        }
        if (mAdapter != null && mAdapter.isDiscovering()) {
            mAdapter.cancelDiscovery();
        }
        stopRepeat();
        sendStopBeforeClose();
        mConnected = false;
        closeQuietly();
    }
}
