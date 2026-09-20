package com.trevor.assistant

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private val TrevorBackground = Color(0xFF04131D)
private val TrevorSurface = Color(0xCC0C2634)
private val TrevorCyan = Color(0xFF73DFFF)
private val TrevorMuted = Color(0xFF91AEBB)

private enum class TrevorScreen { HOME, SETTINGS, API_KEY, DEVELOPER }

object TrevorUiFileSelection {
    var selected: Uri? by mutableStateOf(null)
}

@Composable
fun TrevorApp(context: Context, launchFilePicker: () -> Unit) {
    var screen by remember { mutableStateOf(TrevorScreen.HOME) }
    var settings by remember { mutableStateOf(TrevorSettingsStore.load(context)) }

    fun update(value: TrevorSettings) {
        settings = value
        TrevorSettingsStore.save(context, value)
    }

    MaterialTheme {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(TrevorBackground, Color(0xFF082333), TrevorBackground))
            )
        ) {
            when (screen) {
                TrevorScreen.HOME -> HomeScreen(context, settings, launchFilePicker, { screen = TrevorScreen.SETTINGS }, { screen = TrevorScreen.DEVELOPER })
                TrevorScreen.SETTINGS -> SettingsScreen(settings, ::update, { screen = TrevorScreen.HOME }, { screen = TrevorScreen.API_KEY })
                TrevorScreen.API_KEY -> ApiKeyScreen(context) { screen = TrevorScreen.SETTINGS }
                TrevorScreen.DEVELOPER -> DeveloperScreen(context) { screen = TrevorScreen.HOME }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(context: Context, settings: TrevorSettings, launchFilePicker: () -> Unit, openSettings: () -> Unit, openDeveloper: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("TREVOR online. Ask me something.") }
    var busy by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(TrevorMode.NORMAL) }
    val scope = rememberCoroutineScope()

    fun send() {
        val command = input.trim()
        if (busy || command.isBlank()) return
        busy = true
        response = "Processing..."
        scope.launch {
            val result = TrevorCore.process(context, command, settings.aiEnabled, settings.geminiEnabled, settings.conciseResponses, settings.technicalDetail, if (mode == TrevorMode.NORMAL) null else mode)
            response = when (result) {
                is TrevorCoreResult.Answer -> result.text
                is TrevorCoreResult.Error -> result.message
            }
            busy = false
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Column { Text("T.R.E.V.O.R", fontWeight = FontWeight.Bold); Text(TrevorVersion.label(context) + " • " + if (busy) "PROCESSING" else "ONLINE", color = TrevorMuted, fontSize = 11.sp) } },
                actions = {
                    if (settings.developerMode || settings.debugMode) OutlinedButton(onClick = openDeveloper) { Text("DEV") }
                    OutlinedButton(onClick = openSettings) { Text("SETTINGS") }
                }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                FrostPanel {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("TREVOR CORE", color = TrevorCyan, fontWeight = FontWeight.Bold)
                        HolographicOrb()
                        Text("ORB • " + if (busy) "THINKING" else "IDLE", color = TrevorMuted, fontSize = 10.sp)
                    }
                }
            }
            item {
                Text("ORBITAL MODES", color = TrevorMuted, fontSize = 10.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ModeChip("ANALYSE", TrevorMode.ANALYSE, mode) { mode = it }
                    ModeChip("RESEARCH", TrevorMode.RESEARCH, mode) { mode = it }
                    ModeChip("PROJECT", TrevorMode.PROJECT, mode) { mode = it }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ModeChip("RATIO", TrevorMode.RATIO_SHIFTER, mode) { mode = it }
                    ModeChip("NORMAL", TrevorMode.NORMAL, mode) { mode = it }
                }
            }
            item {
                when (mode) {
                    TrevorMode.PROJECT -> ProjectPanel()
                    TrevorMode.RATIO_SHIFTER -> RatioPanel()
                    else -> AssistantPanel(response, input, busy, { input = it }, ::send, launchFilePicker, mode == TrevorMode.RESEARCH)
                }
            }
            item {
                FrostPanel {
                    Text("SYSTEM STATUS", color = TrevorCyan, fontWeight = FontWeight.Bold)
                    StatusRow("CORE", "READY")
                    StatusRow("AI", if (settings.aiEnabled && settings.geminiEnabled) "READY" else "DISABLED")
                    StatusRow("MODE", mode.name)
                    StatusRow("FILE", if (TrevorUiFileSelection.selected == null) "NONE" else "SELECTED")
                    StatusRow("OFFLINE", if (settings.offlineFirst) "ENABLED" else "DISABLED")
                }
            }
        }
    }
}

@Composable
private fun HolographicOrb() {
    Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(170.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color.White, TrevorCyan, Color(0xFF0A4058), TrevorBackground))))
        Box(Modifier.size(190.dp).border(1.dp, TrevorCyan.copy(alpha = 0.65f), CircleShape))
        Box(Modifier.size(210.dp).border(1.dp, TrevorCyan.copy(alpha = 0.25f), CircleShape))
    }
}

@Composable
private fun AssistantPanel(response:String,input:String,busy:Boolean,onInput:(String)->Unit,send:()->Unit,attach:()->Unit,research:Boolean) {
    FrostPanel {
        Text(if (research) "RESEARCH • Discover. Compare. Verify." else "AI ASSISTANCE • NORMAL / ANALYSE", color = TrevorCyan, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0x660A1C28))) { Text(response, Modifier.padding(11.dp)) }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, onInput, Modifier.weight(1f), placeholder = { Text(if (research) "Search the web..." else "Ask TREVOR anything...") })
            if (!research) Button(onClick = attach) { Text("FILE") }
            Button(onClick = send, enabled = !busy) { Text("SEND") }
        }
    }
}

@Composable
private fun ProjectPanel() {
    FrostPanel {
        Text("PROJECTS • Plan. Organise. Achieve.", color = TrevorCyan, fontWeight = FontWeight.Bold)
        Text("Persistent project storage is staged for the intelligence phase.", color = TrevorMuted, fontSize = 10.sp)
        listOf("Mobile App Development" to 60, "Home Automation" to 30, "Learning Path" to 80, "Personal Finance" to 45).forEach {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(it.first + " • " + it.second + "%", modifier = Modifier.weight(1f))
                Text("REFERENCE", color = TrevorMuted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun RatioPanel() {
    var a by remember { mutableStateOf("3") }
    var b by remember { mutableStateOf("5") }
    var c by remember { mutableStateOf("6") }
    var d by remember { mutableStateOf("10") }
    FrostPanel {
        Text("RATIO SHIFTER • Transform. Scale. Calculate.", color = TrevorCyan, fontWeight = FontWeight.Bold)
        Text("Offline-first proportional calculator", color = TrevorMuted, fontSize = 10.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RatioField(a) { a = it }; Text(":"); RatioField(b) { b = it }; Text("→"); RatioField(c) { c = it }; Text(":"); RatioField(d) { d = it }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            OutlinedButton(onClick = { val x=a;a=b;b=x;val y=c;c=d;d=y }, Modifier.weight(1f)) { Text("SWAP") }
            OutlinedButton(onClick = { a="3";b="5";c="6";d="10" }, Modifier.weight(1f)) { Text("RESET") }
            Button(onClick = { val x=a.toDoubleOrNull();val y=b.toDoubleOrNull();val z=c.toDoubleOrNull();if(x!=null&&y!=null&&z!=null&&x!=0.0){val r=z*y/x;d=if(r%1.0==0.0)r.toInt().toString() else "%.4f".format(r)} }, Modifier.weight(1f)) { Text("SHIFT") }
        }
    }
}

@Composable private fun RatioField(value:String,change:(String)->Unit){OutlinedTextField(value,change,Modifier.weight(1f),singleLine=true)}

@Composable
private fun ModeChip(label:String,mode:TrevorMode,selected:TrevorMode,click:(TrevorMode)->Unit){
    Text(label,color=if(mode==selected)TrevorBackground else TrevorCyan,fontSize=9.sp,modifier=Modifier.clip(RoundedCornerShape(18.dp)).background(if(mode==selected)TrevorCyan else Color(0x331B536A)).clickable{click(mode)}.padding(horizontal=10.dp,vertical=8.dp))
}

@Composable
private fun FrostPanel(content:@Composable ColumnScope.()->Unit){
    Card(Modifier.fillMaxWidth().border(1.dp,TrevorCyan.copy(alpha=.35f),RoundedCornerShape(18.dp)),shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=TrevorSurface),content=content)
}

@Composable private fun StatusRow(label:String,value:String){Row(Modifier.fillMaxWidth().padding(vertical=2.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(label,color=TrevorMuted,fontSize=10.sp);Text(value,color=TrevorCyan,fontSize=10.sp)}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(s:TrevorSettings,update:(TrevorSettings)->Unit,back:()->Unit,apiKey:()->Unit){
    Scaffold(containerColor=Color.Transparent,topBar={TopAppBar(title={Text("SYSTEM SETTINGS")},navigationIcon={Button(onClick=back){Text("BACK")}})}){pad->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(13.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            item{Text("AI",color=TrevorCyan,fontWeight=FontWeight.Bold)}
            item{SettingSwitch("AI enabled",s.aiEnabled){update(s.copy(aiEnabled=it))}}
            item{SettingSwitch("Gemini enabled",s.geminiEnabled){update(s.copy(geminiEnabled=it))}}
            item{Button(onClick=apiKey,Modifier.fillMaxWidth()){Text("GEMINI API KEY")}}
            item{SettingSwitch("Concise responses",s.conciseResponses){update(s.copy(conciseResponses=it))}}
            item{SettingSwitch("Technical detail",s.technicalDetail){update(s.copy(technicalDetail=it))}}
            item{HorizontalDivider()}
            item{Text("INTERFACE / ORB",color=TrevorCyan,fontWeight=FontWeight.Bold)}
            item{SettingSwitch("Orb enabled",s.orbEnabled){update(s.copy(orbEnabled=it))}}
            item{SettingSwitch("Animations",s.animations){update(s.copy(animations=it))}}
            item{SettingSwitch("Status indicators",s.showStatusIndicators){update(s.copy(showStatusIndicators=it))}}
            item{SettingSwitch("Quick actions",s.showQuickActions){update(s.copy(showQuickActions=it))}}
            item{HorizontalDivider()}
            item{Text("OFFLINE / SECURITY",color=TrevorCyan,fontWeight=FontWeight.Bold)}
            item{SettingSwitch("Offline-first",s.offlineFirst){update(s.copy(offlineFirst=it))}}
            item{SettingSwitch("Secure storage",s.secureStorage){update(s.copy(secureStorage=it))}}
            item{SettingSwitch("Local API-key encryption",s.localApiKeyEncryption){update(s.copy(localApiKeyEncryption=it))}}
            item{HorizontalDivider()}
            item{Text("DEVELOPER",color=TrevorCyan,fontWeight=FontWeight.Bold)}
            item{SettingSwitch("Developer mode",s.developerMode){update(s.copy(developerMode=it))}}
            item{SettingSwitch("Debug mode",s.debugMode){update(s.copy(debugMode=it))}}
            item{Button(onClick={update(TrevorSettings())},Modifier.fillMaxWidth()){Text("RESTORE DEFAULTS")}}
        }
    }
}

@Composable private fun SettingSwitch(label:String,checked:Boolean,change:(Boolean)->Unit){Row(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(checked,onCheckedChange=change)}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyScreen(context:Context,back:()->Unit){
    var key by remember{mutableStateOf("")};var message by remember{mutableStateOf("")}
    Scaffold(containerColor=Color.Transparent,topBar={TopAppBar(title={Text("GEMINI API KEY")},navigationIcon={Button(onClick=back){Text("BACK")}})}){pad->
        Column(Modifier.fillMaxSize().padding(pad).padding(13.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Encrypted local storage. Full key is never displayed.",color=TrevorMuted,fontSize=11.sp)
            OutlinedTextField(key,{key=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("API key")})
            Button(onClick={try{SecureApiKeyStore.save(context,key);key="";message="Key saved securely."}catch(e:Exception){message=e.message?:"Unable to save key."}},Modifier.fillMaxWidth(),enabled=key.isNotBlank()){Text("SAVE / REPLACE")}
            OutlinedButton(onClick={SecureApiKeyStore.clear(context);message="Stored key removed."},Modifier.fillMaxWidth()){Text("REMOVE KEY")}
            Text(message,color=TrevorCyan)
            StatusRow("KEY STATUS",if(SecureApiKeyStore.exists(context))"CONFIGURED" else "NOT CONFIGURED")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeveloperScreen(context:Context,back:()->Unit){
    val state=TrevorStateStore.state.value
    Scaffold(containerColor=Color.Transparent,topBar={TopAppBar(title={Text("DIAGNOSTICS")},navigationIcon={Button(onClick=back){Text("BACK")}})}){pad->
        Column(Modifier.fillMaxSize().padding(pad).padding(13.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            FrostPanel{Text("BUILD",color=TrevorCyan,fontWeight=FontWeight.Bold);StatusRow("VERSION",TrevorVersion.label(context));StatusRow("PACKAGE","com.trevor.assistant")}
            FrostPanel{Text("CORE STATE",color=TrevorCyan,fontWeight=FontWeight.Bold);StatusRow("MODE",state.currentMode?.name?:"NORMAL");StatusRow("ORB",state.orbState.name);StatusRow("REQUEST",state.requestState.name);StatusRow("AI",state.aiState.name);StatusRow("FILE",state.fileState.name);StatusRow("ERROR",state.lastError?:"NONE")}
            FrostPanel{Text("SECURITY",color=TrevorCyan,fontWeight=FontWeight.Bold);StatusRow("API KEY",if(SecureApiKeyStore.exists(context))"CONFIGURED" else "NOT CONFIGURED");StatusRow("SECRETS","NOT DISPLAYED")}
        }
    }
}
