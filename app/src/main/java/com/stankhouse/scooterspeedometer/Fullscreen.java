package com.stankhouse.scooterspeedometer;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.Window;

/** One edge-to-edge, immersive and hardware-accelerated policy for every app screen. */
public final class Fullscreen {
    private Fullscreen() { }
    public static void apply(Activity activity) {
        Window window = activity.getWindow();
        window.setStatusBarColor(Color.TRANSPARENT); window.setNavigationBarColor(Color.TRANSPARENT);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
