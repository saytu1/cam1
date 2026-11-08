package com.homecam.blackbox.rec;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 카메라 프레임(SurfaceTexture)을 받아 날짜/시간 글자를 덧그린 뒤 인코더 입력 Surface 로 출력한다.
 * 모든 GL 작업은 전용 스레드에서 수행.
 */
final class OverlayRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "OverlayRenderer";

    private static final String VS_OES =
            "attribute vec4 aPos;\n" +
            "attribute vec4 aTex;\n" +
            "uniform mat4 uTexM;\n" +
            "varying vec2 vTex;\n" +
            "void main(){ gl_Position = aPos; vTex = (uTexM * aTex).xy; }\n";
    private static final String FS_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "void main(){ gl_FragColor = texture2D(sTex, vTex); }\n";
    private static final String VS_2D =
            "attribute vec4 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "varying vec2 vTex;\n" +
            "void main(){ gl_Position = aPos; vTex = aTex; }\n";
    private static final String FS_2D =
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform sampler2D sTex;\n" +
            "void main(){ gl_FragColor = texture2D(sTex, vTex); }\n";

    private static final float[] FULL_POS = {-1, -1, 1, -1, -1, 1, 1, 1};
    private static final float[] FULL_TEX = {0, 0, 1, 0, 0, 1, 1, 1};

    private final Surface encoderSurface;
    private final int width, height;
    private final long minFrameIntervalNs;
    private final boolean timestamp;

    private HandlerThread thread;
    private Handler handler;
    private EglCore egl;
    private EGLSurface eglSurface;
    private SurfaceTexture cameraTexture;
    private Surface cameraSurface;
    private int oesTex, overlayTex;
    private int oesProg, oesPos, oesTexAttr, oesTexM;
    private int prog2d, p2dPos, p2dTexAttr;
    private final float[] texM = new float[16];
    private FloatBuffer fullPos, fullTex, ovPos, ovTex;

    private Bitmap textBitmap;
    private Canvas textCanvas;
    private Paint fill, stroke;
    private String lastText = "";
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private long lastDrawNs;
    private volatile long framesRendered;
    private volatile boolean released;

    OverlayRenderer(Surface encoderSurface, int width, int height, int fps, boolean timestamp) {
        this.encoderSurface = encoderSurface;
        this.width = width;
        this.height = height;
        this.minFrameIntervalNs = fps > 0 ? (long) (1_000_000_000L / fps * 0.85) : 0;
        this.timestamp = timestamp;
    }

    long getFramesRendered() { return framesRendered; }

    /** GL 스레드를 시작하고 카메라가 그릴 입력 Surface 를 반환 */
    Surface start() throws InterruptedException {
        thread = new HandlerThread("OverlayRenderer");
        thread.start();
        handler = new Handler(thread.getLooper());
        final CountDownLatch latch = new CountDownLatch(1);
        final RuntimeException[] err = new RuntimeException[1];
        handler.post(() -> {
            try {
                init();
            } catch (RuntimeException e) {
                err[0] = e;
            }
            latch.countDown();
        });
        if (!latch.await(5, TimeUnit.SECONDS)) throw new RuntimeException("renderer init timeout");
        if (err[0] != null) throw err[0];
        return cameraSurface;
    }

    void stop() {
        if (handler == null) return;
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(() -> {
            releaseGl();
            latch.countDown();
        });
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) { }
        thread.quitSafely();
        handler = null;
    }

    private void init() {
        egl = new EglCore();
        eglSurface = egl.createWindowSurface(encoderSurface);
        egl.makeCurrent(eglSurface);

        int[] tex = new int[2];
        GLES20.glGenTextures(2, tex, 0);
        oesTex = tex[0];
        overlayTex = tex[1];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        oesProg = buildProgram(VS_OES, FS_OES);
        oesPos = GLES20.glGetAttribLocation(oesProg, "aPos");
        oesTexAttr = GLES20.glGetAttribLocation(oesProg, "aTex");
        oesTexM = GLES20.glGetUniformLocation(oesProg, "uTexM");
        prog2d = buildProgram(VS_2D, FS_2D);
        p2dPos = GLES20.glGetAttribLocation(prog2d, "aPos");
        p2dTexAttr = GLES20.glGetAttribLocation(prog2d, "aTex");

        fullPos = floatBuffer(FULL_POS);
        fullTex = floatBuffer(FULL_TEX);

        if (timestamp) initOverlay();

        cameraTexture = new SurfaceTexture(oesTex);
        cameraTexture.setDefaultBufferSize(width, height);
        cameraTexture.setOnFrameAvailableListener(this, handler);
        cameraSurface = new Surface(cameraTexture);
    }

    private void initOverlay() {
        float textSize = Math.max(14f, height / 22f);
        fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.WHITE);
        fill.setTextSize(textSize);
        fill.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        stroke = new Paint(fill);
        stroke.setColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(2f, textSize / 8f));

        int pad = (int) (textSize / 3);
        int bw = (int) (fill.measureText("0000-00-00 00:00:00") + pad * 2);
        int bh = (int) (textSize * 1.35f + pad);
        textBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        textCanvas = new Canvas(textBitmap);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        updateOverlayText(true);

        // 화면 좌상단에 배치 (NDC 좌표)
        float margin = height / 40f;
        float x0 = -1f + 2f * margin / width;
        float x1 = x0 + 2f * bw / width;
        float yTop = 1f - 2f * margin / height;
        float yBot = yTop - 2f * bh / height;
        ovPos = floatBuffer(new float[]{x0, yBot, x1, yBot, x0, yTop, x1, yTop});
        // 비트맵의 0행(위)이 텍스처 t=0
        ovTex = floatBuffer(new float[]{0, 1, 1, 1, 0, 0, 1, 0});
    }

    private void updateOverlayText(boolean force) {
        String text = fmt.format(new Date());
        if (!force && text.equals(lastText)) return;
        lastText = text;
        textBitmap.eraseColor(Color.TRANSPARENT);
        float pad = fill.getTextSize() / 3;
        float baseline = pad / 2 + fill.getTextSize();
        textCanvas.drawText(text, pad, baseline, stroke);
        textCanvas.drawText(text, pad, baseline, fill);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, textBitmap, 0);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        if (released) return;
        try {
            egl.makeCurrent(eglSurface);
            st.updateTexImage();
            long now = System.nanoTime();
            if (now - lastDrawNs < minFrameIntervalNs) return; // 설정 fps 로 제한
            lastDrawNs = now;
            st.getTransformMatrix(texM);
            draw();
            egl.setPresentationTime(eglSurface, now);
            egl.swap(eglSurface);
            framesRendered++;
        } catch (Exception e) {
            Log.e(TAG, "draw failed", e);
        }
    }

    private void draw() {
        GLES20.glViewport(0, 0, width, height);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(oesProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glUniformMatrix4fv(oesTexM, 1, false, texM, 0);
        drawQuad(oesPos, oesTexAttr, fullPos, fullTex);

        if (timestamp && textBitmap != null) {
            updateOverlayText(false);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(prog2d);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex);
            drawQuad(p2dPos, p2dTexAttr, ovPos, ovTex);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    private static void drawQuad(int posLoc, int texLoc, FloatBuffer pos, FloatBuffer tex) {
        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, pos);
        GLES20.glEnableVertexAttribArray(texLoc);
        // aTex 는 vec4/vec2 이지만 2개 성분만 공급 (나머지는 기본값 z=0,w=1)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, tex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(posLoc);
        GLES20.glDisableVertexAttribArray(texLoc);
    }

    private void releaseGl() {
        released = true;
        try {
            if (cameraTexture != null) {
                cameraTexture.setOnFrameAvailableListener(null);
                cameraTexture.release();
            }
            if (cameraSurface != null) cameraSurface.release();
            if (egl != null) {
                egl.makeCurrent(eglSurface);
                GLES20.glDeleteTextures(2, new int[]{oesTex, overlayTex}, 0);
                GLES20.glDeleteProgram(oesProg);
                GLES20.glDeleteProgram(prog2d);
                egl.releaseSurface(eglSurface);
                egl.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "release", e);
        }
        if (textBitmap != null) textBitmap.recycle();
        egl = null;
    }

    private static FloatBuffer floatBuffer(float[] a) {
        FloatBuffer fb = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(a).position(0);
        return fb;
    }

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("link failed: " + log);
        }
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("compile failed: " + log);
        }
        return s;
    }
}
