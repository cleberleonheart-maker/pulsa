#!/usr/bin/env bash
set -euo pipefail

AAPT2="${AAPT2:-/root/android-sdk/aapt2emu/aapt2}"
LIB_PREFIX='/(abc_|m3_|mtrl_|material_|design_|common_|compat_|widget_|notification_|mdc_|test_|switch_|btn_)'
DEFAULT_FILTER='^res/(drawable|layout|color)/'

usage() {
    echo "uso: $0 dump  <apk> <outdir> [filtro]   # baseline: arvores XML compiladas (cores resolvidas)"
    echo "     $0 gate  <apk> <baseline> [filtro]   # falha se a arvore compilada mudou"
    echo "     filtro padrao: $DEFAULT_FILTER"
}

list_app_res() {
    unzip -Z1 "$1" \
        | grep -E "${2}[A-Za-z0-9_]+\.xml$" \
        | grep -vE "$LIB_PREFIX" \
        | sort -u
}

tree() {
    local apk="$1" listfile="$2" out="$3"
    local args=() f
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        args+=(--file "$f")
    done < "$listfile"
    "$AAPT2" dump xmltree "${args[@]}" "$apk" > "$out.raw" 2>/dev/null
    python3 "$(dirname "$0")/normalizar-dump.py" "$out.raw" "$apk" "$out" "$AAPT2" >/dev/null
    rm -f "$out.raw"
}

dump_to() {
    local apk="$1" outdir="$2" filter="$3"
    mkdir -p "$outdir"
    list_app_res "$apk" "$filter" > "$outdir/files.list"
    tree "$apk" "$outdir/files.list" "$outdir/tree.txt"
}

cmd="${1:-}"; shift || true

case "$cmd" in
dump)
    dump_to "$1" "$2" "${3:-$DEFAULT_FILTER}"
    echo "baseline: $(wc -l < "$2/files.list") recursos, $(wc -l < "$2/tree.txt") linhas -> $2"
    ;;
gate)
    apk="$1"; base="$2"; filter="${3:-$DEFAULT_FILTER}"
    [ -f "$base/tree.txt" ] || { echo "baseline invalida: $base/tree.txt" >&2; exit 2; }
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT
    dump_to "$apk" "$tmp" "$filter"
    rc=0
    diff -q "$base/files.list" "$tmp/files.list" >/dev/null || {
        echo "AVISO: lista de recursos mudou:"; diff "$base/files.list" "$tmp/files.list" | head -20; rc=1; }
    if diff -q "$base/tree.txt" "$tmp/tree.txt" >/dev/null; then
        echo "OK: arvore compilada identica a baseline"
    else
        echo "REGRESSAO: recursos compilados mudaram ($(diff "$base/tree.txt" "$tmp/tree.txt" | grep -c '^[<>]') linhas)"
        rc=1
    fi
    [ "$rc" -eq 0 ] || {
        echo "primeiras linhas divergentes:"
        diff "$base/tree.txt" "$tmp/tree.txt" | head -20
        echo "para localizar: reexecute o gate com um filtro menor (ex.: ^res/drawable/)"
    }
    exit $rc
    ;;
*)
    usage
    exit 2
    ;;
esac
