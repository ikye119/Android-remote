package dev.example.myandroidremote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.URI;

public class MainActivity extends Activity {
    private static final int CAPTURE_REQUEST = 40;
    private static WeakReference<MainActivity> shown = new WeakReference<>(null);
    private EditText url, token;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        shown = new WeakReference<>(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (22 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this); title.setText("My Android Remote"); title.setTextSize(24); layout.addView(title);
        TextView help = new TextView(this);
        help.setText("This app shows a visible notification while connected. Approve screen sharing each time you start a session. Stop at any time here or from the notification.");
        help.setPadding(0, pad / 2, 0, pad / 2); layout.addView(help);
        url = new EditText(this); url.setSingleLine(true); url.setHint("wss://your-domain.example/ws");
        url.setText(getPreferences(MODE_PRIVATE).getString("url", "")); layout.addView(url);
        token = new EditText(this); token.setSingleLine(true); token.setHint("PHONE_TOKEN");
        token.setInputType(129); layout.addView(token);
        addButton(layout, "1. Connect to relay", v -> connect());
        addButton(layout, "2. Enable remote control", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addButton(layout, "3. Start screen sharing", v -> startSharing());
        addButton(layout, "Stop sharing", v -> service(RemoteSessionService.STOP_SHARING));
        addButton(layout, "Disconnect", v -> service(RemoteSessionService.DISCONNECT));
        status = new TextView(this); status.setPadding(0, pad, 0, 0); status.setTextSize(17); layout.addView(status);
        setContentView(layout);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        updateStatus("Ready. Connect to the relay first.");
    }
    private void addButton(LinearLayout layout, String label, View.OnClickListener click) {
        Button b = new Button(this); b.setText(label); b.setOnClickListener(click); layout.addView(b);
    }
    private void service(String action) {
        Intent i = new Intent(this, RemoteSessionService.class); i.setAction(action); startService(i);
    }
    private void connect() {
        String address = url.getText().toString().trim(), secret = token.getText().toString().trim();
        if (secret.length() < 32 || !validAddress(address)) {
            Toast.makeText(this, "Use a wss:// URL and a 32+ character phone token. ws:// works only for a private LAN IP.", Toast.LENGTH_LONG).show(); return;
        }
        getPreferences(MODE_PRIVATE).edit().putString("url", address).apply();
        Intent i = new Intent(this, RemoteSessionService.class); i.setAction(RemoteSessionService.CONNECT);
        i.putExtra("url", address); i.putExtra("token", secret);
        startForegroundService(i);
        token.setText("");
    }
    private boolean validAddress(String raw) {
        try {
            URI u = new URI(raw);
            if (u.getUserInfo() != null || u.getFragment() != null || !"/ws".equals(u.getPath()) || u.getHost() == null) return false;
            if ("wss".equals(u.getScheme())) return true;
            if (!"ws".equals(u.getScheme())) return false;
            // Only a numeric IPv4 address in a private network is allowed for plaintext local testing.
            String host = u.getHost();
            if (!host.matches("[0-9.]+")) return false;
            InetAddress ip = InetAddress.getByName(host);
            return ip.isSiteLocalAddress() || ip.isLoopbackAddress();
        } catch (Exception ex) { return false; }
    }
    private void startSharing() {
        if (RemoteSessionService.current == null || !RemoteSessionService.current.isConnected()) {
            updateStatus("Connect to the relay first."); return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_REQUEST);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CAPTURE_REQUEST && result == RESULT_OK && data != null) {
            Intent i = new Intent(this, RemoteSessionService.class); i.setAction(RemoteSessionService.START_SHARING);
            i.putExtra("result", result); i.putExtra("capture", data); startService(i);
        }
    }
    void updateStatus(String text) { if (status != null) status.setText(text); }
    static void showStatus(String text) {
        MainActivity a = shown.get(); if (a != null) a.runOnUiThread(() -> a.updateStatus(text));
    }
    @Override protected void onDestroy() { if (shown.get() == this) shown.clear(); super.onDestroy(); }
}
