package com.barklight.server;

import android.graphics.Bitmap;
import android.os.IBinder;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * BarkLight Server — corre via ADB shell con permisos de shell/graphics.
 *
 * Uso:
 *   adb push barklight-server.jar /data/local/tmp/
 *   adb shell CLASSPATH=/data/local/tmp/barklight-server.jar \
 *             app_process / com.barklight.server.BarkLightServer
 *
 * Escucha en :7070. BarkLight APK conecta y pide frames bajo demanda.
 *
 * Usa SurfaceControl.screenshot() via reflexion — el metodo exacto varia
 * segun la version de Android, por eso probamos varias estrategias en orden,
 * igual que hace scrcpy internamente.
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

    // Estrategia 1 (API 29+): getPhysicalDisplayIds() + getPhysicalDisplayToken(long)
    // Estrategia 2 (API <30): getBuiltInDisplay(int)
    private static boolean detectStrategy() {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");

            try {
                Method getIds = sc.getDeclaredMethod("getPhysicalDisplayIds");
                getIds.setAccessible(true);
                long[] ids = (long[]) getIds.invoke(null);
                if (ids != null && ids.length > 0) {
                    Method getToken = sc.getDeclaredMethod("getPhysicalDisplayToken", long.class);
                    getToken.setAccessible(true);
                    Object token = getToken.invoke(null, ids[0]);
                    if (token != null) {
                        cachedDisplayToken = token;
                        strategy = 1;
                        System.out.println("[BarkLight] getPhysicalDisplayIds/Token disponible. ids=" + ids.length);
                        return true;
                    }
                }
            } catch (Exception e) {
                System.out.println("[BarkLight] Estrategia 1 no disponible: " + e);
            }

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
            Bitmap bmp = tryScreenshot();
            if (bmp == null) return null;

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
            bmp.recycle();
            return baos.toByteArray();
        } catch (Exception e) {
            System.err.println("[BarkLight] Error en captureFrame: " + e);
            return null;
        }
    }

    private static Bitmap tryScreenshot() throws Exception {
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        IBinder token = (IBinder) cachedDisplayToken;

        // Firma A (API 29-30): screenshot(IBinder, int width, int height)
        try {
            Method m = sc.getDeclaredMethod("screenshot", IBinder.class, int.class, int.class);
            m.setAccessible(true);
            Object result = m.invoke(null, token, CAPTURE_W, CAPTURE_H);
            if (result instanceof Bitmap) return (Bitmap) result;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            System.err.println("[BarkLight] Firma A fallo: " + e);
        }

        // Firma B (API <29): screenshot(Rect, int, int, boolean)
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

        // Firma C: screenshot(IBinder, Rect, int, int, boolean, int)
        try {
            Method m = sc.getDeclaredMethod("screenshot",
                IBinder.class, android.graphics.Rect.class, int.class, int.class, boolean.class, int.class);
            m.setAccessible(true);
            Object result = m.invoke(null, token, new android.graphics.Rect(), CAPTURE_W, CAPTURE_H, false, 0);
            if (result instanceof Bitmap) return (Bitmap) result;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            System.err.println("[BarkLight] Firma C fallo: " + e);
        }

        return null;
    }
}
