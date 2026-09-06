package com.dilfish.autoagent.ui

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilfish.autoagent.accessibility.ClickAccessibilityService
import com.dilfish.autoagent.ai.LlmFactory
import com.dilfish.autoagent.ai.LlmSource
import com.dilfish.autoagent.console.ConsoleSession
import com.dilfish.autoagent.engine.AgentBus
import com.dilfish.autoagent.engine.TaskContext
import com.dilfish.autoagent.engine.TaskRunner
import com.dilfish.autoagent.pi.PiSource
import com.dilfish.autoagent.remote.RemoteManager
import com.dilfish.autoagent.settings.AppSettings
import com.dilfish.autoagent.shot.ProjectionService
import com.dilfish.autoagent.script.Script
import com.dilfish.autoagent.script.ScriptSource
import com.dilfish.autoagent.script.ScriptStore
import com.dilfish.autoagent.script.ScriptStep

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AutoAgentApp()
            }
        }
    }
}

@Composable
fun AutoAgentApp() {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    var scripts by remember { mutableStateOf(ScriptStore.loadAll(context)) }
    var selectedId by remember {
        mutableStateOf(ScriptStore.lastSelected(context) ?: ScriptStore.loadAll(context).firstOrNull()?.id)
    }

    fun reload() {
        scripts = ScriptStore.loadAll(context)
        if (selectedId !in scripts.map { it.id }) selectedId = scripts.firstOrNull()?.id
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(false, onClick = { page = 0 }, icon = {}, label = { Text("主页") })
                NavigationBarItem(false, onClick = { page = 1 }, icon = {}, label = { Text("脚本") })
                NavigationBarItem(false, onClick = { page = 3 }, icon = {}, label = { Text("控制台") })
                NavigationBarItem(false, onClick = { page = 2 }, icon = {}, label = { Text("设置") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (page) {
                0 -> HomeScreen(scripts, selectedId, onSelect = { selectedId = it; ScriptStore.setLastSelected(context, it) })
                1 -> ScriptsScreen(scripts, onSaved = { reload() })
                2 -> SettingsScreen()
                3 -> ConsoleScreen()
            }
        }
    }
}

// ---------------- 主页 ----------------

@Composable
fun HomeScreen(scripts: List<Script>, selectedId: String?, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val serviceOk by ClickAccessibilityService.serviceRunning.collectAsState()
    val running by TaskRunner.running.collectAsState()
    val progress by TaskRunner.progress.collectAsState()
    val logs by AgentBus.logs.collectAsState()
    var pickerOpen by remember { mutableStateOf(false) }
    var aiTask by remember { mutableStateOf("") }
    var aiEngine by remember { mutableStateOf("llm") }
    var engineMenu by remember { mutableStateOf(false) }

    val selected = scripts.firstOrNull { it.id == selectedId }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (serviceOk) "无障碍服务：已开启" else "无障碍服务：未开启",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!serviceOk) {
                        Text("开启后才能读取屏幕和执行点击", fontSize = 12.sp)
                    }
                }
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("去开启") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { pickerOpen = true }) {
                        Text("脚本：${selected?.name ?: "（未选择）"}")
                    }
                    DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                        if (scripts.isEmpty()) DropdownMenuItem(text = { Text("（无脚本）") }, onClick = {})
                        for (s in scripts) {
                            DropdownMenuItem(
                                text = { Text(s.name) },
                                onClick = { onSelect(s.id); pickerOpen = false },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            selected?.let { TaskRunner.start(ScriptSource(it), TaskContext(it.name)) }
                        },
                        enabled = serviceOk && !running && selected != null,
                    ) { Text(if (running) "运行中…" else "开始执行") }
                    OutlinedButton(onClick = { TaskRunner.stop() }, enabled = running) {
                        Text("停止")
                    }
                }
                Text(progress, fontSize = 13.sp)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI 任务", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("大脑：", fontSize = 13.sp)
                    Box {
                        OutlinedButton(onClick = { engineMenu = true }) {
                            Text(if (aiEngine == "llm") "内置 LLM" else "pi agent")
                        }
                        DropdownMenu(expanded = engineMenu, onDismissRequest = { engineMenu = false }) {
                            DropdownMenuItem(text = { Text("内置 LLM") }, onClick = { aiEngine = "llm"; engineMenu = false })
                            DropdownMenuItem(text = { Text("pi agent") }, onClick = { aiEngine = "pi"; engineMenu = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = aiTask,
                    onValueChange = { aiTask = it },
                    label = { Text("用自然语言描述任务") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val desc = aiTask.trim()
                        if (aiEngine == "llm") {
                            val providerType = AppSettings.llmProvider(context)
                            val baseUrl = AppSettings.llmBaseUrl(context)
                            val apiKey = AppSettings.llmApiKey(context)
                            val model = AppSettings.llmModel(context)
                            if (apiKey.isEmpty() || model.isEmpty()) {
                                AgentBus.log("请先在「设置」里配置 LLM API（协议 / key / model）")
                                return@Button
                            }
                            if (providerType == "openai" && baseUrl.isEmpty()) {
                                AgentBus.log("OpenAI 兼容协议需要填写 Base URL（如 https://api.xx.com/v1）")
                                return@Button
                            }
                            TaskRunner.start(
                                LlmSource(LlmFactory.create(providerType, baseUrl, apiKey, model)),
                                TaskContext(desc),
                            )
                        } else {
                            if (AppSettings.piHost(context).isEmpty()) {
                                AgentBus.log("请先在「设置」里配置 pi 服务器")
                                return@Button
                            }
                            TaskRunner.start(PiSource(context.applicationContext), TaskContext(desc))
                        }
                        aiTask = ""
                    },
                    enabled = serviceOk && !running && aiTask.isNotBlank(),
                ) { Text("AI 执行") }
            }
        }

        Text("日志", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f), reverseLayout = true) {
            items(logs) { line ->
                Text(line, fontSize = 12.sp, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

// ---------------- 脚本页 ----------------

@Composable
fun ScriptsScreen(scripts: List<Script>, onSaved: () -> Unit) {
    val context = LocalContext.current
    val serviceOk by ClickAccessibilityService.serviceRunning.collectAsState()
    val editing = EditorState.current
    var newDialog by remember { mutableStateOf(false) }
    var stepDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { newDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("新建")
            }
            Button(
                onClick = {
                    editing?.let {
                        ScriptStore.save(context, it)
                        ScriptStore.setLastSelected(context, it.id)
                        EditorState.open(it)
                        AgentBus.log("脚本「${it.name}」已保存")
                        onSaved()
                    }
                },
                enabled = editing != null && EditorState.dirty,
            ) { Text("保存") }
            OutlinedButton(
                onClick = {
                    editing?.let {
                        ScriptStore.delete(context, it.id)
                        EditorState.open(null)
                        onSaved()
                    }
                },
                enabled = editing != null,
            ) { Text("删除") }
        }

        if (scripts.isNotEmpty()) {
            Text("已有脚本：${scripts.joinToString("、") { it.name }}", fontSize = 12.sp)
        }

        val script = editing
        if (script == null) {
            Text("点击「新建」创建脚本，或从下方打开已有脚本")
            LazyColumn(Modifier.weight(1f)) {
                items(scripts) { s ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleSmall)
                                Text("${s.steps.size} 步 · ${if (s.rounds == 0) "无限循环" else "${s.rounds} 轮"}", fontSize = 12.sp)
                            }
                            Button(onClick = { EditorState.open(s) }) { Text("编辑") }
                        }
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = script.name,
                onValueChange = { EditorState.setName(it) },
                label = { Text("脚本名称") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = script.rounds.toString(),
                onValueChange = { v -> EditorState.setRounds(v.filter { it.isDigit() }.take(6).toIntOrNull() ?: 0) },
                label = { Text("循环轮次（0 = 无限）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val svc = ClickAccessibilityService.instance
                        if (svc == null) {
                            AgentBus.log("无障碍服务未开启，无法选点")
                        } else {
                            svc.startPickPoints { x, y -> EditorState.addPoint(x, y) }
                        }
                    },
                    enabled = serviceOk,
                ) { Text("选点添加") }
                OutlinedButton(onClick = { stepDialog = true }) { Text("手动添加") }
            }

            LazyColumn(Modifier.weight(1f)) {
                items(script.steps.size) { idx ->
                    val i = idx
                    val step = script.steps[i]
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${i + 1}. ${if (step.action == "longClick") "长按" else "点击"} (${step.x.toInt()},${step.y.toInt()}) 延迟${step.postDelayMs}ms ×${step.repeat}",
                                Modifier.weight(1f),
                                fontSize = 13.sp,
                            )
                            IconButton(onClick = { EditorState.moveStep(i, -1) }) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移")
                            }
                            IconButton(onClick = { EditorState.moveStep(i, 1) }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移")
                            }
                            IconButton(onClick = { EditorState.removeStep(i) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除")
                            }
                        }
                    }
                }
            }
        }
    }

    if (newDialog) {
        NewScriptDialog(
            onDismiss = { newDialog = false },
            onCreate = { name ->
                EditorState.open(Script(id = ScriptStore.newId(), name = name))
                newDialog = false
            },
        )
    }
    if (stepDialog) {
        ManualStepDialog(
            onDismiss = { stepDialog = false },
            onAdd = { step ->
                EditorState.addStep(step)
                stepDialog = false
            },
        )
    }
}

@Composable
fun NewScriptDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("脚本 ${java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US).format(java.util.Date())}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建脚本") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") })
        },
        confirmButton = { Button(onClick = { onCreate(name.ifBlank { "未命名" }) }) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ManualStepDialog(onDismiss: () -> Unit, onAdd: (ScriptStep) -> Unit) {
    var x by remember { mutableStateOf("") }
    var y by remember { mutableStateOf("") }
    var delayMs by remember { mutableStateOf("500") }
    var repeat by remember { mutableStateOf("1") }
    var longClick by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动添加步骤") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = x, onValueChange = { x = it },
                        label = { Text("X") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = y, onValueChange = { y = it },
                        label = { Text("Y") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = delayMs, onValueChange = { delayMs = it.filter { c -> c.isDigit() } },
                    label = { Text("点击后延迟 (ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = repeat, onValueChange = { repeat = it.filter { c -> c.isDigit() } },
                    label = { Text("重复次数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(checked = longClick, onCheckedChange = { longClick = it })
                    Spacer(Modifier.width(8.dp))
                    Text(if (longClick) "长按" else "单击")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val px = x.toFloatOrNull()
                val py = y.toFloatOrNull()
                if (px != null && py != null) {
                    onAdd(
                        ScriptStep(
                            x = px, y = py,
                            action = if (longClick) "longClick" else "click",
                            postDelayMs = delayMs.toLongOrNull() ?: 500,
                            repeat = (repeat.toIntOrNull() ?: 1).coerceAtLeast(1),
                        ),
                    )
                }
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------- 控制台页（B2） ----------------

@Composable
fun ConsoleScreen() {
    val running by TaskRunner.running.collectAsState()
    val progress by TaskRunner.progress.collectAsState()
    val logs by AgentBus.logs.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("指令控制台", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { ConsoleSession.stop() }, enabled = running) { Text("停止") }
        }
        Text(
            "click [03] · click 540 1200 · longClick [03] · inputText [02] 你好 · swipe 540 1800 540 800 · scroll up · back · home · wait 1000 · getElements · done",
            fontSize = 11.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入命令…") },
                singleLine = true,
            )
            Button(
                onClick = {
                    if (ConsoleSession.submit(input)) input = ""
                },
                enabled = input.isNotBlank(),
            ) { Text("发送") }
        }
        Text(progress, fontSize = 12.sp)
        LazyColumn(Modifier.weight(1f), reverseLayout = true) {
            items(logs) { line ->
                Text(line, fontSize = 12.sp, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

// ---------------- 设置页 ----------------

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("无障碍服务", style = MaterialTheme.typography.titleMedium)
                Text(
                    "设置 → 无障碍 → AutoAgent → 开启。" +
                        "Android 13+ 若开关为灰色（受限设置）：应用信息 → 右上角菜单 → 允许受限设置，再回来开启。",
                    fontSize = 13.sp,
                )
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("打开无障碍设置") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LLM API", style = MaterialTheme.typography.titleMedium)
                var provider by remember { mutableStateOf(AppSettings.llmProvider(context)) }
                var baseUrl by remember { mutableStateOf(AppSettings.llmBaseUrl(context)) }
                var apiKey by remember { mutableStateOf(AppSettings.llmApiKey(context)) }
                var model by remember { mutableStateOf(AppSettings.llmModel(context)) }
                var provMenu by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("协议：", fontSize = 13.sp)
                    Box {
                        OutlinedButton(onClick = { provMenu = true }) {
                            Text(
                                when (provider) {
                                    "responses" -> "OpenAI Responses"
                                    "anthropic" -> "Anthropic Messages"
                                    else -> "OpenAI Chat Completions"
                                },
                            )
                        }
                        DropdownMenu(expanded = provMenu, onDismissRequest = { provMenu = false }) {
                            DropdownMenuItem(text = { Text("OpenAI Chat Completions") }, onClick = { provider = "openai"; provMenu = false })
                            DropdownMenuItem(text = { Text("OpenAI Responses") }, onClick = { provider = "responses"; provMenu = false })
                            DropdownMenuItem(text = { Text("Anthropic Messages") }, onClick = { provider = "anthropic"; provMenu = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = {
                        Text(
                            when (provider) {
                                "responses" -> "Base URL（如 https://api.openai.com/v1，拼 /responses）"
                                "anthropic" -> "Base URL（留空 = https://api.anthropic.com）"
                                else -> "Base URL（如 https://api.xx.com/v1，拼 /chat/completions）"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("模型名") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    AppSettings.setLlm(context, provider, baseUrl, apiKey, model)
                    AgentBus.log("LLM 配置已保存")
                }) { Text("保存") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("远程指挥（经服务器中转）", style = MaterialTheme.typography.titleMedium)
                var wsUrl by remember { mutableStateOf(AppSettings.remoteWsUrl(context)) }
                var token by remember { mutableStateOf(AppSettings.remoteToken(context)) }
                val remoteState by RemoteManager.state.collectAsState()
                OutlinedTextField(
                    value = wsUrl, onValueChange = { wsUrl = it },
                    label = { Text("中转 WebSocket（如 ws://deb.871116.xyz:8787/ws/phone）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Token") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        AppSettings.setRemote(context, wsUrl, token)
                        if (wsUrl.isBlank() || token.isBlank()) {
                            AgentBus.log("请填写中转地址和 Token")
                        } else {
                            RemoteManager.start(wsUrl.trim(), token.trim())
                        }
                    }) { Text("连接") }
                    OutlinedButton(onClick = { RemoteManager.stop() }) { Text("断开") }
                }
                Text(remoteState, fontSize = 12.sp)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("屏幕截图（可选）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "启用后远程控制页可按需抓取单帧截图。手机重启后需要重新授权。",
                    fontSize = 13.sp,
                )
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { res ->
                    val data = res.data
                    if (res.resultCode == android.app.Activity.RESULT_OK && data != null) {
                        ProjectionService.start(context, res.resultCode, data)
                    } else {
                        AgentBus.log("录屏授权被拒绝")
                    }
                }
                Button(onClick = {
                    val pm = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager
                    launcher.launch(pm.createScreenCaptureIntent())
                }) { Text("授权录屏") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("pi agent（SSH 到服务器）", style = MaterialTheme.typography.titleMedium)
                var piHost by remember { mutableStateOf(AppSettings.piHost(context)) }
                var piPort by remember { mutableStateOf(AppSettings.piPort(context).toString()) }
                var piUser by remember { mutableStateOf(AppSettings.piUser(context)) }
                var piPassword by remember { mutableStateOf(AppSettings.piPassword(context)) }
                var piBin by remember { mutableStateOf(AppSettings.piBinPath(context)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = piHost, onValueChange = { piHost = it },
                        label = { Text("主机") }, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = piPort, onValueChange = { piPort = it.filter { c -> c.isDigit() } },
                        label = { Text("端口") }, modifier = Modifier.weight(0.5f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = piUser, onValueChange = { piUser = it },
                        label = { Text("用户") }, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = piPassword, onValueChange = { piPassword = it },
                        label = { Text("密码") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = piBin, onValueChange = { piBin = it },
                    label = { Text("pi 可执行文件路径") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    AppSettings.setPi(context, piHost, piPort.toIntOrNull() ?: 22, piUser, piPassword, piBin)
                    AgentBus.log("pi 配置已保存")
                }) { Text("保存") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("后台保活", style = MaterialTheme.typography.titleMedium)
                Text(
                    "把本应用加入电池优化白名单 / 允许自启动（部分国产 ROM 还有独立的自启动管理）。",
                    fontSize = 13.sp,
                )
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }) { Text("电池优化设置") }
            }
        }

        Text("AutoAgent v0.1.0", fontSize = 12.sp)
    }
}
