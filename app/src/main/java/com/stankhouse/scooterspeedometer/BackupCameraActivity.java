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
import android.content.pm.PackageManager;
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
    private TextView cameraStatus;
    private boolean opening;
    private static final int CAMERA_PERMISSION=612;

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera();
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            configureTransform(width, height);
        }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { closeCamera(); return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
    };

    private final CameraDevice.StateCallback cameraState = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice device) { opening=false;camera = device;runOnUiThread(()->cameraStatus.setVisibility(View.GONE));createPreview(); }
        @Override public void onDisconnected(CameraDevice device) { opening=false;device.close(); camera = null;showCameraError("Camera disconnected. Tap to retry."); }
        @Override public void onError(CameraDevice device, int error) { opening=false;device.close(); camera = null;showCameraError(error==CameraDevice.StateCallback.ERROR_CAMERA_IN_USE?"Camera is being used by another app. Close it, then tap to retry.":"Camera could not start. Tap to retry."); }
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

        cameraStatus=new TextView(this);cameraStatus.setText("Starting front camera…");cameraStatus.setTextColor(Color.WHITE);cameraStatus.setTextSize(16);cameraStatus.setGravity(Gravity.CENTER);cameraStatus.setPadding(dp(24),dp(16),dp(24),dp(16));cameraStatus.setBackground(UiKit.rounded(this,Color.argb(235,8,20,27),22,UiKit.BORDER));cameraStatus.setOnClickListener(v->openCamera());FrameLayout.LayoutParams statusParams=new FrameLayout.LayoutParams(-1,dp(88),Gravity.CENTER);statusParams.setMargins(dp(32),0,dp(32),0);root.addView(cameraStatus,statusParams);

        mirrorButton = controlButton("↔  Mirrored");
        FrameLayout.LayoutParams mirrorParams = new FrameLayout.LayoutParams(dp(132), dp(52), Gravity.BOTTOM | Gravity.START);
        mirrorParams.setMargins(dp(18), 0, 0, dp(22));
        root.addView(mirrorButton, mirrorParams);
        mirrorButton.setOnClickListener(v -> {
            mirrored = !mirrored;
            mirrorButton.setText(mirrored ? "↔  Mirrored" : "↔  Normal");
            applyMirror();
        });

        Button close = controlButton("×  Close");
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(116), dp(52), Gravity.BOTTOM | Gravity.END);
        closeParams.setMargins(0, 0, dp(18), dp(22));
        root.addView(close, closeParams);
        close.setOnClickListener(v -> finish());

        TextView hint = new TextView(this);
        hint.setText("◉  REAR VIEW ASSIST • FRONT CAMERA");
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
        UiKit.button(button,Color.rgb(5,38,46));
        return button;
    }

    private void enterImmersive() {
        Fullscreen.apply(this);
    }

    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) enterImmersive(); }

    @Override protected void onResume() {
        super.onResume();
        cameraThread = new HandlerThread("BackupCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        if(checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){cameraStatus.setText("Camera permission is required. Tap to allow.");cameraStatus.setOnClickListener(v->requestPermissions(new String[]{android.Manifest.permission.CAMERA},CAMERA_PERMISSION));requestPermissions(new String[]{android.Manifest.permission.CAMERA},CAMERA_PERMISSION);}
        else if (preview.isAvailable()) openCamera(); else preview.setSurfaceTextureListener(surfaceListener);
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
        if (camera != null || opening || cameraHandler == null) return;
        if(checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.CAMERA},CAMERA_PERMISSION);return;}
        opening=true;cameraStatus.setText("Starting front camera…");cameraStatus.setVisibility(View.VISIBLE);
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
            if (cameraId == null) { opening=false;showCameraError("No front-facing camera was found on this phone."); return; }
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                opening=false;requestPermissions(new String[]{android.Manifest.permission.CAMERA},CAMERA_PERMISSION); return;
            }
            manager.openCamera(cameraId, cameraState, cameraHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) { opening=false;showCameraError("Front camera unavailable. Tap to retry."); }
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
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            camera.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (camera == null) return;
                    session = configured;
                    try { session.setRepeatingRequest(request.build(), null, cameraHandler); }
                    catch (CameraAccessException | IllegalStateException ignored) { showCameraError("Preview stopped. Tap to retry."); }
                    preview.post(() -> configureTransform(preview.getWidth(), preview.getHeight()));
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed) { showNoCamera(); }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) { showCameraError("Could not create camera preview. Tap to retry."); }
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
        opening=false;
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
    }

    private void showNoCamera() { showCameraError("Front camera is unavailable. Tap to retry."); }
    private void showCameraError(String message){runOnUiThread(()->{cameraStatus.setText(message);cameraStatus.setVisibility(View.VISIBLE);cameraStatus.setOnClickListener(v->openCamera());});}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==CAMERA_PERMISSION&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED){cameraStatus.setOnClickListener(v->openCamera());openCamera();}else if(request==CAMERA_PERMISSION)showCameraError("Camera permission denied. Tap to try again.");}

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
