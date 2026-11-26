package com.github.aura.asbox.player.host

interface TextureRenderHost {

    fun setOnSurfaceReadyListener(listener: Runnable?)

    fun setOutputSize(width: Int, height: Int)
}
