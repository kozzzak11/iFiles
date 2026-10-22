package com.serhii.ifiles

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/** Offline document viewport with native pan and pinch zoom. */
class OfficeWebView(context:Context):WebView(context) {
    var onZoom:(Float)->Unit = {}
    init {
        settings.javaScriptEnabled=false
        settings.allowFileAccess=false
        settings.allowContentAccess=false
        settings.blockNetworkLoads=true
        settings.useWideViewPort=true
        settings.loadWithOverviewMode=true
        settings.setSupportZoom(true)
        settings.builtInZoomControls=true
        settings.displayZoomControls=false
        webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
            override fun onScaleChanged(view:WebView,oldScale:Float,newScale:Float) {
                onZoom(newScale/resources.displayMetrics.density)
            }
        }
    }
    @Suppress("DEPRECATION")
    fun actualZoom()=scale/resources.displayMetrics.density
    fun fitWidth() {
        // Scroll range is in physical pixels at the current scale; no JS or document scripts.
        val range=computeHorizontalScrollRange().coerceAtLeast(width)
        if(width>0 && range>0)zoomBy((width.toFloat()/range).coerceIn(0.01f,100f))
        scrollTo(0,scrollY)
    }
}

@Composable fun OfficePreview(html:String) {
    var web by remember(html){mutableStateOf<OfficeWebView?>(null)}
    var zoom by remember(html){mutableFloatStateOf(1f)}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceEvenly) {
            TextButton(onClick={web?.fitWidth()}){Text("По ширине")}
            TextButton(onClick={web?.zoomBy(0.8f)}){Text("−")}
            Text("${(zoom*100).roundToInt()}%")
            TextButton(onClick={web?.zoomBy(1.25f)}){Text("+")}
            TextButton(onClick={web?.let{it.zoomBy((1f/it.actualZoom()).coerceIn(0.01f,100f))}}){Text("100%")}
        }
        AndroidView(factory={ctx->OfficeWebView(ctx).apply {
            web=this
            onZoom={zoom=it}
            loadDataWithBaseURL(null,html,"text/html","UTF-8",null)
        }},onRelease={web=null;it.destroy()},modifier=Modifier.weight(1f).fillMaxWidth())
    }
}
