package com.example.nunarecorder.ui.screen

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.nunarecorder.enroll.EnrollmentCode
import com.example.nunarecorder.web.LoginAutofill

/**
 * 内置浏览器：在 App 里打开标注站，并把两套账号自动填进去。
 *
 * 用户 2026-08-15 的需求原话：「手机 app 内置一个浏览器来打开标注 UI，入组后自动保存
 * sso 账号密码、而且一次登陆后也免密……这样全都可以在 app 一站式完成」。
 *
 * 三件事分别由三处保证：
 * - **自动填** —— [LoginAutofill] 决定哪个主机填哪一套，站外一律不填；
 * - **一次登录后免密** —— Cookie 持久化（见下），靠的是会话 Cookie 而不是反复重填；
 * - **凭据留在 App 里** —— 它们本来就随入组码下发并存在 `EnrollmentStore`，
 *   这里没有新增任何存储。
 *
 * ## Cookie 是这个功能的核心，也是它的清理责任
 *
 * 免密登录 = 保留网关和标注站的会话 Cookie。所以设备归还、或参与者退出入组时
 * **必须清掉**，否则下一个人拿到这台手机就直接是上一个人的身份。
 * 清理入口在 [clearBrowserSession]，由撤销入组的路径调用。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AnnotationBrowserScreen(
    enrollment: EnrollmentCode?,
    onShowCredentials: () -> Unit,
    onOpenExternally: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val startUrl = enrollment?.webUrl
    if (enrollment == null || startUrl.isNullOrBlank()) {
        Column(modifier.fillMaxSize().padding(24.dp)) {
            Text("还没有标注网站地址", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onShowCredentials) { Text("查看账号密码说明") }
            Text(
                if (enrollment == null) {
                    "请先到「入组」页扫描研究员给的二维码。"
                } else {
                    "这张入组卡里没有带标注网站地址，请联系研究员补一张。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        return
    }

    val webHost = remember(startUrl) { runCatching { Uri.parse(startUrl).host }.getOrNull() }
    // 每个主机只自动提交一次，防止凭据错误时变成无限重试把网关账号锁掉
    val autoSubmitted = remember { mutableSetOf<String>() }
    var loading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf(startUrl) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // WebView 是系统组件，正常一定在；但它可能正在被商店更新，或被精简 ROM 拿掉，
    // 那时构造会抛 MissingWebViewPackageException。**在进入 AndroidView 之前就构造**，
    // 否则异常发生在组合期，直接闪退。
    val webView = remember { runCatching { WebView(context) }.getOrNull() }

    if (webView == null) {
        Column(modifier.fillMaxSize().padding(24.dp)) {
            Text("这台手机的内置浏览器组件不可用", style = MaterialTheme.typography.titleMedium)
            Text(
                "可以改用系统浏览器打开标注网站，账号密码在下面这一页里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Row {
                TextButton(onClick = { onOpenExternally(startUrl) }) { Text("用系统浏览器打开") }
                TextButton(onClick = onShowCredentials) { Text("查看账号密码") }
            }
        }
        return
    }

    // 系统返回键先在网页里后退，退到头了再交给外面。
    // **canGoBack() 不是可观察状态**，直接写进 enabled 只会在首次组合时求值一次，
    // 之后翻多少页它都还是 false——返回键会直接退出这一页而不是网页内后退。
    // 所以用一个 state 在导航变化时刷新。
    var canGoBack by remember { mutableStateOf(false) }
    BackHandler(enabled = canGoBack) { webView.goBack() }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 主机名一直显示：自动填充是按主机决定的，参与者应当看得见自己在哪
            Text(
                runCatching { Uri.parse(currentUrl).host }.getOrNull() ?: currentUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { webView?.loadUrl(startUrl) }) { Text("首页") }
            // 自动填充失败时得有退路，否则一个填不上的浏览器就是死路
            TextButton(onClick = onShowCredentials) { Text("账号密码") }
            TextButton(onClick = { onOpenExternally(currentUrl) }) { Text("用浏览器打开") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            // 离开这个标签页时销毁。不销毁的话每切一次就漏一个 WebView 的原生内存，
            // 而且页面里正在播的录音会在后台继续响。
            // 代价是切回来要重新加载页面——登录状态在 Cookie 里，不用重新登录。
            onRelease = { runCatching { it.destroy() } },
            factory = {
                webView.apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // 标注站是给手机用的，按手机宽度渲染
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?, url: String?, favicon: android.graphics.Bitmap?
                        ) {
                            loading = true
                            url?.let { currentUrl = it }
                            canGoBack = view?.canGoBack() == true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            url?.let { currentUrl = it }
                            canGoBack = view?.canGoBack() == true
                            val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return
                            val target = LoginAutofill.targetFor(host, webHost)
                            // 站外什么都不注入。这一句是整个功能的安全边界。
                            if (target == LoginAutofill.Target.NONE) return
                            val creds = LoginAutofill.credentialsFor(enrollment, target) ?: return
                            val submit = LoginAutofill.shouldAutoSubmit(host, autoSubmitted)
                            if (submit) autoSubmitted.add(host)
                            view?.evaluateJavascript(
                                LoginAutofill.fillScript(creds.first, creds.second, submit)
                            ) { }
                        }
                    }
                    loadUrl(startUrl)
                }
            }
        )
    }
}

/**
 * 清掉内置浏览器的登录状态。
 *
 * **设备是要回收再发给下一个参与者的**，而免密登录意味着会话 Cookie 留在机器上。
 * 不清的话，下一个人打开标注站直接就是上一个人的身份——他能看到、甚至能改
 * 别人的标注。这比"要重新登录一次"严重得多。
 */
fun clearBrowserSession() {
    runCatching {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
