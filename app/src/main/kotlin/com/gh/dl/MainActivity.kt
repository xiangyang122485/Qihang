package com.gh.dl

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gh.dl.ui.GhApp
import com.gh.dl.ui.theme.GhTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 解析深链：仅接受 github.com / www.github.com 且路径至少两段（/owner/repo）的仓库链接，
        // 其余 VIEW 意图（单段路径、其它 host）一律忽略，不污染解析器输入框。
        val link = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                val h = uri.host?.lowercase()
                if ((h == "github.com" || h == "www.github.com") &&
                    uri.pathSegments.size >= 2
                ) uri.toString() else null
            }
            else -> null
        }
        setContent {
            GhTheme { GhApp(link) }
        }
    }
}
