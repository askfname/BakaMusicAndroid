package com.bakamusic.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.bakamusic.android.data.*
import com.bakamusic.android.service.*
import com.bakamusic.android.util.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BakaApplication : Application() {
    lateinit var repository: AppRepository
    lateinit var playbackController: PlaybackController
    lateinit var sourceService: MusicSourceService
    lateinit var sessionStore: PlayerSessionStore

    /**
     * 应用前台状态：RESUMED 的 Activity 计数>0 即前台
     * 供轮询/歌词 ticker 按前后台分档——后台只保留播放必需的解码与事件
     * 所有 UI 驱动的定时刷新全部停掉（无订阅即无重组），后台省电总开关
     */
    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(this)
        playbackController = PlaybackController(this)
        sourceService = MusicSourceService(this)
        sessionStore = PlayerSessionStore(this)

        var resumedCount = 0
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedCount++
                _foreground.value = true
            }

            override fun onActivityPaused(activity: Activity) {
                if (--resumedCount <= 0) {
                    resumedCount = 0
                    _foreground.value = false
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
