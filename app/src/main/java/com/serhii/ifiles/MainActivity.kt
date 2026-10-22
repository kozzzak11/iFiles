package com.serhii.ifiles

import kotlinx.coroutines.CancellationException

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.io.File

class MainActivity: ComponentActivity() {
    private var incoming by mutableStateOf<List<String>>(emptyList())
    private var incomingView by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge();receive(intent)
        setContent { FilesApp(incoming,incomingView,clearIncoming={incoming=emptyList()}) }
    }
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);setIntent(intent);receive(intent)}
    private fun receive(intent: Intent) {
        val refs=mutableListOf<Uri>()
        when(intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let {refs+=it}
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let{refs+=it}
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let{refs+=it}
        }
        intent.clipData?.let { clip ->for(i in 0 until clip.itemCount)clip.getItemAt(i).uri?.let { refs+=it } }
        incoming=refs.filter{it.scheme=="content"}.distinct().map{it.toString()};incomingView=intent.action==Intent.ACTION_VIEW
        if(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION!=0)refs.forEach { u ->
            runCatching { contentResolver.takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }
}
fun humanSize(size: Long): String {
    if(size<0)return "—"
    var n=size.toDouble();val units=listOf("Б","КБ","МБ","ГБ","ТБ");var i=0
    while(n>=1024 && i<units.lastIndex){n/=1024;i++}
    return if(i==0)"$size Б" else "%.1f %s".format(n,units[i])
}
fun dateText(time: Long)=if(time<=0)"" else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(time))
private val Blue=Color(0xFF007AFF)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable fun FilesApp(incoming: List<String>,incomingView: Boolean,clearIncoming:()->Unit,vm: FilesViewModel=viewModel()) {
    val a by vm.state.collectAsStateWithLifecycle();val s=vm.storage;val context=androidx.compose.ui.platform.LocalContext.current
    val scope=rememberCoroutineScope();val snack=remember{SnackbarHostState()}
    val dark=when(a.theme){"Тёмная"->true;"Светлая"->false;else->isSystemInDarkTheme()}
    SideEffect {
        val window=(context as? android.app.Activity)?.window
        if(window!=null)androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply {
            isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
        }
    }
    val colors=if(dark)darkColorScheme(primary=Color(0xFF0A84FF),background=Color(0xFF000000),surface=Color(0xFF1C1C1E),surfaceContainer=Color(0xFF2C2C2E)) else lightColorScheme(primary=Blue,background=Color(0xFFF2F2F7),surface=Color.White,surfaceContainer=Color(0xFFE5E5EA))
    var preview by remember{mutableStateOf<Entry?>(null)};var contextEntry by remember{mutableStateOf<Entry?>(null)}
    var nameDialog by remember{mutableStateOf<String?>(null)};var nameValue by remember{mutableStateOf("")};var renameRef by remember{mutableStateOf<Entry?>(null)}
    var confirmTrash by remember{mutableStateOf<List<String>?>(null)};var info by remember{mutableStateOf<Entry?>(null)}
    var toolbar by remember{mutableStateOf(false)}
    var showOperations by remember{mutableStateOf(false)}
    var clip by remember{mutableStateOf(runCatching{JSONArray(s.prefs.getString("clipboard","[]")).let{j->(0 until j.length()).map{j.getString(it)}}}.getOrDefault(emptyList()))}
    var cut by remember{mutableStateOf(s.prefs.getBoolean("cut",false))}
    var roots by remember{mutableStateOf<List<Entry>>(emptyList())}
    var trash by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var destinationAction by remember{mutableStateOf<Pair<String,List<String>>?>(null)}
    val treePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){u ->
        if(u!=null)runCatching{s.addTree(u);vm.refresh()}.onFailure(vm::error)
    }
    val volumePicker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri=result.data?.data
        if(result.resultCode==android.app.Activity.RESULT_OK && uri!=null) {
            runCatching { s.addTree(uri) }.onSuccess {
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { s.stat(uri.toString()) } }
                        .onSuccess { vm.open(it) }.onFailure(vm::error)
                }
            }.onFailure(vm::error)
            vm.refresh()
        }
    }
    fun openLocation(entry: Entry) {
        if(entry.requiresGrant && entry.volumeId!=null) {
            val intent=DeviceLocations(s).accessIntent(entry.volumeId)
            if(intent==null)vm.notice("Накопитель отключён")
            else runCatching { volumePicker.launch(intent) }.onFailure(vm::error)
        } else vm.open(entry)
    }
    val allFiles=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){vm.refresh()}
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    fun launchAllFiles() {runCatching { allFiles.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:${context.packageName}"))) }.onFailure(vm::error)}
    fun parent(e: Entry): String? = s.parentOf(e.ref)
    fun saveParent(e: Entry) {s.rememberParent(e.ref)}
    fun clipboard(refs: List<String>,move: Boolean) {clip=refs;cut=move;s.prefs.edit().putString("clipboard",JSONArray(refs).toString()).putBoolean("cut",move).apply();vm.change{it.copy(selected=emptySet(),selecting=false)};vm.notice("Откройте папку назначения и нажмите «Вставить»")}
    fun external(e: Entry,share: Boolean) {scope.launch {
        runCatching {
            val u=withContext(Dispatchers.IO){s.externalUri(e.ref){ensureActive()}}
            val intent=if(share)Intent(Intent.ACTION_SEND).setType(e.mime).putExtra(Intent.EXTRA_STREAM,u) else Intent(Intent.ACTION_VIEW).setDataAndType(u,e.mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.clipData=ClipData.newRawUri(e.name,u)
            context.startActivity(Intent.createChooser(intent,if(share)"Поделиться" else "Открыть с помощью"))
        }.onFailure {if(it is CancellationException)throw it;vm.notice("Не удалось открыть файл: ${it.message}")}
    }}
    fun shareMany(refs: List<String>) {scope.launch {
        runCatching {
            val uris=ArrayList(withContext(Dispatchers.IO){refs.map{s.externalUri(it){ensureActive()}}})
            val intent=Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData=ClipData.newRawUri("Файлы",uris.first()).apply {uris.drop(1).forEach{addItem(ClipData.Item(it))}}
            context.startActivity(Intent.createChooser(intent,"Поделиться"))
        }.onFailure(vm::error)
    }}
    LaunchedEffect(incoming) {if(incomingView && incoming.size==1) {runCatching{withContext(Dispatchers.IO){s.stat(incoming.single())}}.onSuccess{preview=it}.onFailure(vm::error);clearIncoming()} }
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner) {val observer=LifecycleEventObserver { _,event ->if(event==Lifecycle.Event.ON_RESUME)vm.refresh()};owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}}
    LaunchedEffect(a.loading,a.tab,a.locationsVersion) {
        if(!a.loading) {
            try { roots=vm.roots() } catch(e: CancellationException) {throw e} catch(e: Exception) {vm.error(e)}
            trash=withContext(Dispatchers.IO) { FileEngine(s).trashRecords() }
        }
    }
    LaunchedEffect(a.notice) {a.notice?.let{snack.showSnackbar(it);vm.notice(null)}}
    BackHandler(a.stack.isNotEmpty() || a.selecting || a.tab !in listOf("Обзор","Недавние") || a.query.isNotEmpty()) {
        if(a.stack.isEmpty() && !a.selecting && a.query.isEmpty())vm.tab("Обзор") else vm.back()
    }
    MaterialTheme(colorScheme=colors) {
        Scaffold(containerColor=colors.background,snackbarHost={SnackbarHost(snack)},bottomBar={
            Surface(tonalElevation=0.dp,shadowElevation=0.5.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().height(60.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
                    listOf("Недавние" to Icons.Outlined.Schedule,"Обзор" to Icons.Outlined.Folder).forEach { (tab,icon) ->
                        val active=if(tab=="Обзор")a.tab!="Недавние" else a.tab==tab
                        Column(Modifier.weight(1f).fillMaxHeight().clickable { vm.tab(tab) },horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                            Icon(icon,tab,tint=if(active)colors.primary else colors.onSurfaceVariant);Text(tab,fontSize=10.sp,color=if(active)colors.primary else colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(a.stack.isNotEmpty() || a.tab !in listOf("Обзор","Недавние"))TextButton(onClick={if(a.stack.isEmpty())vm.tab("Обзор") else vm.back()},contentPadding=PaddingValues(0.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBackIos,null,Modifier.size(18.dp));Text(a.stack.dropLast(1).lastOrNull()?.name ?: "Обзор",maxLines=1,modifier=Modifier.widthIn(max=150.dp),overflow=TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.weight(1f))
                    if(a.selecting)TextButton(onClick={vm.change{it.copy(selecting=false,selected=emptySet())}}){Text("Готово")}
                    else Box {
                        IconButton(onClick={toolbar=true}){Icon(Icons.Outlined.MoreHoriz,"Действия",tint=colors.primary)}
                        DropdownMenu(toolbar,{toolbar=false}) {
                            if(a.current!=null)DropdownMenuItem(text={Text("Новая папка")},onClick={toolbar=false;nameValue="Новая папка";nameDialog="mkdir"},leadingIcon={Icon(Icons.Outlined.CreateNewFolder,null)})
                            if(a.entries.isNotEmpty())DropdownMenuItem(text={Text("Выбрать")},onClick={toolbar=false;vm.change{it.copy(selecting=true)}})
                            DropdownMenuItem(text={Text(if(a.grid)"Список" else "Значки")},onClick={toolbar=false;vm.grid()})
                            HorizontalDivider()
                            (if(a.tab=="Недавние" && a.current==null)listOf("Открытые","Имя","Размер","Дата","Тип") else listOf("Имя","Размер","Дата","Тип")).forEach{item->DropdownMenuItem(text={Text("${if(a.sort==item)"✓ " else ""}$item")},onClick={toolbar=false;vm.sort(item)})}
                            DropdownMenuItem(text={Text(if(a.descending)"По возрастанию" else "По убыванию")},onClick={toolbar=false;vm.change{it.copy(descending=!it.descending)};vm.refresh()})
                            HorizontalDivider()
                            listOf("Системная","Светлая","Тёмная").forEach{t->DropdownMenuItem(text={Text("${if(t==a.theme)"✓ " else ""}$t тема")},onClick={toolbar=false;vm.theme(t)})}
                            DropdownMenuItem(text={Text(if(a.showHidden)"Скрыть служебные файлы" else "Показать служебные файлы")},onClick={toolbar=false;vm.hidden()})
                            DropdownMenuItem(text={Text("Добавить папку")},onClick={toolbar=false;treePicker.launch(null)})
                            if(!Environment.isExternalStorageManager())DropdownMenuItem(text={Text("Разрешить доступ к памяти")},onClick={toolbar=false;launchAllFiles()})
                            DropdownMenuItem(text={Text("История операций")},onClick={toolbar=false;showOperations=true})
                            DropdownMenuItem(text={Text("Обновить")},onClick={toolbar=false;vm.refresh()})
                        }
                    }
                }
                Text(if(a.selecting)"Выбрано: ${a.selected.size}" else a.current?.name ?: a.tab,Modifier.padding(horizontal=20.dp,vertical=2.dp),fontSize=34.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(a.tab!="Недавно удалённые" && (a.current!=null || a.tab!="Обзор")) {
                    OutlinedTextField(a.query,{vm.query(it)},Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp),placeholder={Text(if(a.current!=null)"Поиск в папке и подпапках" else "Поиск")},leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(a.query.isNotEmpty())IconButton(onClick={vm.query("")}){Icon(Icons.Outlined.Cancel,"Очистить поиск")}},singleLine=true,shape=RoundedCornerShape(12.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Color.Transparent,focusedBorderColor=colors.primary,unfocusedContainerColor=colors.surfaceContainer,focusedContainerColor=colors.surface))
                }
                a.warning?.let {Text(it,Modifier.padding(horizontal=20.dp,vertical=4.dp),fontSize=12.sp,color=colors.error)}
                val jobs=a.jobs.filter{!it.state.isFinished}
                if(jobs.isNotEmpty()) {
                    val j=jobs.firstOrNull{it.state==androidx.work.WorkInfo.State.RUNNING} ?: jobs.first()
                    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Text(j.progress.getString("file") ?: "Файловая операция…",Modifier.weight(1f).padding(8.dp),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp)
                        TextButton(onClick={vm.cancel(j.id)}){Text("Отменить")}
                    }
                }
                if(incoming.isNotEmpty() && !incomingView)Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){
                    Text("Получено файлов: ${incoming.size}",Modifier.weight(1f));TextButton(onClick={vm.operation("copy",incoming,parent=a.current?.ref ?: s.local);clearIncoming()}){Text("Сохранить здесь")};IconButton(onClick=clearIncoming){Icon(Icons.Outlined.Close,"Закрыть")}
                }
                if(clip.isNotEmpty() && a.current!=null)Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("${if(cut)"Перемещение" else "Копирование"}: ${clip.size}",Modifier.weight(1f),fontSize=12.sp)
                    TextButton(onClick={vm.operation(if(cut)"move" else "copy",clip);clip=emptyList();s.prefs.edit().remove("clipboard").apply()}){Text("Вставить")}
                    IconButton(onClick={clip=emptyList();s.prefs.edit().remove("clipboard").apply()}){Icon(Icons.Outlined.Close,"Отменить вставку")}
                }
                destinationAction?.let { action ->Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("Выберите папку восстановления",Modifier.weight(1f),fontSize=12.sp)
                    TextButton(enabled=a.current!=null,onClick={vm.operation(action.first,action.second);destinationAction=null}){Text("Здесь")}
                    IconButton(onClick={destinationAction=null}){Icon(Icons.Outlined.Close,"Отменить")}
                }}
                if(a.selecting) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp)) {
                        TextButton(onClick={vm.change{it.copy(selected=a.entries.map{e->e.ref}.toSet())}}){Text("Все")}
                        TextButton(enabled=a.selected.isNotEmpty(),onClick={clipboard(a.selected.toList(),false)}){Text("Копировать")}
                        TextButton(enabled=a.selected.isNotEmpty(),onClick={clipboard(a.selected.toList(),true)}){Text("Переместить")}
                        TextButton(enabled=a.selected.isNotEmpty(),onClick={confirmTrash=a.selected.toList()}){Text("Удалить")}
                        TextButton(enabled=a.selected.isNotEmpty() && a.current!=null,onClick={nameValue="Архив.zip";nameDialog="zip"}){Text("Сжать")}
                        TextButton(enabled=a.selected.isNotEmpty(),onClick={shareMany(a.selected.toList())}){Text("Поделиться")}
                    }
                }
                AnimatedContent(targetState=a,contentKey={it.current?.ref ?: it.tab},transitionSpec={fadeIn() togetherWith fadeOut()},modifier=Modifier.weight(1f),label="Folder") { folder ->
                    when {
                        folder.tab=="Недавно удалённые" && folder.current==null -> TrashList(trash,onRestore={id->vm.operation("restore",listOf(id))},onChoose={id->destinationAction="restore" to listOf(id);vm.tab("Обзор")},onPurge={ids->confirmTrash=ids.map { "trash:" + it }})
                        folder.current==null && folder.tab=="Обзор" -> BrowseHome(roots,folder.favorites,s,onOpen={openLocation(it)},onFavorites={vm.tab("Избранное")},onTrash={vm.tab("Недавно удалённые")},onConnect={treePicker.launch(null)},onAllFiles=::launchAllFiles,onUnlink={ref->s.prefs.edit().putStringSet("roots",s.strings("roots")-ref).apply();runCatching{s.resolver.releasePersistableUriPermission(s.uri(ref),Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)};vm.refresh()})
                        else -> Box(Modifier.fillMaxSize()) {
                            if(folder.error!=null)Column(Modifier.align(Alignment.Center).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Outlined.Lock,null,Modifier.size(40.dp));Text(folder.error!!,textAlign=TextAlign.Center);TextButton(onClick={treePicker.launch(null)}){Text("Выбрать папку")};TextButton(onClick={vm.refresh()}){Text("Повторить")}}
                            else if(folder.entries.isEmpty() && !folder.loading)Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Outlined.FolderOpen,null,Modifier.size(64.dp),tint=colors.onSurfaceVariant);Text(if(folder.query.isNotBlank())"Ничего не найдено" else "Здесь пока пусто",color=colors.onSurfaceVariant)}
                            else {
                                val tap:(Entry)->Unit={e->saveParent(e);if(folder.selecting)vm.select(e.ref) else if(e.directory)vm.open(e) else {s.recent(e.ref);preview=e}}
                                val long:(Entry)->Unit={saveParent(it);contextEntry=it}
                                if(folder.grid)LazyVerticalGrid(GridCells.Adaptive(105.dp),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                                    items(folder.entries,key={it.ref}){e->FileCell(e,s,true,e.ref in folder.selected,folder.selecting,e.ref in folder.favorites,{tap(e)},{long(e)})}
                                } else LazyColumn(contentPadding=PaddingValues(horizontal=20.dp,vertical=8.dp)) {
                                    items(folder.entries,key={it.ref}){e->FileCell(e,s,false,e.ref in folder.selected,folder.selecting,e.ref in folder.favorites,{tap(e)},{long(e)});HorizontalDivider(color=colors.outlineVariant.copy(alpha=.4f))}
                                }
                            }
                            if(folder.loading)LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                        }
                    }
                }
                if(a.current!=null && !a.loading)Text("${a.entries.size} элементов",Modifier.fillMaxWidth().padding(8.dp),textAlign=TextAlign.Center,fontSize=11.sp,color=colors.onSurfaceVariant)
            }
        }
        preview?.let{e->Preview(e,s,{preview=null},{external(it,false)},{external(it,true)},if(a.current!=null)a.entries else emptyList())}
        contextEntry?.let { e ->ModalBottomSheet(onDismissRequest={contextEntry=null}) {
            Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically){FileGlyph(e,s,Modifier.size(40.dp));Text(e.name,Modifier.padding(start=12.dp),fontWeight=FontWeight.SemiBold,maxLines=2)}
            val actions=mutableListOf<Pair<String,()->Unit>>()
            actions+=(if(e.ref in a.favorites)"Убрать из избранного" else "В избранное") to {vm.pin(e.ref)}
            actions+="Выбрать" to {vm.select(e.ref)}
            actions+="Копировать" to {clipboard(listOf(e.ref),false)}
            actions+="Переместить" to {clipboard(listOf(e.ref),true)}
            if(parent(e)!=null)actions+="Переименовать" to {renameRef=e;nameValue=e.name;nameDialog="rename"}
            if(!e.directory) {actions+="Быстрый просмотр" to {s.recent(e.ref);preview=e};actions+="Поделиться" to {external(e,true)};actions+="Открыть в приложении" to {external(e,false)}}
            if(a.current!=null) {
                actions+="Сжать в ZIP" to {vm.change{it.copy(selected=setOf(e.ref))};nameValue="${e.name.substringBeforeLast('.')}.zip";nameDialog="zip"}
                if(e.name.endsWith(".zip",true) && !e.directory)actions+="Распаковать" to {vm.operation("unzip",listOf(e.ref))}
            }
            actions+="Свойства" to {info=e}
            actions+="В корзину" to {confirmTrash=listOf(e.ref)}
            LazyColumn(Modifier.heightIn(max=500.dp)) {items(actions){action->TextButton(onClick={contextEntry=null;action.second()},modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp),contentPadding=PaddingValues(12.dp)){Text(action.first,Modifier.fillMaxWidth(),color=if(action.first=="В корзину")colors.error else colors.primary)}}}
            Spacer(Modifier.height(16.dp))
        }}
        nameDialog?.let { kind ->
            AlertDialog(onDismissRequest={nameDialog=null},title={Text(when(kind){"mkdir"->"Новая папка";"rename"->"Переименовать";else->"Создать ZIP"})},text={OutlinedTextField(nameValue,{nameValue=it},singleLine=true,label={Text("Имя")})},confirmButton={TextButton(onClick={
                runCatching{Names.check(nameValue)}.onSuccess {
                    when(kind){"rename"->renameRef?.let{vm.operation(kind,listOf(it.ref),parent=parent(it) ?: "",name=nameValue)};else->vm.operation(kind,name=nameValue)}
                    nameDialog=null
                }.onFailure(vm::error)
            }){Text("Сохранить")}},dismissButton={TextButton(onClick={nameDialog=null}){Text("Отмена")}})
        }
        confirmTrash?.let { refs ->
            val purge=refs.firstOrNull()?.startsWith("trash:")==true
            AlertDialog(onDismissRequest={confirmTrash=null},title={Text(if(purge)"Удалить навсегда?" else "Переместить в корзину?")},text={Text(if(purge)"Будет удалено навсегда: " + refs.size + ". Восстановить эти элементы будет невозможно." else "Элементов: ${refs.size}. Сначала будет сохранена и проверена копия. Корзина хранится в данных iFiles; очистка данных или удаление приложения уничтожит её.")},confirmButton={TextButton(onClick={
                if(purge)vm.operation("purge",refs.map{it.removePrefix("trash:")}) else {
                    // Per-item parent references survive selections from Recent and Favorites.
                    refs.groupBy{s.parentOf(it) ?: s.local}.forEach{(p,group)->vm.operation("trash",group,sourceParent=p)}
                }
                confirmTrash=null
            }){Text(if(purge)"Удалить навсегда" else "В корзину",color=colors.error)}},dismissButton={TextButton(onClick={confirmTrash=null}){Text("Отмена")}})
        }
        info?.let{e->Properties(e,s){info=null}}
        if(showOperations)ModalBottomSheet(onDismissRequest={showOperations=false}) {
            Text("История операций",Modifier.padding(20.dp),fontSize=24.sp,fontWeight=FontWeight.Bold)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=500.dp),contentPadding=PaddingValues(20.dp)) {
                if(a.jobs.isEmpty())item{Text("Операций пока нет")}
                items(a.jobs,key={it.id}){job->Column(Modifier.fillMaxWidth().padding(vertical=8.dp)) {
                    val result=when(job.state){androidx.work.WorkInfo.State.SUCCEEDED->"Завершено";androidx.work.WorkInfo.State.FAILED->"Ошибка";androidx.work.WorkInfo.State.CANCELLED->"Отменено: проверьте назначение и корзину";androidx.work.WorkInfo.State.RUNNING->"Выполняется";else->"В очереди"}
                    Text(result,fontWeight=FontWeight.SemiBold)
                    Text(job.outputData.getString("error") ?: job.progress.getString("file") ?: job.id.toString(),fontSize=12.sp)
                    if(!job.state.isFinished)TextButton(onClick={vm.cancel(job.id)}){Text("Отменить")}
                };HorizontalDivider()}
            }
        }
        LaunchedEffect(Unit){if(android.os.Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)notifications.launch(Manifest.permission.POST_NOTIFICATIONS)}
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun FileCell(e: Entry,s: Storage,grid: Boolean,selected: Boolean,selecting: Boolean,favorite: Boolean,onClick:()->Unit,onLong:()->Unit) {
    val bg=if(selected)MaterialTheme.colorScheme.primary.copy(alpha=.12f) else Color.Transparent
    val modifier=Modifier.fillMaxWidth().background(bg,RoundedCornerShape(10.dp)).combinedClickable(onClick=onClick,onLongClick=onLong)
    if(grid)Column(modifier.padding(6.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Box {FileGlyph(e,s,Modifier.size(68.dp));if(selecting)Icon(if(selected)Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,"Выделение",Modifier.align(Alignment.BottomEnd).size(22.dp),tint=MaterialTheme.colorScheme.primary)}
        Text(e.name,Modifier.padding(top=6.dp),fontSize=13.sp,maxLines=2,textAlign=TextAlign.Center,overflow=TextOverflow.Ellipsis)
        Text(if(favorite)"★" else if(e.directory)"Папка" else humanSize(e.size),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    } else Row(modifier.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
        if(selecting)Icon(if(selected)Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,"Выделение",Modifier.padding(end=10.dp).size(22.dp),tint=MaterialTheme.colorScheme.primary)
        FileGlyph(e,s,Modifier.size(40.dp));Column(Modifier.weight(1f).padding(start=12.dp)) {
            Text(e.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=16.sp)
            Text(listOf(dateText(e.modified),if(e.directory)"Папка" else humanSize(e.size)).filter{it.isNotEmpty()}.joinToString(" · "),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(favorite)Icon(Icons.Outlined.Star,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.primary)
        if(e.directory)Icon(Icons.Outlined.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun BrowseHome(roots: List<Entry>,favorites: Set<String>,s: Storage,onOpen:(Entry)->Unit,onFavorites:()->Unit,onTrash:()->Unit,onConnect:()->Unit,onAllFiles:()->Unit,onUnlink:(String)->Unit) {
    var space by remember{mutableStateOf<Pair<Long,Long>?>(null)}
    LaunchedEffect(Unit){space=withContext(Dispatchers.IO){runCatching{StatFs(Environment.getExternalStorageDirectory().path).let{it.availableBytes to it.totalBytes}}.getOrNull()}}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {Text("Места",fontSize=21.sp,fontWeight=FontWeight.Bold)}
        item {Surface(shape=RoundedCornerShape(12.dp)){Column {
            roots.forEachIndexed { i,e ->Row(Modifier.fillMaxWidth().clickable{onOpen(e)}.padding(start=16.dp,end=8.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically){
                Icon(if(e.volumeId!=null)Icons.Outlined.Usb else if(e.name=="На этом телефоне")Icons.Outlined.Smartphone else Icons.Outlined.Folder,null,tint=MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                    Text(e.name,maxLines=2)
                    if(e.requiresGrant)Text("Нажмите, чтобы разрешить доступ",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(e.ref in s.strings("roots"))IconButton(onClick={onUnlink(e.ref)}){Icon(Icons.Outlined.LinkOff,"Отключить папку")}
                Icon(Icons.Outlined.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
            };if(i<roots.lastIndex)HorizontalDivider(Modifier.padding(start=52.dp))}
            HorizontalDivider(Modifier.padding(start=52.dp))
            TextButton(onClick=onConnect,Modifier.fillMaxWidth().padding(8.dp)){Icon(Icons.Outlined.Add,null);Text("Добавить папку",Modifier.padding(start=8.dp))}
        }}}
        if(!Environment.isExternalStorageManager())item {Surface(shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(16.dp)){
            Text("Общая память телефона",fontWeight=FontWeight.SemiBold)
            Text("Для разделов «На этом телефоне» и «Загрузки» разрешите доступ к пользовательским файлам. Можно работать и только с выбранными папками.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))
            TextButton(onClick=onAllFiles){Text("Разрешить доступ")}
        }}}
        item {Text("Разделы",fontSize=21.sp,fontWeight=FontWeight.Bold)}
        item {Surface(shape=RoundedCornerShape(12.dp)){Column {
            Row(Modifier.fillMaxWidth().clickable(onClick=onFavorites).padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Star,null,tint=MaterialTheme.colorScheme.primary);Text("Избранное",Modifier.weight(1f).padding(start=12.dp));Text("${favorites.size}",color=MaterialTheme.colorScheme.onSurfaceVariant);Icon(Icons.Outlined.ChevronRight,null)}
            HorizontalDivider(Modifier.padding(start=52.dp))
            Row(Modifier.fillMaxWidth().clickable(onClick=onTrash).padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.DeleteOutline,null,tint=MaterialTheme.colorScheme.primary);Text("Недавно удалённые",Modifier.weight(1f).padding(start=12.dp));Icon(Icons.Outlined.ChevronRight,null)}
        }}}
        space?.let{(free,total)->item {Surface(shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(16.dp)){
            Text("Память телефона",fontWeight=FontWeight.SemiBold);LinearProgressIndicator(progress={((total-free).toFloat()/total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(vertical=12.dp));Text("Свободно ${humanSize(free)} из ${humanSize(total)}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}}}
    }
}
@Composable private fun TrashList(records: List<JSONObject>,onRestore:(String)->Unit,onChoose:(String)->Unit,onPurge:(List<String>)->Unit) {
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val ids=records.map { it.getString("id") }.toSet()
    LaunchedEffect(ids) { selected=selected.intersect(ids);if(ids.isEmpty())selecting=false }
    fun toggle(id: String) { selected=if(id in selected)selected-id else selected+id }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp)) {
            if(selecting) {
                TextButton(onClick={selected=ids}) { Text("Выбрать всё") }
                TextButton(enabled=selected.isNotEmpty(),onClick={onPurge(selected.toList())}) {
                    Text("Удалить выбранные навсегда",color=MaterialTheme.colorScheme.error)
                }
                TextButton(onClick={selecting=false;selected=emptySet()}) { Text("Готово") }
            } else {
                TextButton(enabled=ids.isNotEmpty(),onClick={selecting=true}) { Text("Выбрать") }
                TextButton(enabled=ids.isNotEmpty(),onClick={onPurge(ids.toList())}) {
                    Text("Удалить всё",color=MaterialTheme.colorScheme.error)
                }
            }
        }
        if(selecting)Text("Выбрано: " + selected.size,Modifier.padding(horizontal=20.dp),fontSize=12.sp)
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {Text("Элементы можно восстановить или удалить навсегда. Очистка корзины не затрагивает остальные файлы.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(records.isEmpty())item {Text("Корзина пуста",Modifier.padding(vertical=24.dp))}
            items(records,key={it.getString("id")}) { r ->
                val id=r.getString("id")
                Surface(shape=RoundedCornerShape(12.dp),color=if(id in selected)MaterialTheme.colorScheme.primary.copy(alpha=.12f) else MaterialTheme.colorScheme.surface) {
                    Column(Modifier.clickable(enabled=selecting) { toggle(id) }.padding(16.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            if(selecting)Checkbox(checked=id in selected,onCheckedChange={toggle(id)})
                            Text(r.getString("name"),Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                        }
                        Text(dateText(r.optLong("time")),fontSize=12.sp)
                        val state=r.optString("state")
                        Text(when(state) {
                            "copying" -> "Незавершённая копия. Исходник не удалялся."
                            "verified" -> "Копия проверена. Удаление исходника могло прерваться."
                            "source_retained" -> "Исходник сохранён полностью или частично. Резервная копия доступна."
                            "restored" -> "Восстановлено. Резервная копия сохранена."
                            else -> "В корзине"
                        },fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(!selecting)Row(Modifier.horizontalScroll(rememberScrollState())) {
                            if(state!="copying") {
                                TextButton(onClick={onRestore(id)}) {Text("Восстановить")}
                                TextButton(onClick={onChoose(id)}) {Text("В другую папку")}
                            }
                            TextButton(onClick={onPurge(listOf(id))}) {Text("Удалить",color=MaterialTheme.colorScheme.error)}
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun Properties(e: Entry,s: Storage,close:()->Unit) {
    var bytes by remember(e.ref){mutableLongStateOf(e.size)};var count by remember(e.ref){mutableIntStateOf(0)};var error by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(e.ref){if(e.directory)try {val result=withContext(Dispatchers.IO){
        var total=0L;var n=0;val queue=java.util.ArrayDeque<Entry>();queue.add(e);val seen=hashSetOf<String>()
        while(queue.isNotEmpty()){kotlinx.coroutines.currentCoroutineContext().ensureActive();val x=queue.removeFirst();if(!seen.add(x.ref))continue;if(x.directory)queue.addAll(s.list(x.ref)) else{total+=x.size.coerceAtLeast(0);n++}}
        total to n
    };bytes=result.first;count=result.second}catch(ex:Exception){if(ex is kotlinx.coroutines.CancellationException)throw ex;error=ex.message}}
    AlertDialog(onDismissRequest=close,title={Text("Свойства")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        Text(e.name,fontWeight=FontWeight.Bold);Text("Тип: ${if(e.directory)"Папка" else e.mime}");Text("Размер: ${humanSize(bytes)}");if(e.directory)Text("Файлов: $count");Text("Изменён: ${dateText(e.modified)}");Text(e.ref,fontSize=11.sp);error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick=close){Text("Готово")}})
}
