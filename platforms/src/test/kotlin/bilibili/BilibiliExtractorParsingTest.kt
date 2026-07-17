package bilibili

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import github.hua0512.data.media.VideoFormat
import github.hua0512.plugins.base.ExtractorError
import github.hua0512.plugins.bilibili.download.BilibiliExtractor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class BilibiliExtractorParsingTest : FunSpec({

  test("url regex recognizes canonical live room urls") {
    val match = BilibiliExtractor.URL_REGEX.toRegex().matchEntire("https://live.bilibili.com/22603245")

    match.shouldNotBeNull()
    match.groupValues.last() shouldBeEqual "22603245"
    BilibiliExtractor.URL_REGEX.toRegex().matchEntire("https://www.bilibili.com/video/BV1xx411c7mD") shouldBe null
  }

  test("extract initializes live room metadata from room APIs") {
    val result = createExtractor().extract()

    result.isOk shouldBe true
    val mediaInfo = result.get()
    mediaInfo.shouldNotBeNull()
    mediaInfo.site shouldBe "https://live.bilibili.com/22603245"
    mediaInfo.live shouldBe true
    mediaInfo.title shouldBe "Bilibili fixture live room"
    mediaInfo.artist shouldBe "Fixture Anchor"
    mediaInfo.streams.shouldNotBeEmpty()
  }

  test("extract returns not-live media info without requesting play streams") {
    val seenRequests = mutableListOf<io.ktor.client.request.HttpRequestData>()
    val result = createExtractor(
      routes = BilibiliFixtureRoutes(roomInit = "room_init_not_live.json"),
      seenRequests = seenRequests,
    ).extract()

    result.isOk shouldBe true
    val mediaInfo = result.get()
    mediaInfo.shouldNotBeNull()
    mediaInfo.live shouldBe false
    mediaInfo.streams shouldBe emptyList()
    seenRequests.map { it.url.encodedPath }.none { it.contains("getRoomPlayInfo") } shouldBe true
  }

  test("extract rejects live room responses with no playable streams") {
    val result = createExtractor(
      routes = BilibiliFixtureRoutes(playInfo = "play_info_empty_streams.json"),
    ).extract()

    result.isErr shouldBe true
    result.getError() shouldBe ExtractorError.NoStreamsFound
  }

  test("extract surfaces bilibili api errors as invalid responses") {
    val result = createExtractor(
      routes = BilibiliFixtureRoutes(roomInit = "room_init_error.json"),
    ).extract()

    result.isErr shouldBe true
    result.getError().shouldBeInstanceOf<ExtractorError.InvalidResponse>()
  }

  test("extract parses flv, hls, avc, hevc, and multiple qn variants") {
    val result = createExtractor().extract()

    result.isOk shouldBe true
    val streams = result.get()!!.streams
    streams.map { it.format }.toSet().shouldContainAll(listOf(VideoFormat.flv, VideoFormat.hls))
    streams.mapNotNull { it.extras["codec"] }.toSet().shouldContainAll(listOf("avc", "hevc"))
    streams.mapNotNull { it.extras["qn"] }.toSet().shouldContainAll(listOf("400", "10000", "20000"))

    streams.any {
      it.format == VideoFormat.flv &&
        it.extras["codec"] == "avc" &&
        it.extras["qn"] == "20000" &&
        it.quality == "4K" &&
        it.bitrate == 20000L
    } shouldBe true
    streams.any {
      it.format == VideoFormat.hls &&
        it.extras["codec"] == "avc" &&
        it.extras["qn"] == "10000" &&
        it.quality == "Original" &&
        it.bitrate == 10000L
    } shouldBe true
  }
})

private fun createExtractor(
  routes: BilibiliFixtureRoutes = BilibiliFixtureRoutes(),
  seenRequests: MutableList<io.ktor.client.request.HttpRequestData> = mutableListOf(),
): BilibiliExtractor =
  BilibiliExtractor(
    bilibiliFixtureClient(routes, seenRequests),
    bilibiliTestJson,
    "https://live.bilibili.com/22603245",
  ).apply {
    prepare()
  }
