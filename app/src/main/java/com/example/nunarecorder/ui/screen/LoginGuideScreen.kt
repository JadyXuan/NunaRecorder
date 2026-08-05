package com.example.nunarecorder.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nunarecorder.enroll.EnrollmentCode

/**
 * 「怎么登录网站」。
 *
 * 参与者最容易卡的一步是：**过了学校的 SSO 之后，又要一个账号密码，这是哪来的？**
 * 那是两套完全不同的凭据——SSO 是进校园网关的，第二个才是自己的研究账号。
 * 界面上必须把这件事讲明白，否则每个人都会来问一遍。
 */
@Composable
fun LoginGuideScreen(
    enrollment: EnrollmentCode?,
    onCopy: (label: String, value: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("怎么登录标注网站", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        if (enrollment == null) {
            Text(
                "请先在「入组」页扫描二维码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            return@Column
        }

        Text(
            "标注要在浏览器里做，一共三步。第 2 步和第 3 步是**两套不同的账号**，别弄混。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        StepCard(1, "用浏览器打开标注网站") {
            val url = enrollment.webUrl
            if (url != null) {
                CopyRow("网址", url, onCopy)
            } else {
                Text(
                    "入组码里没有带网站地址，请向研究员索取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        StepCard(2, "先过学校网关（SSO）") {
            val sso = enrollment.login?.ssoUsername
            if (sso != null) {
                Text(
                    "这一步是进校园网络的关口，全体参与者共用同一个账号，不是你自己的账号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(6.dp))
                CopyRow("网关账号", sso, onCopy)
                enrollment.login.ssoPassword?.let { CopyRow("网关密码", it, onCopy) }
            } else {
                Text(
                    "网关账号密码请向研究员索取（入组码里没有带）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        StepCard(3, "再登录你自己的研究账号") {
            Text(
                "过了网关之后会看到我们的登录页，这时才用你自己的账号——" +
                    "就是下面这个，和上一步的网关账号不是一回事。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(6.dp))
            val login = enrollment.login
            if (login != null) {
                CopyRow("你的账号", login.username, onCopy)
                CopyRow("你的密码", login.password, onCopy)
            } else {
                CopyRow("你的账号", enrollment.participantId, onCopy)
                Text(
                    "密码在入组卡上（这里没有带）。丢了请联系研究员重置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        Text(
            "采集本身不需要登录网站——App 会自己上传。网站只用来给自己的录音打标。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun StepCard(step: Int, title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(22.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$step",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, onCopy: (String, String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.width(72.dp)
        )
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
        OutlinedButton(
            onClick = { onCopy(label, value) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(30.dp)
        ) { Text("复制", style = MaterialTheme.typography.labelSmall) }
    }
}
