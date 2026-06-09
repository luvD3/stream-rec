package bilibili

import github.hua0512.data.media.DanmuDataWrapper
import github.hua0512.data.media.DanmuDataWrapper.DanmuData
import github.hua0512.plugins.bilibili.danmu.BilibiliDanmuProtocol
import github.hua0512.plugins.bilibili.danmu.BilibiliWbiSigner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream

class BilibiliDanmuPacketTest : FunSpec({

  test("encodes and decodes bilibili danmu packet headers") {
    val body = """{"uid":0}""".encodeToByteArray()
    val encoded = BilibiliDanmuProtocol.encode(
      operation = BilibiliDanmuProtocol.OP_AUTH,
      body = body,
      sequence = 99,
    )

    val decoded = BilibiliDanmuProtocol.decode(encoded)

    decoded.shouldHaveSize(1)
    decoded.single().operation shouldBe BilibiliDanmuProtocol.OP_AUTH
    decoded.single().version shouldBe BilibiliDanmuProtocol.VERSION_JSON
    decoded.single().sequence shouldBe 99
    decoded.single().body.decodeToString() shouldBe """{"uid":0}"""
  }

  test("maps plain json DANMU_MSG to DanmuData") {
    val encoded = BilibiliDanmuProtocol.encodeMessagePacket(danmuMsgJson())

    val messages = BilibiliDanmuProtocol.decodeMessages(encoded, bilibiliTestJson)

    messages.shouldHaveSize(1)
    val danmu = messages.single().shouldBeInstanceOf<DanmuData>()
    danmu.uid shouldBe 123456L
    danmu.sender shouldBe "FixtureUser"
    danmu.color shouldBe 16777215
    danmu.content shouldBe "hello bilibili"
    danmu.fontSize shouldBe 25
    danmu.serverTime shouldBe 1762257601000L
  }

  test("maps PREPARING command to end of danmu") {
    val encoded = BilibiliDanmuProtocol.encodeMessagePacket("""{"cmd":"PREPARING","roomid":22603245}""")

    val messages = BilibiliDanmuProtocol.decodeMessages(encoded, bilibiliTestJson)

    messages shouldBe listOf(DanmuDataWrapper.EndOfDanmu)
  }

  test("inflates zlib nested message packets") {
    val nested = BilibiliDanmuProtocol.encodeMessagePacket(danmuMsgJson(content = "zlib hello")) +
      BilibiliDanmuProtocol.encodeMessagePacket("""{"cmd":"PREPARING","roomid":22603245}""")
    val compressed = zlib(nested)
    val encoded = BilibiliDanmuProtocol.encode(
      operation = BilibiliDanmuProtocol.OP_MESSAGE,
      body = compressed,
      version = BilibiliDanmuProtocol.VERSION_ZLIB,
    )

    val messages = BilibiliDanmuProtocol.decodeMessages(encoded, bilibiliTestJson)

    messages.shouldHaveSize(2)
    messages[0].shouldBeInstanceOf<DanmuData>().content shouldBe "zlib hello"
    messages[1] shouldBe DanmuDataWrapper.EndOfDanmu
  }

  test("inflates brotli nested message packets") {
    val compressed = Base64.getDecoder().decode(BROTLI_NESTED_DANMU_PACKET)
    val encoded = BilibiliDanmuProtocol.encode(
      operation = BilibiliDanmuProtocol.OP_MESSAGE,
      body = compressed,
      version = BilibiliDanmuProtocol.VERSION_BROTLI,
    )

    val messages = BilibiliDanmuProtocol.decodeMessages(encoded, bilibiliTestJson)

    messages.shouldHaveSize(1)
    val danmu = messages.single().shouldBeInstanceOf<DanmuData>()
    danmu.uid shouldBe 998877L
    danmu.sender shouldBe "BrotliUser"
    danmu.content shouldBe "brotli hello"
    danmu.serverTime shouldBe 1762257601000L
  }

  test("ignores malformed and unsupported message packets") {
    BilibiliDanmuProtocol.decode(byteArrayOf(1, 2, 3)) shouldBe emptyList()

    val encoded = BilibiliDanmuProtocol.encodeMessagePacket("""{"cmd":"SEND_GIFT","data":{"giftName":"ignored"}}""")

    BilibiliDanmuProtocol.decodeMessages(encoded, bilibiliTestJson) shouldBe emptyList()
  }

  test("generates documented Bilibili WBI mixin key and signature") {
    val imgKey = "7cd084941338484aae1ad9425b84077c"
    val subKey = "4932caff0ff746eab6f01bf08b70ac45"

    BilibiliWbiSigner.mixinKey(imgKey, subKey) shouldBe "ea1db124af3c7062474693fa704f4ff8"

    val signed = BilibiliWbiSigner.sign(
      params = mapOf(
        "foo" to "114",
        "bar" to "514",
        "zab" to "1919810",
      ),
      imgKey = imgKey,
      subKey = subKey,
      timestampSeconds = 1702204169,
    )

    signed["wts"] shouldBe "1702204169"
    signed["w_rid"] shouldBe "8f6f2b5b3d485fe1886cec6a0be8c5d4"
  }
})

private const val BROTLI_NESTED_DANMU_PACKET =
  "G5EAEIzURnnhS0aodAaDa9hnmXBZF1uruZSPjti9woErqwdSAOx+6gYHGiCnYZgFOIAONuiNILISBzbgJMvClZ94BaRbHLazQ0CZdTuTZY8aEPk/wnQqVJqEmlprjSZUmxqT2FRURCgUAnNis8/f7XxnKzj13jlriXzxGGGKNBEqmqb/dt4C"

private fun danmuMsgJson(
  content: String = "hello bilibili",
  serverTime: Long = 1762257601L,
): String =
  """{"cmd":"DANMU_MSG","info":[[0,1,25,16777215,$serverTime,0,0,""],"$content",[123456,"FixtureUser",0,0,0,10000,1,""],[],[],{}]}"""

private fun BilibiliDanmuProtocol.encodeMessagePacket(json: String): ByteArray =
  encode(
    operation = OP_MESSAGE,
    body = json.encodeToByteArray(),
  )

private fun zlib(data: ByteArray): ByteArray {
  val out = ByteArrayOutputStream()
  DeflaterOutputStream(out).use {
    it.write(data)
  }
  return out.toByteArray()
}
