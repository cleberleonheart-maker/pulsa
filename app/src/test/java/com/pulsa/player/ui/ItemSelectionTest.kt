package com.pulsa.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A seleção múltipla precisa **avisar** quem desenhou a lista, e não só contar.
 *
 * Acompanha o bug: a `ItemSelection` tinha um listener só, a barra. O adapter lia
 * `isSelected` no `bind` e nunca era avisado de nada, então o toque longo marcava o
 * item no estado mas a linha continuava com o fundo e o círculo de sempre — a barra
 * contava "1 selecionada" e a lista não mostrava nada. Como `ItemSelection` não depende
 * de Android, a regra cabe num teste de JVM.
 */
class ItemSelectionTest {

    /** Guarda o que cada mudança de seleção contou como "item tocado". */
    private class Events : ItemSelection.Listener {
        val touched = mutableListOf<Long?>()
        override fun onSelectionChanged(selection: ItemSelection, touched: Long?) {
            this.touched.add(touched)
        }
    }

    private fun recorder(): Pair<ItemSelection, Events> {
        val sel = ItemSelection()
        val events = Events()
        sel.addListener(events)
        return sel to events
    }

    @Test
    fun `toque longo avisa com o id do item que entrou`() {
        val (sel, events) = recorder()
        sel.start(7L)
        assertEquals(listOf<Long?>(7L), events.touched)
        assertTrue(sel.isSelected(7L))
    }

    @Test
    fun `os dois ouvintes sao avisados - a barra e o adapter`() {
        val sel = ItemSelection()
        var bar = 0
        var adapter = 0
        sel.addListener(object : ItemSelection.Listener {
            override fun onSelectionChanged(selection: ItemSelection, touched: Long?) {
                bar++
            }
        })
        sel.addListener(object : ItemSelection.Listener {
            override fun onSelectionChanged(selection: ItemSelection, touched: Long?) {
                adapter++
            }
        })
        sel.start(1L)
        sel.toggle(2L)
        sel.clear()
        // Com um listener só, o adapter ficava de fora e a linha nunca era repintada.
        assertEquals(3, bar)
        assertEquals(3, adapter)
    }

    @Test
    fun `ouvinte que se solta para de ser avisado`() {
        val sel = ItemSelection()
        val events = Events()
        sel.addListener(events)
        sel.start(1L)
        sel.removeListener(events)
        sel.start(2L)
        assertEquals(listOf<Long?>(1L), events.touched)
    }

    @Test
    fun `o mesmo ouvinte nao entra duas vezes`() {
        val sel = ItemSelection()
        val events = Events()
        sel.addListener(events)
        sel.addListener(events)
        sel.start(1L)
        assertEquals(1, events.touched.size)
    }

    @Test
    fun `marcar e desmarcar o mesmo item avisa sempre`() {
        val (sel, events) = recorder()
        sel.start(3L)
        sel.toggle(3L)
        assertEquals(listOf<Long?>(3L, 3L), events.touched)
        assertFalse(sel.isActive)
    }

    @Test
    fun `marcar tudo e desmarcar tudo sao mudanca geral, nao de um item so`() {
        val (sel, events) = recorder()
        sel.selectAll(listOf(1L, 2L, 3L))
        // touched null = quem escuta tem que repintar a lista inteira, porque o círculo e
        // o menu de três pontinhos mudam em todas as linhas.
        assertEquals(listOf<Long?>(null), events.touched)
        sel.selectAll(listOf(1L, 2L, 3L))
        assertEquals(listOf<Long?>(null, null), events.touched)
        assertFalse(sel.isActive)
    }

    @Test
    fun `item que sumiu da lista e avisado pelo id`() {
        val (sel, events) = recorder()
        sel.selectAll(listOf(1L, 2L, 3L))
        events.touched.clear()
        sel.retainOnly(setOf(1L, 3L))
        assertEquals(listOf<Long?>(2L), events.touched)
        assertEquals(2, sel.count)
    }

    @Test
    fun `perder varios itens de uma vez e mudanca geral`() {
        val (sel, events) = recorder()
        sel.selectAll(listOf(1L, 2L, 3L, 4L))
        events.touched.clear()
        sel.retainOnly(setOf(1L, 2L))
        assertEquals(listOf<Long?>(null), events.touched)
        assertEquals(2, sel.count)
    }

    @Test
    fun `nada mudado nao avisa ninguem`() {
        val (sel, events) = recorder()
        sel.retainOnly(setOf(1L, 2L))
        sel.start(1L)
        events.touched.clear()
        sel.retainOnly(setOf(1L, 2L))
        assertTrue(events.touched.isEmpty())
    }

    @Test
    fun `a contagem e o que a pessoa le antes de confirmar a exclusao`() {
        val sel = ItemSelection()
        sel.start(1L)
        sel.toggle(2L)
        sel.toggle(3L)
        assertEquals(3, sel.count)
        sel.toggle(2L)
        assertEquals(2, sel.count)
        assertEquals(setOf(1L, 3L), sel.snapshot())
    }

    @Test
    fun `snapshot e copia - mexer nela nao muda a selecao`() {
        val sel = ItemSelection()
        sel.start(1L)
        val copy = sel.snapshot().toMutableSet()
        copy.add(99L)
        assertEquals(1, sel.count)
    }

    @Test
    fun `marcar tudo numa lista vazia nao liga o modo selecao`() {
        val (sel, events) = recorder()
        sel.selectAll(emptyList())
        // `isActive` é o que liga a barra e o círculo: com lista vazia, "1 selecionada"
        // seria mentira.
        assertFalse(sel.isActive)
        assertTrue(events.touched.isEmpty())
    }

    @Test
    fun `isAllSelected e verdadeiro so quando nao sobra nenhuma`() {
        val sel = ItemSelection()
        val all = listOf(1L, 2L, 3L)
        assertFalse(sel.isAllSelected(all))
        sel.selectAll(all)
        assertTrue(sel.isAllSelected(all))
        sel.toggle(2L)
        assertFalse(sel.isAllSelected(all))
    }

    @Test
    fun `selecao vazia e desmarcar tudo nao mudam nada`() {
        val (sel, events) = recorder()
        sel.clear()
        assertFalse(sel.isActive)
        assertTrue(events.touched.isEmpty())
    }
}
