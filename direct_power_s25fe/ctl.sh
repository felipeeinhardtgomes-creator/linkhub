#!/system/bin/sh
# Direct Power S25 FE (One UI 9) — usa a proteção de bateria nativa da Samsung via settings (sem root)
# Uso: ctl.sh on <alvo%> | off | status | check | diag
MODDIR=${0%/*}
LOG="$MODDIR/log.txt"
ORIG="$MODDIR/orig"
MODEL=$(getprop ro.product.model)

log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; [ "$(wc -c < "$LOG" 2>/dev/null)" -gt 20000 ] && tail -n 80 "$LOG" > "$LOG.t" && mv "$LOG.t" "$LOG"; }

case "$MODEL" in SM-S731*) ;; *) [ -f "$MODDIR/.force" ] || { echo "UNSUPPORTED:$MODEL"; exit 2; } ;; esac

gget() { settings get global "$1" 2>/dev/null; }

save_orig() { # guarda valores originais uma única vez
  [ -f "$ORIG" ] && return
  { echo "protect=$(gget protect_battery)"; echo "thr=$(gget battery_protection_threshold)"; } > "$ORIG"
  log "originais salvos: $(tr '\n' ' ' < $ORIG)"
}

case "$1" in
  on)
    T=$2
    case "$T" in ''|*[!0-9]*) echo "ERR:alvo invalido"; exit 1 ;; esac
    [ "$T" -lt 1 ] && { echo "ERR:alvo invalido"; exit 1; }
    save_orig
    [ "$(gget protect_battery)" = "1" ] || settings put global protect_battery 1
    [ "$(gget battery_protection_threshold)" = "$T" ] || settings put global battery_protection_threshold "$T"
    got=$(gget battery_protection_threshold)
    if [ "$got" = "$T" ]; then echo "OK:protecao=$T"; else log "falhou: escreveu $T leu '$got'"; echo "ERR:leu $got"; exit 3; fi ;;
  off)
    if [ -f "$ORIG" ]; then
      thr=$(sed -n 's/^thr=//p' "$ORIG"); pr=$(sed -n 's/^protect=//p' "$ORIG")
      [ -n "$thr" ] && [ "$thr" != "null" ] && settings put global battery_protection_threshold "$thr"
      [ -n "$pr" ] && [ "$pr" != "null" ] && settings put global protect_battery "$pr"
      rm -f "$ORIG"; log "restaurado thr=$thr protect=$pr"
    fi
    echo "OK:restaurado" ;;
  status) echo "$(gget protect_battery) $(gget battery_protection_threshold)" ;;
  check)  echo "OK:$MODEL" ;;
  diag)
    echo "modelo=$MODEL uid=$(id -u)"
    echo "protect_battery=$(gget protect_battery) threshold=$(gget battery_protection_threshold) recharge=$(gget battery_protection_recharge_level)"
    cat "$ORIG" 2>/dev/null; dumpsys battery | grep -E "^  (status|level|AC powered)" ;;
  *) echo "uso: ctl.sh on <alvo>|off|status|check|diag"; exit 1 ;;
esac
