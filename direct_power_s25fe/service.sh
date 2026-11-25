#!/system/bin/sh
MODDIR=${0%/*}
TARGET_FILE="$MODDIR/target_level"
PIDF="$MODDIR/worker.pid"
CTL="$MODDIR/ctl.sh"
[ -f "$TARGET_FILE" ] || echo "0" > "$TARGET_FILE"

if [ -f "$PIDF" ] && kill -0 "$(cat $PIDF)" 2>/dev/null; then exit 0; fi
echo $$ > "$PIDF"

ACTIVE=0
# O próprio sistema segura a carga no limite; o worker só mantém a configuração aplicada
while true; do
  TARGET=$(cat "$TARGET_FILE" 2>/dev/null || echo 0)
  if [ "$TARGET" -gt 0 ] 2>/dev/null; then
    sh "$CTL" on "$TARGET" >/dev/null 2>&1; ACTIVE=1
  elif [ "$ACTIVE" = 1 ]; then
    sh "$CTL" off >/dev/null 2>&1; ACTIVE=0
  fi
  sleep 15
done
