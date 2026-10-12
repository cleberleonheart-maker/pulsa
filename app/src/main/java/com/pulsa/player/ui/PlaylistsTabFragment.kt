package com.pulsa.player.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.pulsa.player.MainActivity
import com.pulsa.player.R
import com.pulsa.player.core.Permissions
import com.pulsa.player.data.M3u
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.model.Playlist
import com.pulsa.player.ui.adapter.PlaylistListAdapter
import com.pulsa.player.core.ThreadPool

/**
 * Lista de todas as playlists do usuário.
 *
 * Criar playlist era possível de duas formas só, e nenhuma delas era a óbvia: pelo menu `⋮`
 * de uma música, ou pelo botão "Adicionar à playlist" do Agora Tocando. Ou seja, **era
 * obrigatório ter uma música na mão** — dava impossível começar uma playlist de cabeça, que
 * é justamente o caso de uso ("quero juntar as músicas que eu curto depois").
 *
 * Aqui o botão `+` do cabeçalho cria uma playlist vazia, e o mesmo botão aparece sozinho no
 * estado vazio (o `MaterialButton` `empty_action` do layout, previsto para isso e nunca
 * ligado).
 */
class PlaylistsTabFragment : Fragment() {

    private var headerContainer: View? = null
    private var headerTitle: TextView? = null
    private var headerSubtitle: TextView? = null
    private var emptyView: View? = null
    private var emptyText: TextView? = null
    private var emptyAction: MaterialButton? = null
    private var adapter: PlaylistListAdapter? = null
    private var loading = false

    /**
     * F5 — importar playlist `.m3u` de outro player. O `OpenDocument` aceita os MIME que os
     * players costumam registrar para `.m3u`/`.m3u8`; `text/plain` cobre os que salvam como
     * texto puro e não se anunciam.
     */
    private val importM3u = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importFrom(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        headerContainer = view.findViewById(R.id.header_container)
        headerTitle = view.findViewById(R.id.header_title)
        headerSubtitle = view.findViewById(R.id.header_subtitle)
        emptyView = view.findViewById(R.id.empty_view)
        emptyText = view.findViewById(R.id.empty_text)
        emptyAction = view.findViewById(R.id.empty_action)
        emptyAction?.apply {
            setText(R.string.new_playlist)
            setOnClickListener { promptNewPlaylist() }
        }

        // O `+` do cabeçalho. A arte some porque aqui o cabeçalho é um contador, não capa de
        // álbum — o 72dp de `ic_music_note` ficaria estranho ao lado de "3 playlists".
        view.findViewById<ImageView>(R.id.header_art).visibility = View.GONE
        view.findViewById<ImageView>(R.id.header_menu).apply {
            visibility = View.VISIBLE
            contentDescription = getString(R.string.new_playlist)
            setOnClickListener { showPlaylistsMenu() }
        }

        val a = PlaylistListAdapter(
            onClick = { playlist -> (activity as? MainActivity)?.openPlaylist(playlist) },
            onMenu = { playlist ->
                if (!playlist.system) {
                    PlaylistDialog.showActions(requireContext(), playlist) { reload() }
                }
            }
        )
        adapter = a
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = a
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        if (view == null) return
        if (loading) return
        val app = context?.applicationContext ?: return
        loading = true
        ThreadPool.post {
            val playlists = try {
                PlaylistDb.get(app).playlists()
            } catch (t: Throwable) {
                emptyList()
            }
            ThreadPool.onUi {
                loading = false
                if (isAdded) bind(playlists)
            }
        }
    }

    private fun bind(playlists: List<Playlist>) {
        adapter?.playlists = playlists
        if (playlists.isEmpty()) {
            // Sem o cabeçalho: ele mostraria "0 playlists" em cima de um estado vazio que já
            // convida a criar uma. O botão do `empty_view` cobre esse caso.
            headerContainer?.visibility = View.GONE
            showEmpty(getString(R.string.empty_playlist), showAction = true)
        } else {
            headerContainer?.visibility = View.VISIBLE
            headerTitle?.text = getString(R.string.tab_playlists)
            headerSubtitle?.text = playlistCount(playlists.size)
            showEmpty(null, showAction = false)
        }
    }

    /** "1 playlist" / "3 playlists". */
    private fun playlistCount(n: Int): String =
        if (n == 1) getString(R.string.one_playlist) else getString(R.string.n_playlists, n)

    /**
     * Abre o diálogo de nome e recarrega a lista quando ele confirmar.
     *
     * O `createPlaylist` roda na main porque o `PlaylistDialog` já o chama assim nos outros
     * dois lugares ([PlaylistDialog.promptNew]); o banco é Room e a operação é de uma linha.
     */
    private fun promptNewPlaylist() {
        val ctx = context ?: return
        PlaylistDialog.promptNew(ctx) { id ->
            if (id <= 0L) return@promptNew
            android.widget.Toast.makeText(
                ctx, R.string.playlist_created, android.widget.Toast.LENGTH_SHORT
            ).show()
            reload()
        }
    }

    fun reload() {
        loading = false
        load()
    }

    /**
     * Menu `⋮` do cabeçalho: criar playlist ou importar um `.m3u`.
     *
     * Antes era só "nova playlist" direto no toque. O mesmo botão virou a porta de entrada da
     * importação — que é a outra coisa que se faz com a lista de playlists, e não tinha teto
     * nenhum até agora (o backup em JSON era só do próprio Pulsa).
     */
    private fun showPlaylistsMenu() {
        val options = arrayOf(
            getString(R.string.new_playlist),
            getString(R.string.m3u_import)
        )
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptNewPlaylist()
                    1 -> importM3u.launch(
                        arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "text/plain")
                    )
                }
            }
            .show()
    }

    /**
     * Importa um `.m3u`: casa as faixas com a biblioteca e cria/atualiza a playlist.
     *
     * Ler, casar e gravar vão para o `ThreadPool` — o parse é barato, mas o casamento varre o
     * MediaStore e o insert é Room, e nenhum dos dois pode rodar na main thread. O nome da
     * playlist sai do nome do arquivo (é o que a pessoa espera ao escolher "rock.m3u").
     */
    private fun importFrom(uri: Uri) {
        val app = context?.applicationContext ?: return
        if (!Permissions.hasAccess(app)) {
            android.widget.Toast.makeText(
                requireContext(), R.string.dj_no_permission, android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        ThreadPool.post {
            val text = runCatching {
                app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                failImport(R.string.m3u_failed)
                return@post
            }
            val name = displayName(app, uri)
                ?.removeSuffix(".m3u8")
                ?.removeSuffix(".m3u")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: app.getString(R.string.m3u_import_default)

            val tracks = M3u.parse(text)
            val resolved = if (tracks.isEmpty()) null else M3u.resolve(app, tracks)
            if (resolved == null || resolved.songs.isEmpty()) {
                failImport(R.string.m3u_import_empty)
                return@post
            }

            val added = runCatching {
                val db = PlaylistDb.get(app)
                val id = db.playlistIdForRestore(name)
                resolved.songs.count { db.addSong(id, it) }
            }.getOrDefault(0)

            ThreadPool.onUi {
                if (!isAdded) return@onUi
                val missing = resolved.missing.size
                val base = getString(R.string.m3u_import_done, added, name)
                val message = if (missing > 0) {
                    base + " — " + getString(R.string.m3u_import_missing, missing)
                } else {
                    base
                }
                android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
                reload()
            }
        }
    }

    private fun failImport(messageRes: Int) {
        ThreadPool.onUi {
            if (isAdded) android.widget.Toast.makeText(
                requireContext(), messageRes, android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Nome de exibição do `content://` escolhido, sem o esquema de colunas do MediaStore. */
    private fun displayName(app: android.content.Context, uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun showEmpty(text: String?, showAction: Boolean) {
        emptyText?.text = text
        emptyView?.visibility = if (text != null) View.VISIBLE else View.GONE
        emptyAction?.visibility = if (showAction) View.VISIBLE else View.GONE
    }

    fun title(): String = requireContext().getString(R.string.tab_playlists)
}
