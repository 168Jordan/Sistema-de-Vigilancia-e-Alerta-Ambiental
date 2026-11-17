#!/bin/bash
# run.sh — contrato de execução do TP2 (CC 2026/2027)
#
#   ./run.sh broker     --id <id> --config <ficheiro>
#   ./run.sh publisher  --id <id> --config <ficheiro>
#   ./run.sh subscriber --id <id> --config <ficheiro>
#
# - Resolve os seus próprios caminhos: pode ser invocado de qualquer diretoria.
# - Compila uma só vez, mesmo com três invocações em simultâneo, e não volta a
#   compilar se o código não mudou.
# - Acaba com "exec": o processo Java fica no grupo de processos de quem invocou

here=$(cd "$(dirname "$0")" && pwd)
src="$here/src"
MAIN_CLASS="Main"      # classe com o main(): src/Main.java (pacote por omissão)

die() { echo "run.sh: $*" >&2; exit 1; }

command -v java  >/dev/null 2>&1 || die "java não encontrado"
command -v javac >/dev/null 2>&1 || die "javac não encontrado (é preciso um JDK)"
[ -d "$src" ] || die "diretoria de código não encontrada: $src"

# Diretoria das classes compiladas: ao lado do código, ou em /tmp se não der
# para escrever lá (arquivo extraído em modo só de leitura).
if [ -w "$here" ]; then
    out="$here/build"
else
    out="${TMPDIR:-/tmp}/cc-tp2-build-$(printf '%s' "$here" | cksum | cut -d' ' -f1)"
fi

# Assinatura do código: caminhos, tamanhos e datas dos .java. Se mudar, recompila.
assinatura() {
    find "$src" -type f -name '*.java' -printf '%p %s %T@\n' 2>/dev/null \
        | LC_ALL=C sort | cksum
}

# Compila para uma diretoria temporária e só a põe no sítio se correu tudo bem:
# uma compilação falhada nunca deixa um build a meio.
compilar() {
    local sig tmp
    sig=$(assinatura)
    if [ -f "$out/.ok" ] && [ "$(cat "$out/.assinatura" 2>/dev/null)" = "$sig" ]; then
        return 0                                   # já está atualizado
    fi
    tmp="$out.tmp.$$"
    rm -rf "$tmp"
    mkdir -p "$tmp" || return 1
    find "$src" -type f -name '*.java' > "$tmp/.fontes"
    if [ ! -s "$tmp/.fontes" ]; then
        echo "run.sh: nenhum ficheiro .java em $src" >&2
        rm -rf "$tmp"; return 1
    fi
    if ! javac -encoding UTF-8 -nowarn -d "$tmp" @"$tmp/.fontes" 1>&2; then
        rm -rf "$tmp"; return 1
    fi
    rm -f "$tmp/.fontes"
    echo "$sig" > "$tmp/.assinatura"
    : > "$tmp/.ok"
    rm -rf "$out"
    mv "$tmp" "$out"
}

# Só uma invocação compila de cada vez; as outras esperam e depois veem o build pronto.
compilar_com_trinco() {
    mkdir -p "$(dirname "$out")" || return 1
    if command -v flock >/dev/null 2>&1; then
        # O trinco vive só dentro do subshell: o Java lançado depois não o herda.
        ( flock 9 || exit 1; compilar ) 9>"$out.lock"
    else
        # Sem flock: mkdir é atómico, serve de trinco.
        local d="$out.lockdir" n=0 rc
        until mkdir "$d" 2>/dev/null; do
            n=$((n + 1))
            [ "$n" -gt 600 ] && rm -rf "$d"        # ~60 s: trinco abandonado
            sleep 0.1
        done
        compilar; rc=$?
        rmdir "$d" 2>/dev/null
        return $rc
    fi
}

compilar_com_trinco || die "falha na compilação"

exec java -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8 -XX:+UseSerialGC \
     -cp "$out" "$MAIN_CLASS" "$@"