package com.travisse.pikasync

import android.content.Context
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.posthog.PostHog

/**
 * Thin PostHog wrapper. Event names are shared verbatim with the iOS app and
 * the web funnel so cross-platform insights merge. Anonymous IDs only; never
 * put photo content, filenames, or URIs in properties.
 */
object Analytics {
    private const val PROJECT_KEY = "phc_omBAXECCW6N5Cr6YZovQYJpC6qNt4oCQ9tvkdiX9PsuR"
    @Volatile private var ready = false

    fun setup(context: Context) {
        if (ready) return
        val config = PostHogAndroidConfig(
            apiKey = PROJECT_KEY,
            host = "https://us.i.posthog.com",
        )
        config.captureApplicationLifecycleEvents = true
        PostHogAndroid.setup(context.applicationContext, config)
        PostHog.register("platform", "android")
        PostHog.register("app_variant", "firebase")
        PostHog.register("app_version", BuildConfig.VERSION_NAME)
        ready = true
    }

    fun capture(event: String, props: Map<String, Any> = emptyMap()) {
        if (!ready) return
        PostHog.capture(event, properties = props)
    }
}
