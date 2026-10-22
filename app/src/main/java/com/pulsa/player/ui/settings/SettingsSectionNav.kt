package com.pulsa.player.ui.settings

import android.view.View
import android.widget.ScrollView
import com.google.android.material.tabs.TabLayout
import com.pulsa.player.R
import com.pulsa.player.SettingsActivity

private data class Section(val titleRes: Int, val headerId: Int)

private val sections = listOf(
    Section(R.string.sect_appearance, R.id.sect_appearance),
    Section(R.string.sect_audio, R.id.sect_audio),
    Section(R.string.sect_dj, R.id.sect_dj),
    Section(R.string.sect_download, R.id.sect_download),
    Section(R.string.sect_maintenance, R.id.sect_maintenance),
    Section(R.string.sect_account, R.id.sect_account),
)

/**
 * Navegação por abas entre as seções de Configurações.
 *
 * A tela continua sendo uma coluna única dentro de um [ScrollView]; as abas apenas rolam até o
 * cabeçalho da seção. Assim os `bind*()` seguem funcionando com `findViewById` na Activity.
 */
internal class SettingsSectionNav(activity: SettingsActivity) {
    private val tabs = activity.findViewById<TabLayout>(R.id.settings_tabs)
    private val scroll = activity.findViewById<ScrollView>(R.id.settings_scroll)
    private val headers = sections.map { activity.findViewById<View>(it.headerId) }
    private val topMargin = (32 * activity.resources.displayMetrics.density).toInt()

    private var current = 0
    private var suppressScrollSync = false

    fun setup() {
        sections.forEach { tabs.addTab(tabs.newTab().setText(it.titleRes)) }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                goTo(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
                goTo(tab.position)
            }
        })

        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            // Durante a rolagem programática o cabeçalho de destino pode não alcançar o topo
            // (fim da lista); sem isso a aba voltaria para a seção anterior.
            if (suppressScrollSync) return@setOnScrollChangeListener
            val index = headers.indexOfLast { it.top <= scrollY + topMargin }
            if (index >= 0 && index != current) {
                current = index
                if (tabs.selectedTabPosition != index) tabs.getTabAt(index)?.select()
            }
        }
    }

    fun goTo(index: Int) {
        val target = index.coerceIn(0, sections.size - 1)
        current = target
        if (tabs.selectedTabPosition != target) tabs.getTabAt(target)?.select()
        suppressScrollSync = true
        scroll.smoothScrollTo(0, headers[target].top)
        scroll.postDelayed({ suppressScrollSync = false }, 500)
    }

    fun goBy(delta: Int) = goTo(current + delta)
}
