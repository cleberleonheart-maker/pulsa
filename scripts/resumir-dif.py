#!/usr/bin/env python3
"""Resume o diff da arvore compilada em mudancas de cor, o que a migracao
deve tocar, e qualquer outra diferenca, que e sempre um bug."""
import re
import sys
from collections import Counter

ATTR = re.compile(r"A: \S+?:(\w+)\(0x[0-9a-f]+\)=(.*)$")
COLOR = re.compile(r"^#[0-9a-f]{6,8}$")


def parse(path):
    out = []
    for line in open(path, encoding="utf-8"):
        m = ATTR.search(line)
        if m:
            out.append((m.group(1), m.group(2)))
    return out


def main():
    old, new = sys.argv[1], sys.argv[2]
    a, b = parse(old), parse(new)
    if len(a) != len(b):
        print(f"ATENCAO: linhas de atributo {len(a)} -> {len(b)}")
    colors, outros = Counter(), Counter()
    for (an, av), (bn, bv) in zip(a, b):
        if an != bn:
            outros[f"atributo {an} -> {bn}"] += 1
            continue
        if av == bv:
            continue
        if COLOR.match(av) and COLOR.match(bv):
            colors[(av, bv)] += 1
        else:
            outros[f"{an}: {av} -> {bv}"] += 1

    print(f"mudancas de cor: {sum(colors.values())} em {len(colors)} pares")
    for (ov, nv), n in sorted(colors.items(), key=lambda kv: -kv[1]):
        print(f"  {n:>3}x  {ov} -> {nv}")
    if outros:
        print(f"\nOUTRAS MUDANCAS (esperado: 0): {sum(outros.values())}")
        for k, n in sorted(outros.items(), key=lambda kv: -kv[1])[:20]:
            print(f"  {n:>3}x  {k}")


if __name__ == "__main__":
    main()
