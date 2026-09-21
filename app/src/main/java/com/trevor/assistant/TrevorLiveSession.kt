package com.trevor.assistant

import android.content.Context
import android.media.*
import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TrevorLiveState(val connected:Boolean=false,val listening:Boolean=false,val speaking:Boolean=false,val transcript:String="",val response:String="",val error:String?=null)

object TrevorLiveSession {
    private const val MODEL="gemini-3.1-flash-live-preview"
    private const val URL="wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val lock=Any()
    private var socket:WebSocket?=null
    private var recorder:AudioRecord?=null
    private var player:AudioTrack?=null
    private var recordingJob:Job?=null
    private var state=TrevorLiveState()
    private val listeners=mutableSetOf<(TrevorLiveState)->Unit>()

    fun addListener(l:(TrevorLiveState)->Unit){synchronized(lock){listeners+=l;l(state)}}
    fun removeListener(l:(TrevorLiveState)->Unit){synchronized(lock){listeners-=l}}
    private fun publish(f:(TrevorLiveState)->TrevorLiveState){val s=synchronized(lock){state=f(state);state};synchronized(lock){listeners.toList()}.forEach{it(s)}}

    fun connect(context:Context):Boolean{
        synchronized(lock){if(socket!=null&&state.connected)return true}
        val key=SecureApiKeyStore.load(context.applicationContext)
        if(key.isNullOrBlank()){publish{it.copy(error="Gemini API key is not configured.")};return false}
        val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS).build()
        val request=Request.Builder().url("$URL?key="+java.net.URLEncoder.encode(key,"UTF-8")).build()
        socket=client.newWebSocket(request,object:WebSocketListener(){
            override fun onOpen(ws:WebSocket,response:Response){
                publish{it.copy(connected=true,error=null)}
                ws.send(JSONObject().put("setup",JSONObject().put("model","models/$MODEL")
                    .put("generation_config",JSONObject().put("response_modalities",org.json.JSONArray().put("AUDIO")))
                    .put("system_instruction",JSONObject().put("parts",org.json.JSONArray().put(JSONObject().put("text",TrevorIdentity.IMMUTABLE_DIRECTIVE))))).toString())
            }
            override fun onMessage(ws:WebSocket,text:String){handleMessage(text)}
            override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){publish{it.copy(connected=false,listening=false,speaking=false,error=t.message?:"Gemini Live connection failed.")}}
            override fun onClosed(ws:WebSocket,code:Int,reason:String){publish{it.copy(connected=false,listening=false,speaking=false)}}
        })
        return true
    }

    fun sendText(context:Context,text:String){
        if(text.isBlank())return
        if(!state.connected&&!connect(context))return
        socket?.send(JSONObject().put("client_content",JSONObject()
            .put("turns",org.json.JSONArray().put(JSONObject().put("role","user").put("parts",org.json.JSONArray().put(JSONObject().put("text",text)))))
            .put("turn_complete",true)).toString())
        publish{it.copy(transcript=text,response="",error=null)}
    }

    fun startMicrophone(context:Context):Boolean{
        if(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            publish{it.copy(error="Microphone permission is required. Open TREVOR and grant microphone access first.")};return false
        }
        if(!state.connected&&!connect(context))return false
        if(recordingJob?.isActive==true)return true
        val rate=16000
        val min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        if(min<=0){publish{it.copy(error="This device does not expose a compatible microphone input.")};return false}
        recorder=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,min*2)
        recorder?.startRecording();publish{it.copy(listening=true,error=null)}
        recordingJob=scope.launch{
            val buffer=ByteArray(min.coerceAtLeast(2048))
            while(isActive&&state.listening){
                val n=recorder?.read(buffer,0,buffer.size)?:-1
                if(n>0)socket?.send(JSONObject().put("realtime_input",JSONObject().put("media_chunks",org.json.JSONArray().put(
                    JSONObject().put("mime_type","audio/pcm;rate=16000").put("data",Base64.encodeToString(buffer.copyOf(n),Base64.NO_WRAP))))).toString())
            }
        }
        return true
    }

    fun stopMicrophone(){publish{it.copy(listening=false)};recordingJob?.cancel();recordingJob=null;runCatching{recorder?.stop()};recorder?.release();recorder=null}
    fun disconnect(){stopMicrophone();socket?.close(1000,"TREVOR Live stopped");socket=null;player?.release();player=null;publish{TrevorLiveState()}}

    private fun handleMessage(text:String){
        runCatching{
            val root=JSONObject(text)
            val server=root.optJSONObject("server_content")?:root.optJSONObject("serverContent")?:return
            val turn=server.optJSONObject("model_turn")?:server.optJSONObject("modelTurn")
            val parts=turn?.optJSONArray("parts")
            var response=""
            if(parts!=null)for(i in 0 until parts.length()){
                val p=parts.optJSONObject(i)?:continue
                response+=p.optString("text","")
                val inline=p.optJSONObject("inline_data")?:p.optJSONObject("inlineData")
                if(inline!=null){val d=inline.optString("data","");if(d.isNotBlank())playPcm(Base64.decode(d,Base64.DEFAULT))}
            }
            val input=server.optJSONObject("input_transcription")?:server.optJSONObject("inputTranscription")
            val output=server.optJSONObject("output_transcription")?:server.optJSONObject("outputTranscription")
            val inputText=input?.optString("text","")?:""
            val outputText=output?.optString("text","")?:""
            publish{it.copy(transcript=if(inputText.isNotBlank())inputText else it.transcript,response=if(outputText.isNotBlank())outputText else if(response.isNotBlank())it.response+response else it.response,speaking=response.isNotBlank()||outputText.isNotBlank())}
            if(server.optBoolean("turn_complete",false)||server.optBoolean("turnComplete",false))publish{it.copy(speaking=false)}
        }.onFailure{e->publish{it.copy(error="Live message error: "+(e.message?:"unknown error"))}}
    }

    private fun playPcm(bytes:ByteArray){
        if(bytes.isEmpty())return
        if(player==null){
            val min=AudioTrack.getMinBufferSize(24000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)
            player=AudioTrack(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),min.coerceAtLeast(4096),AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE)
            player?.play()
        }
        player?.write(bytes,0,bytes.size)
    }
}
