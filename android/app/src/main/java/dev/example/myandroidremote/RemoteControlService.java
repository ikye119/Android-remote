package dev.example.myandroidremote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import org.json.JSONObject;

public class RemoteControlService extends AccessibilityService {
    static RemoteControlService current;
    @Override protected void onServiceConnected() { current = this; super.onServiceConnected(); }
    @Override public void onDestroy() { if (current == this) current = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    void execute(JSONObject command) {
        if (RemoteSessionService.current == null || !RemoteSessionService.current.isSharing()) return;
        String type = command.optString("type");
        if ("back".equals(type)) { performGlobalAction(GLOBAL_ACTION_BACK); return; }
        if ("home".equals(type)) { performGlobalAction(GLOBAL_ACTION_HOME); return; }
        if (!"tap".equals(type) && !"swipe".equals(type)) return;
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        windows.getDefaultDisplay().getRealMetrics(dm);
        Path p = new Path();
        p.moveTo((float) (command.optDouble("x") * dm.widthPixels), (float) (command.optDouble("y") * dm.heightPixels));
        if ("swipe".equals(type)) p.lineTo((float) (command.optDouble("toX") * dm.widthPixels),
                (float) (command.optDouble("toY") * dm.heightPixels));
        long duration = "tap".equals(type) ? 70 : Math.max(100, Math.min(1200, command.optLong("ms", 300)));
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, duration)).build();
        dispatchGesture(gesture, null, null);
    }
}
