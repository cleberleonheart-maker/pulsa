#!/usr/bin/env python3
"""T0.1 - tokenizacao fiel: substitui hex hardcoded por @color/<token> sem mudar valor."""
import os
import re
import sys
from collections import OrderedDict

RES = "/root/pulsa/app/src/main/res"
COLORS = f"{RES}/values/colors.xml"
DOC = "/root/pulsa/docs/T0-TOKENS.md"
SCAN_DIRS = ["drawable", "layout", "color"]
# icones legados passam por geracao de PNG em build, que nao aceita @color
EXCLUDE = {
    "drawable/ic_launcher.xml",
    "drawable/ic_splash.xml",
    "drawable/avatar_masculino.xml",
    "drawable/virgin_avatar.xml",
}

# base RGB (RRGGBB) -> familia; variantes de alpha viram <familia>_aXX
FAMILY = {
    "B6FF2E": "neon_green",
    "A8E62A": "neon_lime",
    "FF2E4D": "neon_red",
    "FF6A7A": "neon_coral",
    "FF4A5E": "neon_ember",
    "FF5A45": "neon_rose",
    "A78BFA": "aurora_violet",
    "7C62E0": "aurora_indigo",
    "C4B5FD": "aurora_lilac",
    "FFFFFF": "white",
    "000000": "black",
    "06100B": "radar_bg",
    "0C1C13": "radar_raised",
    "0A2112": "radar_deep",
    "04100A": "radar_sunken",
    "04140A": "radar_home",
    "11291B": "radar_art",
    "14331E": "radar_line",
    "15331E": "radar_line_soft",
    "2D4A2B70": "radar_outline",
    "6FA81C": "chartreuse",
    "1A0B34": "violet_deep",
    "1B1030": "ink_base",
    "150529": "ink_black",
    "1D1640": "ink_violet",
    "2A2060": "violet_mid",
    "20123F": "violet_card",
    "1B2E66": "blue_deep",
    "0E1B4A": "blue_night",
    "8FD8FF": "accent_sky",
    "50E342F5": "accent_magenta",
    "7000FFFF": "accent_cyan_dim",
    "D9F6FF": "accent_ice",
    "E6FFF0": "accent_mint",
    "B3FF3D93": "accent_pink_dim",
    "C91F36": "accent_crimson",
    "0B0820": "bg",
    "00FFFF": "accent_cyan",
    "08150E": "radar_panel",
    "1A0B2E": "violet_ink",
    "1B0B34": "violet_deep",
    "4A2B70": "violet_line",
    "E342F5": "accent_fuchsia",
    "E9D5FF": "lavender_white",
    "EAF6FF": "white_cool",
    "FF3D93": "accent_pink",
    "FFE9A8": "cream",
    "FFE9C4": "cream_warm",
}

# quando varios tokens existentes tem o mesmo valor, escolhe este
PREFERRED = {"#A78BFA": "aurora_violet", "#0B0820": "bg", "#FFFFFF": "white"}

MISSING = {}
HEX = re.compile(r"#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?")
TOKEN_XML = re.compile(r'<color name="([^"]+)">\s*(#[0-9A-Fa-f]{6,8})\s*</color>')


def load_existing():
    out = {}
    text = open(COLORS, encoding="utf-8").read()
    for name, val in TOKEN_XML.findall(text):
        out.setdefault(val.upper(), name)
    for val, name in PREFERRED.items():
        out[val.upper()] = name
    return out


def token_for(argb, existing, new_tokens):
    value = argb.upper()[1:]  # sem o '#'
    if len(value) == 8:
        alpha, base = value[:2], value[2:]
    else:
        alpha, base = "FF", value
    if base not in FAMILY:
        sys.exit(f"cor sem familia definida: {argb} (base {base})")
    # #FF6A7A e #FFFF6A7A sao o mesmo token
    canon = "#" + (base if alpha == "FF" else alpha + base)
    if canon in existing:
        return existing[canon], False
    if canon in new_tokens:
        return new_tokens[canon], False
    fam = FAMILY[base]
    name = fam if alpha == "FF" else f"{fam}_a{alpha}"
    new_tokens[canon] = name
    return name, True


def main():
    existing = load_existing()
    new_tokens = OrderedDict()
    mapping = OrderedDict()
    files = []
    for d in SCAN_DIRS:
        p = f"{RES}/{d}"
        if not os.path.isdir(p):
            continue
        for f in sorted(os.listdir(p)):
            if f.endswith(".xml") and f"{d}/{f}" not in EXCLUDE:
                files.append(f"{p}/{f}")

    changed = 0
    for path in files:
        text = open(path, encoding="utf-8").read()
        found = set(HEX.findall(text))
        for argb in sorted(found, key=lambda h: (-len(h), h)):
            name, is_new = token_for(argb, existing, new_tokens)
            mapping[argb.upper()] = name
        if not found:
            continue
        new_text = text
        for argb in sorted(found, key=lambda h: (-len(h), h)):
            new_text = re.sub(
                re.escape(argb), "@color/" + mapping[argb.upper()], new_text, flags=re.I
            )
        if new_text != text:
            open(path, "w", encoding="utf-8").write(new_text)
            changed += 1

    block = ["", "    <!-- T0.1 tokens de marca/superficie extraidos de drawable, layout e color -->"]
    for argb, name in new_tokens.items():
        block.append(f'    <color name="{name}">{argb}</color>')
    text = open(COLORS, encoding="utf-8").read()
    text = text.replace("</resources>", "\n".join(block) + "\n</resources>")
    open(COLORS, "w", encoding="utf-8").write(text)

    lines = [
        "# T0.1 - Catalogo de tokens",
        "",
        "Gerado por `scripts/tokenizar.py`. Todo `@color/x` neste catalogo tem o",
        "mesmo valor ARGB do hex que substituiu, entao a arvore compilada nao muda.",
        "",
        f"- {changed} arquivos XML alterados",
        f"- {len(EXCLUDE)} icones legados mantidos com hex literal (PNG gerado em build): "
        + ", ".join(f"`{e}`" for e in sorted(EXCLUDE)),
        f"- {len(mapping)} cores distintas encontradas",
        f"- {len(new_tokens)} tokens novos (o resto ja existia em `values/colors.xml`)",
        "",
        "",
        "## Como verificar",
        "",
        "```bash",
        "python3 scripts/verificar-tokenizacao.py            # prova no fonte: cor e estrutura",
        "./gradlew assembleDebug                              # build",
        "./scripts/verificar-visual.sh gate \\",
        "    app/build/outputs/apk/debug/app-debug.apk /tmp/opencode/visual-base",
        "```",
        "",
        "O gate compara a arvore compilada com a baseline do APK original. O",
        "normalizador resolve `@0x7f...` para o valor da cor e para `tipo/nome`,",
        "porque ids de recurso mudam a cada token novo criado.",
        "",
        "| hex original | token | origem |",
        "| --- | --- | --- |",
    ]
    for argb, name in sorted(mapping.items(), key=lambda kv: (kv[1], kv[0])):
        src = "novo" if argb in new_tokens else "reaproveitado"
        lines.append(f"| `{argb}` | `@color/{name}` | {src} |")
    open(DOC, "w", encoding="utf-8").write("\n".join(lines) + "\n")

    if MISSING:
        print("BASES SEM FAMILIA DEFINIDA:")
        for base, vals in sorted(MISSING.items()):
            print(f"  {base}: " + ", ".join("#"+v for v in sorted(vals)))
    print(f"arquivos alterados: {changed}")
    print(f"tokens novos: {len(new_tokens)}  reusados: {len(mapping) - len(new_tokens)}")


if __name__ == "__main__":
    main()
