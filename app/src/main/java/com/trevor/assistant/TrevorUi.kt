package com.trevor.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.collectAsState
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private val TrevorBg = Color(0xFF04131D)
private val TrevorPanel = Color(0xCC0C2634)
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

    fun save(s: TrevorSettings) {
        settings = s
        TrevorSettingsStore.save(context, s)
    }

    MaterialTheme {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(TrevorBg, Color(0xFF082333), TrevorBg))
            )
        ) {
            when (screen) {
                TrevorScreen.HOME -> TrevorHome(context, settings, launchFilePicker, { screen = TrevorScreen.SETTINGS }, { screen = TrevorScreen.DEVELOPER })
                TrevorScreen.SETTINGS -> TrevorSettingsScreen(settings, ::save, { screen = TrevorScreen.HOME }, { screen = TrevorScreen.API_KEY })
                TrevorScreen.API_KEY -> TrevorApiKeyScreen(context) { screen = TrevorScreen.SETTINGS }
                TrevorScreen.DEVELOPER -> TrevorDeveloperScreen(context) { screen = TrevorScreen.HOME }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrevorHome(context: Context, settings: TrevorSettings, launchFilePicker: () -> Unit, onSettings: () -> Unit, onDeveloper: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("TREVOR online. Ask me something.") }
    var busy by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableStateOf(TrevorMode.NORMAL) }
    val scope = rememberCoroutineScope()
    val state by TrevorStateStore.state.collectAsState()

    fun send() {
        val clean = input.trim()
        if (busy || clean.isBlank()) return
        busy = true
        response = "Processing..."
        scope.launch {
            val result = TrevorCore.process(
                context, clean, settings.aiEnabled, settings.geminiEnabled,
                settings.conciseResponses, settings.technicalDetail,
                if (selectedMode == TrevorMode.NORMAL) null else selectedMode
            )
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
                title = {
                    Column {
                        Text("T.R.E.V.O.R", fontSize = 19.sp)
                        Text(TrevorVersion.label(context) + " • " + if (busy) "PROCESSING" else "ONLINE", fontSize = 11.sp, color = TrevorMuted)
                    }
                },
                actions = {
                    if (settings.developerMode || settings.debugMode) IconButton(onClick = onDeveloper) { Icon(Icons.Filled.DeveloperMode, "Developer", tint = TrevorCyan) }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings", tint = TrevorCyan) }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                FrostPanel {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("TREVOR CORE", color = TrevorCyan, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        if (settings.orbEnabled) AndroidView({ TrevorOrbView(it) }, Modifier.size(245.dp))
                        Text(state.orbState.name, color = TrevorMuted, fontSize = 10.sp)
                    }
                }
            }
            item {
                Text("ORBITAL MODES", color = TrevorMuted, fontSize = 10.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ModeChip("ANALYSE", TrevorMode.ANALYSE, selectedMode) { selectedMode = it }
                    ModeChip("RESEARCH", TrevorMode.RESEARCH, selectedMode) { selectedMode = it }
                    ModeChip("PROJECT", TrevorMode.PROJECT, selectedMode) { selectedMode = it }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ModeChip("RATIO SHIFTER", TrevorMode.RATIO_SHIFTER, selectedMode) { selectedMode = it }
                    ModeChip("NORMAL", TrevorMode.NORMAL, selectedMode) { selectedMode = it }
                }
            }
            item {
                when (selectedMode) {
                    TrevorMode.NORMAL -> AssistantWorkspace(response, input, busy, { input = it }, ::send, launchFilePicker)
                    TrevorMode.ANALYSE -> AssistantWorkspace(response, input, busy, { input = it }, ::send, launchFilePicker, "ANALYSE • Solve. Calculate. Understand.")
                    TrevorMode.RESEARCH -> AssistantWorkspace(response, input, busy, { input = it }, ::send, null, "RESEARCH • Discover. Compare. Verify.")
                    TrevorMode.PROJECT -> ProjectWorkspace()
                    TrevorMode.RATIO_SHIFTER -> RatioWorkspace()
                }
            }
            item {
                FrostPanel {
                    Text("SYSTEM STATUS", color = TrevorCyan, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    StatusRow("CORE", "READY")
                    StatusRow("AI", if (settings.aiEnabled && settings.geminiEnabled) "READY" else "DISABLED")
                    StatusRow("MODE", state.currentMode?.name ?: "NORMAL")
                    StatusRow("FILE", if (TrevorUiFileSelection.selected == null) "NONE" else "SELECTED")
                    StatusRow("OFFLINE", if (settings.offlineFirst) "ENABLED" else "DISABLED")
                }
            }
        }
    }
}

@Composable
private fun AssistantWorkspace(response: String, input: String, busy: Boolean, onInput: (String)->Unit, onSend: ()->Unit, onFile: (() -> Unit)?, title: String = "AI ASSISTANCE") {
    FrostPanel {
        Text(title, color = TrevorCyan, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        ResponseBox(response)
        Spacer(Modifier.height(6.dp))
        InputRow(input, busy, onInput, onSend, onFile, if (title.startsWith("RESEARCH")) "Search the web..." else "Ask TREVOR anything...")
    }
}

@Composable
private fun ProjectWorkspace() {
    FrostPanel {
        Text("PROJECTS • Plan. Organise. Achieve.", color = TrevorCyan, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text("Persistent project storage is staged for the intelligence phase.", color = TrevorMuted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        listOf("Mobile App Development" to 60, "Home Automation" to 30, "Learning Path" to 80, "Personal Finance" to 45).forEach {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Folder, null, tint = TrevorCyan)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) { Text(it.first); Text(it.second.toString() + "% • reference workspace", color = TrevorMuted, fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun RatioWorkspace() {
    var a by remember { mutableStateOf("3") }; var b by remember { mutableStateOf("5") }
    var c by remember { mutableStateOf("6") }; var d by remember { mutableStateOf("10") }
    fun shift() { val x=a.toDoubleOrNull(); val y=b.toDoubleOrNull(); val z=c.toDoubleOrNull(); if(x!=null&&y!=null&&z!=null&&x!=0.0){ val v=z*y/x; d=if(v%1.0==0.0)v.toInt().toString() else "%.4f".format(v) } }
    FrostPanel {
        Text("RATIO SHIFTER • Transform. Scale. Calculate.", color = TrevorCyan, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text("Works locally without internet.", color = TrevorMuted, fontSize = 10.sp)
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            RatioField(a){a=it}; Text(":"); RatioField(b){b=it}; Text("→"); RatioField(c){c=it}; Text(":"); RatioField(d){d=it}
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            OutlinedButton(onClick={val t=a;a=b;b=t;val u=c;c=d;d=u}, modifier=Modifier.weight(1f)){Icon(Icons.Filled.SwapHoriz,null);Text("Swap")}
            OutlinedButton(onClick={a="3";b="5";c="6";d="10"}, modifier=Modifier.weight(1f)){Icon(Icons.Filled.Refresh,null);Text("Reset")}
            Button(onClick=::shift, modifier=Modifier.weight(1f)){Text("Shift")}
        }
    }
}

@Composable private fun RatioField(v:String, change:(String)->Unit){ OutlinedTextField(v,change,Modifier.weight(1f),singleLine=true) }

@Composable
private fun InputRow(input:String,busy:Boolean,onInput:(String)->Unit,onSend:()->Unit,onFile:(()->Unit)?,placeholder:String){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        OutlinedTextField(input,onInput,Modifier.weight(1f),placeholder={Text(placeholder)})
        if(onFile!=null) IconButton(onClick=onFile){Icon(Icons.Filled.AttachFile,"Attach file",tint=TrevorCyan)}
        IconButton(onClick=onSend,enabled=!busy){Icon(Icons.Filled.Send,"Send",tint=TrevorCyan)}
    }
}

@Composable private fun ResponseBox(text:String){
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color(0x660A1C28))){Text(text,Modifier.padding(11.dp),fontSize=14.sp)}
}

@Composable private fun ModeChip(label:String,mode:TrevorMode,selected:TrevorMode,onClick:(TrevorMode)->Unit){
    val active=mode==selected
    Text(label,color=if(active)TrevorBg else TrevorCyan,fontSize=9.sp,
        modifier=Modifier.clip(RoundedCornerShape(18.dp)).background(if(active)TrevorCyan else Color(0x331B536A))
            .border(1.dp,TrevorCyan,RoundedCornerShape(18.dp)).clickable{onClick(mode)}.padding(horizontal=9.dp,vertical=8.dp))
}

@Composable private fun FrostPanel(content:@Composable ColumnScope.()->Unit){
    Card(Modifier.fillMaxWidth().border(1.dp,Color(0x5573DFFF),RoundedCornerShape(18.dp)),shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=TrevorPanel)){Column(Modifier.padding(13.dp),content=content)}
}

@Composable private fun StatusRow(label:String,value:String){
    Row(Modifier.fillMaxWidth().padding(vertical=2.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(label,color=TrevorMuted,fontSize=10.sp);Text(value,color=TrevorCyan,fontSize=10.sp)}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TrevorSettingsScreen(s:TrevorSettings,onChange:(TrevorSettings)->Unit,onBack:()->Unit,onApiKey:()->Unit){
    Scaffold(containerColor=Color.Transparent,topBar={TopAppBar(title={Text("SYSTEM SETTINGS")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Filled.ArrowBack,"Back")}})}){pad->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(13.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            item{Text("AI",color=TrevorCyan,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)}
            item{SettingSwitch("AI enabled",s.aiEnabled){onChange(s.copy(aiEnabled=it))}}
            item{SettingSwitch("Gemini enabled",s.geminiEnabled){onChange(s.copy(geminiEnabled=it))}}
            item{OutlinedButton(onClick=onApiKey,Modifier.fillMaxWidth()){Text("Gemini API Key • secure storage")}}
            item{SettingSwitch("Concise responses",s.conciseResponses){onChange(s.copy(conciseResponses=it))}}
            item{SettingSwitch("Technical detail",s.technicalDetail){onChange(s.copy(technicalDetail=it))}}
            item{HorizontalDivider()}
            item{Text("INTERFACE / ORB",color=TrevorCyan,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)}
            item{SettingSwitch("Orb enabled",s.orbEnabled){onChange(s.copy(orbEnabled=it))}}
            item{SettingSwitch("Animations",s.animations){onChange(s.copy(animations=it))}}
            item{SettingSwitch("Status indicators",s.showStatusIndicators){onChange(s.copy(showStatusIndicators=it))}}
            item{SettingSwitch("Quick actions",s.showQuickActions){onChange(s.copy(showQuickActions=it))}}
            item{HorizontalDivider()}
            item{Text("OFFLINE / SECURITY",color=TrevorCyan,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)}
            item{SettingSwitch("Offline-first",s.offlineFirst){onChange(s.copy(offlineFirst=it))}}
            item{SettingSwitch("Secure storage",s.secureStorage){onChange(s.copy(secureStorage=it))}}
            item{SettingSwitch("Local API-key encryption",s.localApiKeyEncryption){onChange(s.copy(localApiKeyEncryption=it))}}
            item{HorizontalDivider()}
            item{Text("DEVELOPER",color=TrevorCyan,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)}
            item{SettingSwitch("Developer mode",s.developerMode){onChange(s.copy(developerMode=it))}}
            item{SettingSwitch("Debug mode",s.debugMode){onChange(s.copy(debugMode=it))}}
            item{OutlinedButton(onClick={onChange(TrevorSettings());},Modifier.fillMaxWidth()){Text("Restore defaults")}}
        }
    }
}

@Composable private fun SettingSwitch(label:String,checked:Boolean,onChecked:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(checked,onCheckedChange=onChecked)}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TrevorApiKeyScreen(context:Context,onBack:()->Unit){
    var key by remember{mutableStateOf("")};var msg by remember{mutableStateOf("")}
    Scaffold(containerColor=Color.Transparent,topBar={TopAppBar(title={Text("GEMINI API KEY")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Filled.ArrowBack,"Back")}})}){pad->
        Column(Modifier.fillMaxSize().padding(pad).padding(13.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
            Text("Android Keystore-backed encrypted storage. The full key is never displayed.",color=TrevorMuted,fontSize=11.sp)
            OutlinedTextField(key,{key=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("API key")})
            Button(onClick={try{SecureApiKeyStore.save(context,key);key="";msg="Key saved securely."}catch(e:Exception){msg=e.message?:"Unable to save key."}},Modifier.fillMaxWidth(),enabled=key.isNotBlank()){Text("Save / Replace")}
            OutlinedButton(onClick={SecureApiKeyStore.clear(context);msg="Stored key removed."},Modifier.fillMaxWidth()){Text("Remove key")}
            if(msg.isNotBlank())Text(msg,color=TrevorCyan)
            StatusRow("KEY STATUS",if(SecureApiKeyStore.exists(context))"CONFIGURED" else "NOT CONFIGURED")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TrevorDeveloperScreen(context:Context,onBack:()->Unit){
    val state by TrevorStateStore.state.collectAsState()
    Scaffold(containerColor=Color.Transparent,topBar={TopAppBar(title={Text("DIAGNOSTICS")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Filled.ArrowBack,"Back")}})}){pad->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(13.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            item{FrostPanel{Text("BUILD",color=TrevorCyan,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold);StatusRow("VERSION",TrevorVersion.label(context));StatusRow("PACKAGE","com.trevor.assistant")}}
            item{FrostPanel{Text("CORE STATE",color=TrevorCyan,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold);StatusRow("MODE",state.currentMode?.name?:"NORMAL");StatusRow("ORB",state.orbState.name);StatusRow("REQUEST",state.requestState.name);StatusRow("AI",state.aiState.name);StatusRow("FILE",state.fileState.name);StatusRow("ERROR",state.lastError?:"NONE")}}
            item{FrostPanel{Text("SECURITY",color=TrevorCyan,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold);StatusRow("API KEY",if(SecureApiKeyStore.exists(context))"CONFIGURED" else "NOT CONFIGURED");StatusRow("SECRETS","NOT DISPLAYED")}}
        }
    }
}

private class TrevorOrbView(context:Context):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var ry=0.35f;private var rx=-0.2f;private var zoom=1f;private var lx=0f;private var ly=0f;private var ld=0f;private var running=true
    override fun onDetachedFromWindow(){running=false;super.onDetachedFromWindow()}
    override fun onAttachedToWindow(){super.onAttachedToWindow();running=true;postInvalidateOnAnimation()}
    override fun onDraw(c:Canvas){
        val cx=width/2f;val cy=height/2f;val r=min(width,height)*.30f*zoom
        p.shader=RadialGradient(cx-r*.3f,cy-r*.4f,r*1.2f,intArrayOf(Color.WHITE.toArgb(),TrevorCyan.toArgb(),Color(0xFF0A3C55.toInt()),TrevorBg.toArgb()),floatArrayOf(0f,.2f,.65f,1f),Shader.TileMode.CLAMP);c.drawCircle(cx,cy,r,p);p.shader=null
        p.style=Paint.Style.STROKE;p.strokeWidth=1.1f;p.color=Color(0x8873DFFF.toInt())
        for(i in -3..3){val lat=i/3f;val ry2=r*sqrt(max(0f,1-lat*lat));val y=cy+lat*r;c.drawOval(cx-ry2,y-r*.1f,cx+ry2,y+r*.1f,p)}
        for(i in 0 until 8){val a=(i*Math.PI/4+ry).toFloat();val rx2=r*kotlin.math.abs(kotlin.math.cos(a));c.drawOval(cx-rx2,cy-r,cx+rx2,cy+r,p)}
        p.color=Color(0x6673DFFF.toInt());c.drawCircle(cx,cy,r*1.23f,p);c.save();c.rotate(rx*25f,cx,cy);c.drawOval(cx-r*1.32f,cy-r*.16f,cx+r*1.32f,cy+r*.16f,p);c.restore();p.style=Paint.Style.FILL
        if(running){ry+=.006f;postInvalidateOnAnimation()}
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{
        when(e.actionMasked){MotionEvent.ACTION_DOWN->{lx=e.x;ly=e.y};MotionEvent.ACTION_POINTER_DOWN->{ld=dist(e)};MotionEvent.ACTION_MOVE->if(e.pointerCount>=2){val d=dist(e);if(ld>0)zoom=(zoom*d/ld).coerceIn(.65f,1.45f);ld=d}else{ry+=(e.x-lx)*.008f;rx+=(e.y-ly)*.008f;lx=e.x;ly=e.y}}
        invalidate();return true
    }
    private fun dist(e:MotionEvent):Float{val dx=e.getX(0)-e.getX(1);val dy=e.getY(0)-e.getY(1);return sqrt(dx*dx+dy*dy)}
}
