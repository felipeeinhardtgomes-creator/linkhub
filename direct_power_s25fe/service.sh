#!/system/bin/sh
MODDIR=${0%/*}
TARGET_FILE="$MODDIR/target_level"
PIDF="$MODDIR/worker.pid"
CTL="$MODDIR/ctl.sh"
[ -f "$TARGET_FILE" ] || echo "0" > "$TARGET_FILE"

# evita duplicar
if [ -f "$PIDF" ] && kill -0 "$(cat $PIDF)" 2>/dev/null; then exit 0; fi
echo $$ > "$PIDF"

STATE=off
set_state() { [ "$STATE" = "$1" ] && return; sh "$CTL" "$1" >/dev/null 2>&1; STATE=$1; }

# Loop permanente (não depende de logcat nem da UI aberta)
while true; do
  TARGET=$(cat "$TARGET_FILE" 2>/dev/null || echo 0)
  B=$(dumpsys battery)
  LEVEL=$(echo "$B" | awk '/^  level:/{print $2}')
  PWR=0; echo "$B" | grep -q -E "(AC|USB|Wireless|Dock) powered: true" && PWR=1

  if [ "$TARGET" -gt 0 ] && [ "$PWR" = 1 ] && [ -n "$LEVEL" ]; then
    if [ "$LEVEL" -ge "$TARGET" ]; then set_state on
    else set_state off; fi
    D=$((TARGET-LEVEL)); [ $D -lt 0 ] && D=$((-D))
    [ $D -le 3 ] && S=2 || S=10
  else
    # sem limite, sem carregador ou desligado: garante carga normal
    STATE=x; set_state off
    S=10
  fi
  sleep $S
done
