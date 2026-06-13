# BarkLight Server

Servidor JAR que corre **dentro del TV via ADB shell** con permisos de
`shell`/`graphics`. Usa `SurfaceControl.screenshot()` por reflexión para
leer el framebuffer **sin crear un VirtualDisplay** y sin `MediaProjection`.

## Por qué esto soluciona el lag de video

- `MediaProjection` + `VirtualDisplay` obliga al compositor de Android a
  renderizar **dos** displays simultáneamente → compite con el decoder de
  video → stuttering en YouTube/Netflix.
- `SurfaceControl.screenshot()` es una captura **directa del framebuffer**,
  sin crear displays adicionales. Es la misma técnica que usa `scrcpy`.
- Solo apps con uid `shell` o `graphics` pueden llamar este método —
  por eso el JAR corre via `adb shell`, no como parte de la APK normal.

## Compilar

```bash
mkdir output
docker build -t barklight-server-builder .
docker run --rm -v "$(pwd)/output:/output" barklight-server-builder
```

Genera `output/barklight-server.jar`.

## Instalar y correr en el TV

```bash
# 1. Copiar el JAR al TV
adb push output/barklight-server.jar /data/local/tmp/

# 2. Ejecutar — queda corriendo en foreground (usar & o nohup para background)
adb shell CLASSPATH=/data/local/tmp/barklight-server.jar \
          app_process / com.barklight.server.BarkLightServer

# Para correrlo en background y que sobreviva al cierre de la sesión adb:
adb shell "CLASSPATH=/data/local/tmp/barklight-server.jar \
          nohup app_process / com.barklight.server.BarkLightServer > /data/local/tmp/barklight.log 2>&1 &"
```

## Verificar que está corriendo

```bash
adb shell ps | grep app_process
adb shell cat /data/local/tmp/barklight.log
```

Deberías ver:
```
[BarkLight] Servidor iniciando en puerto 7070
[BarkLight] SurfaceControl accesible — OK
[BarkLight] Escuchando en :7070
```

## Auto-inicio

Para que el servidor arranque automáticamente al encender el TV, hay que
ejecutarlo después de cada boot. Opciones:

1. **Manual**: ejecutar el comando `adb shell ... &` después de encender el TV
2. **Script en la Pi/PC**: un cron/systemd que detecte cuando el TV está
   disponible por ADB y lance el servidor automáticamente

Ver `start-server.sh` para la opción 2.

## Troubleshooting

**"Sin acceso a SurfaceControl"**
El método `getBuiltInDisplay` o `screenshot` no existe en esta versión de
Android. Revisar los nombres de método para tu API level con:
```bash
adb shell "CLASSPATH=/data/local/tmp/barklight-server.jar app_process / com.barklight.server.BarkLightServer" 2>&1
```

**Conexión rechazada desde la APK**
El servidor escucha en `127.0.0.1:7070` — solo accesible desde el propio TV.
La APK BarkLight se conecta a `localhost:7070` automáticamente.
