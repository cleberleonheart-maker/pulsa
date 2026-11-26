package com.pulsa.player.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeKeyboardTest {

    @Test
    fun palavraAntesEDepoisDoCursorIgnoraSeparadores() {
        assertEquals("mundo", ImeEditorProfile.wordBeforeCursor("olá mundo"))
        assertEquals("bem", ImeEditorProfile.wordBeforeCursor("😀 bem"))
        assertEquals("exemplo", ImeEditorProfile.wordBeforeCursor("exemplo"))
        assertEquals("", ImeEditorProfile.wordBeforeCursor("olá "))
        assertEquals("resto", ImeEditorProfile.wordAfterCursor("resto mais"))
        assertEquals("bem-vindo", ImeEditorProfile.wordAfterCursor("bem-vindo!"))
        assertEquals("", ImeEditorProfile.wordAfterCursor("...texto"))
    }

    @Test
    fun tecladoPortuguesTemCedilhaEEspanholTemEnie() {
        val portugues = characters(ImeKeyboardLayout.rows("pt", ImeLayer.LETTERS))
        val espanhol = characters(ImeKeyboardLayout.rows("es", ImeLayer.LETTERS))
        val ingles = characters(ImeKeyboardLayout.rows("en", ImeLayer.LETTERS))
        assertTrue(portugues.contains("ç"))
        assertTrue(espanhol.contains("ñ"))
        assertFalse(ingles.contains("ç"))
        assertFalse(ingles.contains("ñ"))
    }

    @Test
    fun tecladoAdaptaEmailEUrl() {
        val email = characters(ImeKeyboardLayout.rows("en", ImeLayer.LETTERS, email = true))
        val url = characters(ImeKeyboardLayout.rows("en", ImeLayer.LETTERS, url = true))
        assertTrue(email.contains("@"))
        assertTrue(url.contains("/"))
        assertTrue(url.contains(".com"))
    }

    @Test
    fun acentosSeguemIdiomaETamanhoDaLetra() {
        assertTrue(ImeKeyboardLayout.accentAlternatives("a", false, "pt").contains("ã"))
        assertTrue(ImeKeyboardLayout.accentAlternatives("a", true, "pt").contains("Ã"))
        assertTrue(ImeKeyboardLayout.accentAlternatives("n", false, "es").contains("ñ"))
        assertFalse(ImeKeyboardLayout.accentAlternatives("a", false, "en").contains("á"))
    }

    @Test
    fun sugestoesRespeitamIdiomaEAcento() {
        assertTrue(ImeSuggestionEngine.suggestions("ca", "pt").contains("casa"))
        assertTrue(ImeSuggestionEngine.suggestions("aman", "pt").contains("amanhã"))
        assertFalse(ImeSuggestionEngine.suggestions("co", "en").contains("como"))
        assertTrue(ImeSuggestionEngine.suggestions("ho", "zz").contains("home"))
    }

    @Test
    fun sugestoesRespeitamLimite() {
        assertEquals(2, ImeSuggestionEngine.suggestions("a", "pt", limit = 2).size)
    }

    @Test
    fun campoNumericoAceitaSomenteCaracteresPermitidos() {
        val inteiro = profile(strictNumber = true)
        val comSinal = profile(strictNumber = true, numberSigned = true)
        val decimal = profile(strictNumber = true, numberDecimal = true)
        assertTrue(inteiro.acceptsInput("123"))
        assertFalse(inteiro.acceptsInput("12a"))
        assertFalse(inteiro.acceptsInput("-1"))
        assertFalse(inteiro.acceptsInput("1.5"))
        assertTrue(comSinal.acceptsInput("-1"))
        assertTrue(decimal.acceptsInput("1.5"))
    }

    private fun characters(rows: List<List<ImeKey>>): List<String> =
        rows.flatMap { row -> row.mapNotNull { it.character } }

    private fun profile(
        strictNumber: Boolean,
        numberSigned: Boolean = false,
        numberDecimal: Boolean = false,
    ): ImeEditorProfile = ImeEditorProfile(
        sensitive = false,
        multiline = false,
        email = false,
        url = false,
        number = strictNumber,
        strictNumber = strictNumber,
        numberSigned = numberSigned,
        numberDecimal = numberDecimal,
        noSuggestions = false,
        noEnterAction = false,
        typeNull = false,
        actionId = 0,
        action = 0,
        actionLabel = null,
        initialCapsMode = 0,
    )
}
