"""
Verificação das migrações 6 -> 7 e 7 -> 8 sem aparelho.

Por que um script e não um teste de instrumentation: o que precisa ser provado é que o SQL
embutido em `MIGRATION_6_7` e `MIGRATION_7_8`, aplicado sobre um banco criado pelo
`SQLiteOpenHelper` antigo, termina com **exatamente** o esquema que o Room valida na abertura.
Isso é SQLite puro, roda aqui, e não vale o custo de subir Robolectric no build.

A 7 -> 8 é a que mais importa agora: ela cria as duas tabelas de podcast **com os índices**, e
o Room valida índice também. Uma tabela com as colunas certas e o índice faltando abre o app,
joga `IllegalStateException` e o usuário fica sem podcast e sem app — o erro aparece na
primeira abertura depois da atualização, que é o pior lugar para descobrir um erro de esquema.

O schema esperado é lido do código gerado pelo KSP (`PulsaDatabase_Impl.java`), e não escrito
à mão aqui — se alguém mudar uma entidade, o esperado muda junto e o script continua comparando
a coisa certa.

Uso: python3 tools/check_room_migration.py
"""

import os
import re
import sqlite3
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KOTLIN = os.path.join(ROOT, "app/src/main/java/com/pulsa/player/data/db/PulsaDatabase.kt")
GENERATED = os.path.join(
    ROOT, "app/build/generated/ksp/debug/java/com/pulsa/player/data/db/PulsaDatabase_Impl.java"
)
TABLES = ["playlists", "playlist_songs", "favorites", "song_meta"]

# F3: as tabelas que a 7->8 cria. Ficam fora de `TABLES` de propósito — elas não existem no
# banco v7, e incluí-las na lista da 6->7 faria a comparação com o esquema legado rodarem
# contra tabelas que ninguém criou ainda.
PODCAST_TABLES = ["podcast_feeds", "podcast_episodes"]


def join_concat_after(source: str, marker: str) -> str:
    """A partir de `marker`, junta os literais unidos por `+` numa string só.

    É o que o Kotlin faz em runtime, e é por isso que não dá para ler o arquivo com regex
    simples: `"CREATE TABLE ... (" + "..." + "...")` é um statement só, não três.
    """
    return join_concat_at(source, source.index('"', source.index(marker)))


def join_concat_at(source: str, i: int) -> str:
    """`join_concat_after` a partir de uma aspa de abertura já localizada.

    Separado porque os `execSQL` da 7->8 precisam ser extraídos por posição, e não por
    marcador: todos começam com `db.execSQL(` e a busca por texto do statement não distingue
    um do outro.
    """
    out = []
    while True:
        m = re.compile(r'"((?:[^"\\]|\\.)*)"').match(source, i)
        if not m:
            break
        out.append(m.group(1).replace('\\"', '"').replace("\\`", "`"))
        j = m.end()
        while j < len(source) and source[j] in " \t\r\n":
            j += 1
        if j < len(source) and source[j] == "+":
            i = j + 1
            while i < len(source) and source[i] in " \t\r\n":
                i += 1
            continue
        return "".join(out)
    return "".join(out)


def first_concat_starting_with(source: str, prefix: str) -> str:
    """Primeiro literal que começa com `prefix`, junto com o resto da concatenação."""
    idx = source.index(prefix)
    start = source.rindex('"', 0, idx)
    return join_concat_after(source, source[start : idx + 1])


def migration_statements(kotlin: str) -> list[str]:
    """Os quatro `rebuild(...)` da migração, já com as tabelas resolvidas."""
    rename = first_concat_starting_with(kotlin, "ALTER TABLE")
    copy = first_concat_starting_with(kotlin, "INSERT INTO")
    drop = first_concat_starting_with(kotlin, "DROP TABLE")

    stmts = []
    for table in TABLES:
        anchor = f'create = "CREATE TABLE IF NOT EXISTS `{table}` ("'
        at = kotlin.index(anchor)
        create = join_concat_after(kotlin, anchor)
        # O `columns =` vem logo depois do bloco `create =` daquele mesmo `rebuild(...)` — e o
        # recorte a partir dali importa, senão a primeira playlist da lista rouba a lista de
        # colunas das outras.
        col_anchor = 'columns = "'
        col_at = kotlin.index(col_anchor, at)
        columns = join_concat_after(kotlin[col_at:], col_anchor)
        old = f"{table}_v6"
        stmts.append(rename.replace("$table", table).replace("$old", old))
        stmts.append(create)
        stmts.append(
            copy.replace("$table", table)
            .replace("$old", old)
            .replace("$columns", columns)
        )
        stmts.append(drop.replace("$table", table).replace("$old", old))
    return stmts


def migration_78_statements(kotlin: str) -> list[str]:
    """Os `execSQL` de `MIGRATION_7_8`, na ordem em que o Kotlin executaria.

    O recorte é do bloco da migração até o `@Volatile` seguinte: varrer o arquivo inteiro
    pegaria também o SQL do `onCreate` do Room, e o banco de teste receberia as tabelas já
    criadas pelo próprio Room — a validação passaria sem a migração ter feito nada.
    """
    at = kotlin.index("MIGRATION_7_8")
    end = kotlin.index("@Volatile", at)
    region = kotlin[at:end]

    out = []
    i = 0
    while True:
        at_exec = region.find("execSQL(", i)
        if at_exec < 0:
            break
        quote = region.find('"', at_exec)
        if quote < 0:
            break
        out.append(join_concat_at(region, quote))
        i = at_exec + len("execSQL(")
    return out


def room_expected_podcast_ddl(java: str) -> list[str]:
    """Os `CREATE` das tabelas e índices de podcast, na ordem do arquivo gerado.

    Só `CREATE`: o mesmo arquivo gerado também traz `DROP TABLE` e `DELETE` de podcast, e
    puxá-los para o banco de referência derrubaria as tabelas logo depois de criá-las — a
    comparação passaria a medir o banco errado. Sem `re.S` de propósito: os statements
    gerados são de uma linha só, e com o `.` cruzando linha o quantificador guloso engoliria o arquivo
    inteiro.
    """
    out = []
    for m in re.finditer(r'execSQL\("(.+)"\);', java):
        sql = m.group(1).replace("\\`", "`")
        if not sql.lstrip().startswith("CREATE "):
            continue
        if "podcast_feeds" in sql or "podcast_episodes" in sql:
            out.append(sql)
    if not out:
        sys.exit(
            f"Nenhum CREATE de podcast no DDL gerado ({GENERATED}).\n"
            "Rode ./gradlew kspDebugKotlin antes."
        )
    return out


def index_set(db: sqlite3.Connection, table: str) -> set[tuple]:
    """Os índices de uma tabela como `(nome, é único, colunas)`.

    Comparar só as colunas deixaria passar o erro mais provável desta migração: criar as
    tabelas do jeito certo mas esquecer o `UNIQUE` do `feed_url`, que é o que impede a mesma
    URL de virar duas assinaturas. O Room valida os índices na abertura, então a diferença
    apareceria como crash de esquema e não como bug silencioso.
    """
    out = set()
    for row in db.execute(f"PRAGMA index_list({table})").fetchall():
        name, unique = row[1], row[2]
        cols = tuple(c[2] for c in db.execute(f'PRAGMA index_info("{name}")').fetchall())
        out.add((name, bool(unique), cols))
    return out


def room_expected_ddl() -> dict[str, str]:
    if not os.path.exists(GENERATED):
        sys.exit(f"DDL gerado não encontrado: {GENERATED}\nRode ./gradlew kspDebugKotlin antes.")
    java = open(GENERATED, encoding="utf-8").read()
    out = {}
    for table in TABLES:
        # Greedy até o `")` que fecha o `execSQL` — o `)` final do DDL faz parte do SQL, e
        # um match lazy comeria ele e entregaria um CREATE truncado.
        m = re.search(r'execSQL\("(CREATE TABLE IF NOT EXISTS `%s` .+)"\);' % table, java)
        if not m:
            sys.exit(f"DDL de {table} não encontrado no código gerado.")
        out[table] = m.group(1).replace("\\`", "`")
    return out


def legacy_schema() -> str:
    """O `onCreate` do `SQLiteOpenHelper` que estava em `PlaylistDb` antes do Room."""
    return """
CREATE TABLE playlists (
    "_id" INTEGER PRIMARY KEY AUTOINCREMENT,
    "name" TEXT NOT NULL,
    "created" INTEGER NOT NULL DEFAULT 0,
    "auto_add" INTEGER NOT NULL DEFAULT 0,
    "system" INTEGER NOT NULL DEFAULT 0);
CREATE TABLE playlist_songs (
    "_id" INTEGER PRIMARY KEY AUTOINCREMENT,
    "playlist_id" INTEGER NOT NULL,
    "song_id" INTEGER NOT NULL,
    "path" TEXT, "title" TEXT, "artist" TEXT,
    "album" TEXT, "album_id" INTEGER, "duration" INTEGER);
CREATE TABLE favorites (
    "song_id" INTEGER PRIMARY KEY,
    "path" TEXT, "title" TEXT, "artist" TEXT,
    "album" TEXT, "album_id" INTEGER, "duration" INTEGER,
    "liked_at" INTEGER NOT NULL DEFAULT 0);
CREATE TABLE song_meta (
    "song_id" INTEGER PRIMARY KEY,
    "title" TEXT, "artist" TEXT, "album" TEXT);
"""


def column_info(db: sqlite3.Connection, table: str) -> list[tuple]:
    rows = db.execute(f"PRAGMA table_info({table})").fetchall()
    return [(r[1], r[2], r[3], r[4], r[5]) for r in rows]


def validate_78(kotlin: str, v7: sqlite3.Connection) -> bool:
    """Aplica a 7->8 sobre uma cópia de v7 e confere esquema, índices e dados.

    `v7` é o banco **já no esquema da 6->7**, e não o legado: a 7->8 roda depois da 6->7 num
    app real, então é sobre o resultado da outra que ela tem de ser provada.
    """
    print("\n== Migracao 7->8 (F3, podcast) ==")
    failed = False

    stmts = migration_78_statements(kotlin)
    print(f"  {len(stmts)} statement(s) extraido(s) de MIGRATION_7_8")
    if len(stmts) < 5:
        failed = True
        print("  FALHA a extracao pegou poucos statements; a checagem abaixo nao diria nada")

    # As tabelas de podcast nao podem ja existir: e o que prova que esta migracao e de criacao
    # e nao uma reconstrucao silenciosa de dados.
    preexistentes = [t for t in PODCAST_TABLES if table_exists(v7, t)]
    if preexistentes:
        failed = True
        print(f"  FALHA {preexistentes} ja existia em v7; a 7->8 deveria apenas criar")
    else:
        print(f"  ok   {' e '.join(PODCAST_TABLES)} nao existia em v7 (migracao so cria)")

    # Uma **cópia** do v7, e não um banco novo: a 7->8 roda em cima de um banco que já tem
    # as quatro tabelas da 6->7, e a prova de que ela não mexe nelas só vale se elas
    # estiverem presentes durante e depois. `backup` copia também os dados, o `ATTACH` num
    # banco em memória não.
    db = sqlite3.connect(":memory:")
    v7.backup(db)

    # Um `db.execute` por statement, e não `executescript`: é o que o Room faz (`execSQL`
    # roda um statement só) e evita o `sqlite3_complete` não achar a fronteira entre dois
    # `CREATE` sem ponto e vírgula, o que daria "near CREATE: syntax error" num script que
    # na app está correto.
    for sql in stmts:
        db.execute(sql)
    db.commit()

    # Idempotencia: o Room pode reaplicar a migracao depois de um `fallbackToDestructiveMigration`
    # parcial, e um `CREATE TABLE` sem IF NOT EXISTS estoura aqui em vez de no aparelho.
    try:
        for sql in stmts:
            db.execute(sql)
        print("  ok   reaplicar os statements nao estoura (idempotente)")
    except sqlite3.Error as e:
        failed = True
        print(f"  FALHA reaplicar os statements estoura: {e}")

    expected = room_expected_podcast_ddl(open(GENERATED, encoding="utf-8").read())
    fresh = sqlite3.connect(":memory:")
    for sql in expected:
        fresh.execute(sql)

    for table in PODCAST_TABLES:
        if not table_exists(db, table):
            failed = True
            print(f"  FALHA {table} nao foi criada")
            continue
        got_cols = column_info(db, table)
        want_cols = column_info(fresh, table)
        if got_cols == want_cols:
            print(f"  ok   {table}: {len(got_cols)} colunas iguais ao esquema do Room")
        else:
            failed = True
            print(f"  FALHA {table}: colunas divergem")
            for a, b in zip(got_cols, want_cols or []):
                if a != b:
                    print(f"      obtido={a}")
                    print(f"      esperado={b}")
            if len(got_cols) != len(want_cols or []):
                print(f"      contagem difere: {len(got_cols)} vs {len(want_cols or [])}")

        got_idx = index_set(db, table)
        want_idx = index_set(fresh, table)
        if got_idx == want_idx:
            uniq = [i[0] for i in sorted(got_idx) if i[1]]
            print(f"  ok   {table}: {len(got_idx)} indice(s) iguais; unique={uniq or 'nenhum'}")
        else:
            failed = True
            print(f"  FALHA {table}: indices divergem")
            for extra in sorted(got_idx - want_idx):
                print(f"      so na migracao: {extra}")
            for falta in sorted(want_idx - got_idx):
                print(f"      faltando:      {falta}")

    # O UNIQUE do feed_url e o que trava assinatura duplicada; sem ele o teste passa e o bug
    # continua la, entao a checagem e explicita e nao apenas "os indices conferem".
    feed_url_unique = any(
        name == "index_podcast_feeds_feed_url" and unique
        for name, unique, _ in index_set(db, "podcast_feeds")
    )
    if feed_url_unique:
        print("  ok   podcast_feeds.feed_url tem indice UNIQUE")
    else:
        failed = True
        print("  FALHA podcast_feeds.feed_url SEM UNIQUE: assinar a mesma URL duplicaria")

    # Duas linhas com a mesma URL tem de ser barrado pelo indice.
    try:
        db.execute(
            "INSERT INTO podcast_feeds (feed_url, title) VALUES ('https://x/feed.xml', 'A')"
        )
        db.execute(
            "INSERT INTO podcast_feeds (feed_url, title) VALUES ('https://x/feed.xml', 'B')"
        )
        failed = True
        print("  FALHA o banco aceitou dois feeds com a mesma URL")
    except sqlite3.IntegrityError:
        print("  ok   banco recusou feed duplicado (IntegrityError)")

    # Guid repetido no mesmo podcast tambem tem de ser barrado: e o que impede a lista de
    # crescer sozinha a cada refresh.
    try:
        db.execute(
            "INSERT INTO podcast_episodes (podcast_id, guid, audio_url)"
            " VALUES (1, 'g1', 'https://x/1.mp3')"
        )
        db.execute(
            "INSERT INTO podcast_episodes (podcast_id, guid, audio_url)"
            " VALUES (1, 'g1', 'https://x/1b.mp3')"
        )
        failed = True
        print("  FALHA o banco aceitou o mesmo guid duas vezes no mesmo podcast")
    except sqlite3.IntegrityError:
        print("  ok   banco recusou guid repetido no mesmo podcast")

    # O mesmo guid em podcasts diferentes tem de passar: podcast distintos publicam o mesmo
    # guid com frequencia suficiente para acontecer, e barrar aqui perderia episódios.
    try:
        db.execute(
            "INSERT INTO podcast_episodes (podcast_id, guid, audio_url)"
            " VALUES (2, 'g1', 'https://y/1.mp3')"
        )
        print("  ok   guid igual em podcasts diferentes aceito")
    except sqlite3.IntegrityError as e:
        failed = True
        print(f"  FALHA guid igual em podcasts diferentes foi barrado: {e}")

    # Nada das tabelas da 6->7 pode ter sido tocado.
    for table in TABLES:
        rows = db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()
        if rows is None:
            failed = True
            print(f"  FALHA {table} sumiu durante a 7->8")
            continue
        want = v7.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        if rows[0] != want:
            failed = True
            print(f"  FALHA {table}: {rows[0]} linha(s), antes eram {want}")
        else:
            print(f"  ok   {table} intacta ({rows[0]} linha(s))")

    return not failed


def table_exists(db: sqlite3.Connection, table: str) -> bool:
    row = db.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)
    ).fetchone()
    return row is not None


def main() -> int:
    expected = room_expected_ddl()
    kotlin = open(KOTLIN, encoding="utf-8").read()

    legacy = sqlite3.connect(":memory:")
    legacy.executescript(legacy_schema())

    # Banco com dados: a migração tem que carregar as linhas, não só criar as tabelas.
    legacy.execute(
        "INSERT INTO playlists (_id, name, created, auto_add, system) VALUES (7, 'Fav', 111, 1, 1)"
    )
    legacy.execute(
        "INSERT INTO playlist_songs (_id, playlist_id, song_id, path, title, artist, album,"
        " album_id, duration) VALUES (3, 7, 42, '/m/a.mp3', 'A', 'B', 'C', 9, 180000)"
    )
    legacy.execute(
        "INSERT INTO favorites (song_id, path, title, artist, album, album_id, duration,"
        " liked_at) VALUES (42, '/m/a.mp3', 'A', 'B', 'C', 9, 180000, 12345)"
    )
    legacy.execute("INSERT INTO song_meta (song_id, title, artist, album) VALUES (42, 'A', 'B', 'C')")
    legacy.commit()

    before = {t: column_info(legacy, t) for t in TABLES}
    migrated = sqlite3.connect(":memory:")
    migrated.executescript(legacy_schema())
    migrated.executescript(
        "INSERT INTO playlists (_id, name, created, auto_add, system) VALUES (7, 'Fav', 111, 1, 1);"
        "INSERT INTO playlist_songs (_id, playlist_id, song_id, path, title, artist, album,"
        " album_id, duration) VALUES (3, 7, 42, '/m/a.mp3', 'A', 'B', 'C', 9, 180000);"
        "INSERT INTO favorites (song_id, path, title, artist, album, album_id, duration,"
        " liked_at) VALUES (42, '/m/a.mp3', 'A', 'B', 'C', 9, 180000, 12345);"
        "INSERT INTO song_meta (song_id, title, artist, album) VALUES (42, 'A', 'B', 'C');"
    )

    stmts = migration_statements(kotlin)
    if len(stmts) != 16:
        sys.exit(f"Esperava 16 statements (4 tabelas x 4), montei {len(stmts)}.")
    for sql in stmts:
        migrated.execute(sql)
    # `backup()` trava se a conexão de origem tem transação aberta — os `execute` acima
    # abriram uma. Sem este commit o script fica pendurado em `validate_78`, sem mensagem.
    migrated.commit()

    failed = False
    for table in TABLES:
        got = column_info(migrated, table)
        # Compara com o DDL que o Room geraria de fato, num banco limpo.
        fresh = sqlite3.connect(":memory:")
        fresh.execute(expected[table])
        want = column_info(fresh, table)

        if got == want:
            print(f"  ok   {table}: {len(got)} colunas iguais ao esquema do Room")
        else:
            failed = True
            print(f"  FALHA {table}")
            for a, b in zip(got, want or []):
                mark = "  " if a == b else "->"
                print(f"      {mark} obtido={a}")
                if a != b:
                    print(f"         esperado={b}")
            if len(got) != len(want or []):
                print(f"      contagem difere: {len(got)} vs {len(want or [])}")

    # As linhas precisam ter atravessado.
    checks = [
        ("playlists", 1),
        ("playlist_songs", 1),
        ("favorites", 1),
        ("song_meta", 1),
    ]
    for table, want_rows in checks:
        rows = migrated.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        if rows != want_rows:
            failed = True
            print(f"  FALHA dados perdidos em {table}: {rows} linha(s), esperava {want_rows}")
        else:
            print(f"  ok   {table}: {rows} linha(s) preservada(s)")

    # E o _id da playlist/autoincrement tem de continuar advanced, senão a próxima playlist
    # criada colide com a 7.
    seq = migrated.execute("SELECT seq FROM sqlite_sequence WHERE name='playlists'").fetchone()
    if not seq or seq[0] < 7:
        failed = True
        print(f"  FALHA sqlite_sequence de playlists não preservado: {seq}")
    else:
        print(f"  ok   sqlite_sequence de playlists = {seq[0]}")

    # Prova de que a reconstrução é necessária: sem ela, o schema antigo **não** passa na
    # validação do Room. Se algum dia essa diferença sumir (ou o Room parar de ser estrito),
    # a migração rebuild vira trabalho à toa e este aviso aponta para onde.
    drift = []
    for table in TABLES:
        fresh = sqlite3.connect(":memory:")
        fresh.execute(expected[table])
        want = column_info(fresh, table)
        got = before[table]
        for b, w in zip(got, want):
            if b != w:
                drift.append(f"{table}.{b[0]} notnull={b[2]} vs {w[2]}")
    if drift:
        print("\n  Schema legado x Room (por isso a migração reconstrói as tabelas):")
        for d in drift:
            print(f"    - {d}")
    else:
        print("\n  AVISO: o schema legado bate com o do Room; a reconstrução pode ser simplificada.")

    # A 7->8 roda sobre o resultado da 6->7, entao ela recebe `migrated` — ja com as quatro
    # tabelas no esquema do Room e as linhas da 6->7 dentro.
    if not validate_78(kotlin, migrated):
        failed = True

    if failed:
        print("\nMIGRACAO NAO CONFERE")
        return 1
    print("\nMigracoes 6->7 e 7->8 conferem com o esquema do Room e preservam os dados.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
