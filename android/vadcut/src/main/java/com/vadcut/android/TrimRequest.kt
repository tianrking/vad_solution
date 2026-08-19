package com.vadcut.android

import android.net.Uri

class TrimRequest private constructor(builder: Builder) {
    val inputUri: Uri = builder.inputUri
    val outputUri: Uri = builder.outputUri
    val config: TrimConfig = builder.config

    class Builder(
        internal val inputUri: Uri,
        internal val outputUri: Uri,
    ) {
        internal var config: TrimConfig = TrimConfig.voiceMemo()

        fun setConfig(value: TrimConfig) = apply { config = value }
        fun build(): TrimRequest = TrimRequest(this)
    }
}
