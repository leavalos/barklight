package com.barklight.server;

import android.graphics.Bitmap;
import android.os.IBinder;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * BarkLight Server — corre via ADB shell con permisos de shell/graphics.
 *
 * Uso:
 *   adb push barklight-server.jar /data/local/tmp/
 *   adb shell CLASSPATH=/data/local/tmp/barklight-server.jar \
 *             app_process32 / com.barklight.server.BarkLightServer
 *
 * Escucha en :7070. BarkLight APK conecta y pide frames bajo demanda.
 *
 * Estrategias de captura (en orden de prueba):
 *  - Android 11+ (API 30+): SurfaceControl.captureDisplay(DisplayCaptureArgs)
 *    -> ScreenshotHardwareBuffer -> Bitmap.wrapHardwareBuffer()
 *  - Android <11: getBuiltInDisplay(int) + screenshot(IBinder, int, int)
 *  - Android <10: screenshot(Rect, int, int, boolean)
 */
public class BarkLightServer {

    private static final int PORT = 7070;
    private static final int JPEG_QUALITY = 40;
    private static final int CAPTURE_W = 214;
    private static final int CAPTURE_H = 120;

    private static int strategy = -1;
    private static Object cachedDisplayToken = null;

    public static void main(String[] args) throws Exception {
        System.out.println("[BarkLight] Iniciando - resolucion " + CAPTURE_W + "x" + CAPTURE_H);
        System.out.println("[BarkLight] Android version: " + android.os.Build.VERSION.SDK_INT);

        if (!detectStrategy()) {
            System.err.println("[BarkLight] ERROR: Ninguna estrategia de SurfaceControl funciono.");
        } else {
            System.out.println("[BarkLight] Estrategia detectada: " + strategy);
            byte[] test = captureFrame();
            if (test != null) {
                System.out.println("[BarkLight] Captura de prueba OK (" + test.length + " bytes)");
            } else {
                System.err.println("[BarkLight] Captura de prueba fallo - revisar permisos");
            }
        }

        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("[BarkLight] Escuchando en :" + PORT);
            while (true) {
                Socket client = server.accept();
                System.out.println("[BarkLight] Cliente conectado: " + client.getInetAddress());
                handleClient(client);
            }
        }
    }

    private static void handleClient(Socket socket) {
        Thread t = new Thread(() -> {
            try (DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
                byte[] req = new byte[1];
                while (!socket.isClosed()) {
                    int read = socket.getInputStream().read(req);
                    if (read == -1) break;
                    if (req[0] == 1) {
                        byte[] jpeg = captureFrame();
                        if (jpeg != null) {
                            out.writeInt(jpeg.length);
                            out.write(jpeg);
                        } else {
                            out.writeInt(0);
                        }
                        out.flush();
                    }
                }
            } catch (IOException e) {
                System.out.println("[BarkLight] Cliente desconectado: " + e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ── DETECCION DE ESTRATEGIA ───────────────────────────────────────────────

    private static boolean detectStrategy() {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");

            // Estrategia 3 (API 30+): getInternalDisplayToken() + captureDisplay()
            try {
                Method getToken = sc.getDeclaredMethod("getInternalDisplayToken");
                getToken.setAccessible(true);
                Object token = getToken.invoke(null);
                if (token != null) {
                    cachedDisplayToken = token;
                    strategy = 3;
                    System.out.println("[BarkLight] getInternalDisplayToken disponible");
                    return true;
                }
            } catch (Exception e) {
                System.out.println("[BarkLight] Estrategia 3 no disponible: " + e);
            }

            // Estrategia 1 (API 29): getPhysicalDisplayIds / getPhysicalDisplayToken
            try {
                Method getIds = sc.getDeclaredMethod("getPhysicalDisplayIds");
                getIds.setAccessible(true);
                long[] ids = (long[]) getIds.invoke(null);
                if (ids != null && ids.length > 0) {
                    Method getTok = sc.getDeclaredMethod("getPhysicalDisplayToken", long.class);
                    getTok.setAccessible(true);
                    Object token = getTok.invoke(null, ids[0]);
                    if (token != null) {
                        cachedDisplayToken = token;
                        strategy = 1;
                        System.out.println("[BarkLight] getPhysicalDisplayIds/Token disponible");
                        return true;
                    }
                }
            } catch (Exception e) {
                System.out.println("[BarkLight] Estrategia 1 no disponible: " + e);
            }

            // Estrategia 2 (API <29): getBuiltInDisplay(0)
            try {
                Method getBuiltIn = sc.getDeclaredMethod("getBuiltInDisplay", int.class);
                getBuiltIn.setAccessible(true);
                Object token = getBuiltIn.invoke(null, 0);
                if (token != null) {
                    cachedDisplayToken = token;
                    strategy = 2;
                    System.out.println("[BarkLight] getBuiltInDisplay disponible");
                    return true;
                }
            } catch (Exception e) {
                System.out.println("[BarkLight] Estrategia 2 no disponible: " + e);
            }

        } catch (Exception e) {
            System.err.println("[BarkLight] No se pudo cargar SurfaceControl: " + e);
        }
        return false;
    }

    private static byte[] captureFrame() {
        if (strategy == -1 || cachedDisplayToken == null) return null;
        try {
            Bitmap bmp = (strategy == 3) ? captureDisplayApi30() : tryScreenshotLegacy();
            if (bmp == null) return null;

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
            bmp.recycle();
            return baos.toByteArray();
        } catch (Exception e) {
            System.err.println("[BarkLight] Error en captureFrame: " + e);
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Android 11+ (API 30): SurfaceControl.captureDisplay(DisplayCaptureArgs)
     *
     * Equivalente Java:
     *   IBinder token = SurfaceControl.getInternalDisplayToken();
     *   SurfaceControl.DisplayCaptureArgs.Builder b =
     *       new SurfaceControl.DisplayCaptureArgs.Builder(token);
     *   b.setSize(CAPTURE_W, CAPTURE_H);
     *   SurfaceControl.ScreenshotHardwareBuffer shb =
     *       SurfaceControl.captureDisplay(b.build());
     *   Bitmap bmp = Bitmap.wrapHardwareBuffer(shb.getHardwareBuffer(), shb.getColorSpace());
     */
    private static Bitmap captureDisplayApi30() throws Exception {
        Class<?> scClass = Class.forName("android.view.SurfaceControl");
        Class<?> argsClass = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs");
        Class<?> builderClass = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
        Class<?> shbClass = Class.forName("android.view.SurfaceControl$ScreenshotHardwareBuffer");

        // new Builder(IBinder displayToken)
        Constructor<?> builderCtor = builderClass.getDeclaredConstructor(IBinder.class);
        builderCtor.setAccessible(true);
        Object builder = builderCtor.newInstance((IBinder) cachedDisplayToken);

        // builder.setSize(width, height) — puede no existir en todas las versiones
        try {
            Method setSize = builderClass.getDeclaredMethod("setSize", int.class, int.class);
            setSize.setAccessible(true);
            setSize.invoke(builder, CAPTURE_W, CAPTURE_H);
        } catch (NoSuchMethodException e) {
            System.out.println("[BarkLight] setSize no disponible, usando tamaño nativo");
        }

        // DisplayCaptureArgs args = builder.build()
        Method build = builderClass.getDeclaredMethod("build");
        build.setAccessible(true);
        Object args = build.invoke(builder);

        // ScreenshotHardwareBuffer shb = SurfaceControl.captureDisplay(args)
        Method captureDisplay = scClass.getDeclaredMethod("captureDisplay", argsClass);
        captureDisplay.setAccessible(true);
        Object shb = captureDisplay.invoke(null, args);

        if (shb == null) {
            System.err.println("[BarkLight] captureDisplay devolvio null");
            return null;
        }

        // HardwareBuffer hb = shb.getHardwareBuffer()
        Method getHwBuffer = shbClass.getDeclaredMethod("getHardwareBuffer");
        getHwBuffer.setAccessible(true);
        Object hwBuffer = getHwBuffer.invoke(shb);

        // ColorSpace cs = shb.getColorSpace()
        Object colorSpace = null;
        try {
            Method getCs = shbClass.getDeclaredMethod("getColorSpace");
            getCs.setAccessible(true);
            colorSpace = getCs.invoke(shb);
        } catch (Exception ignored) { }

        // Bitmap bmp = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
        Class<?> hwBufferClass = Class.forName("android.hardware.HardwareBuffer");
        Class<?> colorSpaceClass = Class.forName("android.graphics.ColorSpace");
        Method wrapHwBuffer = Bitmap.class.getDeclaredMethod("wrapHardwareBuffer", hwBufferClass, colorSpaceClass);
        wrapHwBuffer.setAccessible(true);
        Object bmpObj = wrapHwBuffer.invoke(null, hwBuffer, colorSpace);

        if (bmpObj == null) return null;

        // Copiar a un bitmap software (ARGB_8888) para poder comprimir a JPEG
        Bitmap hwBmp = (Bitmap) bmpObj;
        Bitmap swBmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false);
        hwBmp.recycle();
        return swBmp;
    }

    private static Bitmap tryScreenshotLegacy() throws Exception {
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        IBinder token = (IBinder) cachedDisplayToken;

        try {
            Method m = sc.getDeclaredMethod("screenshot", IBinder.class, int.class, int.class);
            m.setAccessible(true);
            Object result = m.invoke(null, token, CAPTURE_W, CAPTURE_H);
            if (result instanceof Bitmap) return (Bitmap) result;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            System.err.println("[BarkLight] Firma A fallo: " + e);
        }

        try {
            Method m = sc.getDeclaredMethod("screenshot",
                android.graphics.Rect.class, int.class, int.class, boolean.class);
            m.setAccessible(true);
            Object result = m.invoke(null, new android.graphics.Rect(), CAPTURE_W, CAPTURE_H, false);
            if (result instanceof Bitmap) return (Bitmap) result;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            System.err.println("[BarkLight] Firma B fallo: " + e);
        }

        return null;
    }
}
