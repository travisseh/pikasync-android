package com.travisse.pikasync

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Wake arm 3 — STUB. No Firebase project is configured yet; this class is
 * registered in the manifest so the wake path is wired end-to-end. Once a
 * google-services.json is added, a high-priority data message will land here
 * and run the same instrumented sync as the other two arms.
 */
class FcmService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        SyncEngine.runSync(applicationContext, "push")
    }

    override fun onNewToken(token: String) {
        WakeLog.record(applicationContext, "push", -1, -1, "new FCM token (stub)")
    }
}
