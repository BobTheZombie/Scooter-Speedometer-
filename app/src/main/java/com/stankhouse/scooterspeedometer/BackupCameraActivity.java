package com.stankhouse.scooterspeedometer;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Arrays;

public class BackupCameraActivity extends Activity {
    private TextureView preview;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private android.util.Size previewSize;
    private String cameraId;
    private boolean mirrored = true;
    private Button mirrorButton;

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera();
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            configureTransform(width, height);
        }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
    };

    private final CameraDevice.StateCallback cameraState = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice device) { camera = device; createPreview(); }
        @Override public void onDisconnected(CameraDevice device) { device.close(); camera = null; }
        @Override public void onError(CameraDevice device, int error) { device.close(); camera = null; finish(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersive();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new TextureView(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        root.addView(new GuideView(), new FrameLayout.LayoutParams(-1, -1));

        mirrorButton = controlButton("MIRRORED");
        FrameLayout.LayoutParams mirrorParams = new FrameLayout.LayoutParams(dp(132), dp(52), Gravity.BOTTOM | Gravity.START);
        mirrorParams.setMargins(dp(18), 0, 0, dp(22));
        root.addView(mirrorButton, mirrorParams);
        mirrorButton.setOnClickListener(v -> {
            mirrored = !mirrored;
            mirrorButton.setText(mirrored ? "MIRRORED" : "NORMAL");
            applyMirror();
        });

        Button close = controlButton("CLOSE  ×");
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(116), dp(52), Gravity.BOTTOM | Gravity.END);
        closeParams.setMargins(0, 0, dp(18), dp(22));
        root.addView(close, closeParams);
        close.setOnClickListener(v -> finish());

        TextView hint = new TextView(this);
        hint.setText("FRONT CAMERA • SUPPLEMENTAL VIEW ONLY");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(Color.argb(165, 0, 0, 0));
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-1, dp(34), Gravity.TOP);
        root.addView(hint, hintParams);
        setContentView(root);
    }

    private Button controlButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setBackgroundColor(Color.argb(220, 5, 28, 34));
        return button;
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) enterImmersive(); }

    @Override protected void onResume() {
        super.onResume();
        cameraThread = new HandlerThread("BackupCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        if (preview.isAvailable()) openCamera(); else preview.setSurfaceTextureListener(surfaceListener);
    }

    @Override protected void onPause() {
        closeCamera();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try { cameraThread.join(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            cameraThread = null; cameraHandler = null;
        }
        super.onPause();
    }

    private void openCamera() {
        if (camera != null || cameraHandler == null) return;
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics info = manager.getCameraCharacteristics(id);
                Integer facing = info.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraId = id;
                    StreamConfigurationMap map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) previewSize = chooseSize(map.getOutputSizes(SurfaceTexture.class));
                    break;
                }
            }
            if (cameraId == null) { showNoCamera(); return; }
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                finish(); return;
            }
            manager.openCamera(cameraId, cameraState, cameraHandler);
        } catch (CameraAccessException | SecurityException e) { showNoCamera(); }
    }

    private android.util.Size chooseSize(android.util.Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new android.util.Size(1280, 720);
        android.util.Size best = sizes[0];
        long bestScore = Long.MAX_VALUE;
        for (android.util.Size size : sizes) {
            long pixels = (long) size.getWidth() * size.getHeight();
            if (pixels > 1920L * 1080L) continue;
            long score = Math.abs(pixels - 1280L * 720L);
            if (score < bestScore) { best = size; bestScore = score; }
        }
        return best;
    }

    private void createPreview() {
        if (camera == null || !preview.isAvailable()) return;
        try {
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null) return;
            if (previewSize != null) texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(surface);
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            camera.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (camera == null) return;
                    session = configured;
                    try { session.setRepeatingRequest(request.build(), null, cameraHandler); }
                    catch (CameraAccessException ignored) { }
                    preview.post(() -> configureTransform(preview.getWidth(), preview.getHeight()));
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed) { showNoCamera(); }
            }, cameraHandler);
        } catch (CameraAccessException e) { showNoCamera(); }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) { applyMirror(); return; }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        boolean swapped = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        float bufferWidth = swapped ? previewSize.getHeight() : previewSize.getWidth();
        float bufferHeight = swapped ? previewSize.getWidth() : previewSize.getHeight();
        RectF bufferRect = new RectF(0, 0, bufferWidth, bufferHeight);
        bufferRect.offset(viewRect.centerX() - bufferRect.centerX(), viewRect.centerY() - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
        float scale = Math.max((float) viewHeight / bufferHeight, (float) viewWidth / bufferWidth);
        matrix.postScale(scale, scale, viewRect.centerX(), viewRect.centerY());
        if (swapped) matrix.postRotate(90f * (rotation - 2), viewRect.centerX(), viewRect.centerY());
        preview.setTransform(matrix);
        applyMirror();
    }

    private void applyMirror() { preview.setScaleX(mirrored ? -1f : 1f); }

    private void closeCamera() {
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
    }

    private void showNoCamera() {
        runOnUiThread(() -> android.widget.Toast.makeText(this, "Front camera is unavailable", android.widget.Toast.LENGTH_LONG).show());
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private class GuideView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        GuideView() { super(BackupCameraActivity.this); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float topY = h * .40f, bottomY = h * .82f;
            float topHalf = w * .15f, bottomHalf = w * .40f;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(dp(5));
            paint.setShadowLayer(dp(4), 0, 0, Color.BLACK);
            paint.setColor(Color.rgb(66, 255, 130));
            path.reset(); path.moveTo(w / 2f - topHalf, topY); path.lineTo(w / 2f - bottomHalf, bottomY);
            path.moveTo(w / 2f + topHalf, topY); path.lineTo(w / 2f + bottomHalf, bottomY); canvas.drawPath(path, paint);
            drawGuide(canvas, topY, topHalf, Color.rgb(72, 255, 125));
            drawGuide(canvas, h * .61f, w * .275f, Color.rgb(255, 214, 0));
            drawGuide(canvas, bottomY, bottomHalf, Color.rgb(255, 72, 72));
            paint.clearShadowLayer();
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(185, 0, 0, 0));
            canvas.drawRoundRect(new RectF(w * .16f, h * .09f, w * .84f, h * .16f), dp(16), dp(16), paint);
            paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(dp(16)); paint.setFakeBoldText(true);
            canvas.drawText("BACKUP VIEW • CHECK SURROUNDINGS", w / 2f, h * .135f, paint);
        }
        private void drawGuide(Canvas canvas, float y, float halfWidth, int color) {
            paint.setColor(color); paint.setStrokeWidth(dp(6));
            canvas.drawLine(getWidth() / 2f - halfWidth, y, getWidth() / 2f + halfWidth, y, paint);
        }
    }
}
