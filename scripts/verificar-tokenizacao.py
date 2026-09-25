#!/usr/bin/env python3
"""Prova que a tokenizacao nao alterou nenhum valor de cor.

Para cada arquivo alterado, compara a sequencia ordenada de cores
(resolvendo @color/x no colors.xml) entre a versao original e a atual.
"""
import os
import re
import sys

BASE = sys.argv[1] if len(sys.argv) > 1 else "/tmp/opencode/res-backup-t01"
CUR = "/root/pulsa/app/src/main/res"
DIRS = ["drawable", "layout", "color"]

HEX = re.compile(r"#[0-9A-Fa-f]{6,8}")
REF = re.compile(r"@color/([A-Za-z0-9_]+)")


def norm_hex(h):
    h = h[1:].upper()
    return "#" + (h if len(h) == 8 else "FF" + h)


def load_colors():
    vals = {}
    for f in os.listdir(f"{CUR}/values"):
        if not f.endswith(".xml"):
            continue
        t = open(f"{CUR}/values/{f}", encoding="utf-8").read()
        for n, v in re.findall(r'<color name="([^"]+)">\s*(#[0-9A-Fa-f]{6,8})\s*</color>', t):
            vals[n] = norm_hex(v)
    return vals


def colors_of(text, vals):
    out = []
    for m in re.finditer(r"#[0-9A-Fa-f]{6,8}|@color/[A-Za-z0-9_]+", text):
        tok = m.group(0)
        if tok.startswith("#"):
            out.append(norm_hex(tok))
        else:
            name = tok.split("/", 1)[1]
            if name not in vals:
                sys.exit(f"token sem valor: {name}")
            out.append(vals[name])
    return out


def strip_colors(text):
    return REF.sub("<C>", HEX.sub("<C>", text))


def main():
    vals = load_colors()
    changed = same_color = struct_diff = 0
    problems = []
    for d in DIRS:
        bd, cd = f"{BASE}/{d}", f"{CUR}/{d}"
        if not os.path.isdir(bd):
            continue
        for f in sorted(os.listdir(bd)):
            if not f.endswith(".xml"):
                continue
            old = open(f"{bd}/{f}", encoding="utf-8").read()
            new = open(f"{cd}/{f}", encoding="utf-8").read()
            if old == new:
                continue
            changed += 1
            if colors_of(old, vals) == colors_of(new, vals):
                same_color += 1
            else:
                problems.append(f"{d}/{f}: valores de cor diferentes")
            if strip_colors(old) != strip_colors(new):
                struct_diff += 1
                problems.append(f"{d}/{f}: estrutura alem das cores mudou")
    print(f"arquivos alterados:   {changed}")
    print(f"mesma cor resolvida:  {same_color}")
    print(f"estrutura identica:   {changed - struct_diff}")
    if problems:
        print("PROBLEMAS:")
        for p in problems:
            print("  " + p)
        sys.exit(1)
    print("OK: tokenizacao fiel em cor e estrutura")


if __name__ == "__main__":
    main()
