/*
 * MIT License
 *
 * Stream-rec  https://github.com/hua0512/stream-rec
 *
 * Copyright (c) 2026 hua0512 (https://github.com/hua0512)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package github.hua0512.plugins.bilibili.danmu

import github.hua0512.data.media.DanmuDataWrapper
import github.hua0512.data.media.DanmuDataWrapper.DanmuData
import github.hua0512.data.media.DanmuDataWrapper.EndOfDanmu
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream
import kotlin.time.Clock

data class BilibiliDanmuPacket(
  val operation: Int,
  val version: Int,
  val sequence: Int,
  val body: ByteArray,
)

object BilibiliDanmuProtocol {
  const val OP_HEARTBEAT = 2
  const val OP_HEARTBEAT_REPLY = 3
  const val OP_MESSAGE = 5
  const val OP_AUTH = 7
  const val OP_AUTH_REPLY = 8

  const val VERSION_JSON = 0
  const val VERSION_HEARTBEAT = 1
  const val VERSION_ZLIB = 2
  const val VERSION_BROTLI = 3

  private const val HEADER_LENGTH = 16
  private const val SECONDS_TO_MILLIS_THRESHOLD = 100_000_000_000L

  fun encode(
    operation: Int,
    body: ByteArray = ByteArray(0),
    version: Int = VERSION_JSON,
    sequence: Int = 1,
  ): ByteArray {
    val buffer = ByteBuffer.allocate(HEADER_LENGTH + body.size).order(ByteOrder.BIG_ENDIAN)
    buffer.putInt(HEADER_LENGTH + body.size)
    buffer.putShort(HEADER_LENGTH.toShort())
    buffer.putShort(version.toShort())
    buffer.putInt(operation)
    buffer.putInt(sequence)
    buffer.put(body)
    return buffer.array()
  }

  fun decode(data: ByteArray): List<BilibiliDanmuPacket> {
    if (data.size < HEADER_LENGTH) return emptyList()

    val packets = mutableListOf<BilibiliDanmuPacket>()
    var offset = 0
    while (offset + HEADER_LENGTH <= data.size) {
      val packetLength = data.readInt(offset)
      val headerLength = data.readUnsignedShort(offset + 4)
      val version = data.readUnsignedShort(offset + 6)
      val operation = data.readInt(offset + 8)
      val sequence = data.readInt(offset + 12)

      if (
        packetLength < headerLength ||
        headerLength < HEADER_LENGTH ||
        offset + packetLength > data.size
      ) {
        break
      }

      val body = data.copyOfRange(offset + headerLength, offset + packetLength)
      packets += BilibiliDanmuPacket(
        operation = operation,
        version = version,
        sequence = sequence,
        body = body,
      )
      offset += packetLength
    }
    return packets
  }

  fun decodeMessages(data: ByteArray, json: Json): List<DanmuDataWrapper?> =
    decode(data).flatMap { packet -> packet.decodeMessages(json) }.filterNotNull()

  private fun BilibiliDanmuPacket.decodeMessages(json: Json): List<DanmuDataWrapper?> {
    if (operation != OP_MESSAGE) return emptyList()
    return when (version) {
      VERSION_JSON -> listOfNotNull(parseMessage(body.decodeToString(), json))
      VERSION_ZLIB -> inflateZlib(body)?.let { decodeMessages(it, json) }.orEmpty()
      VERSION_BROTLI -> inflateBrotli(body)?.let { decodeMessages(it, json) }.orEmpty()
      else -> emptyList()
    }
  }

  private fun parseMessage(body: String, json: Json): DanmuDataWrapper? =
    runCatching {
      val root = json.parseToJsonElement(body).jsonObject
      val cmd = root["cmd"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
      when {
        cmd.startsWith("DANMU_MSG") -> root.toDanmuData()
        cmd == "PREPARING" -> EndOfDanmu
        else -> null
      }
    }.getOrNull()

  private fun JsonObject.toDanmuData(): DanmuData? {
    val info = this["info"]?.jsonArray ?: return null
    val metadata = info.getOrNull(0)?.jsonArray
    val user = info.getOrNull(2)?.jsonArray

    return DanmuData(
      uid = user?.getOrNull(0)?.jsonPrimitive?.longOrNull ?: 0L,
      sender = user?.getOrNull(1)?.jsonPrimitive?.contentOrNull.orEmpty(),
      color = metadata?.getOrNull(3)?.jsonPrimitive?.intOrNull ?: -1,
      content = info.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return null,
      fontSize = metadata?.getOrNull(2)?.jsonPrimitive?.intOrNull ?: 0,
      serverTime = normalizeServerTime(metadata?.getOrNull(4)?.jsonPrimitive?.longOrNull),
    )
  }

  private fun normalizeServerTime(serverTime: Long?): Long =
    when {
      serverTime == null || serverTime <= 0L -> Clock.System.now().toEpochMilliseconds()
      serverTime < SECONDS_TO_MILLIS_THRESHOLD -> serverTime * 1000
      else -> serverTime
    }

  private fun inflateZlib(data: ByteArray): ByteArray? =
    runCatching {
      InflaterInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }.getOrNull()

  private fun inflateBrotli(data: ByteArray): ByteArray? =
    runCatching {
      BrotliInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }.getOrNull()

  private fun ByteArray.readInt(offset: Int): Int =
    ByteBuffer.wrap(this, offset, Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).int

  private fun ByteArray.readUnsignedShort(offset: Int): Int =
    ByteBuffer.wrap(this, offset, Short.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
}
