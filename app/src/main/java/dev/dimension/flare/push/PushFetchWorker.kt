package dev.dimension.flare.push

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.dimension.flare.R
import dev.dimension.flare.data.push.PushNotificationService
import dev.dimension.flare.data.push.PushReauthRequiredException
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext

/**
 * D12: fetches one account's new notifications after a push wake-up and hands them to
 * [PushNotifier]. Pushes for the same account run one after another (APPEND_OR_REPLACE on a unique
 * per-account work name); each run only fetches what is newer than the stored lastSeenId, so a burst
 * costs extra runs, never duplicate notifications.
 *
 * WorkManager's default factory instantiates workers reflectively through the
 * `(Context, WorkerParameters)` constructor, which [JvmOverloads] generates; the extra parameters are
 * test seams only.
 */
internal class PushFetchWorker
    @JvmOverloads
    constructor(
        appContext: Context,
        params: WorkerParameters,
        private val pushNotificationService: PushNotificationService? = null,
        private val notifier: PushNotifier? = null,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val accountKey =
                inputData.getString(KEY_ACCOUNT_KEY)?.let { parseInstance(it) } ?: return Result.failure()
            val service =
                pushNotificationService ?: GlobalContext.get().get<PushNotificationService>()
            val notifier = notifier ?: GlobalContext.get().get<PushNotifier>()
            val items =
                try {
                    service.fetchNew(accountKey)
                } catch (e: PushReauthRequiredException) {
                    // The token cannot use push; the service already marked the account ReauthRequired.
                    return Result.failure()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // runAttemptCount is 0 on the first run: retry after attempts 1 and 2, give up on the 3rd.
                    return if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
                }
            if (items.isNotEmpty()) {
                notifier.show(items)
            }
            return Result.success()
        }

        override suspend fun getForegroundInfo(): ForegroundInfo {
            val notification =
                NotificationCompat
                    .Builder(applicationContext, PushNotifier.CHANNEL_OTHER)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(applicationContext.getString(R.string.push_fetching))
                    .setOngoing(true)
                    .build()
            return ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }

        companion object {
            const val KEY_ACCOUNT_KEY = "accountKey"

            /** D12: at most 3 attempts in total for transient failures. */
            const val MAX_ATTEMPTS = 3

            private const val WORK_NAME_PREFIX = "push-fetch-"
            private const val FOREGROUND_NOTIFICATION_ID = 42

            fun enqueue(
                context: Context,
                accountKey: MicroBlogKey,
            ) {
                val request =
                    OneTimeWorkRequestBuilder<PushFetchWorker>()
                        .setInputData(workDataOf(KEY_ACCOUNT_KEY to accountKey.toString()))
                        .setConstraints(
                            Constraints
                                .Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        ).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(WORK_NAME_PREFIX + accountKey, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }
        }
    }
