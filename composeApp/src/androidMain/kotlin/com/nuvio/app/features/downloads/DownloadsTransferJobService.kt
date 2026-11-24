package com.nuvio.app.features.downloads

import android.app.job.JobParameters
import android.app.job.JobService
import android.net.Network
import androidx.annotation.RequiresApi
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

@RequiresApi(34)
class DownloadsTransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = mutableMapOf<String, Job>()
    private val networks = mutableMapOf<String, Network?>()

    override fun onStartJob(params: JobParameters): Boolean = startTransfer(params)

    /**
     * SAMplay 2.0.4: Android also calls this when only the network's details change (a Galaxy on
     * Wi-Fi did so every 45–60 s). Starting over each time re-asked every subtitle add-on and never
     * reached the video («Downloading • 0 B» for ever), so only a different network starts over.
     */
    override fun onNetworkChanged(params: JobParameters) {
        val generation = params.extras.getString(AndroidDownloadScheduler.GENERATION) ?: return
        if (runs[generation]?.isActive != true) return
        if (!downloadNetworkChanged(networks[generation], params.network)) return
        jobLog.i { "download moved to another network, continuing there" }
        startTransfer(params)
    }

    private fun startTransfer(params: JobParameters): Boolean {
        val scheduler = DownloadsPlatformDownloader.scheduler(this)
        val fileName = params.extras.getString(AndroidDownloadScheduler.FILE_NAME) ?: return false
        val generation = params.extras.getString(AndroidDownloadScheduler.GENERATION) ?: return false
        val transfer = scheduler.store.get(fileName)?.takeIf { it.generation == generation } ?: return false
        if (!scheduler.isActive(transfer)) return false
        DownloadsLiveStatusPlatform.initialize(this)
        setNotification(params, DownloadsLiveStatusPlatform.notificationId(transfer.item.id),
            DownloadsLiveStatusPlatform.buildNotification(this, transfer.item), JOB_END_NOTIFICATION_POLICY_REMOVE)
        networks[generation] = params.network
        val run = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val retry = scheduler.execute(transfer, params.network) { updated ->
                    updated.item.totalBytes?.let { updateEstimatedNetworkBytes(params, it, 0L) }
                    updateTransferredNetworkBytes(params, updated.item.downloadedBytes, 0L)
                    DownloadsLiveStatusPlatform.notifyTransfer(updated.item)
                }
                jobFinished(params, retry)
                scheduler.notifyCurrent(fileName)
            } finally {
                if (runs[generation] === currentCoroutineContext()[Job]) runs.remove(generation)
            }
        }
        runs.put(generation, run)?.cancel()
        run.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val scheduler = DownloadsPlatformDownloader.scheduler(this)
        val fileName = params.extras.getString(AndroidDownloadScheduler.FILE_NAME) ?: return false
        val generation = params.extras.getString(AndroidDownloadScheduler.GENERATION) ?: return false
        runs.remove(generation)?.cancel()
        networks.remove(generation)
        val transfer = scheduler.store.get(fileName)?.takeIf { it.generation == generation } ?: return false
        if (params.stopReason == JobParameters.STOP_REASON_USER) {
            scheduler.pause(fileName)
            return false
        }
        return scheduler.isActive(transfer)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

private val jobLog = Logger.withTag("SamplayDownloads")

/** A new network to move to; null (none yet) or the same one keeps the running transfer. */
internal fun downloadNetworkChanged(current: Any?, next: Any?): Boolean = next != null && next != current
