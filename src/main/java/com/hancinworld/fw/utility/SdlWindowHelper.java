package com.hancinworld.fw.utility;

import org.lwjgl.opengl.Display;

import java.awt.Rectangle;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Borderless window support for lwjgl3ify (GregTech: New Horizons on Java 17+).
 *
 * lwjgl3ify replaces LWJGL2 with SDL3. The LWJGL2 "undecorated" trick does not exist there, and SDL's own fullscreen
 * is treated by Windows like exclusive fullscreen (black flicker on alt-tab, nothing can be on top of it).
 * So instead we keep a NORMAL window and make it borderless + monitor sized, calling SDL3 directly through reflection
 * (this mod is compiled against LWJGL2, so it cannot reference LWJGL3 classes directly).
 *
 * All SDL coordinates (display bounds, window position/size) use the same unit, so this is correct with any
 * Windows display scaling and any resolution (1080p, 1440p, 4K, ...).
 */
public final class SdlWindowHelper {

    private static final String SDL_VIDEO = "org.lwjgl.sdl.SDLVideo";
    private static final String SDL_RECT = "org.lwjgl.sdl.SDL_Rect";
    private static final String SDL_STDINC = "org.lwjgl.sdl.SDLStdinc";
    private static final String MAIN_THREAD_EXEC = "me.eigenraven.lwjgl3ify.client.MainThreadExec";

    private SdlWindowHelper() {}

    // ------------------------------------------------------------------------------------------------------------
    // Reflection plumbing
    // ------------------------------------------------------------------------------------------------------------

    private static Class<?> cls(String name) throws ClassNotFoundException {
        try {
            return Class.forName(name, true, SdlWindowHelper.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null)
                return Class.forName(name, true, ctx);
            throw e;
        }
    }

    private static Object video(String method, Class<?>[] types, Object... args) throws Exception {
        Method m = cls(SDL_VIDEO).getMethod(method, types);
        return m.invoke(null, args);
    }

    private static IntBuffer intBuf() {
        return ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    /** Runs the task on SDL's main thread (required for window operations). */
    public static void runOnMainThread(final Runnable task) {
        try {
            Method m = cls(MAIN_THREAD_EXEC).getMethod("runOnMainThread", Runnable.class);
            m.invoke(null, task);
        } catch (Throwable t) {
            task.run();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Window
    // ------------------------------------------------------------------------------------------------------------

    /** SDL_Window pointer from lwjgl3ify's Display replacement. */
    public static long getWindow() throws Exception {
        //Display.class is redirected to org.lwjglx.opengl.Display by lwjgl3ify at runtime.
        Method m = Display.class.getMethod("getWindow");
        return ((Number) m.invoke(null)).longValue();
    }

    /** Window client area in screen coordinates. */
    public static Rectangle getWindowBounds(long window) throws Exception {
        IntBuffer a = intBuf(), b = intBuf();
        video("SDL_GetWindowPosition", new Class<?>[]{long.class, IntBuffer.class, IntBuffer.class}, window, a, b);
        int x = a.get(0), y = b.get(0);
        video("SDL_GetWindowSize", new Class<?>[]{long.class, IntBuffer.class, IntBuffer.class}, window, a, b);
        return new Rectangle(x, y, a.get(0), b.get(0));
    }

    /** Drawable size in real pixels (what Minecraft's framebuffer must match). */
    public static int[] getWindowSizeInPixels(long window) {
        try {
            IntBuffer a = intBuf(), b = intBuf();
            video("SDL_GetWindowSizeInPixels", new Class<?>[]{long.class, IntBuffer.class, IntBuffer.class}, window, a, b);
            return new int[]{a.get(0), b.get(0)};
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setBordered(long window, boolean bordered) throws Exception {
        video("SDL_SetWindowBordered", new Class<?>[]{long.class, boolean.class}, window, bordered);
    }

    public static void setResizable(long window, boolean resizable) throws Exception {
        video("SDL_SetWindowResizable", new Class<?>[]{long.class, boolean.class}, window, resizable);
    }

    public static void setBounds(long window, Rectangle r) throws Exception {
        video("SDL_SetWindowSize", new Class<?>[]{long.class, int.class, int.class}, window, Math.max(1, r.width), Math.max(1, r.height));
        video("SDL_SetWindowPosition", new Class<?>[]{long.class, int.class, int.class}, window, r.x, r.y);
    }

    private static long maximizedFlag() {
        try {
            return ((Number) cls(SDL_VIDEO).getField("SDL_WINDOW_MAXIMIZED").get(null)).longValue();
        } catch (Throwable t) {
            return 0x80L; //SDL3 value
        }
    }

    public static boolean isMaximized(long window) {
        try {
            long flags = ((Number) video("SDL_GetWindowFlags", new Class<?>[]{long.class}, window)).longValue();
            return (flags & maximizedFlag()) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void maximize(long window) throws Exception {
        video("SDL_MaximizeWindow", new Class<?>[]{long.class}, window);
    }

    /** Un-maximizes the window (back to its normal size). */
    public static void restore(long window) throws Exception {
        video("SDL_RestoreWindow", new Class<?>[]{long.class}, window);
    }

    public static void sync(long window) {
        try {
            video("SDL_SyncWindow", new Class<?>[]{long.class}, window);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------------------------------------------------
    // Displays
    // ------------------------------------------------------------------------------------------------------------

    private static Rectangle getDisplayBounds(int displayId) throws Exception {
        Class<?> rectClass = cls(SDL_RECT);
        Object rect = rectClass.getMethod("calloc").invoke(null);
        try {
            Object ok = video("SDL_GetDisplayBounds", new Class<?>[]{int.class, rectClass}, displayId, rect);
            if (ok instanceof Boolean && !((Boolean) ok))
                return null;
            int x = ((Number) rectClass.getMethod("x").invoke(rect)).intValue();
            int y = ((Number) rectClass.getMethod("y").invoke(rect)).intValue();
            int w = ((Number) rectClass.getMethod("w").invoke(rect)).intValue();
            int h = ((Number) rectClass.getMethod("h").invoke(rect)).intValue();
            if (w <= 0 || h <= 0)
                return null;
            return new Rectangle(x, y, w, h);
        } finally {
            try { rectClass.getMethod("free").invoke(rect); } catch (Throwable ignored) {}
        }
    }

    /** Monitor the window is currently on. */
    public static Rectangle getCurrentDisplayBounds(long window) throws Exception {
        int id = ((Number) video("SDL_GetDisplayForWindow", new Class<?>[]{long.class}, window)).intValue();
        if (id == 0)
            id = ((Number) video("SDL_GetPrimaryDisplay", new Class<?>[0])).intValue();
        return getDisplayBounds(id);
    }

    /** Monitor by 1-based index (same numbering as the "fullscreenMonitor" config option), or null. */
    public static Rectangle getDisplayBoundsByIndex(int monitorIndex) {
        if (monitorIndex < 1)
            return null;
        try {
            Object result = video("SDL_GetDisplays", new Class<?>[0]);
            if (!(result instanceof IntBuffer))
                return null;
            IntBuffer ids = (IntBuffer) result;
            try {
                if (ids.remaining() < monitorIndex)
                    return null;
                return getDisplayBounds(ids.get(ids.position() + monitorIndex - 1));
            } finally {
                try {
                    cls(SDL_STDINC).getMethod("SDL_free", Buffer.class).invoke(null, ids);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            LogHelper.warn("Could not list monitors through SDL: " + t);
            return null;
        }
    }
}
