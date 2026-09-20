package com.pulsa.player.dj

object DjCommander {

    fun norm(text: String): String {
        return text.lowercase()
            .replace('á', 'a').replace('à', 'a').replace('â', 'a').replace('ã', 'a').replace('ä', 'a')
            .replace('é', 'e').replace('è', 'e').replace('ê', 'e').replace('ë', 'e')
            .replace('í', 'i').replace('ì', 'i').replace('î', 'i').replace('ï', 'i')
            .replace('ó', 'o').replace('ò', 'o').replace('ô', 'o').replace('õ', 'o').replace('ö', 'o')
            .replace('ú', 'u').replace('ù', 'u').replace('û', 'u').replace('ü', 'u')
            .replace('ç', 'c')
            .trim()
    }

    // Lê a intenção do usuário contornando a pontuacao/enj of the recognizer
    // (ex: "sim.", "Sim!", "sim, pode" -> afirmacao; "nao.", "nao!" -> negacao).
    private fun affirm(norm: String): Boolean =
        norm == "sim" || norm == "afirmativo" || norm == "confirmo" ||
            norm == "pode" || norm == "claro" || norm == "pode apagar" ||
            norm.contains("sim,") || norm.contains("sim ") || norm.endsWith("sim.") ||
            norm.endsWith("sim!") || norm.endsWith("sim?") ||
            norm.contains("pode apagar") || norm.contains("pode excluir") ||
            norm.contains("pode deleta") || norm.contains("pode apaga") ||
            norm.contains("pode exclui") || norm.contains("pode remove") ||
            norm.contains("confirma") || norm.contains("confirmo")

    private fun deny(norm: String): Boolean =
        norm == "nao" || norm == "nao quero" || norm == "não" || norm == "nao!" ||
            norm.contains("nao,") || norm.contains("nao ") || norm.endsWith("nao.") ||
            norm.contains("cancel") || norm.contains("esquece") || norm.contains("cala a boca")

    fun hasWake(norm: String): Boolean =
        norm.contains("virgin") || norm.contains("virgem") ||
            norm.contains("virgene") || norm.contains("vargin")

    fun onlyArtist(norm: String): String? {
        val markers = listOf(
            "toca so ", "toque so ", "tocar so ", "toca somente ", "toque somente ",
            "tocar somente ", "somente ", "apenas "
        )
        for (m in markers) {
            val i = norm.indexOf(m)
            if (i >= 0) {
                val rest = norm.substring(i + m.length).trim().trim(',', '.', '!', '?', ' ')
                if (rest.isNotEmpty()) return rest
            }
        }
        return null
    }

    fun mixArtist(norm: String): String? {
        val markers = listOf("mistura com ", "misturar com ", "mixar com ", "mixa com ")
        for (m in markers) {
            val i = norm.indexOf(m)
            if (i >= 0) {
                val rest = norm.substring(i + m.length).trim().trim(',', '.', '!', '?', ' ')
                if (rest.isNotEmpty()) return rest
            }
        }
        return null
    }

    private fun sleepMatch(norm: String): Boolean =
        listOf("dormir", "dorme", "dormi", "sono", "relaxar", "relaxa", "calma", "calmo",
            "descansar", "acalma", "tranquila", "tranquilo", "modo sono").any { norm.contains(it) }

    private fun moodWildMatch(norm: String): Boolean =
        listOf("bombar", "bombra", "anima", "animar", "acelera", "acelerar",
            "festa", "agit", "empolg", "firmeza", "pancadao").any { norm.contains(it) }

    private fun resumeMatch(norm: String): Boolean =
        norm.contains("de onde parou") || norm.contains("da onde parou") ||
            norm.contains("de onde eu parei") || norm.contains("onde parei") ||
            norm.contains("onde eu parei") ||
            norm.contains("volta pra musica") || norm.contains("volta a musica") ||
            norm.contains("voltar a musica") ||
            norm.contains("volta pra tocar") || norm.contains("volta a tocar") ||
            norm.contains("voltar a tocar") ||
            norm.contains("volta de onde") || norm.contains("voltar de onde") ||
            norm.contains("retoma") || norm.contains("retomar") ||
            norm.contains("recomeca") || norm.contains("recomecar")

    private fun memoryKey(norm: String): Boolean =
        listOf("numero", "telefone", "whats", "zap", "watss", "bluetooth", "bitu",
            "fone", "contato", "ligar").any { norm.contains(it) }

    private fun hasPhoneDigits(norm: String): Boolean {
        if (norm.filter { it.isDigit() }.length in 8..15) return true
        return Regex("""[0-9]+(?:\s*-?\s*[0-9]+)*""").findAll(norm).any {
            it.value.filter { c -> c.isDigit() }.length in 8..13
        }
    }

    private fun memorySave(norm: String): Boolean {
        if (!memoryKey(norm)) return false
        if (hasPhoneDigits(norm)) return true
        if (norm.contains("qual") || norm.contains("lembra")) return false
        return norm.contains("bluetooth") || norm.contains("bitu") || norm.contains("fone")
    }

    private fun memoryRecall(norm: String): Boolean {
        if (!memoryKey(norm)) return false
        if (hasPhoneDigits(norm)) return false
        return norm.contains("qual") || norm.contains("meu") || norm.contains("minha") ||
            norm.contains("lembra")
    }

    fun action(norm: String): String? = when {
        memorySave(norm) -> "memory_save"
        memoryRecall(norm) -> "memory_recall"
        moodWildMatch(norm) -> "mood_wild"
        sleepMatch(norm) -> "sleep"
        norm.contains("repete essa") || norm.contains("repete a musica") ||
            norm.contains("repetir essa") || norm.contains("repita essa") -> "repeat"
        onlyArtist(norm) != null -> "only"
        mixArtist(norm) != null -> "mixwith"
        norm.contains("mix") || norm.contains("mistura") || norm.contains("mixa") -> "mix"
        norm.contains("odia") || norm.contains("odeio") || norm.contains("nao gostei") -> "dislike"
        norm.contains("pula") || norm.contains("pular") || norm.contains("pule") ||
            norm.contains("skip") -> "skip"
        norm.contains("proxima") || norm.contains("passa") || norm.contains("avanc") -> "next"
        resumeMatch(norm) -> "resume"
        norm.contains("anterior") || norm.contains("volta") || norm.contains("voltar") -> "prev"
        norm.contains("pausa") || norm.contains("pausar") || norm.contains("parar") ||
            norm.contains("pare") || norm.contains("stop") ||
            norm == "para" || norm.contains("para a musica") || norm.contains("para o som") ||
            norm.contains("para de tocar") || norm.contains("para agora") -> "pause"
        norm.contains("toca") || norm.contains("toque") || norm.contains("continua") -> "play"
        norm.contains("favorit") || norm.contains("curti") || norm.contains("gostei") ||
            norm.contains("amei") -> "fav"
        norm.contains("reconhec") || norm.contains("identif") || norm.contains("que musica") ||
            norm.contains("o que esta") || norm.contains("o que ta") -> "recognize"
        norm.contains("visualizador") || norm.contains("visualize") || norm.contains("barrinhas") ||
            norm.contains("barras de") || norm.contains("visualizer") -> "visualizer"
        norm.contains("skin") || norm.contains("tema") ||
            norm.contains("shader") || norm.contains("fundo") || norm.contains("efeito visual") ||
            norm.contains("aurora") || norm.contains("particula") || norm.contains("neon") -> "skin"
        norm.contains("karaoke") || norm.contains("karoke") || norm.contains("sem vocal") ||
            norm.contains("tira o vocal") || norm.contains("instrumental") -> "karaoke"
        norm.contains("stem") || norm.contains("separ") -> "stems"
        norm.contains("buscar") || norm.contains("busca") || norm.contains("novidade") ||
            norm.contains("importar") || norm.contains("escanear") || norm.contains("scan") -> "scan"
        norm.contains("repetid") || norm.contains("repitid") || norm.contains("repeti") ||
            norm.contains("duplic") || norm.contains("copias") || norm.contains("iguais") -> "duplicates"
        norm.contains("pendrive") || norm.contains("pendr") || norm.contains("pen drive") ||
            norm.contains("pen-drive") || norm.contains("pen d") || norm.contains("cartao") ||
            norm.contains("usb") || norm.contains("memoria") -> "pendrive"
        norm.contains("confirm") || affirm(norm) || norm == "pode" ||
            norm.contains("pode apagar") || norm.contains("pode excluir") ||
            norm.contains("pode deleta") -> "confirm"
        norm.contains("cancel") || norm.contains("esquece") || norm == "nao" -> "cancel"
        norm.contains("delet") || norm.contains("apag") || norm.contains("exclui") ||
            norm.contains("remove") -> "delete"
        norm.contains("qual") || norm.contains("essa") || norm.contains("tocando") -> "info"
        norm.contains("suger") || norm.contains("sugest") || norm.contains("recomend") ||
            norm.contains("indica uma") || norm.contains("o que voce tocaria") ||
            norm.contains("o que vc tocaria") -> "suggest"
        norm.contains("quem e voce") || norm.contains("quem e vc") || norm.contains("quem voce e") ||
            norm.contains("se apresenta") || norm.contains("se apresente") || norm.contains("conte sua historia") ||
            norm.contains("conta sua historia") || norm.contains("fale de voce") || norm.contains("fala de voce") ||
            norm.contains("o que voce faz") || norm.contains("voce e quem") ||
            norm.contains("como voce nasceu") || norm.contains("sua identidade") -> "identity"
        norm.contains("obrigad") || norm.contains("valeu") -> "thanks"
        norm.contains("oi") || norm.contains("ola") -> "hello"
        else -> null
    }
}
