package com.seloomwarp.vpn;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.provider.Settings;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@CapacitorPlugin(name = "VpnBridge")
public class VpnBridgePlugin extends Plugin {
    private PluginCall pendingCall;
    private Intent pendingIntent;

    /**
     * Parses a V2Ray share URI into a Sing-box profile. This is deliberately
     * separate from connect(): parsing a profile does not claim that a tunnel
     * is running or bypass Android's VPN permission/lifecycle requirements.
     */
    @PluginMethod
    public void parseProxyProfile(PluginCall call) {
        try {
            String link = call.getString("link", "");
            SingBoxProfileParser.Profile profile = SingBoxProfileParser.parse(link);
            JSObject result = new JSObject();
            result.put("protocol", profile.protocol);
            result.put("name", profile.name);
            result.put("server", profile.server);
            result.put("port", profile.port);
            result.put("configJson", profile.configJson);
            call.resolve(result);
        } catch (Throwable error) {
            call.reject("Unable to parse proxy profile: " + error.getMessage(),
                    error instanceof Exception ? (Exception) error : new Exception(error));
        }
    }

    private static final String SECURE_PROFILE_PREFS = "nexalink_secure_profiles";
    private static final String SECURE_PROFILE_KEY = "encrypted_profiles";
    private static final String KEYSTORE_ALIAS = "NexaLinkProxyProfilesV1";

    /** Stores imported proxy links encrypted with an Android Keystore AES-GCM key. */
    @PluginMethod
    public void saveSecureProxyProfiles(PluginCall call) {
        try {
            String json = call.getString("profilesJson", "[]");
            if (json == null || json.length() > 2_000_000) throw new IllegalArgumentException("Proxy profile data is too large");
            new org.json.JSONArray(json);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateProfileKey());
            byte[] encrypted = cipher.doFinal(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            boolean saved = getContext().getSharedPreferences(SECURE_PROFILE_PREFS, android.content.Context.MODE_PRIVATE)
                    .edit().putString(SECURE_PROFILE_KEY, Base64.encodeToString(payload, Base64.NO_WRAP)).commit();
            if (!saved) throw new IllegalStateException("Unable to persist encrypted proxy profiles");
            call.resolve();
        } catch (Throwable error) {
            call.reject("Unable to securely save proxy profiles: " + error.getMessage(), error instanceof Exception ? (Exception) error : new Exception(error));
        }
    }

    /** Loads encrypted proxy links; plaintext is returned only to the in-memory app bridge. */
    @PluginMethod
    public void loadSecureProxyProfiles(PluginCall call) {
        try {
            SharedPreferences prefs = getContext().getSharedPreferences(SECURE_PROFILE_PREFS, android.content.Context.MODE_PRIVATE);
            String encoded = prefs.getString(SECURE_PROFILE_KEY, null);
            JSObject result = new JSObject();
            if (encoded == null || encoded.isEmpty()) { result.put("profilesJson", ""); call.resolve(result); return; }
            byte[] payload = Base64.decode(encoded, Base64.NO_WRAP);
            if (payload.length <= 28) throw new IllegalStateException("Encrypted profile data is truncated");
            byte[] iv = java.util.Arrays.copyOfRange(payload, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(payload, 12, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateProfileKey(), new GCMParameterSpec(128, iv));
            String json = new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
            new org.json.JSONArray(json);
            result.put("profilesJson", json);
            call.resolve(result);
        } catch (Throwable error) {
            call.reject("Unable to decrypt saved proxy profiles: " + error.getMessage(), error instanceof Exception ? (Exception) error : new Exception(error));
        }
    }

    private SecretKey getOrCreateProfileKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEYSTORE_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    @PluginMethod
    public void isVpnConnected(PluginCall call) {
        JSObject result = new JSObject();
        result.put("connected", AlkhalidiVpnService.isConnected());
        call.resolve(result);
    }

    /**
     * Reads the WireGuard backend counters on Capacitor's plugin worker thread.
     * No network request or speed test is started here, keeping the WebView UI responsive.
     */
    @PluginMethod
    public void getTrafficStats(PluginCall call) {
        AlkhalidiVpnService.TrafficSnapshot snapshot = AlkhalidiVpnService.getTrafficSnapshot();
        JSObject result = new JSObject();
        result.put("connected", snapshot.connected);
        result.put("downloadBytes", snapshot.downloadBytes);
        result.put("uploadBytes", snapshot.uploadBytes);
        result.put("latestHandshakeEpochMs", snapshot.latestHandshakeEpochMs);
        result.put("available", snapshot.available);
        call.resolve(result);
    }

    @PluginMethod
    public void listApplications(PluginCall call) {
        try {
            PackageManager packageManager = getContext().getPackageManager();
            List<ApplicationInfo> installed = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
            List<ApplicationInfo> launchable = new ArrayList<>();
            for (ApplicationInfo info : installed) {
                if (packageManager.getLaunchIntentForPackage(info.packageName) != null) launchable.add(info);
            }
            Collections.sort(launchable, Comparator.comparing(info -> packageManager.getApplicationLabel(info).toString(), String.CASE_INSENSITIVE_ORDER));
            JSArray applications = new JSArray();
            for (ApplicationInfo info : launchable) {
                JSObject app = new JSObject();
                app.put("packageName", info.packageName);
                app.put("label", packageManager.getApplicationLabel(info).toString());
                app.put("icon", iconAsDataUrl(packageManager.getApplicationIcon(info)));
                applications.put(app);
            }
            JSObject result = new JSObject();
            result.put("applications", applications);
            call.resolve(result);
        } catch (Throwable error) {
            call.reject("تعذر قراءة التطبيقات المثبتة", error instanceof Exception ? (Exception) error : new Exception(error));
        }
    }

    @PluginMethod
    public void openVpnSettings(PluginCall call) {
        try {
            Intent settings = new Intent(Settings.ACTION_VPN_SETTINGS);
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(settings);
            call.resolve();
        } catch (RuntimeException error) {
            call.reject("Unable to open Android VPN settings: " + error.getMessage(), error);
        }
    }

    /**
     * Requests an Android VPN permission flow, then starts Sing-box in the
     * same AlkhalidiVpnService used by WireGuard. Profile parsing is validated
     * before any service or TUN resource is created.
     */
    @PluginMethod
    public synchronized void connectProxy(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Activity unavailable");
            return;
        }
        try {
            String link = call.getString("link", "");
            SingBoxProfileParser.Profile profile = SingBoxProfileParser.parse(link);
            if (pendingCall != null) {
                pendingCall.reject("A VPN permission request is already pending");
                pendingCall = null;
                pendingIntent = null;
            }
            Intent service = new Intent(activity, AlkhalidiVpnService.class)
                    .setAction(AlkhalidiVpnService.ACTION_CONNECT_SINGBOX);
            put(service, AlkhalidiVpnService.EXTRA_SERVER_NAME, profile.name);
            put(service, AlkhalidiVpnService.EXTRA_CONFIG_JSON, profile.configJson);
            service.putExtra(AlkhalidiVpnService.EXTRA_BYPASS_LAN,
                    call.getBoolean("bypassLanRoute", false));
            service.putExtra(AlkhalidiVpnService.EXTRA_EXCLUDED_APPLICATIONS, joinApplications(call));
            Intent permission = VpnService.prepare(activity);
            if (permission != null) {
                pendingCall = call;
                pendingIntent = service;
                startActivityForResult(call, permission, "vpnPermissionResult");
                return;
            }
            startServiceSafely(service, call);
        } catch (Throwable error) {
            call.reject("Unable to start proxy profile: " + error.getMessage(),
                    error instanceof Exception ? (Exception) error : new Exception(error));
        }
    }

    @PluginMethod
    public synchronized void connect(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Activity unavailable");
            return;
        }

        String endpoint = call.getString("endpoint", "");
        String privateKey = call.getString("privateKey", "");
        String publicKey = call.getString("publicKey", "");
        if (endpoint == null || endpoint.trim().isEmpty()
                || privateKey == null || privateKey.trim().isEmpty()
                || publicKey == null || publicKey.trim().isEmpty()) {
            call.reject("إعدادات WireGuard غير مكتملة");
            return;
        }

        // A second permission flow can otherwise resume the wrong JS call and start
        // a stale tunnel. Keep only one pending request at a time.
        if (pendingCall != null) {
            pendingCall.reject("يوجد طلب اتصال VPN قيد التنفيذ");
            pendingCall = null;
            pendingIntent = null;
        }

        Intent service = new Intent(activity, AlkhalidiVpnService.class)
                .setAction(AlkhalidiVpnService.ACTION_CONNECT);
        put(service, AlkhalidiVpnService.EXTRA_SERVER_NAME, call.getString("serverName", "SeloomWarp WireGuard"));
        put(service, AlkhalidiVpnService.EXTRA_ENDPOINT, endpoint);
        put(service, AlkhalidiVpnService.EXTRA_DNS, call.getString("dns", "1.1.1.1,1.0.0.1"));
        put(service, AlkhalidiVpnService.EXTRA_ADDRESS, call.getString("address", "172.16.0.2/32"));
        put(service, AlkhalidiVpnService.EXTRA_PRIVATE_KEY, privateKey);
        put(service, AlkhalidiVpnService.EXTRA_PUBLIC_KEY, publicKey);
        // IPv6 must be explicitly present in the peer configuration. Routing
        // ::/0 by default breaks IPv6-only destinations on IPv4-only peers.
        put(service, AlkhalidiVpnService.EXTRA_ALLOWED_IPS, call.getString("allowedIPs", "0.0.0.0/0"));
        service.putExtra(AlkhalidiVpnService.EXTRA_MTU, call.getInt("mtu", 1280));
        service.putExtra(AlkhalidiVpnService.EXTRA_KEEPALIVE, call.getInt("keepalive", 25));
        service.putExtra(AlkhalidiVpnService.EXTRA_BYPASS_LAN, call.getBoolean("bypassLanRoute", false));
        service.putExtra(AlkhalidiVpnService.EXTRA_EXCLUDED_APPLICATIONS, joinApplications(call));

        Intent permission = VpnService.prepare(activity);
        if (permission != null) {
            pendingCall = call;
            pendingIntent = service;
            startActivityForResult(call, permission, "vpnPermissionResult");
            return;
        }

        startServiceSafely(service, call);
    }

    @ActivityCallback
    private synchronized void vpnPermissionResult(PluginCall call, androidx.activity.result.ActivityResult result) {
        // Clear state before starting the service so a fast second tap cannot reuse it.
        Intent service = pendingIntent;
        pendingCall = null;
        pendingIntent = null;

        if (result.getResultCode() != Activity.RESULT_OK || service == null) {
            call.reject("تم إلغاء إذن تشغيل الـ VPN");
            return;
        }
        startServiceSafely(service, call);
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Activity unavailable");
            return;
        }
        try {
            Intent service = new Intent(activity, AlkhalidiVpnService.class)
                    .setAction(AlkhalidiVpnService.ACTION_DISCONNECT);
            activity.startService(service);
            JSObject result = new JSObject();
            result.put("status", "disconnected");
            call.resolve(result);
        } catch (RuntimeException error) {
            call.reject("تعذر إيقاف خدمة VPN: " + error.getMessage(), error);
        }
    }

    private void startServiceSafely(Intent intent, PluginCall call) {
        try {
            Activity activity = getActivity();
            if (activity == null) {
                call.reject("Activity unavailable");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(intent);
            } else {
                activity.startService(intent);
            }
            JSObject result = new JSObject();
            result.put("status", "connecting");
            call.resolve(result);
        } catch (RuntimeException error) {
            call.reject("تعذر تشغيل خدمة VPN: " + error.getMessage(), error);
        }
    }

    private static void put(Intent intent, String key, String value) {
        intent.putExtra(key, value == null ? "" : value);
    }

    private static String joinApplications(PluginCall call) {
        try {
            JSArray applications = call.getArray("excludedApplications");
            if (applications == null) return "";
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < applications.length(); index++) {
                String packageName = applications.getString(index);
                if (packageName == null || packageName.trim().isEmpty()) continue;
                if (result.length() > 0) result.append(',');
                result.append(packageName.trim());
            }
            return result.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String iconAsDataUrl(android.graphics.drawable.Drawable drawable) {
        try {
            int size = 96;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, output);
            return "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
