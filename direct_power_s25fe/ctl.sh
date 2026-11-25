#!/system/bin/sh
# Direct Power S25 FE — uso: ctl.sh on|off|status|check|diag
MODDIR=${0%/*}
PS=/sys/class/power_supply/battery
LOG="$MODDIR/log.txt"
MODEL=$(getprop ro.product.model)

# métodos: nó:valor_ligado:valor_desligado  (1º = bypass real; demais = só pausam a carga)
METHODS="batt_slate_mode:1:0 input_suspend:1:0 charging_enabled:0:1 charge_disable:1:0 store_mode:1:0"

log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; [ "$(wc -c < "$LOG" 2>/dev/null)" -gt 20000 ] && tail -n 80 "$LOG" > "$LOG.t" && mv "$LOG.t" "$LOG"; }

case "$MODEL" in SM-S731*) ;; *) [ -f "$MODDIR/.force" ] || { echo "UNSUPPORTED:$MODEL"; exit 2; } ;; esac

apply() { # $1=on|off  -> tenta cada método até um ser aceito e confirmado
  for m in $METHODS; do
    n=${m%%:*}; r=${m#*:}; von=${r%%:*}; voff=${r#*:}
    [ "$1" = on ] && v=$von || v=$voff
    [ -e "$PS/$n" ] || continue
    echo "$v" > "$PS/$n" 2>/dev/null
    now=$(cat "$PS/$n" 2>/dev/null)
    if [ "$now" = "$v" ]; then
      echo "$n" > "$MODDIR/method"
      log "$1 via $n ($now)"
      echo "OK:$n"; return 0
    fi
    log "falhou $n: escreveu $v, leu '$now'"
  done
  log "NENHUM método funcionou para $1 (uid=$(id -u))"
  echo "ERR:sem método (uid=$(id -u))"; return 3
}

case "$1" in
  on|off) apply "$1" ;;
  status) n=$(cat "$MODDIR/method" 2>/dev/null || echo batt_slate_mode); cat "$PS/$n" 2>/dev/null ;;
  check)  echo "OK:$MODEL uid=$(id -u)" ;;
  diag)
    echo "modelo=$MODEL uid=$(id -u) selinux=$(getenforce 2>/dev/null)"
    for m in $METHODS; do n=${m%%:*}; [ -e "$PS/$n" ] && echo "$n existe valor=$(cat $PS/$n 2>/dev/null) escrita=$([ -w $PS/$n ] && echo sim || echo nao)" || echo "$n NAO existe"; done
    echo "android=$(getprop ro.build.version.release) oneui=$(getprop ro.build.version.oneui) fw=$(getprop ro.build.display.id)"
    echo "--- nós relevantes em power_supply:"
    for d in /sys/class/power_supply/*; do echo "[$d]"; ls $d | grep -i -E "slate|suspend|charg|store|full|protect|bypass|limit|input|sleep|current_max"; done
    echo "--- settings:"; for t in global system secure; do settings list $t 2>/dev/null | grep -i -E "protect|batt|charg|bypass" | sed "s/^/$t: /"; done
    echo "--- dumpsys battery:"; dumpsys battery | head -25 ;;
  *) echo "uso: ctl.sh on|off|status|check|diag"; exit 1 ;;
esac
