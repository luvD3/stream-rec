package bilibili

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json

internal val bilibiliTestJson = Json {
  ignoreUnknownKeys = true
  isLenient = true
}

internal data class BilibiliFixtureRoutes(
  val roomInit: String = "room_init_live.json",
  val roomInfo: String = "room_info.json",
  val playInfo: String = "play_info_multi_qn.json",
)

internal fun bilibiliFixture(name: String): String =
  object {}.javaClass.getResourceAsStream("/bilibili/$name")?.bufferedReader()?.readText()
    ?: error("Missing Bilibili fixture: $name")

internal fun bilibiliFixtureClient(
  routes: BilibiliFixtureRoutes = BilibiliFixtureRoutes(),
  seenRequests: MutableList<HttpRequestData> = mutableListOf(),
): HttpClient = HttpClient(MockEngine) {
  engine {
    addHandler { request ->
      seenRequests += request
      val fixtureName = when {
        request.url.encodedPath.contains("room_init") -> routes.roomInit
        request.url.encodedPath.contains("getRoomPlayInfo") -> routes.playInfo
        request.url.encodedPath.contains("getInfoByRoom") -> routes.roomInfo
        request.url.encodedPath.contains("getRoomBaseInfo") -> routes.roomInfo
        request.url.encodedPath.contains("get_info") -> routes.roomInfo
        else -> error("Unhandled Bilibili fixture request: ${request.url}")
      }

      respond(
        content = bilibiliFixture(fixtureName),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
  }
}
