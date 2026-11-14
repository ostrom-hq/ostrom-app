// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-android/.../PushMessaging.kt; see NOTICE.
package run.ostrom.app.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import run.ostrom.app.domain.models.PushNotice
import run.ostrom.app.domain.usecases.push.ReadPushUseCase
import run.ostrom.app.infrastructure.push.AndroidPushPlatform

/**
 * Push on Android (`notifications.push.v1`). The relay sends a data-only FCM message whose `p` is the payload encrypted
 * for this phone: this service decrypts it, finds the signed-in account it is for (server, organization and member),
 * and shows it. A payload it cannot read, or for an account signed out here, shows nothing.
 */
class OstromMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        GlobalContext.getOrNull()?.getOrNull<AndroidPushPlatform>()?.updateToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data[VERSION] != "1") return
        val payload = message.data[PAYLOAD]?.takeIf { it.length <= MAX_PAYLOAD } ?: return
        val read = GlobalContext.getOrNull()?.getOrNull<ReadPushUseCase>() ?: return
        // A background thread with about 20 seconds: the key is in the Keystore-sealed store, read once per process.
        val notice = runBlocking { withTimeoutOrNull(TIMEOUT_MILLIS) { read(payload) } } ?: return
        PushNotifications.show(this, notice)
    }

    private companion object {
        // ostrom-core's PushRelay.DATA_PAYLOAD and DATA_VERSION.
        const val PAYLOAD = "p"
        const val VERSION = "v"
        const val MAX_PAYLOAD = 4_096
        const val TIMEOUT_MILLIS = 10_000L
    }
}

object PushNotifications {
    private const val CHANNEL = "activity"

    /** True when this build carries the Firebase project (google-services.json was there at build time). */
    val configured: Boolean get() = BuildConfig.FIREBASE_APP_ID.isNotEmpty()

    /** Initializes Firebase and the notification channel, and hands FCM's current token to the platform. */
    fun start(context: Context, platform: AndroidPushPlatform) {
        if (!configured) return
        if (FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(context, options)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.push_channel), NotificationManager.IMPORTANCE_HIGH))
        FirebaseMessaging.getInstance().token.addOnSuccessListener { platform.updateToken(it) }
    }

    fun show(context: Context, notice: PushNotice) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        // The tap opens the link through MainActivity, explicitly: no other app can send it there.
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(notice.link), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, notice.link.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setStyle(Notification.BigTextStyle().bigText(notice.body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        // One notification per request (or per account for the rest): a newer one about the same request replaces it.
        context.getSystemService(NotificationManager::class.java).notify(notice.link, 1, notification)
    }
}
