package com.pulsa.player.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * F2 — o download baixado: a lista, o que está em andamento e onde cada arquivo está.
 *
 * Existe separado do serviço porque a **tela precisa da lista mesmo com o serviço morto**.
 * Um item que terminou de baixar continua sendo um arquivo em disco, e a aba "Baixados"
 * tem que mostrar isso depois de o serviço ter sido destruído pelo sistema, de o app ter
 * sido fechado, ou de weeks sem tocar em nada. Se a lista vivesse na memória do serviço,
 * fechar o app apagaria do sumidouro tudo que já foi baixado.
 *
 * Por isso a lista é um JSON em `filesDir`, reescrito a cada mudança. Reescrever o arquivo
 * inteiro é o formato simples e não é caro: a lista tem uma entrada por download, não uma
 * por bloco baixado.
 */
object DownloadStore {

    enum class Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    data class Item(
        /** UUID do PeerTube, ou o hash da URL quando não há. Serve de chave e de nome de arquivo. */
        val id: String,
        val title: String,
        val url: String,
        val pageUrl: String,
        val fileName: String,
        val bytes: Long = 0L,
        /** -1 quando o servidor não manda `Content-Length`. */
        val total: Long = -1L,
        val status: Status = Status.QUEUED,
        val error: String? = null,
        val addedAt: Long = 0L
    ) {
        fun isDone() = status == Status.DONE
        fun isActive() = status == Status.QUEUED || status == Status.RUNNING

        /** 0..1, ou `null` quando o tamanho total é desconhecido (barra indeterminado). */
        fun fraction(): Float? {
            if (total <= 0L) return null
            return (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
    }

    /** `filesDir/downloads`, privado do app: não pede permissão em nenhuma versão do Android. */
    fun dir(context: Context): File =
        File(context.filesDir, "downloads").apply { if (!exists()) mkdirs() }

    fun fileFor(context: Context, item: Item): File = File(dir(context), item.fileName)

    /**
     * Arquivo parcial, usado enquanto o download não termina.
     *
     * `internal` e não `private` de propósito: `private` no topo de um arquivo em Kotlin
     * significa "visível só neste arquivo", e o [DownloadService] — que é quem escreve o
     * parcial — mora em outro arquivo do mesmo pacote.
     */
    internal fun partFor(context: Context, item: Item): File = File(dir(context), "${item.fileName}.part")

    /** O `.part` está no disco: dá para continuar de onde parou em vez de recomeçar. */
    fun isResumable(context: Context, item: Item): Boolean = partFor(context, item).length() > 0L

    private fun stateFile(context: Context): File = File(context.filesDir, "downloads.json")

    private val items = ArrayList<Item>()
    private val ui = Handler(Looper.getMainLooper())

    /**
     * Gravação em **uma** thread só, e não no pool local compartilhado.
     *
     * Duas coisas importam aqui. A primeira é a ordem: `put` pode chegar de duas threads
     * de download ao mesmo tempo, e dois `writeText` no mesmo arquivo em paralelo
     * embaralham — o estado mais novo perde para o mais velho e o item volta no JSON como
     * "baixando" mesmo depois de pronto. A segunda é o destino: gravar o JSON na fila de
     * trabalho local disputaria lugar com a arte das miniaturas, que é o que dá a sensação
     * de lentidão quando a lista de downloads cresce.
     */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "downloads-store").apply { isDaemon = true }
    }
    private val observers = ArrayList<() -> Unit>()
    private var loaded = false

    /** Só leitura, já ordenado: em andamento primeiro, depois o resto por data. */
    @Synchronized
    fun list(): List<Item> =
        items.sortedWith(compareBy({ if (it.isActive()) 0 else 1 }, { -it.addedAt }))

    @Synchronized
    fun byId(id: String): Item? = items.firstOrNull { it.id == id }

    fun addObserver(observer: () -> Unit) = synchronized(observers) {
        // A tela pode se registrar várias vezes (cada `onViewCreated`); sem isso a lista
        // de observadores crescia a cada volta na aba e o mesmo callback era chamado
        // várias vezes por mudança.
        if (!observers.contains(observer)) observers.add(observer)
    }

    fun removeObserver(observer: () -> Unit) = synchronized(observers) { observers.remove(observer) }

    /** Carrega do disco uma vez. Quem chama garante que está na main (ver [ensureLoaded]). */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        // `try/catch` e não `runCatching{}.getOrElse{}`: com dois lambdas em cadeia o
        // compilador não sabe qual é o argumento de `getOrElse`.
        val parsed: List<Item> = try {
            read(context)
        } catch (t: Throwable) {
            emptyList()
        }
        // `synchronized(items)` e não `@Synchronized { }`: a anotação só vale em função, e
        // num bloco o compilador aceita com aviso — mas sem travar nada. `ensureLoaded` roda
        // na main enquanto uma thread de download pode estar em `put`, e limpar a lista
        // fora do lock perderia a gravação que acabou de chegar.
        synchronized(items) {
            items.clear()
            items.addAll(parsed)
        }
    }

    /**
     * Grava (ou atualiza) um item e avisa a tela.
     *
     * `persist` é `false` nas atualizações de progresso de dentro do laço de download: são
     * dezenas por segundo, e reescrever o JSON a cada uma gasta bateria à toa. Quem chama
     * decide — o progresso só vai para a tela, e o estado que importa (baixado, falhou,
     * cancelado) sempre grava.
     */
    @Synchronized
    fun put(context: Context, item: Item, persist: Boolean = true) {
        val i = items.indexOfFirst { it.id == item.id }
        if (i >= 0) items[i] = item else items.add(item)
        if (persist) save(context)
        notifyChanged()
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        // O parcial tem de ir junto: sobrar `arquivo.mp4.part` órfão ocupa espaço sem
        // aparecer na lista, e ninguém ia apagar.
        runCatching { partFor(context, item).delete() }
        runCatching { fileFor(context, item).delete() }
        items.removeAll { it.id == id }
        save(context)
        notifyChanged()
    }

    private fun save(context: Context) {
        val snapshot = ArrayList(items)
        writer.execute {
            runCatching {
                val array = JSONArray()
                snapshot.forEach { array.put(it.toJson()) }
                stateFile(context).writeText(array.toString())
            }
        }
    }

    private fun read(context: Context): List<Item> {
        val text = runCatching { stateFile(context).readText() }.getOrNull() ?: return emptyList()
        val array = JSONArray(text)
        val out = ArrayList<Item>(array.length())
        val dir = dir(context)
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val name = o.optString("fileName")
            if (name.isBlank()) continue
            val saved = Item(
                id = o.optString("id"),
                title = o.optString("title"),
                url = o.optString("url"),
                pageUrl = o.optString("pageUrl"),
                fileName = name,
                bytes = o.optLong("bytes"),
                total = o.optLong("total", -1L),
                status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.DONE),
                error = if (o.isNull("error")) null else o.optString("error"),
                addedAt = o.optLong("addedAt")
            )
            val temFinal = File(dir, name).exists()
            val temParcial = File(dir, "$name.part").length() > 0L
            // Só some da lista o que não tem nem final nem parcial. Exigir o arquivo final
            // — como fazia a primeira versão — apagava da tela todo download na fila ou em
            // andamento: eles não têm arquivo final ainda, e fechar o app no meio do vídeo
            // fazia o download sumir da lista e o espaço ficar ocupado por um `.part` sem
            // dono, que ninguém conseguia mais retomar nem apagar.
            if (!temFinal && !temParcial && saved.status != Status.QUEUED && saved.status != Status.FAILED) {
                continue
            }
            out.add(
                if (saved.status == Status.RUNNING) {
                    // "Baixando" no disco significa que o processo morreu no meio. Deixar
                    // como RUNNING mostraria uma barra que nunca anda e nunca dá para
                    // cancelar; o parcial é retomável, então vira falha com botão de
                    // continuar.
                    saved.copy(
                        status = Status.FAILED,
                        error = saved.error ?: "interrompido"
                    )
                } else {
                    saved.copy(
                        status = if (!temFinal && saved.status == Status.DONE) Status.FAILED else saved.status,
                        bytes = if (temFinal) File(dir, name).length() else saved.bytes,
                        error = if (!temFinal && saved.status == Status.DONE) "arquivo não encontrado" else saved.error
                    )
                }
            )
        }
        return out
    }

    private fun Item.toJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("url", url)
        put("pageUrl", pageUrl)
        put("fileName", fileName)
        put("bytes", bytes)
        put("total", total)
        put("status", status.name)
        put("addedAt", addedAt)
        if (error != null) put("error", error) else put("error", JSONObject.NULL)
    }

    /**
     * Avisa a tela na main.
     *
     * Sempre via `ui.post`, mesmo já estando na main: o `put` vem das threads de download, e
     * um adapter de RecyclerView só pode ser tocado da main. A lista de observadores é
     * copiada antes, porque o callback pode se desregistrar (a tela indo embora) enquanto
     * ainda estamos percorrendo.
     */
    private fun notifyChanged() {
        val snapshot: List<() -> Unit> = synchronized(observers) { ArrayList(observers) }
        if (snapshot.isEmpty()) return
        ui.post { snapshot.forEach { runCatching { it() } } }
    }
}
