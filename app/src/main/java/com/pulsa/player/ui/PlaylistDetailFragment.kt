package com.pulsa.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.model.Playlist
import com.pulsa.player.playback.Playback
import com.pulsa.player.ui.adapter.SongListAdapter
import com.pulsa.player.core.Helper
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.playback.QueueKey

class PlaylistDetailFragment : Fragment(), HighlightSync {

    private var playlistId: Long = -1L
    private var adapter: SongListAdapter? = null
    private var selectionBar: SelectionBar? = null
    private var headerTitle: TextView? = null
    private var headerSubtitle: TextView? = null
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        playlistId = arguments?.getLong(ARG_ID, -1L) ?: -1L
        val name = arguments?.getString(ARG_NAME) ?: ""
        val header = view.findViewById<View>(R.id.header_container)
        header.visibility = View.VISIBLE
        view.findViewById<ImageView>(R.id.header_art).visibility = View.GONE
        headerTitle = view.findViewById(R.id.header_title)
        headerSubtitle = view.findViewById(R.id.header_subtitle)
        headerTitle?.text = name
        val a = SongListAdapter(
            onPlay = { song, pos ->
                adapter?.highlightId = song.id
                adapter?.let { Playback.start(it.songs, pos) }
            },
            onMenu = { song ->
                SongActions.show(
                    requireContext(),
                    song,
                    R.string.remove_from_playlist to { confirmRemove(song) },
                    onDeleted = { load() }
                )
            }
        )
        adapter = a
        // Aqui a ação NÃO apaga o arquivo: sai da playlist e deixa a música no aparelho.
        // Apagar o arquivo por engano aqui seria o pior resultado possível — a pessoa
        // organizes uma playlist e perde as músicas do disco sem ter pedido isso.
        selectionBar = SelectionWiring.setUpSelection(
            this, view, a,
            actionIcon = R.drawable.ic_remove_circle,
            actionLabel = R.string.remove_from_playlist,
            onAction = { songs, reload -> confirmRemoveMany(songs, reload) },
            reload = { load() },
            // F2b: a fila é outra coisa que se faz com uma playlist inteira, e não tem nada
            // a ver com o botão principal — que dali continua sendo "tirar da playlist".
            onExtraAction = { songs -> SongActions.enqueue(requireContext(), songs) }
        )
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = a
        }
        view.findViewById<ImageView>(R.id.header_menu).apply {
            visibility = View.VISIBLE
            setOnClickListener { showAddSongs() }
        }
        view.findViewById<View>(R.id.empty_action).visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        if (view == null) return
        if (loading) return
        loading = true
        val ctx = requireContext().applicationContext
        val playlistId = this.playlistId
        ThreadPool.post {
            val db = PlaylistDb.get(ctx)
            // O `isAutoAdd` fica aqui, na mesma thread do resto da leitura, e **não** dentro do
            // `onUi` abaixo. O Room recusa acesso ao banco na main thread
            // (`assertNotMainThread`), então perguntar de novo na main derrubava o app ao abrir
            // qualquer playlist — era o "trava e fecha" que dava. Além disso a resposta já era
            // conhecida: perguntar duas vezes não muda nada.
            val autoAdd = try {
                if (db.isAutoAdd(playlistId)) {
                    db.syncAutoPlaylist(ctx, playlistId, Library.allSongs(ctx))
                    true
                } else {
                    false
                }
            } catch (t: Throwable) {
                false
            }
            val songs = try {
                db.songs(playlistId)
            } catch (t: Throwable) {
                emptyList()
            }
            ThreadPool.onUi {
                loading = false
                if (isAdded) {
                    adapter?.songs = songs
                    selectionBar?.setAvailable(songs.map { it.id })
                    syncHighlight()
                    if (autoAdd) {
                        headerSubtitle?.text = getString(R.string.auto_count, Helper.trackCount(songs.size, requireContext().resources))
                    } else {
                        headerSubtitle?.text = Helper.trackCount(songs.size, requireContext().resources)
                    }
                    showEmptyOrNot(songs.isEmpty())
                }
            }
        }
    }

    /**
     * Um `DELETE` por item, e o `load()` só depois.
     *
     * O botão do `AlertDialog` roda na main thread e o `removeSong` é query de Room: deixar
     * ali derrubava o app ao remover uma música da playlist. O `requireContext()` também não
     * pode ser lido de dentro do `post` (a activity pode ter sumido), então o contexto vai
     * resolvido antes.
     */
    private fun confirmRemove(song: com.pulsa.player.model.Song) {
        val app = context?.applicationContext ?: return
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(song.title)
            .setMessage(R.string.remove_song_confirm)
            .setPositiveButton(R.string.remove_from_playlist) { dialog, _ ->
                dialog.dismiss()
                ThreadPool.post {
                    runCatching { PlaylistDb.get(app).removeSong(playlistId, song.id) }
                    ThreadPool.onUi { if (isAdded) load() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Confirma e tira várias músicas da playlist de uma vez.
     *
     * Um diálogo só para o lote, como no resto do app. Cada `removeSong` é um `DELETE` no
     * Room; num lote isso não pode ser feito na thread da UI, e a `PlaylistDb` é a mesma
     * instância que o resto do app usa, então o `ThreadPool` evita competir por ela.
     */
    private fun confirmRemoveMany(songs: List<com.pulsa.player.model.Song>, reload: () -> Unit) {
        if (songs.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.remove_from_playlist)
            .setMessage(getString(R.string.remove_songs_confirm, songs.size))
            .setPositiveButton(R.string.remove_from_playlist) { dialog, _ ->
                dialog.dismiss()
                val ids = songs.map { it.id }
                val app = context?.applicationContext ?: return@setPositiveButton
                ThreadPool.post {
                    val db = PlaylistDb.get(app)
                    ids.forEach { db.removeSong(playlistId, it) }
                    ThreadPool.onUi {
                        if (isAdded) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                getString(R.string.removed_songs, ids.size),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            reload()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddSongs() {
        val ctx = requireContext()
        ThreadPool.post {
            val all = Library.allSongs(ctx)
            ThreadPool.onUi {
                if (!isAdded) return@onUi
                if (all.isEmpty()) return@onUi
                val titles = all.map { it.title }
                val checked = BooleanArray(all.size)
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(R.string.add_songs_title)
                    .setMultiChoiceItems(titles.toTypedArray(), checked) { _, _, _ -> }
                    .setPositiveButton(R.string.add_songs) { dialog, _ ->
                        dialog.dismiss()
                        // `checked` é lido na main e o insert vai para o pool: um lote de 30
                        // músicas são 30 queries de Room, e isso na main thread trava a tela
                        // antes mesmo de lançar a exceção.
                        val picked = all.filterIndexed { i, _ -> checked[i] }
                        if (picked.isEmpty()) return@setPositiveButton
                        ThreadPool.post {
                            var added = 0
                            val db = PlaylistDb.get(ctx)
                            picked.forEach { if (db.addSong(playlistId, it)) added++ }
                            ThreadPool.onUi {
                                if (!isAdded) return@onUi
                                if (added > 0) {
                                    android.widget.Toast.makeText(
                                        ctx, ctx.getString(R.string.songs_selected, added),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                load()
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun showEmptyOrNot(empty: Boolean) {
        view?.findViewById<View>(R.id.empty_view)?.visibility = if (empty) View.VISIBLE else View.GONE
        view?.findViewById<TextView>(R.id.empty_text)?.text = getString(R.string.empty_playlist)
    }

    fun title(): String = arguments?.getString(ARG_NAME) ?: ""

    fun reload() {
        load()
    }

    companion object {
        private const val ARG_ID = "id"
        private const val ARG_NAME = "name"

        fun forPlaylist(playlist: Playlist): PlaylistDetailFragment {
            return PlaylistDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ID, playlist.id)
                    putString(ARG_NAME, playlist.name)
                }
            }
        }
    }

    /**
     * Reposiciona o "tocando agora" depois que a faixa mudou fora da lista (mini player,
     * notificação, próximo/anterior). Quem preenche no `load()` é o mesmo caminho.
     */
    override fun syncHighlight() {
        val cur = Playback.currentSong
        adapter?.highlightKey = cur?.let { QueueKey.encode(it) }
        adapter?.highlightId = cur?.id
    }

}
