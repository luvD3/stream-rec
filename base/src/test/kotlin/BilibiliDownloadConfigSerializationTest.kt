import github.hua0512.data.config.DownloadConfig
import github.hua0512.data.config.AppConfig
import github.hua0512.data.config.BilibiliConfigGlobal
import github.hua0512.data.media.VideoFormat
import github.hua0512.data.stream.Streamer
import github.hua0512.data.stream.StreamerState
import github.hua0512.data.stream.StreamingPlatform
import github.hua0512.plugins.download.fillDownloadConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class BilibiliDownloadConfigSerializationTest : FunSpec({
  val json = Json

  test("should deserialize Bilibili streamer download config with frontend recording fields") {
    val streamer = json.decodeFromString<Streamer>(
      """
      {
        "name": "bilibili fixture",
        "url": "https://live.bilibili.com/22603245",
        "platform": "BILIBILI",
        "state": 5,
        "isTemplate": false,
        "downloadConfig": {
          "type": "bilibili",
          "cookies": null,
          "danmu": true,
          "sourceFormat": "flv",
          "fetchDelay": 0,
          "partedDownloadRetry": 0,
          "downloadCheckInterval": null,
          "onPartedDownload": [],
          "onStreamingFinished": []
        }
      }
      """.trimIndent()
    )

    val config = streamer.downloadConfig as DownloadConfig.BilibiliDownloadConfig
    streamer.platform shouldBe StreamingPlatform.BILIBILI
    streamer.state shouldBe StreamerState.CANCELLED
    config.sourceFormat shouldBe VideoFormat.flv
    config.fetchDelay shouldBe 0L
    config.partedDownloadRetry shouldBe 0
    config.downloadCheckInterval shouldBe null
    config.danmu shouldBe true
    config.onPartedDownload shouldBe emptyList()
    config.onStreamingFinished shouldBe emptyList()
  }

  test("should preserve streamer-level Bilibili recording fields when filling config") {
    val filled = DownloadConfig.BilibiliDownloadConfig(
      fetchDelay = 5,
      partedDownloadRetry = 7,
      downloadCheckInterval = 11,
    ).fillDownloadConfig(
      platform = StreamingPlatform.BILIBILI,
      templateConfig = null,
      appConfig = AppConfig(
        bilibiliConfig = BilibiliConfigGlobal(
          fetchDelay = 1,
          partedDownloadRetry = 2,
          downloadCheckInterval = 3,
        )
      ),
    )

    filled.fetchDelay shouldBe 5L
    filled.partedDownloadRetry shouldBe 7
    filled.downloadCheckInterval shouldBe 11L
  }

  test("should fill missing Bilibili recording fields from global config") {
    val filled = DownloadConfig.BilibiliDownloadConfig().fillDownloadConfig(
      platform = StreamingPlatform.BILIBILI,
      templateConfig = null,
      appConfig = AppConfig(
        bilibiliConfig = BilibiliConfigGlobal(
          fetchDelay = 1,
          partedDownloadRetry = 2,
          downloadCheckInterval = 3,
        )
      ),
    )

    filled.fetchDelay shouldBe 1L
    filled.partedDownloadRetry shouldBe 2
    filled.downloadCheckInterval shouldBe 3L
  }
})
