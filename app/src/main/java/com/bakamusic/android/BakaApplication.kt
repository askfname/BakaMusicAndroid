package com.bakamusic.android

import android.app.Application
import com.bakamusic.android.data.*
import com.bakamusic.android.service.*
import com.bakamusic.android.util.*

class BakaApplication : Application() {
    lateinit var repository: AppRepository
    lateinit var playbackController: PlaybackController
    lateinit var sourceService: MusicSourceService
    lateinit var sessionStore: PlayerSessionStore

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(this)
        playbackController = PlaybackController(this)
        sourceService = MusicSourceService(this)
        sessionStore = PlayerSessionStore(this)
    }
}
