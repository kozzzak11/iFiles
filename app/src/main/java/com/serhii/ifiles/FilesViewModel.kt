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
    val showHidden: Boolean=false, val loading: Boolean=false, val error: String?=null, val warning: String?=null, val query: String="", val grid: Boolean=false,
    val sort: String="Имя",val descending: Boolean=false,val selected: Set<String> = emptySet(),
    val selecting: Boolean=false, val favorites: Set<String> = emptySet(),val theme: String="Системная",
    val jobs: List<WorkInfo> = emptyList(), val notice: String? = null, val locationsVersion: Long = 0,
    /** Text matches from the content index (files whose name did not already match). */
    val hits: List<SearchHit> = emptyList(), val indexJobs: List<WorkInfo> = emptyList(), val indexedRoots: Int = 0
) { val current: Entry? get()=stack.lastOrNull()
    val globalSearch: Boolean get()=current==null && tab=="Обзор" && query.isNotBlank()
    val indexing: WorkInfo? get()=indexJobs.firstOrNull { it.state==WorkInfo.State.RUNNING } ?: indexJobs.firstOrNull { !it.state.isFinished }
}

class FilesViewModel(app: Application): AndroidViewModel(app) {
    val storage=Storage(app)
    val index=ContentIndex.get(app)
    private var previousIndex=emptyMap<java.util.UUID,WorkInfo.State>()
    private val mutable=MutableStateFlow(ScreenState(showHidden=storage.prefs.getBoolean("showHidden",false),grid=storage.prefs.getBoolean("grid",false),sort=storage.prefs.getString("sort","Имя")!!,theme=storage.prefs.getString("theme","Системная")!!))
    val state=mutable.asStateFlow();private var loadJob: Job?=null
    private var previousJobs=emptyMap<java.util.UUID,WorkInfo.State>()
    private val storageManager=app.getSystemService(android.os.storage.StorageManager::class.java)
    private val volumeCallback=object: android.os.storage.StorageManager.StorageVolumeCallback() {
        override fun onStateChanged(volume: android.os.storage.StorageVolume) {
            change { it.copy(locationsVersion=it.locationsVersion+1) }
            refresh()
        }
    }
    override fun onCleared() {
        storageManager.unregisterStorageVolumeCallback(volumeCallback)
        super.onCleared()
    }
    init {
        storageManager.registerStorageVolumeCallback(app.mainExecutor,volumeCallback)
        mutable.value=mutable.value.copy(tab=storage.prefs.getString("tab","Обзор")!!)
        // Restoring the last folder touches storage/providers: do it off the main thread.
        viewModelScope.launch {
            val stack=withContext(Dispatchers.IO) { runCatching {
                val refs=JSONArray(storage.prefs.getString("stack","[]"))
                (0 until refs.length()).map { storage.stat(refs.getString(it)) }.takeWhile { it.directory }
            }.getOrDefault(emptyList()) }
            if(mutable.value.stack.isEmpty() && stack.isNotEmpty())mutable.value=mutable.value.copy(stack=stack)
            refresh()
        }
        viewModelScope.launch(Dispatchers.IO) { runCatching { FileEngine(storage).expireTrash() } }
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(Operations.QUEUE).collect { jobs ->
                val completed=jobs.filter { it.state.isFinished && previousJobs[it.id]?.isFinished == false }
                previousJobs=jobs.associate { it.id to it.state }
                mutable.value=mutable.value.copy(jobs=jobs.sortedByDescending { it.id.toString() })
                if(completed.isNotEmpty()) {
                    val failure=completed.firstOrNull { it.state==WorkInfo.State.FAILED || it.outputData.getString("error")!=null }
                    mutable.value=mutable.value.copy(notice=failure?.outputData?.getString("error") ?: "Готово")
                    refresh()
                }
            }
        }
    }
    init {
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(Indexing.QUEUE).collect { jobs ->
                val completed=jobs.filter { it.state.isFinished && previousIndex[it.id]?.isFinished == false }
                previousIndex=jobs.associate { it.id to it.state }
                change { it.copy(indexJobs=jobs) }
                completed.lastOrNull()?.let { job ->
                    if(job.state==WorkInfo.State.SUCCEEDED) {
                        val d=job.outputData
                        notice("Индекс обновлён: ${d.getInt("seen",0)} файлов"+(if(d.getInt("unavailable",0)>0)", недоступно мест: ${d.getInt("unavailable",0)}" else ""))
                    } else if(job.state==WorkInfo.State.CANCELLED)notice("Индексация остановлена. Уже найденное сохранено.")
                    refresh()
                }
            }
        }
    }
    fun indexHere() {
        val ref=state.value.current?.ref ?: return
        Indexing.enqueue(getApplication(),listOf(ref)); notice("Индексация «${state.value.current?.name}» запущена")
    }
    fun indexRoots(roots: List<String>) { Indexing.enqueue(getApplication(),roots); notice("Индексация запущена") }
    fun cancelIndex() { Indexing.cancel(getApplication()) }
    fun setAutoIndex(enabled: Boolean) { Indexing.setAuto(getApplication(),enabled) }
    fun forgetIndex(root: String) { viewModelScope.launch(Dispatchers.IO) { Indexing.forget(getApplication(),root); withContext(Dispatchers.Main) { refresh() } } }
    suspend fun indexInfo(): List<ContentIndex.RootInfo> = withContext(Dispatchers.IO) { runCatching { index.rootInfo() }.getOrDefault(emptyList()) }
    /** A hit whose file is gone (deleted in the app or elsewhere) is dropped from the index right away. */
    fun staleHit(ref: String) { viewModelScope.launch(Dispatchers.IO) { runCatching { index.remove(ref) } }; change { it.copy(hits=it.hits.filterNot { h -> h.ref==ref },entries=it.entries.filterNot { e -> e.ref==ref }) }; notice("Файл больше не существует") }
    /** Opens a search hit; drops it from the index only if the file is really gone (not on an unplugged drive). */
    fun openHit(ref: String, open: (Entry)->Unit) {
        viewModelScope.launch {
            val r=withContext(Dispatchers.IO) { runCatching { storage.stat(ref) } }
            r.onSuccess(open).onFailure { e -> if(withContext(Dispatchers.IO) { missing(ref) }) staleHit(ref) else notice("Файл недоступен: ${e.message}") }
        }
    }
    private val scroll=HashMap<String,Pair<Int,Int>>()
    fun scrollOf(key: String): Pair<Int,Int> = scroll[key] ?: (0 to 0)
    fun saveScroll(key: String, index: Int, offset: Int) { scroll[key]=index to offset }
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
    fun hidden() { change { it.copy(showHidden=!it.showHidden) };storage.prefs.edit().putBoolean("showHidden",state.value.showHidden).apply();refresh() }
    fun grid() {change { it.copy(grid=!it.grid) };storage.prefs.edit().putBoolean("grid",state.value.grid).apply()}
    fun sort(value: String) {change { it.copy(sort=value) };storage.prefs.edit().putString("sort",value).apply();refresh()}
    fun theme(value: String) {change{it.copy(theme=value)};storage.prefs.edit().putString("theme",value).apply()}
    fun select(ref: String) { change { it.copy(selecting=true,selected=if(ref in it.selected)it.selected-ref else it.selected+ref) } }
    fun pin(ref: String) {runCatching{storage.pin(ref)}.onFailure(::error);refresh()}
    fun clearRecents() {storage.clearRecents();refresh()}
    /** True only when the file is really gone (not merely on an unplugged USB drive / revoked folder). */
    private fun missing(ref: String): Boolean = runCatching {
        if(storage.isFile(ref)) {
            val f=storage.file(ref)
            val volume=generateSequence(f.parentFile){it.parentFile}.firstOrNull { it.parentFile?.path=="/storage" }
            !f.exists() && (volume==null || volume.exists())
        } else storage.resolver.query(storage.uri(ref),arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null).use { it!=null && !it.moveToFirst() }
    }.getOrElse { it is java.io.FileNotFoundException }
    fun refresh(debounce: Boolean=false) {
        loadJob?.cancel()
        loadJob=viewModelScope.launch {
            if(debounce)delay(250)
            val a=state.value;change { it.copy(loading=true,error=null,warning=null,favorites=storage.strings("favorites")) }
            try {
                var skipped=0; var hits=emptyList<SearchHit>(); var indexedRoots=0
                val result=withContext(Dispatchers.IO) {
                    val base=when {
                        a.current!=null -> storage.list(a.current!!.ref)
                        a.tab=="Недавние" -> {
                            val found=HashMap<String,Entry>()
                            // Files that were deleted or moved drop out of Recent (unmounted USB/SAF refs are kept).
                            storage.pruneRecents { ref -> runCatching { storage.stat(ref) }.onSuccess { found[ref]=it }.isSuccess || !missing(ref) }
                                .mapNotNull { found[it] }
                        }
                        a.tab=="Избранное" -> storage.strings("favorites").mapNotNull { runCatching { storage.stat(it) }.getOrNull() }
                        else -> emptyList()
                    }
                    val visible=base.filter { a.showHidden || !PreviewFiles.hidden(it.name) }
                    // Content index: instant (no walk), scoped to the open folder, otherwise everything indexed.
                    val indexHits=if(a.query.isBlank())emptyList() else runCatching { index.search(a.query,a.current?.ref,300) }.getOrDefault(emptyList())
                        .filter { h -> val gone=storage.isFile(h.ref) && !storage.file(h.ref).exists(); if(gone)runCatching { index.remove(h.ref) }; !gone }
                    indexedRoots=runCatching { index.roots().size }.getOrDefault(0)
                    val entries=if(a.query.isBlank())visible else if(a.globalSearch) {
                        val words=ContentIndex.words(a.query)
                        indexHits.filter { h -> words.all { h.name.contains(it,true) } }.map { it.entry(storage) }
                    } else {
                        val found=ArrayList<Entry>(); val queue=java.util.ArrayDeque<Entry>()
                        queue.addAll(visible);val visited=hashSetOf<String>()
                        while(queue.isNotEmpty()) {
                            ensureActive();val item=queue.removeFirst()
                            if(!visited.add(item.ref))continue
                            if(item.name.contains(a.query,true))found+=item
                            if(item.directory && a.current!=null) {
                                try {queue.addAll(storage.list(item.ref).filter { a.showHidden || !PreviewFiles.hidden(it.name) })}catch(e: SecurityException){skipped++}catch(e: IllegalArgumentException){skipped++}catch(e: IllegalStateException){skipped++}
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
                    val sorted=if(a.tab=="Недавние" && a.sort=="Открытые" && a.current==null && a.query.isBlank()) entries
                    else entries.sortedWith(compareByDescending<Entry>{it.directory}.then(order))
                    val shown=sorted.map { it.ref }.toHashSet()
                    hits=indexHits.filter { it.inContent && it.ref !in shown }
                    sorted
                }
                change { it.copy(entries=result,hits=hits,indexedRoots=indexedRoots,loading=false,warning=if(skipped>0)"Поиск: нет доступа к $skipped папкам. Результаты неполные." else null) }
            } catch(e: CancellationException) {throw e} catch(e: Exception) {change { it.copy(loading=false,error=e.message ?: "Нет доступа",entries=emptyList(),hits=emptyList()) }}
        }
    }
    fun operation(kind: String, refs: List<String> = state.value.selected.toList(), parent: String=state.value.current?.ref ?: "",name: String="",sourceParent: String=state.value.current?.ref ?: "") {
        Operations.enqueue(getApplication(),kind,refs,parent,name,sourceParent)
        change { it.copy(selected=emptySet(),selecting=false,notice="Операция добавлена") }
    }
    suspend fun roots(): List<Entry> = withContext(Dispatchers.IO) { DeviceLocations(storage).roots() }
    /** Cancels only this operation; the jobs queued behind it keep running (see [Operations.cancel]). */
    fun cancel(id: java.util.UUID) {Operations.cancel(getApplication(),id);notice("Отмена…")}
}
