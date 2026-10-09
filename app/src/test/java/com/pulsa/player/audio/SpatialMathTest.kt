package com.pulsa.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * A matemática do 3D e do surround.
 *
 * Sem Android e sem Media3: [SpatialMath] é aritmética pura, que é justamente a parte que dá
 * para quebrar em silêncio. Os defeitos que motivaram estes testes foram todos silenciosos —
 * nenhum estalava, nenhum logava, só mudava o som:
 *
 * - o limitador ligado com o surround **desligado**, comendo a dinâmica de toda música;
 * - a linha de atraso com metade do comprimento pretendido (metade do atraso, não o dobro);
 * - o ganho de pan sem normalizar a energia, com um buraco de ~3 dB a cada volta.
 */
class SpatialMathTest {

    private fun cosAt(x: Float): Float = cos(x.toDouble()).toFloat()

    private fun sinAt(x: Float): Float = sin(x.toDouble()).toFloat()

    private fun dB(x: Float): Float = 20f * log10(x.toDouble()).toFloat()

    // ---------------------------------------------------------------- surround

    @Test
    fun `surround desligado e identidade bit a bit`() {
        // Sem surround não pode haver largura, cross-feed nem trim: o 3D sozinho não abre a
        // imagem, e um trim solto cortaria volume sem ninguém ter pedido.
        listOf(0f, 0.25f, 0.5f, 1f).forEach { intensity ->
            assertEquals(1f, SpatialMath.widthFor(intensity, false), 0f)
            assertEquals(0f, SpatialMath.crossGainFor(intensity, false), 0f)
            assertEquals(1f, SpatialMath.trimFor(intensity, false), 0f)
        }
    }

    @Test
    fun `intensidade zero com surround ligado e identidade tambem`() {
        // O botão em 0 tem de dar o mesmo que o botão desligado, senão existe um degrau
        // entre "desligado" e "ligado no mínimo".
        assertEquals(1f, SpatialMath.widthFor(0f, true), 0f)
        assertEquals(0f, SpatialMath.crossGainFor(0f, true), 0f)
        assertEquals(1f, SpatialMath.trimFor(0f, true), 0f)
    }

    @Test
    fun `a abertura cresce monotonica com a intensidade`() {
        var anterior = SpatialMath.widthFor(0f, true)
        var passo = 1
        while (passo <= 20) {
            val intensidade = passo / 20f
            val largura = SpatialMath.widthFor(intensidade, true)
            assertTrue("largura na intensidade $intensidade", largura > anterior)
            anterior = largura
            passo++
        }
        assertEquals("fechou em 1,5", 1.5f, SpatialMath.widthFor(1f, true), 1e-6f)
    }

    @Test
    fun `o trim segura o pico do material em contra fase`() {
        // O pior caso é o material em contra-fase (só `side`), que é o que mais cresce com a
        // abertura. Mesmo lá, com o trim no máximo o pico não passa de ~+2 dB, que é o que o
        // limitador do processador resolve.
        val pico = SpatialMath.widthFor(1f, true) * SpatialMath.trimFor(1f, true)
        assertTrue("pico de ${dB(pico)} dB", dB(pico) < 2.1f)
    }

    @Test
    fun `material em fase nao ganha volume com o surround no maximo`() {
        // L = R = 1 => mid = 1, side = 0: a saída é só `trim`, então cai. Subir aqui seria
        // o outro extremo de "abre a imagem" — não é para ser isso.
        assertTrue("trim", SpatialMath.trimFor(1f, true) < 1f)
    }

    // --------------------------------------------------------------------- pan

    @Test
    fun `profundidade zero e identidade`() {
        val cos = FloatArray(64)
        val sin = FloatArray(64)
        // Qualquer ângulo: com depth 0 o pan não gira nada.
        SpatialMath.panGains(cos, sin, 64, 0.002f, 0f, 2.5f)
        cos.forEachIndexed { i, c -> assertEquals("c no $i", 1f, c, 1e-5f) }
        sin.forEachIndexed { i, s -> assertEquals("s no $i", 0f, s, 1e-5f) }
    }

    @Test
    fun `profundidade um e a rotacao verdadeira`() {
        val cos = FloatArray(1)
        val sin = FloatArray(1)
        // count = 1 com passo 0 deixa o bloco inteiro em `from`.
        SpatialMath.panGains(cos, sin, 1, 0f, 1f, 1.1f)
        assertEquals(cosAt(1.1f), cos[0], 1e-4f)
        assertEquals(sinAt(1.1f), sin[0], 1e-4f)
    }

    @Test
    fun `a energia se conserva na volta toda`() {
        // Este é o motivo da normalização: sem ela, no ângulo em que os canais se igualam o
        // sinal perde quase 3 dB e a volta do 3D fica audível como um pulso de volume.
        val depth = 0.6f
        val step = SpatialMath.angleStepFor(depth, 44100)
        val cos = FloatArray(64)
        val sin = FloatArray(64)
        var pior = 0f
        var angle = 0f
        // Três voltas completas dão a cobertura de todo o círculo.
        repeat(200) {
            SpatialMath.panGains(cos, sin, 64, step, depth, angle)
            for (i in 0 until 64) pior = maxOf(pior, abs(1f - (cos[i] * cos[i] + sin[i] * sin[i])))
            angle += step * 64
            if (angle >= SpatialMath.TWO_PI) angle -= SpatialMath.TWO_PI
        }
        assertTrue("desvio maximo de energia: $pior", pior < 1e-3f)
    }

    @Test
    fun `girar o par conserva a energia do sinal`() {
        // A rotação [[c, s], [-s, c]] é ortogonal, então |saída| = |entrada|. É por isso que
        // o 3D não precisa de `trim`: a normalização em `panGains` já faz isso.
        val depth = 1f
        val step = SpatialMath.angleStepFor(depth, 44100)
        val cos = FloatArray(64)
        val sin = FloatArray(64)
        SpatialMath.panGains(cos, sin, 64, step, depth, 0f)
        val l = 0.7f
        val r = -0.2f
        for (i in 0 until 64) {
            val saida = (l * cos[i] + r * sin[i]) * (l * cos[i] + r * sin[i]) +
                (r * cos[i] - l * sin[i]) * (r * cos[i] - l * sin[i])
            assertEquals("i=$i", l * l + r * r, saida, 1e-3f)
        }
    }

    @Test
    fun `o pan desloca o sinal de canal, e nao troca os canais`() {
        val c = FloatArray(1)
        val s = FloatArray(1)

        // Ângulo 0: o sinal que estava na esquerda continua inteiro na esquerda.
        SpatialMath.panGains(c, s, 1, 0f, 1f, 0f)
        assertEquals("esquerda em 0 graus", 1f, c[0], 1e-4f)
        assertEquals("direita em 0 graus", 0f, s[0], 1e-4f)

        // Ângulo de 90 graus: o sinal foi INTEIRO para a direita. Uma troca de canal
        // deslocaria na diagonal e perderia o resto do caminho; aqui ele percorre todo o
        // arco, que é o que dá a sensação de estar em volta em vez de de lado a lado.
        SpatialMath.panGains(c, s, 1, 0f, 1f, SpatialMath.TWO_PI / 4f)
        assertEquals("esquerda em 90 graus", 0f, c[0], 1e-4f)
        assertEquals("direita em 90 graus", 1f, s[0], 1e-4f)

        // E o meio do caminho não estoura: em 45 graus o sinal está dividido e na mesma fase.
        SpatialMath.panGains(c, s, 1, 0f, 1f, SpatialMath.TWO_PI / 8f)
        val metade = c[0]
        assertTrue("meio do caminho $metade", metade > 0.6f && metade < 0.8f)
    }

    @Test
    fun `um ciclo inteiro de amostras da exatamente uma volta`() {
        // E o que garante que a rotação cruza buffers sem dar um salto: o `angle` anda um
        // `angleStep` por amostra, sempre.
        val depth = 1f
        val step = SpatialMath.angleStepFor(depth, 44100)
        assertTrue("passo positivo", step > 0f)
        val total = step * SpatialMath.rotationPeriodSeconds(depth) * 44100f
        assertEquals(SpatialMath.TWO_PI, total, 0.01f)
    }

    @Test
    fun `o ajuste de intensidade muda a velocidade do giro enquanto a musica toca`() {
        // O `angleStep` sai do `depth` de agora e não de um valor guardado no `onConfigure`:
        // guardar faria o slider só mudar a velocidade na faixa seguinte.
        assertTrue(
            "fraco ${SpatialMath.angleStepFor(0.1f, 44100)}, forte ${SpatialMath.angleStepFor(1f, 44100)}",
            SpatialMath.angleStepFor(1f, 44100) > SpatialMath.angleStepFor(0.1f, 44100)
        )
        // E taxa inválida devolve zero em vez de dividir por zero.
        assertEquals(0f, SpatialMath.angleStepFor(0.5f, 0), 0f)
        assertEquals(0f, SpatialMath.angleStepFor(0.5f, -1), 0f)
    }

    @Test
    fun `um bloco de um so sample nao divide por zero`() {
        // `count = 1` zera o `span`, que era a divisão por (count - 1).
        val cos = FloatArray(1)
        val sin = FloatArray(1)
        SpatialMath.panGains(cos, sin, 1, 0.001f, 0.5f, 0.3f)
        assertTrue(!cos[0].isNaN() && !sin[0].isNaN())
    }

    // ------------------------------------------------------------------ atraso

    @Test
    fun `o atraso sai nos milissegundos pedidos`() {
        // A conta é em FRAMES e o comprimento guardado é o dobro, porque a linha é
        // entrelaçada: `writeIndex` anda 2 por frame. Pedindo só os frames, o atraso saía
        // pela metade e o surround chegava fraco sem nenhum erro aparecer.
        listOf(8000, 11025, 22050, 32000, 44100, 48000, 96000, 192000).forEach { taxa ->
            val milissegundos = (SpatialMath.delayLengthFor(taxa) / 2f) / taxa * 1000f
            // A linha só existe em frames inteiros, então o melhor que dá é meio frame de
            // folga. É essa folga que separa o arredondamento de um atraso pela metade.
            val folga = 0.5f / taxa * 1000f
            assertEquals(
                "taxa $taxa deu $milissegundos ms",
                SpatialMath.HAAS_SECONDS * 1000f,
                milissegundos,
                folga
            )
        }
    }

    @Test
    fun `o comprimento do atraso e sempre par`() {
        // Um comprimento ímpar desalinha L e R: o `readIndex` cairia no índice do outro canal.
        listOf(8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 192000).forEach { taxa ->
            assertEquals("taxa $taxa", 0, SpatialMath.delayLengthFor(taxa) % 2)
        }
    }

    @Test
    fun `a mascara da linha circular cobre o comprimento inteiro`() {
        // `and` com máscara só funciona se o array for potência de dois E maior que o
        // comprimento; se não for, a leitura passa do fim.
        listOf(8000, 11025, 44100, 48000, 192000).forEach { taxa ->
            val length = SpatialMath.delayLengthFor(taxa)
            val size = SpatialMath.delaySizeFor(length)
            assertEquals("taxa $taxa: size $size nao e potencia de dois", 1, Integer.bitCount(size))
            assertTrue("taxa $taxa: size $size < length $length", size >= length)
        }
    }

    @Test
    fun `a linha circular nunca da a volta dentro do atraso`() {
        // Se o array fosse menor que o dobro do comprimento, a leitura voltaria no que
        // acabou de escrever e o cross-feed viraria realimentação.
        listOf(8000, 44100, 192000).forEach { taxa ->
            val length = SpatialMath.delayLengthFor(taxa)
            assertTrue("taxa $taxa", SpatialMath.delaySizeFor(length) >= length * 2)
        }
    }

    @Test
    fun `o indice da leitura nunca sai do array, nem na virada`() {
        // Reproduz o acesso do `render` na linha de atraso, com os índices exatos da versão
        // final. Qualquer estouro aqui derrubaria o processo na thread de áudio.
        val length = SpatialMath.delayLengthFor(44100)
        val size = SpatialMath.delaySizeFor(length)
        val mask = size - 1
        val buffer = FloatArray(size)

        var writeIndex = 0
        // Uma volta inteira na linha mais uma, para pegar o wraps exato.
        repeat(size + length + 64) {
            val readIndex = (writeIndex - length) and mask
            assertTrue("readIndex $readIndex", readIndex >= 0 && readIndex < size)
            assertEquals("L sempre em índice par", 0, readIndex % 2)

            assertTrue("writeIndex+1 ${writeIndex + 1}", writeIndex + 1 < size)
            buffer[writeIndex] = 1f
            buffer[writeIndex + 1] = 2f

            val readRight = (readIndex + 1) and mask
            assertTrue("readRight $readRight", readRight < size)
            assertEquals("R sempre em índice ímpar", 1, readRight % 2)

            writeIndex = (writeIndex + 2) and mask
        }
    }

    // --------------------------------------------------------------- limitador

    @Test
    fun `o limitador e transparente ate o joelho`() {
        listOf(0f, 0.1f, 0.5f, 0.84f, SpatialMath.KNEE).forEach { v ->
            assertEquals(v, SpatialMath.softClip(v), 0f)
            assertEquals(-v, SpatialMath.softClip(-v), 0f)
        }
    }

    @Test
    fun `o limitador nunca passa de um`() {
        // É o que segura o `putShort`: estourar o `Short` dá um estalo horrível.
        var v = SpatialMath.KNEE
        while (v < 8f) {
            // Chega em 1 e fica: a saturação é o ponto, e é por isso que precisa ser exata.
            assertTrue("entrou $v saiu ${SpatialMath.softClip(v)}", SpatialMath.softClip(v) <= 1f)
            v *= 1.1f
        }
        assertEquals(1f, SpatialMath.softClip(100f), 1e-6f)
        assertEquals(-1f, SpatialMath.softClip(-100f), 1e-6f)
    }

    @Test
    fun `o limitador e monotonico`() {
        // Monotônico é o que faz diferença na onda: uma curva que não fosse criaria picos
        // novos, que é pior do que distorcer.
        var anterior = 0f
        var v = 0f
        while (v <= 1.6f) {
            val y = SpatialMath.softClip(v)
            assertTrue("nao monotono em $v ($y depois de $anterior)", y >= anterior)
            anterior = y
            v += 0.01f
        }
    }

    @Test
    fun `o limitador e impar`() {
        // Impar, não "simétrico": `softClip(-x)` tem de ser exatamente `-softClip(x)`.
        // A curva abaixo do joelho devolve o valor sem tocar nele, e é aí que uma
        // implementação por magnitude voltaria o sinal errado.
        var v = 0f
        while (v <= 1.5f) {
            assertEquals("x=$v", SpatialMath.softClip(v), -SpatialMath.softClip(-v), 1e-6f)
            v += 0.01f
        }
    }

    @Test
    fun `tudo que o limitador devolve cabe num Short`() {
        // O `render` escreve `(x * 32767).toInt().toShort()`. Se o limitador devolvesse mais
        // que 1, o `toShort()` daria a volta e o estalo seria audível. Aqui se confere o
        // caminho inteiro, do limite até o inteiro que vai para o buffer.
        var v = -SpatialMath.KNEE
        while (v <= 4f) {
            val amostra = (SpatialMath.softClip(v) * 32767f).toInt()
            assertTrue("v=$v virou $amostra", amostra in -32768..32767)
            v += 0.01f
        }
    }

    @Test
    fun `o limitador bate com o tanh de verdade`() {
        // A aproximação de Padé só vale se for mesmo o tanh: o joelho de 0,85 com saturação
        // em 1 é o que dá um joelho "transparente" em vez de uma curva reta. O erro máximo
        // dela é ~0,024 (2,6% perto de over = 1,5), então a tolerância aqui é esse número e
        // não zero — o que não pode é a curva inventar um formato diferente do tanh.
        var pior = 0f
        var x = 0.86f
        while (x < 1.5f) {
            val over = (x - SpatialMath.KNEE) / (1f - SpatialMath.KNEE)
            val real = kotlin.math.tanh(over.toDouble()).toFloat()
            val nosso = (SpatialMath.softClip(x) - SpatialMath.KNEE) / (1f - SpatialMath.KNEE)
            pior = maxOf(pior, kotlin.math.abs(real - nosso))
            x += 0.005f
        }
        assertTrue("erro maximo $pior", pior < 0.025f)
    }
}