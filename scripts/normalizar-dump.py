#!/usr/bin/env python3
"""Normaliza um dump xmltree do aapt2 para nao depender de ids de recurso.

ids mudam sempre que um token novo e adicionado, entao:
  - @0x7f06xxxx (color) vira o valor resolvido #aarrggbb
  - @0x7fXXXXXX (qualquer tipo) vira @tipo/nome
"""
import re
import sys

RESOURCE = re.compile(r"^\s*resource (0x[0-9a-f]+) (\S+)\s*$")
VALUE = re.compile(r"^\s*\(\)\s*(.+?)\s*$")
REF = re.compile(r"@0x[0-9a-f]+")


def load_table(apk, aapt2):
    names, values, ambiguous = {}, {}, set()
    current = None
    for line in subprocess_out(aapt2, apk):
        m = RESOURCE.match(line)
        if m:
            current = m.group(1)
            names[current] = m.group(2)
            continue
        if current is None:
            continue
        m = VALUE.match(line)
        if m and current not in values:
            values[current] = m.group(1)
        elif m and values.get(current) != m.group(1):
            ambiguous.add(current)
    return names, values, ambiguous


def subprocess_out(aapt2, apk):
    import subprocess

    return subprocess.run(
        [aapt2, "dump", "resources", apk], capture_output=True, text=True
    ).stdout.splitlines()


def normalize(tree_path, names, values, ambiguous):
    out = []
    for line in open(tree_path, encoding="utf-8"):
        def sub(m):
            rid = m.group(0)[1:]
            if rid in values and rid not in ambiguous:
                return values[rid]
            if rid in names:
                return "@" + names[rid]
            return m.group(0)
        out.append(REF.sub(sub, line))
    return "".join(out)


def main():
    raw, apk, out = sys.argv[1], sys.argv[2], sys.argv[3]
    aapt2 = sys.argv[4]
    names, values, ambiguous = load_table(apk, aapt2)
    open(out, "w", encoding="utf-8").write(normalize(raw, names, values, ambiguous))
    print(f"normalizado: {len(names)} recursos, {len(ambiguous)} com multiplas configs")


if __name__ == "__main__":
    main()
