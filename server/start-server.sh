#!/usr/bin/env bash
# start-server.sh — Inicia el servidor BarkLight en el TV via ADB
#
# Uso:
#   ./start-server.sh [IP_DEL_TV]
#
# Si no se especifica IP, usa el dispositivo ADB ya conectado.

set -e

JAR_PATH="output/barklight-server.jar"
REMOTE_PATH="/data/local/tmp/barklight-server.jar"
LOG_PATH="/data/local/tmp/barklight.log"

if [ ! -f "$JAR_PATH" ]; then
    echo "Error: $JAR_PATH no existe. Compila primero con Docker."
    exit 1
fi

ADB_TARGET=""
if [ -n "$1" ]; then
    ADB_TARGET="-s $1:5555"
    adb connect "$1:5555" || true
fi

echo "[1/4] Verificando dispositivo..."
adb $ADB_TARGET devices

echo "[2/4] Matando instancias previas del servidor..."
adb $ADB_TARGET shell "pkill -f barklight-server" 2>/dev/null || true
sleep 1

echo "[3/4] Copiando JAR al TV..."
adb $ADB_TARGET push "$JAR_PATH" "$REMOTE_PATH"

echo "[4/4] Iniciando servidor en background..."
adb $ADB_TARGET shell "CLASSPATH=$REMOTE_PATH nohup app_process32 / com.barklight.server.BarkLightServer > $LOG_PATH 2>&1 &"

sleep 2

echo ""
echo "=== Log del servidor ==="
adb $ADB_TARGET shell cat "$LOG_PATH"
echo ""
echo "Si ves 'Escuchando en :7070' el servidor esta listo."
echo "Ahora abre la app BarkLight y presiona 'Captura pantalla'."
