package com.trevor.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import kotlinx.coroutines.*

object TrevorFloatingOverlay {
    private var wm:WindowManager?=null
    private var root:LinearLayout?=null
    private var expanded=false
    private var listener:((TrevorLiveState)->Unit)?=null
    private var mode=TrevorMode.NORMAL

    fun show(context:Context){
        if(!Settings.canDrawOverlays(context)||root!=null)return
        wm=context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d=context.resources.displayMetrics.density
        val box=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding((10*d).toInt(),(7*d).toInt(),(10*d).toInt(),(7*d).toInt());setBackgroundColor(Color.argb(235,7,18,30))}
        val orb=TextView(context).apply{text="◉";textSize=30f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setBackgroundColor(Color.rgb(35,145,190));setOnClickListener{toggle(context)}}
        box.addView(orb,LinearLayout.LayoutParams((58*d).toInt(),(58*d).toInt()));root=box
        val p=WindowManager.LayoutParams((310*d).toInt(),WindowManager.LayoutParams.WRAP_CONTENT,if(Build.VERSION.SDK_INT>=26)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.END;x=(12*d).toInt();y=(90*d).toInt()}
        wm?.addView(box,p)
    }

    private fun toggle(context:Context){
        expanded=!expanded;val r=root?:return
        if(!expanded){while(r.childCount>1)r.removeViewAt(1);return}
        val orb=r.getChildAt(0);r.removeAllViews();r.addView(orb)
        val status=TextView(context).apply{setTextColor(Color.LTGRAY);text="TREVOR • Live idle"}
        val output=TextView(context).apply{setTextColor(Color.WHITE);text="Ready.";setPadding(0,8,0,8)}
        val modes=Spinner(context);modes.adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,TrevorMode.entries.map{it.name});modes.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){mode=TrevorMode.entries[pos]}}
        val input=EditText(context).apply{hint="Ask TREVOR…";setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);setSingleLine(true)}
        val send=Button(context).apply{text="SEND"};val live=Button(context).apply{text="LIVE"};val mic=Button(context).apply{text="MIC"}
        val row=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;addView(send,LinearLayout.LayoutParams(0,52,1f));addView(live,LinearLayout.LayoutParams(0,52,1f));addView(mic,LinearLayout.LayoutParams(0,52,1f))}
        r.addView(status);r.addView(output,LinearLayout.LayoutParams(-1,110));r.addView(modes);r.addView(input);r.addView(row)
        send.setOnClickListener{val q=input.text.toString().trim();if(q.isBlank())return@setOnClickListener;input.setText("");output.text="TREVOR • thinking…";CoroutineScope(Dispatchers.Main).launch{val x=TrevorCore.process(context,q,true,true,true,true,true,mode,null);output.text=when(x){is TrevorCoreResult.Answer->x.text;is TrevorCoreResult.Error->"ERROR • "+x.message}}}
        live.setOnClickListener{if(TrevorLiveSession.connect(context))status.text="TREVOR • Gemini Live connected"}
        mic.setOnClickListener{if(TrevorLiveSession.startMicrophone(context))status.text="TREVOR • listening…"}
        listener?.let{TrevorLiveSession.removeListener(it)}
        val l:(TrevorLiveState)->Unit={s->output.post{val t=listOf(s.transcript.takeIf{it.isNotBlank()}?.let{"You: $it"},s.response.takeIf{it.isNotBlank()}?.let{"TREVOR: $it"},s.error?.let{"ERROR: $it"}).filterNotNull().joinToString("\n\n");if(t.isNotBlank())output.text=t;status.text=when{ s.listening->"TREVOR • listening";s.speaking->"TREVOR • speaking";s.connected->"TREVOR • Live connected";else->"TREVOR • Live idle"}}}
        listener=l;TrevorLiveSession.addListener(l)
    }

    fun hide(){listener?.let{TrevorLiveSession.removeListener(it)};listener=null;root?.let{runCatching{wm?.removeView(it)}};root=null;wm=null;expanded=false}
}
