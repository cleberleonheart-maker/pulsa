#!/usr/bin/env python3
"""T0.2b - renomeia os tokens que assumiram a paleta Aurora e deduplica.

Nao muda nenhum valor: so o nome. Tokens cujo valor ja existe sob um nome
antigo sao removidos e as referencias passam a apontar para o sobrevivente.
"""
import re
import sys
from collections import defaultdict

RES = "/root/pulsa/app/src/main/res"
COLORS = f"{RES}/values/colors.xml"
DIRS = ["drawable", "layout", "color"]

PREFIX = [
    ("neon_green_", "aurora_violet_"),
    ("neon_lime_", "aurora_cyan_"),
    ("neon_red_", "aurora_rose_"),
    ("neon_coral_", "aurora_pink_"),
]
EXACT = {
    "neon_green": "aurora_violet",
    "neon_lime": "aurora_cyan",
    "neon_red": "aurora_rose",
    "neon_coral": "aurora_pink",
    "chartreuse": "aurora_teal",
    "radar_bg": "bg",
    "radar_raised": "bg_elev",
    "radar_deep": "surface",
    "radar_panel_aF0": "surface_aF0",
    "radar_sunken": "glass_fill",
    "radar_home": "surface_variant",
    "radar_art": "glass_fill_strong",
    "radar_line_soft": "neon_border_soft",
}


def rename(name):
    if name in EXACT:
        return EXACT[name]
    m = re.match(r"^(radar_[a-z_]+)_a([0-9A-F]{2})$", name)
    if m and m.group(1) in EXACT:
        return f"{EXACT[m.group(1)]}_a{m.group(2)}"
    for old, new in PREFIX:
        if name.startswith(old):
            return new + name[len(old):]
    return name


def main():
    text = open(COLORS, encoding="utf-8").read()
    items = re.findall(r'<color name="([^"]+)">\s*(#[0-9A-Fa-f]{6,8})\s*</color>', text)

    renamed = {n: rename(n) for n, _ in items}
    changed = {k: v for k, v in renamed.items() if k != v}

    # quem ja tinha o valor antes da renomeacao sobrevive
    before = {n.upper(): v.upper() for n, v in items}
    survivor = {}
    dropped = set()
    by_value = defaultdict(list)
    for n, v in items:
        by_value[v.upper()].append(n)
    survivor = {}
    dropped = set()
    for value, names in by_value.items():
        if len(names) == 1:
            survivor[value] = names[0]
            continue
        # token preexistente nunca e removido: pode estar referenciado em Kotlin
        kept = sorted(n for n in names if n not in changed)
        if kept:
            survivor[value] = kept[0]
            dropped |= {n for n in names if n in changed}
        else:
            # todos foram renomeados: fica o de nome novo menor
            novo = sorted(renamed[n] for n in names)[0]
            survivor[value] = novo
            dropped |= {n for n in names if renamed[n] != novo}

    kept = []
    for n, v in items:
        if n in dropped:
            continue
        name = renamed[n]
        if any(name == k for k, _ in kept):
            continue
        kept.append((name, v))

    # reescreve o arquivo linha a linha, preservando comentarios e ordem
    out_lines = []
    emitted = set()
    for line in text.splitlines():
        m = re.match(r'(\s*<color name=")([^"]+)(">\s*)(#[0-9A-Fa-f]{6,8})(</color>)', line)
        if not m:
            out_lines.append(line)
            continue
        n = m.group(2)
        if n in dropped:
            continue
        nome = renamed[n]
        if nome in emitted:
            continue
        emitted.add(nome)
        out_lines.append(m.group(1) + nome + m.group(3) + m.group(4) + m.group(5))
    open(COLORS, "w", encoding="utf-8").write("\n".join(out_lines) + "\n</resources>\n")

    # mapa final para reescrever as referencias
    remap = {}
    for n, _ in items:
        if n in dropped:
            target = survivor[before[n.upper()]]
            remap[n] = renamed.get(target, rename(target))
        else:
            remap[n] = renamed[n]

    refs = 0
    for d in DIRS:
        import os
        for f in sorted(os.listdir(f"{RES}/{d}")):
            if not f.endswith(".xml"):
                continue
            p = f"{RES}/{d}/{f}"
            t = open(p, encoding="utf-8").read()
            orig = t
            for old, new in sorted(remap.items(), key=lambda kv: -len(kv[0])):
                if old != new:
                    t = t.replace(f"@color/{old}", f"@color/{new}")
            if t != orig:
                open(p, "w", encoding="utf-8").write(t)
                refs += 1

    print(f"tokens: {len(items)} -> {len(kept)}")
    print(f"renomeados: {len(changed)}  removidos por duplicar: {len(dropped)}")
    print(f"arquivos com referencia atualizada: {refs}")
    left = [n for n, _ in items if re.match(r"^(radar_|neon_green|neon_lime|neon_red|neon_coral|chartreuse)", n)]
    print(f"nomes Radar restantes no catalogo: {len(left)} {left[:5]}")


if __name__ == "__main__":
    main()
