package com.serhii.ifiles

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** iOS system colors (values only; no Apple assets). */
object Ios {
    val Blue=Color(0xFF007AFF); val Red=Color(0xFFFF3B30); val Orange=Color(0xFFFF9500); val Indigo=Color(0xFF5856D6)
    fun secondary(dark: Boolean)=if(dark)Color(0x99EBEBF5) else Color(0x993C3C43)
    fun separator(dark: Boolean)=if(dark)Color(0xFF38383A) else Color(0xFFC6C6C8)
    fun fill(dark: Boolean)=if(dark)Color(0xFF1C1C1E) else Color(0xFFE3E3E8)
}

/** Rounded grey search field like iOS (magnifier, placeholder, clear button). */
@Composable fun IosSearchField(value: String, onChange: (String)->Unit, placeholder: String, dark: Boolean, modifier: Modifier=Modifier) {
    val secondary=Ios.secondary(dark)
    Row(modifier.height(38.dp).clip(RoundedCornerShape(10.dp)).background(Ios.fill(dark)).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search,null,Modifier.size(20.dp),tint=secondary)
        Box(Modifier.weight(1f).padding(horizontal=6.dp)) {
            if(value.isEmpty())Text(placeholder,color=secondary,fontSize=17.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            BasicTextField(value,onChange,Modifier.fillMaxWidth().semantics{contentDescription="Поиск"},singleLine=true,
                textStyle=TextStyle(fontSize=17.sp,color=MaterialTheme.colorScheme.onBackground),cursorBrush=SolidColor(MaterialTheme.colorScheme.primary))
        }
        if(value.isNotEmpty())Icon(Icons.Outlined.Cancel,"Очистить поиск",Modifier.size(20.dp).clickable{onChange("")},tint=secondary)
    }
}

data class SwipeAction(val label: String, val icon: ImageVector, val color: Color, val run: ()->Unit)

/**
 * List row with iOS-style swipe actions: drag left reveals trailing actions (full swipe runs the last one),
 * drag right reveals the leading action.
 */
@Composable fun SwipeRow(enabled: Boolean, leading: List<SwipeAction>, trailing: List<SwipeAction>, content: @Composable ()->Unit) {
    val density=LocalDensity.current
    val button=with(density){76.dp.toPx()}
    val offset=remember{Animatable(0f)}
    val scope=rememberCoroutineScope()
    var width by remember{mutableFloatStateOf(1f)}
    val maxRight=button*leading.size; val maxLeft=-button*trailing.size
    fun settle(velocity: Float) = scope.launch {
        val x=offset.value
        when {
            trailing.isNotEmpty() && x < -width*0.6f -> { offset.animateTo(-width,tween(160)); trailing.last().run(); offset.snapTo(0f) }
            leading.isNotEmpty() && x > width*0.45f -> { offset.animateTo(0f,tween(160)); leading.first().run() }
            x < maxLeft/2 || velocity < -1500f && x<0 -> offset.animateTo(maxLeft)
            x > maxRight/2 || velocity > 1500f && x>0 -> offset.animateTo(maxRight)
            else -> offset.animateTo(0f)
        }
    }
    Box(Modifier.fillMaxWidth().onSizeChanged{width=it.width.toFloat().coerceAtLeast(1f)}) {
        if(offset.value<0f)Row(Modifier.matchParentSize(),horizontalArrangement=Arrangement.End) {
            trailing.forEach { a -> ActionButton(a,Modifier.fillMaxHeight().width(with(density){(-offset.value/trailing.size).coerceAtLeast(0f).toDp()})){ scope.launch{offset.animateTo(0f)};a.run() } }
        }
        if(offset.value>0f)Row(Modifier.matchParentSize(),horizontalArrangement=Arrangement.Start) {
            leading.forEach { a -> ActionButton(a,Modifier.fillMaxHeight().width(with(density){(offset.value/leading.size).coerceAtLeast(0f).toDp()})){ scope.launch{offset.animateTo(0f)};a.run() } }
        }
        Box(Modifier.offset{IntOffset(offset.value.roundToInt(),0)}.background(MaterialTheme.colorScheme.background)
            .draggable(rememberDraggableState{d->scope.launch{offset.snapTo((offset.value+d).coerceIn(if(trailing.isEmpty())0f else -width,if(leading.isEmpty())0f else width))}},Orientation.Horizontal,enabled=enabled,onDragStopped={v->settle(v)})) {
            content()
        }
    }
}
@Composable private fun ActionButton(a: SwipeAction, modifier: Modifier, onClick: ()->Unit) {
    Column(modifier.background(a.color).clickable(onClick=onClick).semantics{contentDescription=a.label},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        Icon(a.icon,null,tint=Color.White,modifier=Modifier.size(22.dp))
        Text(a.label,color=Color.White,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Clip)
    }
}

data class MenuAction(val label: String, val icon: ImageVector, val destructive: Boolean=false, val run: ()->Unit)

/** iOS-style context menu: lifted item preview above a rounded translucent action list. */
@Composable fun IosContextMenu(e: Entry, s: Storage, dark: Boolean, groups: List<List<MenuAction>>, onDismiss: ()->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=if(dark).45f else .22f)).clickable(onClick=onDismiss),contentAlignment=Alignment.Center) {
            Column(Modifier.padding(horizontal=28.dp).widthIn(max=360.dp).fillMaxWidth()) {
                Row(Modifier.shadow(18.dp,RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp).fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    FileGlyph(e,s,Modifier.size(52.dp))
                    Column(Modifier.padding(start=12.dp)) {
                        Text(e.name,fontWeight=FontWeight.SemiBold,fontSize=17.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(listOf(dateText(e.modified),if(e.directory)"Папка" else humanSize(e.size)).filter{it.isNotEmpty()}.joinToString(" – "),fontSize=13.sp,color=Ios.secondary(dark))
                    }
                }
                Spacer(Modifier.height(10.dp))
                val rows=groups.filter{it.isNotEmpty()}
                Column(Modifier.shadow(18.dp,RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha=.97f)).heightIn(max=600.dp).verticalScroll(rememberScrollState())) {
                    rows.forEachIndexed { g, group ->
                        group.forEachIndexed { i, a ->
                            Row(Modifier.fillMaxWidth().clickable{onDismiss();a.run()}.padding(horizontal=16.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(a.label,Modifier.weight(1f),fontSize=17.sp,color=if(a.destructive)Ios.Red else MaterialTheme.colorScheme.onSurface)
                                Icon(a.icon,null,Modifier.size(20.dp),tint=if(a.destructive)Ios.Red else MaterialTheme.colorScheme.onSurface)
                            }
                            if(i<group.lastIndex)HorizontalDivider(thickness=0.5.dp,color=Ios.separator(dark))
                        }
                        if(g<rows.lastIndex)Box(Modifier.fillMaxWidth().height(8.dp).background(Ios.separator(dark).copy(alpha=.35f)))
                    }
                }
            }
        }
    }
}

/** Single list row: large icon, 17sp name, grey "date – size" subtitle, inset separator. */
@OptIn(ExperimentalFoundationApi::class)
@Composable fun IosFileRow(e: Entry, s: Storage, dark: Boolean, selected: Boolean, selecting: Boolean, favorite: Boolean, divider: Boolean, onClick: ()->Unit, onLong: ()->Unit) {
    Column(Modifier.fillMaxWidth().background(if(selected)MaterialTheme.colorScheme.primary.copy(alpha=.12f) else MaterialTheme.colorScheme.background).combinedClickable(onClick=onClick,onLongClick=onLong)) {
        Row(Modifier.fillMaxWidth().padding(start=16.dp,end=12.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
            if(selecting)Icon(if(selected)Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,"Выделение",Modifier.padding(end=12.dp).size(24.dp),tint=if(selected)MaterialTheme.colorScheme.primary else Ios.secondary(dark))
            FileGlyph(e,s,Modifier.size(52.dp))
            Column(Modifier.weight(1f).padding(start=14.dp)) {
                Text(e.name,maxLines=1,overflow=TextOverflow.MiddleEllipsis,fontSize=17.sp)
                Text(listOf(dateText(e.modified),if(e.directory)"Папка" else humanSize(e.size)).filter{it.isNotEmpty()}.joinToString(" – "),fontSize=13.sp,color=Ios.secondary(dark),maxLines=1)
            }
            if(favorite)Icon(Icons.Outlined.Star,"В избранном",Modifier.padding(start=6.dp).size(16.dp),tint=Ios.Orange)
            if(e.directory)Icon(Icons.Outlined.ChevronRight,null,Modifier.padding(start=4.dp),tint=Ios.secondary(dark))
        }
        if(divider)HorizontalDivider(Modifier.padding(start=if(selecting)118.dp else 82.dp),thickness=0.5.dp,color=Ios.separator(dark))
    }
}

/** Search result found in a file's text: name, highlighted snippet and the containing folder. */
@OptIn(ExperimentalFoundationApi::class)
@Composable fun SearchHitRow(hit: SearchHit, s: Storage, dark: Boolean, divider: Boolean, onClick: ()->Unit) {
    val e=remember(hit.ref){hit.entry(s)}
    val (plain,ranges)=remember(hit.snippet){Snippets.parse(hit.snippet)}
    val highlight=if(dark)Color(0x66FFD60A) else Color(0x66FFCC00)
    val text=remember(plain,ranges,dark){androidx.compose.ui.text.buildAnnotatedString {
        append(plain)
        ranges.forEach { r -> if(r.first>=0 && r.last<plain.length)addStyle(androidx.compose.ui.text.SpanStyle(fontWeight=FontWeight.SemiBold,background=highlight),r.first,r.last+1) }
    }}
    val folder=remember(hit.ref){runCatching{s.parentOf(hit.ref)?.let{p->if(s.isFile(p))s.file(p).name else null}}.getOrNull()}
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).clickable(onClick=onClick).semantics{contentDescription="Результат: "+hit.name}) {
        Row(Modifier.fillMaxWidth().padding(start=16.dp,end=12.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.Top) {
            FileGlyph(e,s,Modifier.size(44.dp))
            Column(Modifier.weight(1f).padding(start=14.dp)) {
                Text(hit.name,maxLines=1,overflow=TextOverflow.MiddleEllipsis,fontSize=17.sp)
                Text(text,fontSize=13.sp,maxLines=3,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.85f))
                Text(listOfNotNull(folder,dateText(hit.modified).ifEmpty{null},if(hit.size>=0)humanSize(hit.size) else null).joinToString(" – "),fontSize=12.sp,color=Ios.secondary(dark),maxLines=1)
            }
        }
        if(divider)HorizontalDivider(Modifier.padding(start=74.dp),thickness=0.5.dp,color=Ios.separator(dark))
    }
}
