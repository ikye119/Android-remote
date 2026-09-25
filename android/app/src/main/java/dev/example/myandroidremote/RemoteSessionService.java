package dev.example.myandroidremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class RemoteSessionService extends Service {
    public static final String CONNECT = "dev.example.myandroidremote.CONNECT";
    public static final String START_SHARING = "dev.example.myandroidremote.START_SHARING";
    public static final String STOP_SHARING = "dev.example.myandroidremote.STOP_SHARING";
    public static final String DISCONNECT = "dev.example.myandroidremote.DISCONNECT";
    private static final String CHANNEL = "remote_status";
    private static final int NOTIFICATION = 7;
    static RemoteSessionService current;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private WebSocket socket;
    private boolean connected = false, viewer = false;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread frames;
    private long lastFrame = 0;

    @Override public void onCreate() {
        super.onCreate(); current = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Remote access status", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i == null) return START_NOT_STICKY;
        String action = i.getAction();
        if (CONNECT.equals(action)) {
            stopSharing();
            if (socket != null) { socket.close(1000, "Reconnecting"); socket = null; }
            connected = false; viewer = false;
            foreground(false);
            String address = i.getStringExtra("url"), token = i.getStringExtra("token");
            if (address != null && token != null) connect(address, token);
        } else if (START_SHARING.equals(action)) {
            if (!connected || !viewer || projection != null) {
                MainActivity.showStatus("Connect the dashboard before starting sharing."); return START_NOT_STICKY;
            }
            Intent consent = i.getParcelableExtra("capture");
            if (consent != null) beginCapture(i.getIntExtra("result", 0), consent);
        } else if (STOP_SHARING.equals(action)) stopSharing();
        else if (DISCONNECT.equals(action)) { stopSharing(); if (socket != null) socket.close(1000, "Disconnected"); stopSelf(); }
        return START_NOT_STICKY;
    }
    private void foreground(boolean sharing) {
        int type = sharing ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(sharing), type);
        else startForeground(NOTIFICATION, notification(sharing));
    }
    private Notification notification(boolean sharing) {
        Intent stop = new Intent(this, RemoteSessionService.class);
        stop.setAction(sharing ? STOP_SHARING : DISCONNECT);
        PendingIntent action = PendingIntent.getService(this, sharing ? 2 : 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(sharing ? "Your screen is being shared" : "Remote app connected")
                .setContentText(sharing ? "Tap Stop sharing to end this session" : "Screen is not being shared")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, sharing ? "Stop sharing" : "Disconnect", action).build())
                .build();
    }
    private void connect(String address, String token) {
        MainActivity.showStatus("Connecting to relay…");
        Request req = new Request.Builder().url(address).build();
        socket = client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                try {
                    JSONObject auth = new JSONObject().put("type", "auth").put("role", "phone").put("token", token);
                    ws.send(auth.toString());
                } catch (Exception ignored) { ws.close(1008, "Invalid auth"); }
            }
            @Override public void onMessage(WebSocket ws, String message) {
                main.post(() -> {
                    if (ws != socket) return;
                    try {
                        JSONObject obj = new JSONObject(message);
                        String type = obj.optString("type");
                        if ("ready".equals(type)) {
                            connected = true; viewer = obj.optBoolean("peerConnected");
                            MainActivity.showStatus(viewer ? "Dashboard connected. You can start sharing." : "Waiting for dashboard.");
                        } else if ("peer".equals(type)) {
                            viewer = obj.optBoolean("connected");
                            if (!viewer) stopSharing();
                            MainActivity.showStatus(viewer ? "Dashboard connected. You can start sharing." : "Dashboard left. Sharing stopped.");
                        } else if (isSharing() && viewer && RemoteControlService.current != null) {
                            // Only these actions reach the gesture service; it never reads screen text.
                            if ("tap".equals(type) || "swipe".equals(type) || "back".equals(type) || "home".equals(type))
                                RemoteControlService.current.execute(obj);
                        }
                    } catch (Exception ignored) { }
                });
            }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                main.post(() -> lost(ws));
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                main.post(() -> lost(ws));
            }
        });
    }
    private void lost(WebSocket ws) {
        if (ws != socket) return;
        stopSharing(); connected = false; viewer = false; socket = null;
        MainActivity.showStatus("Disconnected. Open the app to reconnect.");
        stopSelf();
    }
    boolean isConnected() { return connected; }
    boolean isSharing() { return projection != null; }
    private void beginCapture(int result, Intent consent) {
        try {
            // Android requires a foreground mediaProjection service before opening the capture token.
            foreground(true);
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(result, consent);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { main.post(() -> stopSharing()); }
            }, main);
            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager windows = (WindowManager) getSystemService(WINDOW_SERVICE);
            windows.getDefaultDisplay().getRealMetrics(metrics);
            double scale = Math.min(1.0, 540.0 / metrics.widthPixels);
            int w = Math.max(2, (int) (metrics.widthPixels * scale));
            int h = Math.max(2, (int) (metrics.heightPixels * scale));
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            frames = new HandlerThread("screen-frames"); frames.start();
            reader.setOnImageAvailableListener(this::sendImage, new Handler(frames.getLooper()));
            display = projection.createVirtualDisplay("Remote view", w, h, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
            sendStatus(true); MainActivity.showStatus("Sharing active. Stop from the notification at any time.");
        } catch (Exception ex) { stopSharing(); MainActivity.showStatus("Couldn't start screen sharing: " + ex.getMessage()); }
    }
    private void sendImage(ImageReader source) {
        Image img = null;
        try {
            img = source.acquireLatestImage();
            if (img == null || projection == null || !viewer || socket == null ||
                    socket.queueSize() > 500000 || SystemClock.elapsedRealtime() - lastFrame < 250) return;
            lastFrame = SystemClock.elapsedRealtime();
            Image.Plane plane = img.getPlanes()[0];
            int w = img.getWidth(), h = img.getHeight();
            int paddedW = plane.getRowStride() / plane.getPixelStride();
            ByteBuffer pixels = plane.getBuffer(); pixels.rewind();
            Bitmap raw = Bitmap.createBitmap(paddedW, h, Bitmap.Config.ARGB_8888);
            raw.copyPixelsFromBuffer(pixels);
            Bitmap cropped = Bitmap.createBitmap(raw, 0, 0, w, h);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 50, out);
            raw.recycle(); cropped.recycle();
            byte[] jpeg = out.toByteArray();
            if (jpeg.length <= 450000) socket.send(ByteString.of(jpeg));
        } catch (Exception ignored) { } finally { if (img != null) img.close(); }
    }
    private void sendStatus(boolean sharing) {
        if (socket == null || !connected) return;
        try { socket.send(new JSONObject().put("type", "status").put("sharing", sharing).toString()); }
        catch (Exception ignored) { }
    }
    private void stopSharing() {
        boolean wasSharing = projection != null;
        MediaProjection old = projection; projection = null;
        if (reader != null) { reader.setOnImageAvailableListener(null, null); reader.close(); reader = null; }
        if (display != null) { display.release(); display = null; }
        if (old != null) old.stop();
        if (frames != null) { frames.quitSafely(); frames = null; }
        if (wasSharing) { sendStatus(false); foreground(false); MainActivity.showStatus("Screen sharing stopped."); }
    }
    @Override public void onDestroy() {
        stopSharing(); if (socket != null) socket.close(1000, "App closed"); socket = null;
        client.dispatcher().executorService().shutdown(); current = null; super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
