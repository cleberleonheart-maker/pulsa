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
import com.google.android.material.button.MaterialButton
import com.pulsa.player.MainActivity
import com.pulsa.player.R
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
            setOnClickListener { promptNewPlaylist() }
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

    private fun showEmpty(text: String?, showAction: Boolean) {
        emptyText?.text = text
        emptyView?.visibility = if (text != null) View.VISIBLE else View.GONE
        emptyAction?.visibility = if (showAction) View.VISIBLE else View.GONE
    }

    fun title(): String = requireContext().getString(R.string.tab_playlists)
}
