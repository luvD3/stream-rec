import github.hua0512.app.App
import github.hua0512.data.config.AppConfig
import github.hua0512.data.stream.StreamingPlatform
import github.hua0512.plugins.bilibili.danmu.BilibiliDanmu
import github.hua0512.plugins.danmu.base.Danmu
import github.hua0512.plugins.danmu.base.NoDanmu
import github.hua0512.plugins.douyin.danmu.DouyinDanmu
import github.hua0512.plugins.douyu.danmu.DouyuDanmu
import github.hua0512.plugins.huya.danmu.HuyaDanmu
import github.hua0512.plugins.pandatv.danmu.PandaTvDanmu
import github.hua0512.plugins.twitch.danmu.TwitchDanmu
import github.hua0512.services.PlatformDownloaderFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

class PlatformDownloaderFactoryRegressionTest : FunSpec({

  test("binds every supported platform to its existing danmu implementation") {
    val app = testApp()

    platformBindings.forEach { binding ->
      val downloader = PlatformDownloaderFactory.createDownloader(app, binding.platform, binding.url)

      downloader.danmu::class shouldBe binding.danmuClass
    }

    app.releaseAll()
  }
})

private data class PlatformDanmuBinding(
  val platform: StreamingPlatform,
  val url: String,
  val danmuClass: KClass<out Danmu>,
)

private val platformBindings = listOf(
  PlatformDanmuBinding(StreamingPlatform.HUYA, "https://www.huya.com/660000", HuyaDanmu::class),
  PlatformDanmuBinding(StreamingPlatform.DOUYIN, "https://live.douyin.com/123456", DouyinDanmu::class),
  PlatformDanmuBinding(StreamingPlatform.DOUYU, "https://www.douyu.com/288016", DouyuDanmu::class),
  PlatformDanmuBinding(StreamingPlatform.TWITCH, "https://www.twitch.tv/aspaszin", TwitchDanmu::class),
  PlatformDanmuBinding(StreamingPlatform.PANDATV, "https://www.pandalive.co.kr/live/play/fixture", PandaTvDanmu::class),
  PlatformDanmuBinding(StreamingPlatform.WEIBO, "https://weibo.com/u/123456789", NoDanmu::class),
  PlatformDanmuBinding(StreamingPlatform.BILIBILI, "https://live.bilibili.com/22603245", BilibiliDanmu::class),
)

private fun testApp(): App =
  App(
    Json {
      ignoreUnknownKeys = true
      isLenient = true
    },
    HttpClient(MockEngine) {
      engine {
        addHandler {
          respond("{}", HttpStatusCode.OK)
        }
      }
    },
  ).apply {
    updateConfig(AppConfig())
  }
