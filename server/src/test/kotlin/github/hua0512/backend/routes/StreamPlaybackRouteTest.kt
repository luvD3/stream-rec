package github.hua0512.backend.routes

import filesRoute
import github.hua0512.backend.plugins.configureSerialization
import github.hua0512.data.StreamDataId
import github.hua0512.data.StreamerId
import github.hua0512.data.stream.StreamData
import github.hua0512.repo.stream.StreamDataRepo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import streamDataHashWithExtension
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText

class StreamPlaybackRouteTest : FunSpec({

  val json = Json { ignoreUnknownKeys = true }

  test("returns playback manifest for record with video and danmu") {
    val video = createTempFile(suffix = ".flv").apply {
      writeText("video-data")
    }.toFile()
    val danmu = createTempFile(suffix = ".xml").apply {
      writeText("""<i><d p="1,1,25,16777215,0,0,0,0">hello</d></i>""")
    }.toFile()

    testApplication {
      application {
        configureSerialization()
        routingWith(FakeStreamDataRepo(record(video.absolutePath, danmu.absolutePath)))
      }

      val response = client.get("/api/streams/1/playback")

      response.status shouldBe HttpStatusCode.OK
      val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
      body["id"]?.jsonPrimitive?.content shouldBe "1"
      body["video"]?.jsonObject?.get("url")?.jsonPrimitive?.content shouldBe "/files/1/${streamDataHashWithExtension(video.absolutePath)}"
      body["video"]?.jsonObject?.get("contentType")?.jsonPrimitive?.content shouldBe "video/x-flv"
      body["danmu"]?.jsonObject?.get("url")?.jsonPrimitive?.content shouldBe "/files/1/${streamDataHashWithExtension(danmu.absolutePath)}"
      body["danmu"]?.jsonObject?.get("contentType")?.jsonPrimitive?.content shouldBe "application/xml"
    }
  }

  test("returns playback manifest without danmu when danmu path is absent") {
    val video = createTempFile(suffix = ".mp4").apply {
      writeText("video-data")
    }.toFile()

    testApplication {
      application {
        configureSerialization()
        routingWith(FakeStreamDataRepo(record(video.absolutePath, null)))
      }

      val response = client.get("/api/streams/1/playback")

      response.status shouldBe HttpStatusCode.OK
      val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
      body["video"]?.jsonObject?.get("contentType")?.jsonPrimitive?.content shouldBe "video/mp4"
      body["danmu"] shouldBe JsonNull
    }
  }

  test("returns not found for missing playback record") {
    testApplication {
      application {
        configureSerialization()
        routingWith(FakeStreamDataRepo(null))
      }

      client.get("/api/streams/1/playback").status shouldBe HttpStatusCode.NotFound
    }
  }

  test("serves partial content for ranged file requests") {
    val video = createTempFile(suffix = ".mp4").apply {
      writeText("0123456789")
    }.toFile()

    testApplication {
      application {
        routingWith(FakeStreamDataRepo(record(video.absolutePath, null)))
      }

      val response = client.get("/api/files/1/${streamDataHashWithExtension(video.absolutePath)}") {
        header(HttpHeaders.Range, "bytes=2-5")
      }

      response.status shouldBe HttpStatusCode.PartialContent
      response.headers[HttpHeaders.AcceptRanges] shouldBe "bytes"
      response.headers[HttpHeaders.ContentRange] shouldBe "bytes 2-5/10"
      response.headers[HttpHeaders.ContentLength] shouldBe "4"
      response.headers[HttpHeaders.ContentType]?.startsWith("video/mp4") shouldBe true
      response.bodyAsText() shouldBe "2345"
    }
  }
})

private fun io.ktor.server.application.Application.routingWith(repo: StreamDataRepo) {
  routing {
    route("/api") {
      streamsRoute(Json { ignoreUnknownKeys = true }, repo)
      filesRoute(repo)
    }
  }
}

private fun record(videoPath: String, danmuPath: String?) = StreamData(
  id = 1,
  title = "test record",
  dateStart = 100,
  dateEnd = 200,
  outputFilePath = videoPath,
  danmuFilePath = danmuPath,
  outputFileSize = 10,
  streamerId = 2,
).apply {
  streamerName = "tester"
}

private class FakeStreamDataRepo(private val streamData: StreamData?) : StreamDataRepo {
  override suspend fun getStreamDataById(streamDataId: StreamDataId): StreamData? {
    return streamData?.takeIf { it.id == streamDataId.value }
  }

  override suspend fun getAllStreamData(): List<StreamData> = streamData?.let { listOf(it) } ?: emptyList()

  override suspend fun getStreamDataPaged(
    page: Int,
    pageSize: Int,
    streamers: List<StreamerId>?,
    filter: String?,
    dateStart: Long?,
    dateEnd: Long?,
    sortColumn: String?,
    sortOrder: String?,
  ): List<StreamData> = getAllStreamData()

  override suspend fun count(
    streamers: List<StreamerId>?,
    filter: String?,
    dateStart: Long?,
    dateEnd: Long?,
  ): Long = getAllStreamData().size.toLong()

  override suspend fun save(streamData: StreamData): StreamData = streamData

  override suspend fun delete(id: StreamDataId, deleteLocal: Boolean): Boolean = true

  override suspend fun delete(ids: List<StreamDataId>, deleteLocal: Boolean): Boolean = true
}
