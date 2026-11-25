#!/system/bin/sh
# Direct Power — controle de passagem direta exclusivo p/ Galaxy S25 FE (SM-S731x)
# Uso: ctl.sh on | off | status | check
MODDIR=${0%/*}
PS=/sys/class/power_supply/battery
NODE=$PS/batt_slate_mode   # 1 = fonte alimenta o aparelho, bateria fica parada (bypass)

MODEL=$(getprop ro.product.model)
DEVICE=$(getprop ro.product.device)

is_s25fe() {
  case "$MODEL" in SM-S731*) return 0 ;; esac
  case "$DEVICE" in r*q|*s25fe*) [ "$MODEL" = "SM-S731B" ] && return 0 ;; esac
  return 1
}

if ! is_s25fe && [ ! -f "$MODDIR/.force" ]; then
  echo "UNSUPPORTED:$MODEL"
  exit 2
fi

if [ ! -e "$NODE" ]; then
  echo "NONODE"
  exit 3
fi

case "$1" in
  on)  [ "$(cat $NODE 2>/dev/null)" = "1" ] || echo 1 > "$NODE" ;;
  off) [ "$(cat $NODE 2>/dev/null)" = "0" ] || echo 0 > "$NODE" ;;
  status) echo "$(cat $NODE 2>/dev/null)" ;;
  check) echo "OK:$MODEL" ;;
  *) echo "uso: ctl.sh on|off|status|check"; exit 1 ;;
esac
