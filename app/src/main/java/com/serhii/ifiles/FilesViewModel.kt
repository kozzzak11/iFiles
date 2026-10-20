package com.serhii.ifiles

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

data class ScreenState(
    val tab: String="Обзор", val stack: List<Entry> = emptyList(), val entries: List<Entry> = emptyList(),
    val loading: Boolean=false, val error: String?=null, val warning: String?=null, val query: String="", val grid: Boolean=false,
    val sort: String="Имя",val descending: Boolean=false,val selected: Set<String> = emptySet(),
    val selecting: Boolean=false, val favorites: Set<String> = emptySet(),val theme: String="Системная",
    val jobs: List<WorkInfo> = emptyList(), val notice: String? = null
) { val current: Entry? get()=stack.lastOrNull() }

class FilesViewModel(app: Application): AndroidViewModel(app) {
    val storage=Storage(app)
    private val mutable=MutableStateFlow(ScreenState(grid=storage.prefs.getBoolean("grid",false),sort=storage.prefs.getString("sort","Имя")!!,theme=storage.prefs.getString("theme","Системная")!!))
    val state=mutable.asStateFlow();private var loadJob: Job?=null
    private var previousJobs=emptyMap<java.util.UUID,WorkInfo.State>()
    init {
        runCatching {
            val refs=JSONArray(storage.prefs.getString("stack","[]"));val stack=(0 until refs.length()).map { storage.stat(refs.getString(it)) }.takeWhile { it.directory }
            mutable.value=mutable.value.copy(stack=stack,tab=storage.prefs.getString("tab","Обзор")!!)
        }
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(Operations.QUEUE).collect { jobs ->
                val completed=jobs.filter { it.state.isFinished && previousJobs[it.id]?.isFinished == false }
                previousJobs=jobs.associate { it.id to it.state }
                mutable.value=mutable.value.copy(jobs=jobs.sortedByDescending { it.id.toString() })
                if(completed.isNotEmpty()) {
                    val failure=completed.firstOrNull { it.state==WorkInfo.State.FAILED }
                    mutable.value=mutable.value.copy(notice=failure?.outputData?.getString("error") ?: "Готово")
                    refresh()
                }
            }
        }
        refresh()
    }
    fun change(f: (ScreenState)->ScreenState) { mutable.value=f(mutable.value) }
    fun notice(text: String?) = change { it.copy(notice=text) }
    fun error(e: Throwable) = notice(e.message ?: "Ошибка")
    fun remember() {
        storage.prefs.edit().putString("stack",JSONArray(state.value.stack.map{it.ref}).toString()).putString("tab",state.value.tab).apply()
    }
    fun tab(tab: String) { change { it.copy(tab=tab,stack=emptyList(),entries=emptyList(),query="",selected=emptySet(),selecting=false,sort=if(tab=="Недавние")"Открытые" else if(it.sort=="Открытые")"Имя" else it.sort) };remember();refresh() }
    fun open(e: Entry) { change { it.copy(stack=it.stack+e,entries=emptyList(),query="",selected=emptySet(),selecting=false) };remember();refresh() }
    fun back() {
        val a=state.value
        if(a.selecting)change { it.copy(selecting=false,selected=emptySet()) }
        else if(a.query.isNotEmpty()){change { it.copy(query="") }; refresh()}
        else {change { it.copy(stack=it.stack.dropLast(1),entries=emptyList(),selected=emptySet()) };remember();refresh()}
    }
    fun query(q: String) { change { it.copy(query=q) };refresh(true) }
    fun grid() {change { it.copy(grid=!it.grid) };storage.prefs.edit().putBoolean("grid",state.value.grid).apply()}
    fun sort(value: String) {change { it.copy(sort=value) };storage.prefs.edit().putString("sort",value).apply();refresh()}
    fun theme(value: String) {change{it.copy(theme=value)};storage.prefs.edit().putString("theme",value).apply()}
    fun select(ref: String) { change { it.copy(selecting=true,selected=if(ref in it.selected)it.selected-ref else it.selected+ref) } }
    fun pin(ref: String) {storage.pin(ref);refresh()}
    fun refresh(debounce: Boolean=false) {
        loadJob?.cancel()
        loadJob=viewModelScope.launch {
            if(debounce)delay(250)
            val a=state.value;change { it.copy(loading=true,error=null,warning=null,favorites=storage.strings("favorites")) }
            try {
                var skipped=0
                val result=withContext(Dispatchers.IO) {
                    val base=when {
                        a.current!=null -> storage.list(a.current!!.ref)
                        a.tab=="Недавние" -> storage.recents().mapNotNull { runCatching { storage.stat(it) }.getOrNull() }
                        a.tab=="Избранное" -> storage.strings("favorites").mapNotNull { runCatching { storage.stat(it) }.getOrNull() }
                        else -> emptyList()
                    }
                    val entries=if(a.query.isBlank())base else {
                        val found=ArrayList<Entry>(); val queue=java.util.ArrayDeque<Entry>()
                        queue.addAll(base);val visited=hashSetOf<String>()
                        while(queue.isNotEmpty()) {
                            ensureActive();val item=queue.removeFirst()
                            if(!visited.add(item.ref))continue
                            if(item.name.contains(a.query,true))found+=item
                            if(item.directory && a.current!=null) {
                                try {queue.addAll(storage.list(item.ref))}catch(e: SecurityException){skipped++}catch(e: IllegalArgumentException){skipped++}catch(e: IllegalStateException){skipped++}
                            }
                            if(found.size>=10000)kotlin.error("Найдено более 10 000 элементов. Уточните поиск.")
                        }
                        found
                    }
                    val comparator=when(a.sort) {
                        "Размер" -> compareBy<Entry> { it.size }
                        "Дата" -> compareBy<Entry> { it.modified }
                        "Тип" -> compareBy<Entry> { it.mime }.thenBy { it.name.lowercase() }
                        else -> compareBy<Entry> { it.name.lowercase() }
                    }
                    val order=if(a.descending)comparator.reversed() else comparator
                    if(a.tab=="Недавние" && a.sort=="Открытые" && a.current==null && a.query.isBlank()) entries
                    else entries.sortedWith(compareByDescending<Entry>{it.directory}.then(order))
                }
                change { it.copy(entries=result,loading=false,warning=if(skipped>0)"Поиск: нет доступа к $skipped папкам. Результаты неполные." else null) }
            } catch(e: CancellationException) {throw e} catch(e: Exception) {change { it.copy(loading=false,error=e.message ?: "Нет доступа",entries=emptyList()) }}
        }
    }
    fun operation(kind: String, refs: List<String> = state.value.selected.toList(), parent: String=state.value.current?.ref ?: "",name: String="",sourceParent: String=state.value.current?.ref ?: "") {
        Operations.enqueue(getApplication(),kind,refs,parent,name,sourceParent)
        change { it.copy(selected=emptySet(),selecting=false,notice="Операция добавлена") }
    }
    suspend fun roots(): List<Entry> = withContext(Dispatchers.IO) {
        val result=mutableListOf(storage.stat(storage.local).copy(name="iFiles"))
        if(Environment.isExternalStorageManager()) {
            result+=storage.stat(storage.phone).copy(name="На этом телефоне")
            File(Environment.getExternalStorageDirectory(),"Download").mkdirs()
            result+=storage.stat(storage.downloads).copy(name="Загрузки")
        }
        storage.strings("roots").forEach { ref -> result+=runCatching { storage.stat(ref) }.getOrElse { Entry(ref,"Недоступная папка · выберите повторно",true) } }
        result
    }
    fun cancel(id: java.util.UUID) {WorkManager.getInstance(getApplication()).cancelWorkById(id)}
}
