import github.hua0512.data.config.AppConfig
import github.hua0512.data.config.BilibiliConfigGlobal
import github.hua0512.data.config.DouyinConfigGlobal
import github.hua0512.data.config.DouyuConfigGlobal
import github.hua0512.data.config.HuyaConfigGlobal
import github.hua0512.data.config.PandaTvConfigGlobal
import github.hua0512.data.config.TwitchConfigGlobal
import github.hua0512.data.config.WeiboConfigGlobal
import github.hua0512.data.config.redactedForLog
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AppConfigLogRedactionTest : FunSpec({

  test("should redact platform credentials when logging app config") {
    val redacted = AppConfig(
      outputFolder = "/opt/records",
      huyaConfig = HuyaConfigGlobal(cookies = "huya-cookie"),
      douyinConfig = DouyinConfigGlobal(cookies = "douyin-cookie"),
      douyuConfig = DouyuConfigGlobal(cookies = "douyu-cookie"),
      twitchConfig = TwitchConfigGlobal(authToken = "twitch-token", cookies = "twitch-cookie"),
      pandaTvConfig = PandaTvConfigGlobal(cookies = "panda-cookie"),
      weiboConfig = WeiboConfigGlobal(cookies = "weibo-cookie"),
      bilibiliConfig = BilibiliConfigGlobal(cookies = "SESSDATA=bilibili-session; bili_jct=bilibili-csrf"),
    ).redactedForLog().toString()

    listOf(
      "huya-cookie",
      "douyin-cookie",
      "douyu-cookie",
      "twitch-token",
      "twitch-cookie",
      "panda-cookie",
      "weibo-cookie",
      "bilibili-session",
      "bilibili-csrf",
    ).any { redacted.contains(it) } shouldBe false

    redacted.contains("cookies=<redacted>") shouldBe true
    redacted.contains("authToken=<redacted>") shouldBe true
    redacted.contains("outputFolder=/opt/records") shouldBe true
  }

  test("should preserve blank and null credentials in redacted log config") {
    val redacted = AppConfig(
      twitchConfig = TwitchConfigGlobal(authToken = "", cookies = ""),
      bilibiliConfig = BilibiliConfigGlobal(cookies = null),
    ).redactedForLog()

    redacted.twitchConfig.authToken shouldBe ""
    redacted.twitchConfig.cookies shouldBe ""
    redacted.bilibiliConfig.cookies shouldBe null
  }
})
