package com.stankhouse.scooterspeedometer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

/** Short branded launch screen before the live dashboard initializes. */
public class SplashActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Fullscreen.apply(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28)); root.setBackgroundColor(Color.rgb(3, 9, 14));
        ImageView art = new ImageView(this);
        try {
            InputStream input = getAssets().open("bobthezombie_splash.webp.b64");
            byte[] encoded = new byte[input.available()];
            int ignored = input.read(encoded); input.close();
            byte[] image = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
            art.setImageBitmap(BitmapFactory.decodeByteArray(image, 0, image.length));
        } catch (Exception ignored) { art.setImageResource(R.drawable.ic_launcher); }
        art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        root.addView(art, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView title = text("SCOOTER SPEEDOMETER", 27, Color.WHITE, true);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(50)));
        TextView credit = text("Developed by BobTheZombie", 16, Color.rgb(0, 229, 255), true);
        root.addView(credit, new LinearLayout.LayoutParams(-1, dp(42)));
        setContentView(root);
        root.setAlpha(0f); root.animate().alpha(1f).setDuration(450).start();
        root.postDelayed(() -> {
            startActivity(new Intent(this, MainActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, 1900L);
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setGravity(Gravity.CENTER); view.setTypeface(Typeface.create("sans-serif-condensed", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) Fullscreen.apply(this); }
}
