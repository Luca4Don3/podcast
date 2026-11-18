package com.podcastapp.ui.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.podcastapp.data.Session
import com.podcastapp.ui.AppViewModel
import com.podcastapp.ui.settings.ServerAddressSetting

/**
 * 登录 / 注册页：邮箱 + 密码，注册可选昵称与邀请码（服务端配置了邀请码时必填）。
 */
@Composable
fun AuthScreen(
    viewModel: AppViewModel,
    busy: Boolean,
    session: Session,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var email by rememberSaveable { mutableStateOf("") }
    // Do not persist secrets or send old credentials to a different server.
    var password by remember(session.baseUrl) { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var inviteCode by remember(session.baseUrl) { mutableStateOf("") }
    val hasServer = session.baseUrl.isNotBlank()
    val focus = LocalFocusManager.current
    val submit = {
        if (hasServer && !busy) {
            focus.clearFocus()
            if (tab == 0) viewModel.login(email, password) { }
            else viewModel.register(email, password, displayName, inviteCode) { }
        }
    }

    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
    Column(
        modifier = Modifier
            .widthIn(max = 460.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(
            Icons.Filled.Podcasts,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("播客", style = MaterialTheme.typography.headlineMedium)
        Text(
            "自己的播客与电台，在各设备间同步收听进度。",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        if (!hasServer) {
            ServerAddressSetting(viewModel = viewModel, session = session, busy = busy)
            Text("设置服务器后即可登录或注册。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }

        if (session.baseUrl.startsWith("http://")) {
            Text("正在使用 HTTP，登录信息和令牌不会被 HTTP 加密。只在可信内网或受保护的路径使用。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }
        TabRow(selectedTabIndex = tab, modifier = Modifier.fillMaxWidth()) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("登录") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("注册") })
        }
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("邮箱") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(if (tab == 0) "密码" else "密码（至少 8 位）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (tab == 0) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )

        if (tab == 1) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("昵称（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = inviteCode,
                onValueChange = { inviteCode = it },
                label = { Text("邀请码（如服务端开启）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = submit,
            enabled = hasServer && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(0.dp))
            } else {
                Text(if (tab == 0) "登录" else "创建账号")
            }
        }

        Spacer(Modifier.height(16.dp))
        if (hasServer) ServerAddressSetting(viewModel = viewModel, session = session, busy = busy)
        Spacer(Modifier.height(16.dp))
    }
    }
}
