package com.homecam.blackbox.rec;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.view.Surface;

/** 최소한의 EGL14 래퍼 (MediaCodec 입력 Surface 에 그리기 위한 recordable 설정) */
final class EglCore {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;

    EglCore() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay failed");
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw new RuntimeException("eglInitialize failed");
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            throw new RuntimeException("eglChooseConfig failed");
        }
        config = configs[0];
        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext failed");
    }

    EGLSurface createWindowSurface(Surface surface) {
        int[] attribs = {EGL14.EGL_NONE};
        EGLSurface s = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0);
        if (s == null || s == EGL14.EGL_NO_SURFACE) throw new RuntimeException("eglCreateWindowSurface failed");
        return s;
    }

    void makeCurrent(EGLSurface s) {
        if (!EGL14.eglMakeCurrent(display, s, s, context)) throw new RuntimeException("eglMakeCurrent failed");
    }

    void setPresentationTime(EGLSurface s, long nsecs) {
        EGLExt.eglPresentationTimeANDROID(display, s, nsecs);
    }

    boolean swap(EGLSurface s) {
        return EGL14.eglSwapBuffers(display, s);
    }

    void releaseSurface(EGLSurface s) {
        if (s != null && s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, s);
    }

    void release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
    }
}
