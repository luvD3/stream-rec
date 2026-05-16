package bilibili

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.unwrap
import github.hua0512.app.App
import github.hua0512.data.config.AppConfig
import github.hua0512.data.config.DownloadConfig
import github.hua0512.data.media.MediaInfo
import github.hua0512.data.media.VideoFormat
import github.hua0512.data.stream.Streamer
import github.hua0512.data.stream.StreamingPlatform
import github.hua0512.plugins.base.ExtractorError
import github.hua0512.plugins.bilibili.download.Bilibili
import github.hua0512.plugins.bilibili.download.BilibiliExtractor
import github.hua0512.plugins.danmu.base.NoDanmu
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import kotlinx.serialization.decodeFromString

class BilibiliAutoStreamSelectionTest : FunSpec({

  test("applyFilters selects the highest actual quality by default") {
    val selected = createDownloader().applyFilters(multiQnMediaInfo.streams).unwrap()

    selected.format shouldBe VideoFormat.flv
    selected.quality shouldBe "4K"
    selected.bitrate shouldBe 20000L
    selected.extras["qn"] shouldBe "20000"
  }

  test("applyFilters prefers the configured source format before falling back") {
    val selected = createDownloader(
      DownloadConfig.BilibiliDownloadConfig(sourceFormat = VideoFormat.hls),
    ).applyFilters(multiQnMediaInfo.streams).unwrap()

    selected.format shouldBe VideoFormat.hls
    selected.quality shouldBe "Original"
    selected.extras["qn"] shouldBe "10000"
    selected.extras["codec"] shouldBe "avc"
  }

  test("applyFilters falls back to flv when the preferred hls source is unavailable") {
    val selected = createDownloader(
      DownloadConfig.BilibiliDownloadConfig(sourceFormat = VideoFormat.hls),
    ).applyFilters(multiQnMediaInfo.streams.filter { it.format == VideoFormat.flv }).unwrap()

    selected.format shouldBe VideoFormat.flv
    selected.quality shouldBe "4K"
    selected.extras["qn"] shouldBe "20000"
  }

  test("applyFilters prefers avc when quality and format are tied") {
    val selected = createDownloader().applyFilters(
      multiQnMediaInfo.streams.filter {
        it.format == VideoFormat.flv && it.extras["qn"] == "20000"
      },
    ).unwrap()

    selected.format shouldBe VideoFormat.flv
    selected.quality shouldBe "4K"
    selected.extras["qn"] shouldBe "20000"
    selected.extras["codec"] shouldBe "avc"
  }

  test("applyFilters returns NoStreamsFound for an empty stream list") {
    val result = createDownloader().applyFilters(emptyList())

    result.isErr shouldBe true
    result.getError() shouldBe ExtractorError.NoStreamsFound
  }

  test("extract uses the anonymous request path when cookies are blank") {
    val seenRequests = mutableListOf<HttpRequestData>()
    val extractor = BilibiliExtractor(
      bilibiliFixtureClient(seenRequests = seenRequests),
      bilibiliTestJson,
      "https://live.bilibili.com/22603245",
    ).apply {
      prepare()
    }

    extractor.extract().isOk shouldBe true
    seenRequests.mapNotNull { it.headers[HttpHeaders.Cookie] }.shouldBeEmpty()
  }

  test("extract sends manually pasted cookies without persisting them") {
    val seenRequests = mutableListOf<HttpRequestData>()
    val extractor = BilibiliExtractor(
      bilibiliFixtureClient(seenRequests = seenRequests),
      bilibiliTestJson,
      "https://live.bilibili.com/22603245",
    ).apply {
      cookies = "SESSDATA=fixture-session; buvid3=fixture-buvid"
      prepare()
    }

    extractor.extract().isOk shouldBe true
    seenRequests.any {
      it.headers[HttpHeaders.Cookie]?.contains("SESSDATA=fixture-session") == true
    } shouldBe true
  }
})

private val multiQnMediaInfo: MediaInfo by lazy {
  bilibiliTestJson.decodeFromString(bilibiliFixture("multi_qn_media_info.json"))
}

private fun createDownloader(
  config: DownloadConfig.BilibiliDownloadConfig = DownloadConfig.BilibiliDownloadConfig(),
): Bilibili {
  val app = App(bilibiliTestJson, bilibiliFixtureClient()).apply {
    updateConfig(AppConfig())
  }
  val extractor = BilibiliExtractor(
    app.client,
    app.json,
    "https://live.bilibili.com/22603245",
  ).apply {
    prepare()
  }
  val downloader = Bilibili(app, NoDanmu(app), extractor)
  val init = downloader.init(
    Streamer(
      id = 1L,
      name = "bilibili",
      url = "https://live.bilibili.com/22603245",
      platform = StreamingPlatform.BILIBILI,
      downloadConfig = config,
    ),
  )

  init.isOk shouldBe true
  return downloader
}
