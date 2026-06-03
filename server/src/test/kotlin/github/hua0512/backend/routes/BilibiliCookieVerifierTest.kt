package github.hua0512.backend.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals

class BilibiliCookieVerifierTest : FunSpec({

  val json = Json { ignoreUnknownKeys = true }

  test("extracts Bilibili live room id") {
    assertEquals(23058L, extractBilibiliRoomId("https://live.bilibili.com/23058?live_from=86002"))
  }

  test("rejects non Bilibili live room url") {
    extractBilibiliRoomId("https://example.com/23058").shouldBeNull()
  }

  test("parses logged in nav response") {
    val state = parseBilibiliLoginState(
      json,
      """
        {
          "code": 0,
          "message": "0",
          "data": {
            "isLogin": true,
            "mid": 12345,
            "uname": "tester"
          }
        }
      """.trimIndent()
    )

    state.loggedIn.shouldBeTrue()
    assertEquals(12345L, state.userId)
    assertEquals("tester", state.userName)
  }

  test("parses highest actual quality from play info current qn") {
    val playInfo = parseBilibiliPlayInfo(
      json,
      """
        {
          "code": 0,
          "data": {
            "room_id": 23058,
            "live_status": 1,
            "playurl_info": {
              "playurl": {
                "g_qn_desc": [
                  { "qn": 400, "desc": "蓝光" },
                  { "qn": 10000, "desc": "原画" },
                  { "qn": 20000, "desc": "4K" }
                ],
                "stream": [
                  {
                    "format": [
                      {
                        "codec": [
                          {
                            "current_qn": 10000,
                            "accept_qn": [400, 10000],
                            "base_url": "/live/test.flv",
                            "url_info": [{ "host": "https://example.com" }]
                          },
                          {
                            "current_qn": 20000,
                            "accept_qn": [400, 10000, 20000],
                            "base_url": "/live/test.m4s",
                            "url_info": [{ "host": "https://example.com" }]
                          }
                        ]
                      }
                    ]
                  }
                ]
              }
            }
          }
        }
      """.trimIndent()
    )

    playInfo.live.shouldBeTrue()
    assertEquals(23058L, playInfo.roomId)
    assertEquals(BilibiliQuality(20000, "4K"), playInfo.highestQuality)
    playInfo.availableQualities shouldContainExactly listOf(
      BilibiliQuality(20000, "4K"),
      BilibiliQuality(10000, "原画"),
      BilibiliQuality(400, "蓝光")
    )
  }
})
