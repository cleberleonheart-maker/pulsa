"""Gera o R.java stub a partir dos resources REAIS do projeto.

O verificador local nao roda aapt, entao precisa de um R compilado. Um R
escrito a mao acaba divergindo do res/ e -- pior -- divergindo em silencio,
deixando passar referencia a string que nao existe (ja aconteceu com
`video_add_to_queue`). Aqui os nomes saem sempre do res/.
"""
import re, os, subprocess, sys, collections

RES = "app/src/main/res"
RAIZ = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True).stdout.strip()
WORK = os.environ.get("TMPDIR", "/tmp") + "/pulsa-verify/rstub"
OUT = f"{WORK}/src/com/pulsa/player/R.java"

def files(sub):
    return subprocess.run(["find", RES, "-mindepth", "2", "-maxdepth", "2", "-path", f"*{sub}*"],
                          capture_output=True, text=True, cwd=RAIZ).stdout.split()

def base(paths):
    return sorted({os.path.splitext(os.path.basename(p))[0] for p in paths})

grupos = collections.defaultdict(set)
for d, tipo in [("/drawable/", "drawable"), ("/layout/", "layout"), ("/menu/", "menu"),
                ("/anim/", "anim"), ("/animator/", "animator"), ("/font/", "font"),
                ("/raw/", "raw")]:
    grupos[tipo] = set(base(files(d)))

ids = set()
for f in files("/layout/") + files("/menu/") + files("/xml/"):
    ids |= set(re.findall(r'android:id="@\+id/([A-Za-z0-9_]+)"', open(f, encoding="utf-8").read()))

vals = collections.defaultdict(set)
for f in files("/values/"):
    txt = open(f, encoding="utf-8").read()
    for m in re.finditer(r'<(string|color|dimen|integer|bool|plurals|attr)\s+[^>]*?name="([^"]+)"', txt):
        vals[m.group(1)].add(m.group(2))
    for m in re.finditer(r'<string-array\s+name="([^"]+)"', txt):
        vals["array"].add(m.group(1))
    for m in re.finditer(r'<item\s+type="(\w+)"\s+name="([^"]+)"', txt):
        vals[m.group(1)].add(m.group(2))
    for m in re.finditer(r'<style\s+name="([\w.]+)"', txt):
        vals["style"].add(m.group(1).replace(".", "_"))

grupos["id"] = ids
for t in ("string", "color", "dimen", "integer", "bool", "plurals", "attr", "array", "style"):
    grupos[t] = vals[t]

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    f.write("package com.pulsa.player;\n\npublic final class R {\n")
    for tipo in sorted(grupos):
        nomes = sorted(grupos[tipo])
        if not nomes:
            continue
        f.write(f"    public static final class {tipo} {{\n")
        for i, n in enumerate(nomes):
            f.write(f"        public static final int {n} = {0x7f000000 + i};\n")
        f.write("    }\n")
    f.write("}\n")

tot = sum(len(v) for v in grupos.values())
print(f"R.java gerado: {tot} simbolos em {OUT}")

# compila e poe as classes na frente do R.jar velho do Gradle, que e stale
CLASSES = f"{WORK}/classes"
subprocess.run(["rm", "-rf", CLASSES], check=True)
os.makedirs(CLASSES, exist_ok=True)
r = subprocess.run(["javac", "-nowarn", "-d", CLASSES, OUT], capture_output=True, text=True)
if r.returncode != 0:
    print(r.stderr, file=sys.stderr)
    sys.exit(1)
print(f"  classes em {CLASSES}")

# o classpath.txt do verificador tem que apontar para as classes do stub
CPFILE = os.environ.get("TMPDIR", "/tmp") + "/pulsa-verify/classpath.txt"
if os.path.exists(CPFILE):
    entradas = [e for e in open(CPFILE).read().strip().split(os.pathsep)
                if e and os.path.abspath(e) != os.path.abspath(CLASSES)]
    with open(CPFILE, "w") as f:
        f.write(os.pathsep.join([CLASSES] + entradas))
    print(f"  classpath.txt apontado para o stub ({len(entradas) + 1} entradas)")

for t in ("string", "drawable", "layout", "id", "style"):
    print(f"  {t}: {len(grupos[t])}")
