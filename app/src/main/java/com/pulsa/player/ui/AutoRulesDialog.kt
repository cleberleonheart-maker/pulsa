package com.pulsa.player.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.R
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.AutoRuleField
import com.pulsa.player.data.PlaylistDb

/**
 * Tela de "Preencher automaticamente" de uma playlist.
 *
 * As regras são casadas contra a biblioteca inteira a cada abertura da playlist
 * (`PlaylistDb.syncAutoPlaylist`) e a cada abertura do app
 * (`MainActivity.seedAutoPlaylists` → `syncAutoSongs`), então o papel da tela é só **definir**
 * o critério — o enchimento acontece sozinho depois.
 *
 * O diálogo vive enquanto a Activity existe e as regras são lidas no `ThreadPool`: o Room recusa
 * acesso ao banco na main thread (`assertNotMainThread`), e perguntar na main derrubava o app
 * ao abrir qualquer playlist.
 */
object AutoRulesDialog {

    private val FIELDS = arrayOf(
        AutoRuleField.TITLE,
        AutoRuleField.ALBUM,
        AutoRuleField.GENRE,
        AutoRuleField.ARTIST
    )

    private val Int.dp: Int
        get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

    fun show(context: Context, playlistId: Long, playlistName: String, onChanged: () -> Unit) {
        val app = context.applicationContext
        ThreadPool.post {
            val rules = runCatching { PlaylistDb.get(app).autoRules(playlistId) }.getOrDefault(emptyList())
            ThreadPool.onUi {
                build(context, playlistId, playlistName, rules, onChanged)
            }
        }
    }

    private fun build(
        context: Context,
        playlistId: Long,
        playlistName: String,
        rules: List<com.pulsa.player.data.AutoRule>,
        onChanged: () -> Unit
    ) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
        }

        root.addView(TextView(context).apply {
            text = context.getString(R.string.auto_rules_hint)
            textSize = 13f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Uma linha por regra, reconstruída a cada mudança: é mais simples (e menos propenso a
        // Estado fora do lugar) do que um adapter — a lista é minúscula e quase nunca muda.
        val rulesContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(rulesContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12.dp })

        fun renderRules(current: List<com.pulsa.player.data.AutoRule>) {
            rulesContainer.removeAllViews()
            if (current.isEmpty()) {
                rulesContainer.addView(TextView(context).apply {
                    text = context.getString(R.string.auto_rules_none)
                    textSize = 13f
                    setTextColor(context.resources.getColor(R.color.text_secondary, null))
                })
                return
            }
            current.forEach { rule ->
                val row = TextView(context).apply {
                    text = "${fieldLabel(context, rule.field)}: ${rule.value}"
                    textSize = 14f
                    setPadding(0, 10.dp, 0, 10.dp)
                    setOnClickListener { confirmRemove(context, playlistId, rule, onChanged, ::renderRules) }
                }
                rulesContainer.addView(row)
            }
        }

        renderRules(rules)

        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                FIELDS.map { fieldLabel(context, it) }
            )
        }
        root.addView(spinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16.dp })

        val keyword = EditText(context).apply {
            hint = context.getString(R.string.auto_rule_keyword_hint)
        }
        root.addView(keyword, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8.dp })

        // O botão fica dentro do diálogo (e não como botão positivo) para não fechar a tela a
        // cada regra: quem monta uma lista costuma adicionar mais de uma seguida.
        val addButton = MaterialButton(context).apply {
            text = context.getString(R.string.auto_rule_add)
            setOnClickListener {
                val field = FIELDS[spinner.selectedItemPosition]
                val value = keyword.text.toString().trim()
                if (value.isEmpty()) {
                    Toast.makeText(context, R.string.auto_rule_keyword_hint, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val app = context.applicationContext
                ThreadPool.post {
                    PlaylistDb.get(app).addAutoRule(playlistId, field, value)
                    val updated = PlaylistDb.get(app).autoRules(playlistId)
                    ThreadPool.onUi {
                        keyword.text.clear()
                        renderRules(updated)
                        Toast.makeText(context, R.string.auto_rule_added, Toast.LENGTH_SHORT).show()
                        onChanged()
                    }
                }
            }
        }
        root.addView(addButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16.dp })

        MaterialAlertDialogBuilder(context)
            .setTitle(playlistName)
            .setView(ScrollView(context).apply { addView(root) })
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun confirmRemove(
        context: Context,
        playlistId: Long,
        rule: com.pulsa.player.data.AutoRule,
        onChanged: () -> Unit,
        rerender: (List<com.pulsa.player.data.AutoRule>) -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.auto_rules_title)
            .setMessage(R.string.auto_rule_remove_confirm)
            .setPositiveButton(R.string.remove_auto) { d, _ ->
                d.dismiss()
                val app = context.applicationContext
                ThreadPool.post {
                    PlaylistDb.get(app).removeAutoRule(rule.id)
                    val updated = PlaylistDb.get(app).autoRules(playlistId)
                    ThreadPool.onUi {
                        rerender(updated)
                        Toast.makeText(context, R.string.auto_rule_removed, Toast.LENGTH_SHORT).show()
                        onChanged()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun fieldLabel(context: Context, field: String): String = when (field) {
        AutoRuleField.ALBUM -> context.getString(R.string.auto_rule_field_album)
        AutoRuleField.GENRE -> context.getString(R.string.auto_rule_field_genre)
        AutoRuleField.ARTIST -> context.getString(R.string.auto_rule_field_artist)
        else -> context.getString(R.string.auto_rule_field_title)
    }
}
