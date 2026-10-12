package dev.kifranei.ampp.media

/** Plugin ZIPs have no Android resource table; keep settings copy in both supported languages. */
internal class MediaSettingsText private constructor(private val chinese: Boolean) {
    private fun text(zh: String, en: String) = if (chinese) zh else en
    val name get() = text("AM 媒体增强", "AM Media Integrations")
    val introduction get() = text("各功能可在下方启用。保存后完全停止并重开 Apple Music。",
        "Enable features below. After saving, fully stop and reopen Apple Music.")
    val lyricon get() = text("词幕集成", "Lyricon integration")
    val iosControls get() = text("使用 iOS 媒体控制按钮", "Use the iOS media control button")
    val iosControlsDescription get() = text("调用系统音频输出面板，设备名显示在输出图标下方。",
        "Open the system audio output panel and show the device name under the output icon.")
    val sharing get() = text("解除分享白名单限制", "Remove sharing whitelist restrictions")
    val sharingDescription get() = text("为歌曲卡片和歌词卡片增加系统图片分享和保存到相册入口。",
        "Add system image sharing and Save to Photos to song and lyrics cards.")
    val playerGlass get() = text("播放页界面启用液态玻璃", "Enable Liquid Glass on the player screen")
    val playerGlassDescription get() = text("为睡眠定时、删除确认弹窗、更多菜单和音质弹窗启用液态玻璃效果。",
        "Apply Liquid Glass to the sleep timer, deletion confirmation dialog, More menu, and audio quality dialog.")
    val qualityGlass get() = text("液态玻璃音质弹窗", "Liquid Glass audio quality dialog")
    val moreGlass get() = text("液态玻璃更多菜单", "Liquid Glass More menu")
    val confirmationGlass get() = text("液态玻璃删除确认弹窗", "Liquid Glass deletion confirmation dialog")
    val sleepGlass get() = text("液态玻璃睡眠定时", "Liquid Glass sleep timer")
    val playerHandle get() = text("播放页顶部把手", "Player screen handle")
    val systemCorners get() = text("播放页系统圆角", "Use system corner radius on the player screen")
    val volumeBar get() = text("播放页音量条", "Player screen volume slider")
    val volumeBarOplusUnsupported get() = text("欧加真（ColorOS / realme UI / OxygenOS）系统暂不支持，已自动禁用",
        "Not supported on ColorOS / realme UI / OxygenOS (OPPO, realme, OnePlus) yet; disabled automatically.")
    val contentDownloads get() = text("下载封面、歌词和介绍", "Download artwork, lyrics and descriptions")
    val contentDownloadsDescription get() = text("在艺术家和专辑详情页下载图片、MP4 动态封面及介绍；播放页更多菜单或长按歌词按钮下载原始 TTML。保存到 Download/AM++。",
        "Download artwork, MP4 motion artwork and descriptions from artist and album pages. Export original TTML from the player More menu or by holding the lyrics button. Save to Download/AM++.")
    val playerAppearance get() = text("播放页外观", "Player screen appearance")
    val disabled get() = text("已关闭", "Disabled")
    val startupFailed get() = text("启动失败", "Startup failed")
    val save get() = text("保存设置", "Save settings")
    val saved get() = text("已保存，重开 Apple Music 后生效", "Saved. Reopen Apple Music to apply.")
    val saveFailed get() = text("保存失败，点击保存设置重试", "Could not save. Tap Save settings to retry.")
    val currentRun get() = text("本次运行", "Current session")

    companion object {
        fun forLanguage(language: String) = MediaSettingsText(language.equals("zh", ignoreCase = true))
    }
}
