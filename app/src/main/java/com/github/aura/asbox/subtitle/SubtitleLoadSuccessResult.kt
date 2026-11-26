package com.github.aura.asbox.subtitle

import com.github.aura.asbox.subtitle.model.TimedTextObject

class SubtitleLoadSuccessResult {
    @JvmField
    var fileName: String? = null

    @JvmField
    var content: String? = null

    @JvmField
    var timedTextObject: TimedTextObject? = null

    @JvmField
    var subtitlePath: String? = null
}
