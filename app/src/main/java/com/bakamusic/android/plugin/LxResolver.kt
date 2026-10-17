package com.bakamusic.android.plugin

import com.bakamusic.android.data.*
import android.util.Log
import kotlinx.coroutines.CancellationException

/** LX 解析结果：Unsupported=无配置走原生；Resolved=命中；Failed=LX 权威失败 */
sealed interface LxOutcome {
    data object Unsupported : LxOutcome
    data class Resolved(val media: MediaItem, val quality: String, val via: String) : LxOutcome
    data object Failed : LxOutcome
}

/** LX 取流解析：已配置时具权威性 */
class LxResolver(private val manager: LxSourceManager) {
    /** 带实际命中音质的 LX 解析 */
    suspend fun resolveDetailed(
        item: MediaItem,
        requested: String,
        exclude: Set<String> = emptySet(),
        cascade: Boolean = true,
        onTrying: ((quality: String, size: Long?, via: String) -> Unit)? = null
    ): LxOutcome {
        val override = manager.getQualityOverride(item.platform) ?: return LxOutcome.Unsupported
        val source = lxSourceForPlatform(item.platform) ?: return LxOutcome.Unsupported
        val plugin = manager.activeFor(source) ?: return LxOutcome.Unsupported
        val via = " · LX·${plugin.name}"
        val base = replaceLxMusicQualities(item, override, true)
        val candidates = (if (cascade) lxQualityFallbacks(requested, override) else listOf(requested))
            .filter { override.contains(it) && !exclude.contains(it) }
        if (candidates.isEmpty()) {
            Log.d("BakaPlugin", "[LX] 无可用候选 ${item.platform}:${item.id} requested=$requested")
            return LxOutcome.Failed
        }
        for (q in candidates) {
            // 尝试即上报 LX 插件名，供播放栏在 loading 期即显示对应插件
            onTrying?.invoke(q, base.qualities[q]?.size, via)
            // 每档最多试两次，非探针错误直接换下一档
            for (attempt in 0..1) {
                try {
                    val url = manager.host.invoke(plugin.hash, source, q, toLxMusicInfo(source, base))
                    if (url.startsWith("http", true)) {
                        Log.d("BakaPlugin", "[LX] 解析命中 ${item.platform}:${item.id} quality=$q via ${plugin.name}")
                        return LxOutcome.Resolved(base.copy(mediaUrl = url, mediaHeaders = emptyMap()), q, via)
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MediaResolveTimeoutException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w("BakaPlugin", "[LX] $q 尝试失败: ${e.message}")
                    if (e is LxProbeException && attempt == 0) continue else break
                }
            }
        }
        Log.d("BakaPlugin", "[LX] 解析失败 ${item.platform}:${item.id} tried=$candidates")
        return LxOutcome.Failed
    }

}
