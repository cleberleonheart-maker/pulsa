#!/usr/bin/env bash
# Verificação rápida de Kotlin + testes unitários SEM Gradle.
#
# Por que isso existe: o build Gradle local não termina neste aparelho (o daemon é
# morto pelo sistema; só a fase de configuração leva ~4min40 e o CI faz tudo em 3min).
# Este script compila os .kt que você indicar contra o classpath real do projeto e
# roda o JUnit direto — segundos, e pega erro de digitação antes de gastar um CI.
#
# O que ele NÃO verifica (o CI continua sendo o juiz):
#   - recursos XML, manifest, aapt2, dex, ProGuard, assinatura;
#   - dependência nova que ainda não está no cache local do Gradle;
#   - código Java, egenerated (R.java, BuildConfig.java) que o Gradle ainda não gerou.
#
# Atenção ao compilar poucos arquivos: o resto do código vem da ÚLTIMA build do Gradle
# (classes já compiladas em app/build). Então símbolo novo em outro arquivo não aparece
# — inclua no comando os arquivos que você mudou junto, ou use o padrão (que já pega
# tudo que o branch tocou) ou --tudo.
#
# Uso:
#   scripts/verificar-rapido.sh                    # compila o que o branch mexeu (~1min)
#   scripts/verificar-rapido.sh --testes           # compila e roda os testes
#   scripts/verificar-rapido.sh --tudo             # compila o app inteiro (~10min)
#   scripts/verificar-rapido.sh app/src/main/java/com/pulsa/player/playback/X.kt
#   scripts/verificar-rapido.sh --recriar-cp       # recalcula o classpath
set -uo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$RAIZ"

WORK="${TMPDIR:-/tmp}/pulsa-verify"
CPFILE="$WORK/classpath.txt"
KOTLIN_VER="${KOTLIN_VER:-1.9.24}"
mkdir -p "$WORK"

erro() { echo "ERRO: $*" >&2; exit 1; }

# ---------------------------------------------------------------- classpath
montar_classpath() {
    echo "montando classpath (1 vez; leve)..." >&2
    python3 - <<'PY' > "$CPFILE" || exit 1
import os, glob, sys

raiz = os.getcwd()
cp = []

# Android SDK: local.properties -> ANDROID_HOME -> caminho conhecido
sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
if not sdk and os.path.exists("local.properties"):
    for line in open("local.properties"):
        if line.startswith("sdk.dir"):
            sdk = line.split("=", 1)[1].strip()
if sdk:
    android_jar = os.path.join(sdk, "platforms", "android-34", "android.jar")
    if os.path.exists(android_jar):
        cp.append(android_jar)

# O que o Gradle gerou por último (R, BuildConfig, classes do Kotlin)
for p in [
    "app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/R.jar",
    "app/build/intermediates/javac/debug/classes",
    "app/build/intermediates/compile_app_classes_jar/debug/classes.jar",
    "app/build/tmp/kotlin-classes/debug",
]:
    if os.path.exists(p):
        cp.append(os.path.join(raiz, p))

# stdlib do Kotlin na mesma versão do plugin (NÃO vale pegar todas: a 1.3.50 vem antes
# na lista e faz `buildList` sumir, com um erro que não existe no build de verdade)
k = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin")
kv = os.environ.get("KOTLIN_VER", "1.9.24")
for pat in (f"kotlin-stdlib/{kv}/*/kotlin-stdlib-{kv}.jar",
            f"kotlin-compiler-embeddable/{kv}/*/*.jar"):
    cp += sorted(glob.glob(os.path.join(k, pat)))

# classes.jar dos AARs já transformados pelo Gradle
for base in (os.path.expanduser("~/.gradle/caches/transforms-3"),
             os.path.expanduser("~/.gradle/caches/transforms-4")):
    if not os.path.isdir(base):
        continue
    for d in os.listdir(base):
        t = os.path.join(base, d, "transformed")
        if not os.path.isdir(t):
            continue
        for root, _, files in os.walk(t):
            for f in files:
                if f == "classes.jar":
                    cp.append(os.path.join(root, f))

# módulos que são .jar direto (media3-exoplayer, lifecycle, gms, kotlinx...).
# Só a versão mais alta de cada artefato: duas versões no classpath fazem o compilador
# escolher a primeira que aparecer, e o resultado muda de máquina para máquina.
m = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")

def chave(v):
    nums = []
    for parte in v.replace("-", ".").split("."):
        nums.append(int(parte) if parte.isdigit() else 0)
    return nums

IGNORAR = ("compiler", "gradle-plugin", "annotation", "source", "lint")

for grp in sorted(os.listdir(m)):
    gp = os.path.join(m, grp)
    if not os.path.isdir(gp):
        continue
    for art in sorted(os.listdir(gp)):
        if any(p in art for p in IGNORAR):
            continue
        ap = os.path.join(gp, art)
        versoes = [v for v in os.listdir(ap) if os.path.isdir(os.path.join(ap, v))]
        if not versoes:
            continue
        melhor = sorted(versoes, key=chave)[-1]
        for root, _, files in os.walk(os.path.join(ap, melhor)):
            for f in files:
                if f.endswith(".jar") and "sources" not in f and "javadoc" not in f:
                    cp.append(os.path.join(root, f))

cp = [c for c in dict.fromkeys(cp) if "kotlin-compiler" not in c or "stdlib" in c]
print(":".join(cp))
PY
    [ -s "$CPFILE" ] || erro "classpath vazio"
}
[ -f "$CPFILE" ] || montar_classpath

# ------------------------------------------------------- compilador do Kotlin
KCP=$(python3 - <<'PY'
import glob, os
k = os.path.expanduser(f"~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin")
parts = glob.glob(f"{k}/kotlin-compiler-embeddable/{os.environ.get('KOTLIN_VER','1.9.24')}/*/*.jar")
parts += glob.glob(f"{k}/kotlin-stdlib/{os.environ.get('KOTLIN_VER','1.9.24')}/*/*.jar")
parts += glob.glob(os.path.expanduser("~/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations/*/*/annotations-*.jar"))
parts += glob.glob(os.path.expanduser("~/.gradle/caches/modules-2/files-2.1/org.jetbrains.intellij.deps/trove4j/*/*/*.jar"))
print(":".join(parts))
PY
)
[ -n "$KCP" ] || erro "kotlin-compiler-embeddable $KOTLIN_VER não está no cache do Gradle"

kotlinc() {
    # 900m e não mais: este aparelho tem 7,6GB com o sistema e o Termux em cima, e outro
    # projeto pode estar compilando junto — com 1200m o compilador entra em swap e leva
    # 20min em vez de 1.
    java -Xmx900m -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -nowarn -Xskip-metadata-version-check -jvm-target 17 -no-stdlib "$@"
}

# ------------------------------------------------------------------- alvos
RODAR_TESTES=0
TUDO=0
for a in "$@"; do
    case "$a" in
        --testes) RODAR_TESTES=1 ;;
        --tudo) TUDO=1 ;;
        --recriar-cp) rm -f "$CPFILE" ;;
    esac
done
[ -f "$CPFILE" ] || montar_classpath

ARQUIVOS=()
for a in "$@"; do
    case "$a" in --*) ;; *) [ -f "$a" ] && ARQUIVOS+=("$a") ;; esac
done
if [ ${#ARQUIVOS[@]} -eq 0 ]; then
    if [ "$TUDO" = 1 ]; then
        # ~10min neste aparelho: compila o app inteiro.
        while IFS= read -r f; do ARQUIVOS+=("$f"); done < <(find app/src/main/java -name "*.kt")
    else
        # O padrão é o que o branch mexeu (e o que está modificado sem commit):
        # é o que você está editando, e compila em ~1min. `ls-files --others` entra porque
        # arquivo novo nem aparece no `git diff`.
        while IFS= read -r f; do
            [ -f "$f" ] && ARQUIVOS+=("$f")
        done < <({ git diff --name-only main...HEAD; git diff --name-only; git diff --name-only --cached; \
                    git ls-files --others --exclude-standard; } 2>/dev/null | sort -u | grep '\.kt$')
        if [ ${#ARQUIVOS[@]} -eq 0 ]; then
            erro "nada mudou em relação à main; use --tudo para compilar o app inteiro"
        fi
    fi
fi

OUT="$WORK/main"
rm -rf "$OUT"; mkdir -p "$OUT"
echo "compilando ${#ARQUIVOS[@]} arquivo(s)..." >&2
# o diretório novo vem PRIMEIRO no classpath: senão o compilador acha a versão velha
CP="out:$RAIZ/app/build/tmp/kotlin-classes/debug:$(cat "$CPFILE")"
CP="${CP//out:/$OUT:}"
kotlinc -cp "$CP" -d "$OUT" "${ARQUIVOS[@]}" || erro "erro de compilação"

if [ "$RODAR_TESTES" = 1 ]; then
    TESTES=()
    while IFS= read -r f; do TESTES+=("$f"); done < <(find app/src/test/java -name "*.kt")
    OUTT="$WORK/test"; rm -rf "$OUTT"; mkdir -p "$OUTT"
    echo "compilando ${#TESTES[@]} teste(s)..." >&2
    kotlinc -cp "$OUTT:$OUT:$(cat "$CPFILE")" -d "$OUTT" "${TESTES[@]}" || erro "erro de compilação dos testes"

    CLASSES=$(cd "$OUTT" && find . -name "*Test.class" | sed 's|^\./||; s|\.class$||; s|/|.|g')
    [ -n "$CLASSES" ] || erro "nenhuma classe de teste encontrada"
    echo "rodando: $(echo "$CLASSES" | wc -l) suíte(s)" >&2
    java -cp "$OUTT:$OUT:$(cat "$CPFILE")" org.junit.runner.JUnitCore $CLASSES
fi

echo "OK" >&2
