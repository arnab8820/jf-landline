package com.jio.calling.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.jio.calling.gateway.GatewayDiscoveryBroadcaster;
import com.jio.calling.gateway.GatewayServer;
import com.jio.calling.model.JioAccount;
import com.jio.calling.model.RouterInfo;
import com.jio.calling.provisioning.JioAccountStorage;
import com.jio.calling.provisioning.JioProvisioningClient;
import com.jio.calling.provisioning.JioRouterDiscovery;
import com.jio.calling.sip.JioCallListener;
import com.jio.calling.sip.JioRegistrationListener;
import com.jio.calling.sip.JioSipManager;
import com.jio.calling.telecom.JioTelecomManager;
import com.jio.calling.ui.InCallActivity;
import com.jio.calling.ui.IncomingCallActivity;
import com.jio.calling.ui.MainActivity;
import com.jio.calling.util.AppPreferences;

/**
 * Foreground Service maintaining 24/7 background presence for the Jio landline.
 * Holds a high-performance WifiLock to keep the Wi-Fi chip awake during phone sleep.
 */
public class JioSipService extends Service implements JioRegistrationListener, JioCallListener {
    private static final String TAG = "JioSipService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "jio_landline_service";

    public static final String ACTION_START = "com.jio.calling.action.START";
    public static final String ACTION_STOP = "com.jio.calling.action.STOP";
    public static final String ACTION_MAKE_CALL = "com.jio.calling.action.MAKE_CALL";
    public static final String ACTION_HANGUP_CALL = "com.jio.calling.action.HANGUP_CALL";
    public static final String ACTION_ANSWER_CALL = "com.jio.calling.action.ANSWER_CALL";
    public static final String ACTION_SWITCH_WIFI = "com.jio.calling.action.SWITCH_WIFI";
    public static final String EXTRA_PHONE_NUMBER = "extra_phone_number";
    public static final String EXTRA_SSID = "extra_ssid";
    private static final String ACTION_KEEPALIVE_ALARM = "com.jio.calling.action.KEEPALIVE_ALARM";

    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock callWakeLock;
    private PowerManager.WakeLock incomingWakeLock;
    private PowerManager.WakeLock keepAliveWakeLock;
    private JioSipManager sipManager;
    private GatewayServer gatewayServer;
    private GatewayDiscoveryBroadcaster discoveryBroadcaster;
    private BroadcastReceiver networkReceiver;
    private PendingIntent keepAlivePendingIntent;
    private BroadcastReceiver keepAliveReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Creating JioSipService");

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Starting Jio Landline..."));

        initLocks();
        acquireWifiLockIfConnected();

        sipManager = JioSipManager.getInstance(this);
        sipManager.initialize();
        sipManager.addRegistrationListener(this);
        sipManager.addCallListener(this);

        registerNetworkMonitor();
        startKeepAliveAlarm();

        // Start Companion Gateway Server & Discovery
        try {
            gatewayServer = new GatewayServer(this);
            gatewayServer.start();
            discoveryBroadcaster = new GatewayDiscoveryBroadcaster(this);
            discoveryBroadcaster.start();
        } catch (Exception e) {
            Log.e(TAG, "Error starting Gateway server: " + e.getMessage(), e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        safeStartForeground(NOTIFICATION_ID, buildNotification("Starting Jio Landline..."));

        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_SWITCH_WIFI.equals(action)) {
                String targetSsid = intent.getStringExtra(EXTRA_SSID);
                if (targetSsid == null || targetSsid.isEmpty()) {
                    targetSsid = "Arnab-Home-JF";
                }
                boolean switched = switchToWifi(targetSsid);
                Log.i(TAG, "ACTION_SWITCH_WIFI target=" + targetSsid + " result=" + switched);
                return START_STICKY;
            } else if (ACTION_MAKE_CALL.equals(action)) {
                String number = intent.getStringExtra(EXTRA_PHONE_NUMBER);
                if (number != null && !number.isEmpty()) {
                    acquireIncomingWakeLock();
                    sipManager.makeCall(number);
                }
                return START_STICKY;
            } else if (ACTION_HANGUP_CALL.equals(action)) {
                Log.i(TAG, "ACTION_HANGUP_CALL received");
                sipManager.hangupCall();
                return START_STICKY;
            } else if (ACTION_ANSWER_CALL.equals(action)) {
                Log.i(TAG, "ACTION_ANSWER_CALL received");
                sipManager.answerCall();
                return START_STICKY;
            } else if ("com.jio.calling.TEST_INCOMING".equals(action)) {
                String number = intent.getStringExtra("REMOTE_NUMBER");
                if (number == null || number.isEmpty()) number = "09876543210";
                String name = intent.getStringExtra("REMOTE_NAME");
                if (name == null || name.isEmpty()) name = "Telecom Test Caller";
                Log.i(TAG, "Testing Telecom incoming call from: " + number + " (" + name + ")");
                com.jio.calling.telecom.JioTelecomManager.addNewIncomingCall(this, number, name);
                return START_STICKY;
            } else if ("com.jio.calling.TEST_OUTGOING".equals(action)) {
                String number = intent.getStringExtra("NUMBER");
                if (number == null || number.isEmpty()) number = "199";
                Log.i(TAG, "Testing Telecom outgoing call to: " + number);
                com.jio.calling.telecom.JioTelecomManager.placeCall(this, number);
                return START_STICKY;
            }
        }

        // Verify account & register only if not already registered
        if (!sipManager.isRegistered()) {
            new Thread(this::loadOrDiscoverAccount).start();
        }

        return START_STICKY; // Restart service if system kills it for memory
    }

    private void loadOrDiscoverAccount() {
        loadOrDiscoverAccount(false);
    }

    private synchronized void loadOrDiscoverAccount(boolean force) {

        // Discover router on Wi-Fi to verify target extension MAC
        RouterInfo info = JioRouterDiscovery.discover(this);
        String targetMac = (info != null && info.getExtnMacAddress() != null && !info.getExtnMacAddress().isEmpty())
                ? info.getExtnMacAddress()
                : JioSipManager.getLocalWifiMacAddress(this);

        if (!force) {
            JioAccount account = JioAccountStorage.load(this);
            if (account != null && account.isValid()) {
                boolean macMatches = (targetMac == null || targetMac.isEmpty()
                        || targetMac.equalsIgnoreCase(account.getVirtualMac()));
                if (macMatches) {
                    Log.i(TAG, "Loaded saved account: " + account.getUsername() + " (MAC: " + account.getVirtualMac() + ")");
                    sipManager.registerAccount(account);
                    return;
                } else {
                    Log.i(TAG, "Saved account MAC (" + account.getVirtualMac() + ") does not match target MAC (" + targetMac + "). Re-provisioning...");
                }
            }
        }

        // Auto-provision via router
        if (info == null || info.getHost() == null || info.getHost().isEmpty()) {
            Log.e(TAG, "No router discovered! Cannot proceed with provisioning.");
            return;
        }

        String host = info.getHost();
        try {
            Log.i(TAG, "Auto-provisioning via Zero-OTP bypass from " + host + " using MAC: " + targetMac);
            JioAccount newAccount = JioProvisioningClient.acquireCredentialsZeroOtp(host, targetMac);
            if (newAccount != null) {
                if (info != null && info.getMsisdn() != null) {
                    newAccount.setMsisdn(info.getMsisdn());
                }
                JioAccountStorage.save(this, newAccount);
                sipManager.registerAccount(newAccount);
            }
        } catch (Exception e) {
            Log.e(TAG, "Auto-provisioning failed: " + e.getMessage(), e);
        }
    }

    private boolean isRouterReachable(String host) {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress(host, 5068), 1000);
            s.close();
            return true;
        } catch (Exception ignored) {
            try {
                java.net.Socket s = new java.net.Socket();
                s.connect(new java.net.InetSocketAddress(host, 8080), 1000);
                s.close();
                return true;
            } catch (Exception ignored2) {
                return false;
            }
        }
    }

    public boolean switchToWifi(String targetSsid) {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return false;

            android.net.wifi.WifiInfo curInfo = wm.getConnectionInfo();
            if (curInfo != null && curInfo.getSSID() != null) {
                String curSsid = curInfo.getSSID().replace("\"", "");
                if (curSsid.equalsIgnoreCase(targetSsid.replace("\"", ""))) {
                    Log.i(TAG, "Already connected to target Wi-Fi: " + curSsid);
                    return true;
                }
            }

            java.util.List<android.net.wifi.WifiConfiguration> configs = wm.getConfiguredNetworks();
            if (configs != null) {
                for (android.net.wifi.WifiConfiguration c : configs) {
                    if (c.SSID != null && c.SSID.replace("\"", "").equalsIgnoreCase(targetSsid.replace("\"", ""))) {
                        Log.i(TAG, "Switching Wi-Fi to network ID " + c.networkId + " (" + c.SSID + ")");
                        wm.disconnect();
                        boolean enabled = wm.enableNetwork(c.networkId, true);
                        boolean reconnected = wm.reconnect();
                        Log.i(TAG, "enableNetwork=" + enabled + ", reconnect=" + reconnected);
                        return enabled;
                    }
                }
            }
            Log.w(TAG, "Target Wi-Fi SSID not found in configured networks: " + targetSsid);
        } catch (Exception e) {
            Log.e(TAG, "Error switching Wi-Fi: " + e.getMessage(), e);
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private void initLocks() {
        // Wi-Fi lock to ensure connection persists while on Wi-Fi
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "jio:wifi_keepalive");
                wifiLock.setReferenceCounted(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create WifiLock: " + e.getMessage());
        }

        // State-driven WakeLocks (NO permanent 24/7 partial wake lock)
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                // 1. Call-scoped wake lock: held strictly while a VoIP call is connecting or active
                callWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jio:call_active");
                callWakeLock.setReferenceCounted(false);

                // 2. Incoming call wake lock: turns screen on and wakes CPU for incoming call UI
                int incomingFlags = PowerManager.PARTIAL_WAKE_LOCK;
                try {
                    incomingFlags |= PowerManager.ACQUIRE_CAUSES_WAKEUP;
                } catch (Exception ignored) {}
                incomingWakeLock = pm.newWakeLock(incomingFlags, "jio:incoming_call");
                incomingWakeLock.setReferenceCounted(false);

                // 3. Keepalive wake lock: held briefly (2s) only when AlarmManager heartbeat fires
                keepAliveWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jio:keepalive_heartbeat");
                keepAliveWakeLock.setReferenceCounted(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create WakeLocks: " + e.getMessage());
        }
    }

    private void acquireWifiLockIfConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = cm != null ? cm.getActiveNetworkInfo() : null;
            if (activeNetwork != null && activeNetwork.isConnected() && activeNetwork.getType() == ConnectivityManager.TYPE_WIFI) {
                acquireWifiLock();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking Wi-Fi state: " + e.getMessage());
        }
    }

    private void acquireWifiLock() {
        try {
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
                Log.i(TAG, "Acquired WIFI_MODE_FULL lock");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire WifiLock: " + e.getMessage());
        }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                Log.i(TAG, "Released WifiLock");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to release WifiLock: " + e.getMessage());
        }
    }

    private void acquireCallWakeLock() {
        try {
            if (callWakeLock != null && !callWakeLock.isHeld()) {
                callWakeLock.acquire();
                Log.i(TAG, "Acquired Call WakeLock for active call");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error acquiring Call WakeLock: " + e.getMessage());
        }
    }

    private void releaseCallWakeLock() {
        try {
            if (callWakeLock != null && callWakeLock.isHeld()) {
                callWakeLock.release();
                Log.i(TAG, "Released Call WakeLock");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing Call WakeLock: " + e.getMessage());
        }
        try {
            if (incomingWakeLock != null && incomingWakeLock.isHeld()) {
                incomingWakeLock.release();
                Log.i(TAG, "Released Incoming WakeLock");
            }
        } catch (Exception ignored) {}
    }

    private void acquireIncomingWakeLock() {
        try {
            if (incomingWakeLock != null) {
                incomingWakeLock.acquire(15000); // 15s safety timeout
                Log.i(TAG, "Acquired incomingWakeLock (15s timeout)");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire incomingWakeLock: " + e.getMessage());
        }
    }

    private void startKeepAliveAlarm() {
        stopKeepAliveAlarm();

        Intent intent = new Intent(ACTION_KEEPALIVE_ALARM);
        intent.setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        keepAlivePendingIntent = PendingIntent.getBroadcast(this, 0, intent, flags);

        keepAliveReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (keepAliveWakeLock != null) {
                    try {
                        keepAliveWakeLock.acquire(2000);
                    } catch (Exception ignored) {}
                }
                if (sipManager != null) {
                    sipManager.handleKeepAlive();
                }
                scheduleNextKeepAliveAlarm();
            }
        };

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(keepAliveReceiver, new IntentFilter(ACTION_KEEPALIVE_ALARM), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(keepAliveReceiver, new IntentFilter(ACTION_KEEPALIVE_ALARM));
        }
        scheduleNextKeepAliveAlarm();
    }

    private void scheduleNextKeepAliveAlarm() {
        if (keepAlivePendingIntent == null) return;
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        long intervalMs = 45000L; // 45s heartbeat
        long triggerAt = SystemClock.elapsedRealtime() + intervalMs;

        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, keepAlivePendingIntent);
            } else if (Build.VERSION.SDK_INT >= 19) {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, keepAlivePendingIntent);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, keepAlivePendingIntent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to schedule keepalive alarm: " + e.getMessage());
        }
    }

    private void stopKeepAliveAlarm() {
        if (keepAlivePendingIntent != null) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                try {
                    am.cancel(keepAlivePendingIntent);
                } catch (Exception ignored) {}
            }
            keepAlivePendingIntent = null;
        }
        if (keepAliveReceiver != null) {
            try {
                unregisterReceiver(keepAliveReceiver);
            } catch (Exception ignored) {}
            keepAliveReceiver = null;
        }
    }

    private void releaseLocks() {
        releaseCallWakeLock();
        releaseWifiLock();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Jio Landline Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintains background connection to JioFiber router");
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String statusText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Jio Landline")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        safeStartForeground(NOTIFICATION_ID, buildNotification(text));
    }

    private void safeStartForeground(int id, Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                int type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL;
                if (Build.VERSION.SDK_INT >= 34) { // Android 14+
                    if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        type |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                    }
                }
                startForeground(id, notification, type);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Could not startForeground with types: " + e.getMessage());
            }
        }
        startForeground(id, notification);
    }

    private void registerNetworkMonitor() {
        networkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo activeNetwork = cm != null ? cm.getActiveNetworkInfo() : null;
                boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();

                if (isConnected) {
                    String typeName = activeNetwork.getTypeName();
                    Log.i(TAG, "Network connected (type=" + typeName + "). Re-checking registration...");
                    if (activeNetwork.getType() == ConnectivityManager.TYPE_WIFI) {
                        acquireWifiLock();
                    } else {
                        releaseWifiLock();
                    }
                    sipManager.handleNetworkChange();
                    new Thread(JioSipService.this::loadOrDiscoverAccount).start();
                } else {
                    Log.w(TAG, "Network disconnected. SIP offline.");
                    releaseWifiLock();
                    updateNotification("Network disconnected");
                }
            }
        };

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        }
    }

    // --- JioRegistrationListener ---
    @Override
    public void onRegistrationProgress() {
        updateNotification("Connecting to Jio router...");
    }

    @Override
    public void onRegistered() {
        String number = sipManager.getCurrentAccount() != null ? sipManager.getCurrentAccount().getMsisdn() : "";
        updateNotification("Connected: " + number);

        // Auto-register Telecom PhoneAccount if enabled on Android 6.0+
        if (JioTelecomManager.isSupported() && AppPreferences.isTelecomEnabled(this)) {
            JioTelecomManager.registerPhoneAccount(this);
        }
    }

    @Override
    public void onRegistrationFailed(int statusCode, String reason) {
        updateNotification("Registration failed (" + statusCode + ")");
        if (statusCode == 403) {
            Log.w(TAG, "Registration 403 (credentials rotated/expired). Triggering auto re-provisioning...");
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {}
                loadOrDiscoverAccount(true);
            }).start();
        }
    }

    @Override
    public void onUnregistered() {
        updateNotification("Disconnected");
    }

    // --- JioCallListener ---
    @Override
    public void onCallIncoming(String remoteNumber, String remoteDisplayName) {
        Log.i(TAG, "Incoming call received: " + remoteNumber + " (" + remoteDisplayName + ")");

        acquireIncomingWakeLock();

        // If Telecom integration and System In-Call UI is enabled on Android 6.0+, route through Telecom
        if (JioTelecomManager.isSupported() && AppPreferences.isTelecomEnabled(this) && AppPreferences.isUseTelecomUiEnabled(this)) {
            boolean handled = JioTelecomManager.addNewIncomingCall(this, remoteNumber, remoteDisplayName);
            if (handled) {
                Log.i(TAG, "Incoming call routed via Android Telecom system UI");
                return;
            }
        }

        // Default / Legacy / KitKat UI: launch IncomingCallActivity overlay
        Intent incoming = new Intent(this, IncomingCallActivity.class);
        incoming.putExtra("REMOTE_NUMBER", remoteNumber);
        incoming.putExtra("REMOTE_NAME", remoteDisplayName);
        incoming.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(incoming);
    }

    @Override
    public void onCallConnecting() {
        acquireCallWakeLock();
        if (com.jio.calling.telecom.JioTelecomManager.isSupported() && AppPreferences.isUseTelecomUiEnabled(this)) {
            Log.i(TAG, "onCallConnecting: System Telecom In-Call UI is enabled, skipping InCallActivity");
            return;
        }
        Intent inCall = new Intent(this, InCallActivity.class);
        inCall.putExtra(InCallActivity.EXTRA_NUMBER, sipManager.getActiveCallNumber());
        inCall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(inCall);
    }

    @Override public void onCallRinging() {
        acquireCallWakeLock();
    }

    @Override public void onCallEstablished() {
        acquireCallWakeLock();
    }

    @Override public void onCallTerminated(String reason) {
        Log.i(TAG, "Call terminated in service: " + reason);
        releaseCallWakeLock();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Destroying JioSipService");

        if (discoveryBroadcaster != null) {
            discoveryBroadcaster.stop();
            discoveryBroadcaster = null;
        }
        if (gatewayServer != null) {
            gatewayServer.stop();
            gatewayServer = null;
        }

        stopKeepAliveAlarm();

        if (networkReceiver != null) {
            unregisterReceiver(networkReceiver);
        }
        if (sipManager != null) {
            sipManager.removeRegistrationListener(this);
            sipManager.removeCallListener(this);
            sipManager.unregisterAccount();
        }
        releaseLocks();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

