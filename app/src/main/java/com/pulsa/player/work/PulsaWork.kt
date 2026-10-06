package com.pulsa.player.work

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pulsa.player.core.Blacklist
import com.pulsa.player.media.MusicDownloader
import com.pulsa.player.sync.RemoteSync
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * O trabalho de fundo que precisa **sobreviver à morte do processo**.
 *
 * O resto do app usa [com.pulsa.player.core.ThreadPool] e `Handler.postDelayed`: enquanto o
 * processo estiver vivo, roda — e morre com ele. WorkManager é o que troca isso: o SO
 * (JobScheduler/AlarmManager) reassina o job, e ele roda com o app fora da tela e depois de um
 * reinício.
 *
 * **O que NÃO entra aqui, e por quê:** o tick de 3 s do [RemoteSync]. `PeriodicWorkRequest` tem
 * mínimo de 15 min, então colocar o remote control nele faria o web player levar 15 min para
 * responder um play/pause — que é o produto inteiro daquela função. O que foi para cá foi o
 * resto do sync (biblioteca e aprendizado do DJ), que é pesado e não tem latência perceptível.
 * O tick de 3 s continua para estado e comandos, e ficou com metade do trabalho de rede.
 */
object PulsaWork {

    private const val SYNC_PERIODIC = "pulsa_sync_periodic"
    private const val DOWNLOAD = "pulsa_download"

    /** 15 min é o piso do WorkManager, e é uma janela boa para a lista de bloqueio. */
    private const val SYNC_MINUTES = 15L

    private val NETWORK = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Agenda o trabalho de fundo. Idempotente de propósito: [PulsaApp] chama isto a cada
     * arranque do app, e `KEEP` deixa o agendamento existente continuar onde está em vez de
     * recomeçar a contagem — senão o job nunca rodaria em um app aberto o dia todo.
     */
    fun schedule(context: Context) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        runCatching {
            wm.enqueueUniquePeriodicWork(
                SYNC_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(SYNC_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(NETWORK)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                    .build()
            )
        }
    }

    /**
     * Um download solto (o botão de URL nas Configurações), esperando a rede.
     *
     * `REPLACE` porque a intenção aqui é "quero isto agora": um segundo pedido do mesmo item
     * toma o lugar do anterior, em vez de formar fila atrás dele.
     *
     * @return o id do job, para quem quiser acompanhar o resultado; null se nem agendar deu.
     */
    fun download(context: Context, url: String, name: String): UUID? {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_URL to url, DownloadWorker.KEY_NAME to name))
            .setConstraints(NETWORK)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        return runCatching {
            WorkManager.getInstance(context)
                .enqueueUniqueWork("$DOWNLOAD/$name", ExistingWorkPolicy.REPLACE, request)
            request.id
        }.getOrNull()
    }

    /**
     * Avisa quando o download acabou, para a UI ainda falar com o usuário.
     *
     * A callback é sempre no main thread, mas quem cria o Toast é o chamador — vale passar o
     * contexto de aplicação, porque o job termina depois que a tela dos Configurações já saiu.
     */
    fun watchDownload(context: Context, id: UUID, onFinished: (ok: Boolean, path: String) -> Unit) {
        val app = context.applicationContext
        val wm = runCatching { WorkManager.getInstance(app) }.getOrNull() ?: return
        val future = runCatching { wm.getWorkInfoById(id) }.getOrNull() ?: return
        future.addListener({
            val info = runCatching { future.get() }.getOrNull() ?: return@addListener
            val finished = info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
            if (!finished) return@addListener
            onFinished(
                info.state == WorkInfo.State.SUCCEEDED,
                info.outputData.getString(DownloadWorker.KEY_OUT_PATH).orEmpty()
            )
        }, ContextCompat.getMainExecutor(app))
    }

    /**
     * Uma passada de sync: lista de bloqueio, biblioteca e aprendizado do DJ.
     *
     * A blacklist é consultada aqui e não em job separado porque é isto que decide se o resto
     * do sync pode rodar — um job só dela gastaria uma ida à rede a cada 15 min para nada.
     */
    class SyncWorker(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val ctx = applicationContext
            // Barrido não adianta: o tick para de empurrar estado e de aceitar comandos, mas
            // o playback local continua — e não há como matar o processo de dentro daqui.
            if (Blacklist.isBanned(ctx)) return Result.success()
            return if (RemoteSync.syncHeavy(ctx)) Result.success() else Result.retry()
        }

    }

    /**
     * O download por URL das Configurações.
     *
     * Não é o `DownloadService` (que já é serviço de foreground com fila e notificação) — é o
     * caminho simples, que antes era uma thread solta no pool e morria junto com o processo sem
     * deixar rastro. Aqui vira um job com constraint de rede e retry com backoff.
     */
    class DownloadWorker(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val url = inputData.getString(KEY_URL).orEmpty()
            val name = inputData.getString(KEY_NAME).orEmpty()
            if (url.isBlank() || name.isBlank()) return Result.failure()
            return withContext(Dispatchers.IO) {
                MusicDownloader.downloadBlockingUri(applicationContext, url, name)
            }.let { path ->
                // O endereço vai no `outputData` de propósito: o podcast (F3) precisa saber
                // **onde** ficou o episódio para tocar offline e para apagar depois. Um
                // `success()` sem caminho obrigaria a UI a procurar o arquivo pelo nome, e a
                // extensão é decidida pelo servidor — a busca voltaria vazia na maioria dos
                // feeds.
                if (path.isNullOrBlank()) Result.retry()
                else Result.success(workDataOf(DownloadWorker.KEY_OUT_PATH to path))
            }
        }

        companion object {
            const val KEY_URL = "url"
            const val KEY_NAME = "name"
            const val KEY_OUT_PATH = "outPath"
        }
    }
}
