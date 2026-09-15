package com.pulsa.player.util

object DjCommander {

    fun norm(text: String): String {
        return text.lowercase()
            .replace('á', 'a').replace('à', 'a').replace('â', 'a').replace('ã', 'a').replace('ä', 'a')
            .replace('é', 'e').replace('è', 'e').replace('ê', 'e').replace('ë', 'e')
            .replace('í', 'i').replace('ì', 'i').replace('î', 'i').replace('ï', 'i')
            .replace('ó', 'o').replace('ò', 'o').replace('ô', 'o').replace('õ', 'o').replace('ö', 'o')
            .replace('ú', 'u').replace('ù', 'u').replace('û', 'u').replace('ü', 'u')
            .replace('ç', 'c')
    }

    fun hasWake(norm: String): Boolean =
        norm.contains("virgin") || norm.contains("virgem") ||
            norm.contains("virgene") || norm.contains("vargin")

    fun action(norm: String): String? = when {
        norm.contains("mix") || norm.contains("mistura") || norm.contains("mixa") -> "mix"
        norm.contains("odia") || norm.contains("odeio") || norm.contains("nao gostei") -> "dislike"
        norm.contains("pula") || norm.contains("pular") || norm.contains("pule") ||
            norm.contains("skip") -> "skip"
        norm.contains("proxima") || norm.contains("passa") || norm.contains("avanc") -> "next"
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
        norm.contains("repetid") || norm.contains("duplic") || norm.contains("copias") -> "duplicates"
        norm.contains("pendrive") || norm.contains("pen drive") || norm.contains("cartao") ||
            norm.contains("cartão") || norm.contains("usb") -> "pendrive"
        norm.contains("confirm") || norm == "sim" || norm == "pode" ||
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