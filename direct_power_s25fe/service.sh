#!/system/bin/sh
MODDIR=${0%/*}
TARGET_FILE="$MODDIR/target_level"
WORKER_PID_FILE="$MODDIR/worker.pid"

[ -f "$TARGET_FILE" ] || echo "0" > "$TARGET_FILE"

get_level() {
  dumpsys battery | grep "  level:" | awk '{print $2}'
}

set_pass_through() {
  TARGET="$1"
  if [ "$TARGET" = "1" ]; then
    sh "$MODDIR/ctl.sh" on
  else
    sh "$MODDIR/ctl.sh" off
  fi
}

abs() {
  [ "$1" -lt 0 ] && echo $((-$1)) || echo "$1"
}

worker() {
  while true; do
    TARGET=$(cat "$TARGET_FILE" 2>/dev/null || echo "0")

    if [ "$TARGET" -gt 0 ]; then
      LEVEL=$(get_level)
      DIFF=$(abs $((TARGET - LEVEL)))

      if [ "$LEVEL" -ge "$TARGET" ]; then
        set_pass_through 1
      else
        set_pass_through 0
      fi

      if [ "$DIFF" -le 2 ]; then
        S=1
      elif [ "$DIFF" -le 5 ]; then
        S=2
      elif [ "$DIFF" -le 15 ]; then
        S=3
      else
        S=5
      fi
    else
      set_pass_through 0
      S=6
    fi

    sleep "$S"
  done
}

start_worker() {
  if [ ! -f "$WORKER_PID_FILE" ] || ! kill -0 "$(cat $WORKER_PID_FILE)" 2>/dev/null; then
    worker &
    echo $! > "$WORKER_PID_FILE"
  fi
}

stop_worker() {
  if [ -f "$WORKER_PID_FILE" ]; then
    kill "$(cat $WORKER_PID_FILE)" 2>/dev/null
    rm -f "$WORKER_PID_FILE"
  fi
  set_pass_through 0
}

# Se já estiver na tomada ao iniciar, começa o worker
dumpsys battery | grep -q "powered: true" && start_worker

logcat -c 2>/dev/null
logcat -s BatteryService:D -e 'ACTION_POWER_CONNECTED|ACTION_POWER_DISCONNECTED' | while read L; do
  case "$L" in
    *ACTION_POWER_CONNECTED*)    start_worker ;;
    *ACTION_POWER_DISCONNECTED*) stop_worker ;;
  esac
done &

wait
