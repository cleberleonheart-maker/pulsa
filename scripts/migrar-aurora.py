#!/usr/bin/env python3
"""T0.2a - migra as familias Terminal Radar para a paleta Aurora Nebula.

Reescreve apenas o VALOR dos tokens em values/colors.xml, preservando o
alpha de cada um. Nenhum arquivo de drawable/layout e tocado, entao a
unica mudanca esperada na arvore compilada sao as cores listadas no relatorio.
"""
import re
import sys

COLORS = "/root/pulsa/app/src/main/res/values/colors.xml"
REPORT = "/root/pulsa/docs/T0-MIGRACAO-AURORA.md"

# familia Radar -> base Aurora Nebula (fe9d719)
HUE = {
    "neon_green": "A78BFA",   # acento dominante -> violeta, heroi da Aurora
    "neon_lime": "22D3EE",    # acento secundario -> ciano
    "neon_red": "FB7185",     # alerta -> vermelho aurora
    "neon_coral": "F472B6",   # acento rosa -> rosa aurora
    "chartreuse": "34D399",   # verde de grafico -> teal
}
# superficie Radar -> superficie Aurora
SURFACE = {
    "radar_bg": "0B0820",          # bg
    "radar_raised": "151030",      # bg_elev
    "radar_deep": "0E0A26",        # surface
    "radar_sunken": "141030",      # glass_fill
    "radar_home": "1D1640",        # surface_variant
    "radar_art": "1E1748",         # glass_fill_strong
    "radar_line": "332B66",        # neon_border
    "radar_line_soft": "463C85",   # neon_border_soft
    "radar_outline": "453C85",     # panel_border
    "radar_panel": "0E0A26",       # surface
}

TOKEN = re.compile(r'(<color name="([^"]+)">)(#[0-9A-Fa-f]{6,8})(</color>)')


def family(name):
    m = re.match(r"(.+?)_a[0-9A-F]{2}$", name)
    return m.group(1) if m else name


def main():
    text = open(COLORS, encoding="utf-8").read()
    changes = []

    def repl(m):
        head, name, old, tail = m.group(1), m.group(2), m.group(3), m.group(4)
        fam = family(name)
        if fam in HUE:
            base = HUE[fam]
        elif fam in SURFACE:
            base = SURFACE[fam]
        else:
            return m.group(0)
        alpha = old[1:3] if len(old) == 9 else "FF"
        new = "#" + alpha + base
        if new.upper() == old.upper():
            return m.group(0)
        changes.append((name, old.upper(), new.upper()))
        return head + new + tail

    out = TOKEN.sub(repl, text)
    open(COLORS, "w", encoding="utf-8").write(out)

    by_fam = {}
    for name, old, new in changes:
        by_fam.setdefault(family(name), []).append((name, old, new))
    print(f"tokens migrados: {len(changes)}")
    for fam in sorted(by_fam):
        olds = {o for _, o, _ in by_fam[fam]}
        news = {n for _, _, n in by_fam[fam]}
        print(f"  {fam:<16}{len(by_fam[fam]):>3} tokens  {sorted(olds)[0]} -> {sorted(news)[0]}")
    if "--relatorio" in sys.argv:
        lines = [
            "# T0.2a - Migracao para Aurora Nebula",
            "",
            "Gerado por `scripts/migrar-aurora.py`. Apenas o valor dos tokens mudou;",
            "o alpha de cada um foi preservado e nenhum drawable/layout foi tocado.",
            "",
            "## Familia -> matiz",
            "",
            "| familia | tokens | de | para |",
            "| --- | --- | --- | --- |",
        ]
        for fam in sorted(by_fam):
            olds = sorted({o for _, o, _ in by_fam[fam]})
            news = sorted({n for _, _, n in by_fam[fam]})
            lines.append(
                f"| `{fam}` | {len(by_fam[fam])} | `{olds[0]}` | `{news[0]}` |"
            )
        lines += ["", "## Tokens", "", "| token | de | para |", "| --- | --- | --- |"]
        for fam in sorted(by_fam):
            for name, old, new in sorted(by_fam[fam]):
                lines.append(f"| `{name}` | `{old}` | `{new}` |")
        open(REPORT, "w", encoding="utf-8").write("\n".join(lines) + "\n")
        print(f"relatorio: {REPORT}")


if __name__ == "__main__":
    main()
