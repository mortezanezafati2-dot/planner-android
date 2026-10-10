package ir.bulletjournal.app

import android.Manifest
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Purple = Color(0xFF7357D9)
private val Mint = Color(0xFF4BAF9B)
private data class JournalTask(val text: String, val done: Boolean = false)
private data class JournalEvent(val title: String, val date: String = "")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(247,245,252)
        window.navigationBarColor = android.graphics.Color.rgb(247,245,252)
        setContent { BulletJournalApp(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BulletJournalApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("bullet_journal", Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    var page by remember { mutableStateOf("امروز") }
    var habitDraft by remember { mutableStateOf("") }
    var eventDraft by remember { mutableStateOf("") }
    var eventDate by remember { mutableStateOf("") }
    var review by remember { mutableStateOf(prefs.getString("review", "") ?: "") }
    val habits = remember { mutableStateListOf<String>().apply { try { val a=JSONArray(prefs.getString("habits","[]") ?: "[]"); for(i in 0 until a.length()) add(a.getString(i)) } catch (_:Exception) {} } }
    val doneHabits = remember { mutableStateListOf<String>().apply { try { val a=JSONArray(prefs.getString("done_habits","[]") ?: "[]"); for(i in 0 until a.length()) add(a.getString(i)) } catch (_:Exception) {} } }
    val events = remember { mutableStateListOf<JournalEvent>().apply { try { val a=JSONArray(prefs.getString("events","[]") ?: "[]"); for(i in 0 until a.length()) { val o=a.getJSONObject(i); add(JournalEvent(o.optString("title"),o.optString("date"))) } } catch (_:Exception) {} } }
    var note by remember { mutableStateOf(prefs.getString("note", "") ?: "") }
    var taskText by remember { mutableStateOf("") }
    val tasks = remember { mutableStateListOf<JournalTask>().apply {
        val raw = prefs.getString("tasks", "[]") ?: "[]"
        try { val a=JSONArray(raw); for(i in 0 until a.length()) { val o=a.getJSONObject(i); add(JournalTask(o.optString("text"),o.optBoolean("done"))) } } catch (_:Exception) {}
    } }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioStatus by remember { mutableStateOf("برای ثبت یادداشت صوتی، دکمهٔ ضبط را بزن.") }
    var showTheme by remember { mutableStateOf(false) }
    val dark = when(theme) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    val scheme = if(dark) darkColorScheme(primary=Color(0xFFB9A7FF), secondary=Color(0xFF8DD9C8), background=Color(0xFF15141B), surface=Color(0xFF211F29)) else lightColorScheme(primary=Purple, secondary=Mint, background=Color(0xFFF7F5FC), surface=Color.White)
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) try {
            val root=JSONObject().put("app","بولت ژورنال").put("note",note).put("review",review)
            root.put("habits",JSONArray().apply { habits.forEach { put(it) } }).put("doneHabits",JSONArray().apply { doneHabits.forEach { put(it) } }).put("events",JSONArray().apply { events.forEach { put(JSONObject().put("title",it.title).put("date",it.date)) } })
            val arr=JSONArray(); tasks.forEach { arr.put(JSONObject().put("text",it.text).put("done",it.done)) }; root.put("tasks",arr).put("theme",theme)
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(root.toString(2)) }
        } catch (_:Exception) {}
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) try {
            val raw=context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val obj=JSONObject(raw); note=obj.optString("note"); review=obj.optString("review"); habits.clear(); doneHabits.clear(); events.clear()
            val ha=obj.optJSONArray("habits") ?: JSONArray(); for(i in 0 until ha.length()) habits.add(ha.getString(i))
            val dh=obj.optJSONArray("doneHabits") ?: JSONArray(); for(i in 0 until dh.length()) doneHabits.add(dh.getString(i))
            val ea=obj.optJSONArray("events") ?: JSONArray(); for(i in 0 until ea.length()) { val e=ea.getJSONObject(i); events.add(JournalEvent(e.optString("title"),e.optString("date"))) }
            val arr=obj.optJSONArray("tasks") ?: JSONArray()
            for(i in 0 until arr.length()) { val t=arr.getJSONObject(i); tasks.add(JournalTask(t.optString("text"),t.optBoolean("done"))) }
            prefs.edit().putString("note",note).putString("review",review).putString("habits",JSONArray().apply { habits.forEach { put(it) } }.toString()).putString("done_habits",JSONArray().apply { doneHabits.forEach { put(it) } }.toString()).putString("events",JSONArray().apply { events.forEach { put(JSONObject().put("title",it.title).put("date",it.date)) } }.toString()).putString("tasks",JSONArray().apply { tasks.forEach { put(JSONObject().put("text",it.text).put("done",it.done)) } }.toString()).apply()
        } catch (_:Exception) {}
    }
    fun persist() {
        val arr=JSONArray(); tasks.forEach { arr.put(JSONObject().put("text",it.text).put("done",it.done)) }
        prefs.edit().putString("tasks",arr.toString()).putString("note",note).putString("theme",theme).putString("review",review).putString("habits",JSONArray().apply { habits.forEach { put(it) } }.toString()).putString("done_habits",JSONArray().apply { doneHabits.forEach { put(it) } }.toString()).putString("events",JSONArray().apply { events.forEach { put(JSONObject().put("title",it.title).put("date",it.date)) } }.toString()).apply()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) {
            try {
                val f=File(context.filesDir,"voice_note_${System.currentTimeMillis()}.m4a")
                val r=if(Build.VERSION.SDK_INT>=31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
                r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); r.setOutputFile(f.absolutePath); r.prepare(); r.start()
                recorder=r; recording=true; audioStatus="در حال ضبط… فایل در حافظهٔ برنامه ذخیره می‌شود."
            } catch(e:Exception) { audioStatus="ضبط شروع نشد؛ دوباره تلاش کن." }
        } else audioStatus="برای ضبط صدا، اجازهٔ میکروفون لازم است."
    }

    MaterialTheme(colorScheme=scheme) {
        Scaffold(
            containerColor=MaterialTheme.colorScheme.background,
            topBar={ CenterAlignedTopAppBar(
                title={ Column(horizontalAlignment=Alignment.CenterHorizontally) { Text("بولت ژورنال",fontWeight=FontWeight.Bold,fontSize=21.sp); Text("دفتر کوچکِ روزهای تو",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) } },
                actions={ IconButton(onClick={showTheme=true}) { Icon(Icons.Default.Palette,"پوسته") } },
                colors=TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor=MaterialTheme.colorScheme.background)
            ) },
            bottomBar={ NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
                listOf("امروز" to Icons.Default.Today,"کارها" to Icons.Default.CheckCircle,"یادداشت" to Icons.Default.EditNote,"عادت‌ها" to Icons.Default.Favorite,"تقویم" to Icons.Default.CalendarMonth,"مرور" to Icons.Default.AutoAwesome,"ابزارها" to Icons.Default.Settings).forEach { (label,icon) ->
                    NavigationBarItem(selected=page==label,onClick={page=label},icon={Icon(icon,null)},label={Text(label,fontSize=11.sp)})
                }
            } }
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=18.dp), verticalArrangement=Arrangement.spacedBy(14.dp), contentPadding=PaddingValues(top=12.dp,bottom=24.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                        Column {
                            Text(when(page){"امروز"->"سلام، امروزت چطور است؟";"کارها"->"فهرست کارها";"یادداشت"->"ذهن‌نوشته‌ها";"عادت‌ها"->"عادت‌های کوچک، تغییرهای بزرگ";"تقویم"->"رویدادها و قرارها";"مرور"->"مرور و بازتاب روز";else->"تنظیمات و پشتیبان"},fontSize=22.sp,fontWeight=FontWeight.Bold)
                            Text(SimpleDateFormat("EEEE، d MMMM", Locale("fa")).format(Date()),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp)
                        }
                        Box(Modifier.size(48.dp).background(Purple.copy(alpha=.12f),CircleShape),contentAlignment=Alignment.Center) { Icon(Icons.Default.AutoAwesome,null,tint=Purple,modifier=Modifier.size(25.dp)) }
                    }
                }
                if(page=="امروز") {
                    item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        StatCard("کارهای امروز", "${tasks.count{it.done}} از ${tasks.size}", Icons.Default.TaskAlt, Modifier.weight(1f))
                        StatCard("یادداشت‌ها", if(note.isBlank()) "شروع کن" else "ذخیره‌شده", Icons.Default.EditNote, Modifier.weight(1f))
                    } }
                    item { SectionCard("تمرکز امروز","سه کار مهمت را انتخاب کن") {
                        OutlinedTextField(taskText,{taskText=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("مثلاً: ۲۰ دقیقه مطالعه")},singleLine=true,shape=RoundedCornerShape(16.dp),trailingIcon={IconButton(onClick={ if(taskText.isNotBlank()){tasks.add(JournalTask(taskText.trim()));taskText="";persist()} }) { Icon(Icons.Default.Add,"افزودن",tint=Purple) } })
                        Spacer(Modifier.height(8.dp))
                        if(tasks.isEmpty()) Text("هنوز کاری ثبت نشده؛ اولین موردت را اضافه کن.",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp)
                        tasks.take(4).forEachIndexed { i,t -> TaskRow(t,{tasks[i]=t.copy(done=!t.done);persist()}) }
                        TextButton(onClick={page="کارها"}) { Text("دیدن همهٔ کارها") }
                    } }
                    item { SectionCard("یادداشت روزانه","هر چیزی که دوست داری ثبت کن") {
                        OutlinedTextField(note,{note=it;persist()},modifier=Modifier.fillMaxWidth().heightIn(min=120.dp),placeholder={Text("امروز چه چیزی برایت مهم است؟")},shape=RoundedCornerShape(16.dp))
                    } }
                } else if(page=="کارها") {
                    item { SectionCard("کار تازه","") {
                        OutlinedTextField(taskText,{taskText=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("عنوان کار")},singleLine=true,shape=RoundedCornerShape(16.dp),trailingIcon={IconButton(onClick={if(taskText.isNotBlank()){tasks.add(JournalTask(taskText.trim()));taskText="";persist()}}){Icon(Icons.Default.Add,"افزودن")} })
                    } }
                    itemsIndexed(tasks) { i,t -> SectionCard("","") { TaskRow(t,{tasks[i]=t.copy(done=!t.done);persist()}); Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick={tasks.removeAt(i);persist()}){Text("حذف",color=MaterialTheme.colorScheme.error)}} } }
                } else if(page=="یادداشت") {
                    item { SectionCard("یادداشت روزانه","متن به‌صورت خودکار ذخیره می‌شود") {
                        OutlinedTextField(note,{note=it;persist()},modifier=Modifier.fillMaxWidth().heightIn(min=230.dp),placeholder={Text("ایده‌ها، اتفاق‌ها و فکرهایت را اینجا بنویس…")},shape=RoundedCornerShape(18.dp))
                        Spacer(Modifier.height(12.dp))
                        Button(onClick={if(recording){try{recorder?.stop();recorder?.release();recorder=null;recording=false;audioStatus="ضبط صوتی ذخیره شد."}catch(_:Exception){audioStatus="ضبط پایان نیافت."}}else micPermission.launch(Manifest.permission.RECORD_AUDIO)},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp)) { Icon(if(recording) Icons.Default.Stop else Icons.Default.Mic,null); Spacer(Modifier.width(8.dp)); Text(if(recording)"پایان ضبط" else "ضبط یادداشت صوتی") }
                        Text(audioStatus,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                } else if(page=="عادت‌ها") {
                    item { SectionCard("عادت تازه","کارهایی که می‌خواهی به‌طور منظم انجام بدهی") {
                        Row(verticalAlignment=Alignment.CenterVertically) { OutlinedTextField(habitDraft,{habitDraft=it},modifier=Modifier.weight(1f),singleLine=true,placeholder={Text("مثلاً مطالعهٔ روزانه")},shape=RoundedCornerShape(14.dp)); IconButton(onClick={ if(habitDraft.isNotBlank() && habitDraft.trim() !in habits){habits.add(habitDraft.trim());habitDraft="";persist()} }) { Icon(Icons.Default.Add,"افزودن") } }
                    } }
                    item { SectionCard("امروز","برای ثبت عادت، دایره را علامت بزن") { if(habits.isEmpty()) Text("هنوز عادتی اضافه نکرده‌ای.") else habits.forEach { h -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Checkbox(h in doneHabits,{ checked -> if(checked) doneHabits.add(h) else doneHabits.remove(h); persist() }); Text(h,Modifier.weight(1f)); IconButton(onClick={habits.remove(h);doneHabits.remove(h);persist()}) { Icon(Icons.Default.Delete,"حذف",tint=MaterialTheme.colorScheme.error) } } } } }
                } else if(page=="تقویم") {
                    item { SectionCard("ثبت رویداد","عنوان و تاریخ را وارد کن") { OutlinedTextField(eventDraft,{eventDraft=it},modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("مثلاً جلسه یا قرار")},shape=RoundedCornerShape(14.dp)); OutlinedTextField(eventDate,{eventDate=it},modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("تاریخ (مثلاً ۱۴۰۵/۰۷/۱۸)")},shape=RoundedCornerShape(14.dp)); Button(onClick={if(eventDraft.isNotBlank()){events.add(JournalEvent(eventDraft.trim(),eventDate.trim()));eventDraft="";eventDate="";persist()}},modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Add,null); Spacer(Modifier.width(8.dp)); Text("ثبت رویداد") } } }
                    item { SectionCard("رویدادهای ثبت‌شده","") { if(events.isEmpty()) Text("هنوز رویدادی ثبت نشده است.") else events.forEach { e -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(e.title,fontWeight=FontWeight.SemiBold); if(e.date.isNotBlank()) Text(e.date,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }; IconButton(onClick={events.remove(e);persist()}) { Icon(Icons.Default.Delete,"حذف",tint=MaterialTheme.colorScheme.error) } } } } }
                } else if(page=="مرور") {
                    item { SectionCard("بازتاب روز","چه چیزی خوب پیش رفت؟ چه چیزی را فردا بهتر می‌کنی؟") { OutlinedTextField(review,{review=it;persist()},modifier=Modifier.fillMaxWidth().heightIn(min=220.dp),placeholder={Text("امروز بابت چه چیزی خوشحالی؟ چه چیزی یاد گرفتی؟")},shape=RoundedCornerShape(16.dp)); Text("انجام‌شده‌ها: ${tasks.count{it.done}} از ${tasks.size} کار",fontWeight=FontWeight.SemiBold); Text("عادت‌های امروز: ${doneHabits.size} از ${habits.size}",fontWeight=FontWeight.SemiBold) } }
                } else {
                    item { SectionCard("ظاهر برنامه","پوستهٔ مورد علاقه‌ات را انتخاب کن") {
                        listOf("system" to "پیروی از سیستم","light" to "روشن","dark" to "تیره").forEach { (value,label) -> Row(Modifier.fillMaxWidth().clickable{theme=value;persist()}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(theme==value,{theme=value;persist()}); Text(label)} }
                    } }
                    item { SectionCard("پشتیبان‌گیری","اطلاعاتت را در یک فایل JSON ذخیره یا بازیابی کن") {
                        Button(onClick={backupLauncher.launch("bullet-journal-backup.json")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp)){Icon(Icons.Default.SaveAlt,null);Spacer(Modifier.width(8.dp));Text("ذخیرهٔ نسخهٔ پشتیبان")}
                        OutlinedButton(onClick={restoreLauncher.launch(arrayOf("application/json","text/*"))},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp)){Icon(Icons.Default.Restore,null);Spacer(Modifier.width(8.dp));Text("بازیابی از فایل")}
                        Text("پشتیبان فعلی شامل کارها، یادداشت روزانه و پوسته است.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    item { SectionCard("دربارهٔ برنامه","") { Text("بولت ژورنال",fontSize=18.sp,fontWeight=FontWeight.Bold); Text("نسخهٔ ۱٫۰ • رابط بومی اندروید",color=MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
            }
        }
        if(showTheme) AlertDialog(onDismissRequest={showTheme=false},title={Text("انتخاب پوسته")},text={Column { listOf("system" to "پیروی از سیستم","light" to "روشن","dark" to "تیره").forEach{(v,l)->TextButton(onClick={theme=v;persist();showTheme=false}){Text(l)} } }},confirmButton={TextButton(onClick={showTheme=false}){Text("بستن")}})
    }
}

@Composable private fun StatCard(title:String,value:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier=Modifier) {
    Card(modifier,shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),elevation=CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(icon,null,tint=Purple,modifier=Modifier.size(24.dp)); Text(title,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant); Text(value,fontSize=18.sp,fontWeight=FontWeight.Bold)
        }
    }
}
@Composable private fun SectionCard(title:String,subtitle:String,content:@Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),elevation=CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(title.isNotBlank()) Text(title,fontSize=17.sp,fontWeight=FontWeight.Bold)
            if(subtitle.isNotBlank()) Text(subtitle,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
@Composable private fun TaskRow(task:JournalTask,onToggle:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable{onToggle()}.padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically) {
        Checkbox(task.done,{onToggle()},colors=CheckboxDefaults.colors(checkedColor=Mint))
        Text(task.text,modifier=Modifier.weight(1f),fontSize=14.sp,color=if(task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        if(task.done) Icon(Icons.Default.CheckCircle,null,tint=Mint,modifier=Modifier.size(18.dp))
    }
}
