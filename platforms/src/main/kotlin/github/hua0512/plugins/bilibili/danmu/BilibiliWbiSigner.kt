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

import github.hua0512.utils.md5
import kotlin.time.Clock

internal object BilibiliWbiSigner {
  private val mixinKeyEncTab = intArrayOf(
    46, 47, 18, 2, 53, 8, 23, 32,
    15, 50, 10, 31, 58, 3, 45, 35,
    27, 43, 5, 49, 33, 9, 42, 19,
    29, 28, 14, 39, 12, 38, 41, 13,
    37, 48, 7, 16, 24, 55, 40, 61,
    26, 17, 0, 1, 60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6, 63,
    57, 62, 11, 36, 20, 34, 44, 52,
  )

  fun sign(
    params: Map<String, String>,
    imgKey: String,
    subKey: String,
    timestampSeconds: Long = Clock.System.now().epochSeconds,
  ): Map<String, String> {
    val signedParams = params + ("wts" to timestampSeconds.toString())
    val mixinKey = mixinKey(imgKey, subKey)
    val query = signedParams
      .toSortedMap()
      .map { (key, value) -> "${key.percentEncode()}=${value.removeWbiIgnoredChars().percentEncode()}" }
      .joinToString("&")
    return signedParams + ("w_rid" to (query + mixinKey).md5())
  }

  fun mixinKey(imgKey: String, subKey: String): String {
    val rawKey = imgKey + subKey
    return mixinKeyEncTab
      .asSequence()
      .mapNotNull { rawKey.getOrNull(it) }
      .take(32)
      .joinToString("")
  }

  private fun String.removeWbiIgnoredChars(): String =
    filterNot { it == '!' || it == '\'' || it == '(' || it == ')' || it == '*' }

  private fun String.percentEncode(): String = buildString {
    for (byte in this@percentEncode.encodeToByteArray()) {
      val value = byte.toInt() and 0xff
      val char = value.toChar()
      if (
        char in 'A'..'Z' ||
        char in 'a'..'z' ||
        char in '0'..'9' ||
        char == '-' ||
        char == '_' ||
        char == '.' ||
        char == '~'
      ) {
        append(char)
      } else {
        append('%')
        append(value.toString(16).uppercase().padStart(2, '0'))
      }
    }
  }
}
