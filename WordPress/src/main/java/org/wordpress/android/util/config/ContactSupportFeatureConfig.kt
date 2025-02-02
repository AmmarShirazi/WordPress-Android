package org.wordpress.android.util.config

import org.wordpress.android.BuildConfig
import org.wordpress.android.annotation.Feature
import javax.inject.Inject

private const val CONTACT_SUPPORT_REMOTE_FIELD = "contact_support"

@Feature(CONTACT_SUPPORT_REMOTE_FIELD, false)
class ContactSupportFeatureConfig @Inject constructor(
    appConfig: AppConfig
) : FeatureConfig(
    appConfig,
    BuildConfig.CONTACT_SUPPORT,
    CONTACT_SUPPORT_REMOTE_FIELD
)
