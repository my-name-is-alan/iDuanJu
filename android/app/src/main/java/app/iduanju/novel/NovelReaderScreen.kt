package app.iduanju.novel

import android.app.Activity
import android.content.ContextWrapper
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.iduanju.core.novel.LegadoReview
import app.iduanju.core.novel.NovelChapter
import app.iduanju.core.novel.NovelSource
import app.iduanju.ui.components.*
import app.iduanju.ui.theme.IdjColors
import app.iduanju.ui.theme.LocalIdjColors
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale
import kotlin.math.roundToInt

private val ReaderAccent=Color(0xFF309714)
private val ListenInk=Color(0xB8333333)
private val themeNames=listOf("白色","米黄","护眼绿","夜间")
private val turns=listOf("curl" to "仿真","slide" to "滑动","cover" to "覆盖","none" to "无动画","scroll" to "上下滚动")
private val fontNames=listOf("system" to "系统字体","songti" to "宋体","kaiti" to "楷体","yuanti" to "圆体","wenkai" to "霞鹜文楷","sourceserif" to "思源宋体","sourcesans" to "思源黑体")

/** Native peer of current iOS NovelReader. Reader canvas and menus are overlaid, never stacked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NovelReaderScreen(
    bookKey:String,bookTitle:String,author:String,cover:String,sourceName:String,
    chapters:List<NovelChapter>,chapterIndex:Int,body:String,busy:Boolean,error:String?,cached:Int,
    addedAt:Long=0,initialOffset:Int=0,source:NovelSource?=null,bookUrl:String="",onBack:()->Unit,onChapter:(Int)->Unit,onDownload:(Int)->Unit,onSwitch:(Int)->Unit,
    onLoadChapter:suspend(Int)->String,onSearch:suspend(String)->List<app.iduanju.core.novel.NovelCache.SearchHit>,onPrefetch:(Int)->Unit,onPosition:(Int,Int)->Unit
) {
    val context=LocalContext.current;val view=LocalView.current;val scope=rememberCoroutineScope()
    DisposableEffect(Unit){
        app.iduanju.IdjApp.instance.musicPlayer.enterImmersive("novelReader")
        onDispose{app.iduanju.IdjApp.instance.musicPlayer.leaveImmersive("novelReader")}
    }
    val prefs=remember{context.getSharedPreferences("novel",0)}
    var settings by remember{mutableStateOf(ReaderSettings.read(prefs.getString("reader.settings",null)))}
    val pal=ReaderPalette.of(settings.theme);val bg=Color(pal.bg);val panel=Color(pal.panel);val ink=Color(pal.ink);val secondary=Color(pal.secondary)
    var menu by remember(bookKey){mutableStateOf(false)};var tab by remember(bookKey){mutableStateOf("")}
    var showReviews by remember(bookKey){mutableStateOf(false)}
    val hasReviews = remember(source?.id) { source != null && LegadoReview.isEnabled(source) }
    val hasImages = remember(body, source?.id) { source?.isImage == true || body.contains("<img", ignoreCase = true) }
    var endChapter by remember(bookKey){mutableIntStateOf(-1)};var page by remember{mutableIntStateOf(1)};var pages by remember{mutableIntStateOf(1)}
    var pageView by remember{mutableStateOf<NovelPageView?>(null)}
    var autoTurn by remember{mutableStateOf(false)};var brightness by remember{mutableFloatStateOf(.5f)}
    var listenError by remember{mutableStateOf<String?>(null)};var speech by remember{mutableStateOf<TextToSpeech?>(null)};var speaking by remember{mutableStateOf(false)};var rate by remember{mutableFloatStateOf(1f)}
    var fontBusy by remember{mutableStateOf<String?>(null)};var fontError by remember{mutableStateOf<String?>(null)};var fontRevision by remember{mutableIntStateOf(0)}
    var lastOffset by remember(bookKey){mutableIntStateOf(initialOffset)}
    var lastChapter by remember(bookKey){mutableIntStateOf(chapterIndex.coerceAtLeast(0))}
    val resume=remember(settings.turn){lastChapter to lastOffset}
    var scrollTarget by remember(bookKey,settings.turn){mutableStateOf(if(settings.turn=="scroll")resume else null)}
    val restoreScroll=scrollTarget!=null
    var jumpTarget by remember(bookKey){mutableStateOf<Pair<Int,Int>?>(null)}
    val scrollViews=remember(bookKey){mutableMapOf<Int,android.widget.TextView>()}
    val scrollState=rememberLazyListState(chapterIndex.coerceAtLeast(0))
    val texts=remember(bookKey){mutableStateMapOf<Int,String>()};val textErrors=remember(bookKey){mutableStateMapOf<Int,String>()}
    val current=if(settings.turn=="scroll")scrollState.firstVisibleItemIndex.coerceIn(0,(chapters.size-1).coerceAtLeast(0)) else chapterIndex.coerceAtLeast(0)
    val title=chapters.getOrNull(current)?.title.orEmpty()
    val activity=remember(view){generateSequence(view.context){(it as? ContextWrapper)?.baseContext}.filterIsInstance<Activity>().firstOrNull()}
    val density=LocalDensity.current
    val observedTop=maxOf(WindowInsets.statusBarsIgnoringVisibility.getTop(density),WindowInsets.displayCutout.getTop(density))
    val observedBottom=WindowInsets.navigationBars.getBottom(density)
    val stableTop=remember(bookKey){observedTop}
    val stableBottom=remember(bookKey){observedBottom}
    val readStart=remember(bookKey){android.os.SystemClock.elapsedRealtime()}
    val elapsedBefore=remember(bookKey){prefs.getLong("reader.time.$bookKey",0)}
    fun change(s:ReaderSettings){settings=s;prefs.edit().putString("reader.settings",s.json()).apply()}
    // [tab] survives closing so the panel fades out with its content; opening always starts from the bare toolbar.
    fun closeMenu(){menu=false}
    fun toggleMenu(){if(menu)closeMenu() else {tab="";menu=true}}
    fun jump(index:Int,offset:Int=0){
        if(index !in chapters.indices)return
        autoTurn=false
        if(settings.turn=="scroll" || hasImages){
            if(offset>0){
                scrollTarget=index to offset
            } else {
                scrollTarget=null
                lastChapter=index;lastOffset=0
                onPosition(index,0);onPrefetch(index)
            }
            scope.launch{scrollState.scrollToItem(index,0)}
        }
        else if(index==chapterIndex)pageView?.goToOffset(offset) else {endChapter=-1;jumpTarget=index to offset;onChapter(index)}
        closeMenu()
    }
    fun selectPanel(id:String){menu=true;tab=if(tab==id||(id=="font"&&tab in listOf("fonts","turn")))"" else id}
    DisposableEffect(activity,view){
        val w=activity?.window;val old=w?.attributes?.screenBrightness;val oldNav=w?.navigationBarColor
        val oldCutout=w?.attributes?.layoutInDisplayCutoutMode
        if(w!=null){WindowCompat.setDecorFitsSystemWindows(w,false);w.attributes=w.attributes.apply{layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES}}
        onDispose{
            w?.let{WindowCompat.getInsetsController(it,view).show(WindowInsetsCompat.Type.statusBars());if(old!=null){it.attributes=it.attributes.apply{screenBrightness=old}};if(oldNav!=null)it.navigationBarColor=oldNav;if(oldCutout!=null)it.attributes=it.attributes.apply{layoutInDisplayCutoutMode=oldCutout}}
            prefs.edit().putLong("reader.time.$bookKey",elapsedBefore+android.os.SystemClock.elapsedRealtime()-readStart).apply()
        }
    }
    SideEffect {activity?.window?.let{w->val c=WindowCompat.getInsetsController(w,w.decorView);c.isAppearanceLightStatusBars=settings.theme!=3;c.isAppearanceLightNavigationBars=settings.theme!=3;w.statusBarColor=pal.bg;w.navigationBarColor=pal.bg;if(menu)c.show(WindowInsetsCompat.Type.statusBars()) else c.hide(WindowInsetsCompat.Type.statusBars())}}
    DisposableEffect(Unit){val t=TextToSpeech(context){status->if(status!=TextToSpeech.SUCCESS)listenError="朗读引擎不可用"};speech=t;onDispose{t.stop();t.shutdown();if(speaking)app.iduanju.music.AppAudioArbiter.release(app.iduanju.music.AppAudioArbiter.Owner.NOVEL_TTS)}}
    LaunchedEffect(body,chapterIndex){
        texts[chapterIndex.coerceAtLeast(0)]=body
        if(jumpTarget?.first==chapterIndex)jumpTarget=null
    }
    LaunchedEffect(hasImages,chapterIndex){
        if(hasImages && settings.turn!="scroll"){
            scrollTarget=null
            scrollState.scrollToItem(chapterIndex.coerceAtLeast(0),0)
        }
    }
    LaunchedEffect(scrollTarget){
        val target=scrollTarget ?: return@LaunchedEffect
        if(target.first in textErrors){ scrollTarget=null; return@LaunchedEffect }
        kotlinx.coroutines.delay(1500)
        if(scrollTarget==target) scrollTarget=null
    }
    LaunchedEffect(settings.turn){if(settings.turn=="scroll")scrollState.scrollToItem(resume.first) else if(resume.first!=chapterIndex)onChapter(resume.first)}
    LaunchedEffect(settings.turn,hasImages,scrollState){var prefetched=-1;if(settings.turn=="scroll"||hasImages)snapshotFlow{Triple(scrollState.firstVisibleItemIndex,scrollState.firstVisibleItemScrollOffset,scrollTarget!=null)}.distinctUntilChanged().collect{(i,y,restoring)->
        if(!restoring){
            val t=scrollViews[i];val l=t?.layout
            val prefix=chapters.getOrNull(i)?.title?.takeIf{it.isNotBlank()}?.let{it.length+2}?:0
            val offset=if(l==null)0 else (l.getLineStart(l.getLineForVertical(y))-prefix).coerceAtLeast(0)
            lastChapter=i;lastOffset=offset;onPosition(i,offset);if(prefetched!=i){prefetched=i;onPrefetch(i)}
        }
    }}
    LaunchedEffect(autoTurn,settings.turn){if(autoTurn&&settings.turn!="scroll")while(isActive){delay(8000);pageView?.next()}}
    BackHandler{if(menu)closeMenu() else onBack()}
    val layoutMetrics = app.iduanju.ui.components.LocalLayoutMetrics.current
    val readerModifier = if (layoutMetrics.readerMaxWidth != androidx.compose.ui.unit.Dp.Unspecified) {
        Modifier.widthIn(max = layoutMetrics.readerMaxWidth).fillMaxHeight()
    } else {
        Modifier.fillMaxSize()
    }
    val colors=readerColors(settings.theme)
    val backdrop=rememberLayerBackdrop{drawRect(bg);drawContent()}
    val canGlass=android.os.Build.VERSION.SDK_INT>=31
    val menuState=remember(bookKey){MutableTransitionState(false)}
    menuState.targetState=menu
    Box(Modifier.fillMaxSize().background(bg).testTag("novel.reader"), contentAlignment = Alignment.TopCenter) {
        // Capture safe geometry once. System-bar visibility must not reflow or translate text.
        val contentPadding=with(density){PaddingValues(top=stableTop.toDp(),bottom=stableBottom.toDp())}
        // The page is recorded for the glass bars only while they are on screen, so page turns never pay for it.
        Box(Modifier.fillMaxSize().then(if(canGlass&&(menu||menuState.currentState))Modifier.layerBackdrop(backdrop) else Modifier),contentAlignment=Alignment.TopCenter){
        if(settings.turn!="scroll" && !hasImages)AndroidView(factory={NovelPageView(it).also{v->pageView=v}},modifier=readerModifier.padding(contentPadding).testTag("novel.page").semantics{contentDescription=chapters.getOrNull(chapterIndex)?.title.orEmpty()+"\n"+body},update={v->
            v.onMenu={toggleMenu()};v.enabledTurns=!busy
            v.onEdge={d->val next=chapterIndex+d;if(next in chapters.indices){endChapter=if(d<0)next else -1;onChapter(next)}}
            v.onPosition={offset,p,n->lastChapter=chapterIndex.coerceAtLeast(0);lastOffset=offset;page=p;pages=n;onPosition(chapterIndex.coerceAtLeast(0),offset)}
            v.update(bookKey+"|"+chapterIndex+"|"+fontRevision,body,chapters.getOrNull(chapterIndex)?.title.orEmpty(),settings,if(jumpTarget?.first==chapterIndex)jumpTarget!!.second else if(endChapter==chapterIndex)Int.MAX_VALUE else if(lastChapter==chapterIndex)lastOffset else initialOffset)
        }) else {
            Box(readerModifier.padding(contentPadding)) {
                val retryTick=remember(bookKey){mutableStateMapOf<Int,Int>()}
                LazyColumn(state=scrollState,modifier=Modifier.fillMaxSize().testTag("novel.scroll").pointerInput(menu){detectTapGestures{p->if(p.x<size.width/3f)scope.launch{scrollState.animateScrollBy(-size.height*.85f)} else if(p.x>size.width*2/3f)scope.launch{scrollState.animateScrollBy(size.height*.85f)} else toggleMenu()}},contentPadding=PaddingValues(start=(12+28*settings.margin).dp,end=(12+28*settings.margin).dp,top=32.dp,bottom=30.dp)) {
                    items(maxOf(1,chapters.size),key={"$it|"+(chapters.getOrNull(it)?.url?:"local")},contentType={"chapter"}){i->
                        LaunchedEffect(bookKey,i,retryTick[i]?:0){
                            if(i !in texts)try{
                                textErrors.remove(i)
                                texts[i]=onLoadChapter(i).ifBlank{"${chapters.getOrNull(i)?.title.orEmpty()}\n（本章暂无内容）"}
                            }catch(e:CancellationException){throw e}catch(e:Exception){
                                textErrors[i]=e.message?:"章节读取失败"
                                if(scrollTarget?.first==i)scrollTarget=null
                            }
                        }
                        val content = texts[i] ?: textErrors[i] ?: "正文读取中…"
                        val segments = remember(content) { splitChapterSegments(content) }
                        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                            segments.forEachIndexed { segIdx, seg ->
                                when (seg) {
                                    is ChapterSegment.ImageBlock -> NovelInlineImage(seg.url, source)
                                    is ChapterSegment.TextBlock -> {
                                        val segTitle = if (segIdx == 0) chapters.getOrNull(i)?.title.orEmpty() else ""
                                        AndroidView(factory = { android.widget.TextView(it) }, modifier = Modifier.fillMaxWidth(), update = { t ->
                                            if (segIdx == 0) scrollViews[i] = t
                                            val identity = Triple(seg.text, settings, fontRevision)
                                            if (t.tag != identity) {
                                                t.tag = identity; t.text = ReaderText.content(seg.text, segTitle, settings, context.resources.displayMetrics.scaledDensity)
                                                t.textSize = settings.font; t.typeface = ReaderText.typeface(context, settings.fontID); t.setTextColor(pal.ink); t.includeFontPadding = false; t.justificationMode = android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
                                                t.setLineSpacing(settings.font * context.resources.displayMetrics.scaledDensity * (.25f + .75f * settings.spacing), 1f)
                                            }
                                            if (segIdx == 0 && restoreScroll && i == scrollTarget?.first && i in texts) t.doOnLayout {
                                                val l = t.layout
                                                if (l == null) { scrollTarget = null; return@doOnLayout }
                                                val prefix = chapters.getOrNull(i)?.title?.takeIf { it.isNotBlank() }?.let { it.length + 2 } ?: 0
                                                val line = l.getLineForOffset(((scrollTarget?.second ?: 0) + prefix).coerceIn(0, t.text.length))
                                                scope.launch { scrollState.scrollToItem(i, l.getLineTop(line)); scrollTarget = null }
                                            }
                                        })
                                    }
                                }
                            }
                            if (i in textErrors) {
                                Box(Modifier.padding(top = 8.dp).clip(CircleShape).background(colors.glass).pressClick(.97f) {
                                    textErrors.remove(i); retryTick[i] = (retryTick[i] ?: 0) + 1
                                }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text("重新加载本章", color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
                Text(title,color=secondary,fontSize=12.sp,modifier=Modifier.align(Alignment.TopStart).padding(horizontal=(12+28*settings.margin).dp))
                Text("${current+1}/${chapters.size}",color=secondary,fontSize=12.sp,modifier=Modifier.align(Alignment.BottomEnd).padding(horizontal=20.dp,vertical=8.dp))
            }
        }
        }
        CompositionLocalProvider(LocalIdjColors provides colors) {
        if(busy)GlassSpinner(Modifier.align(Alignment.Center),size=24.dp,color=ReaderAccent,stroke=2.dp)
        if(error!=null)Text(error,color=colors.danger,fontSize=13.sp,lineHeight=19.sp,modifier=Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top=56.dp).padding(horizontal=20.dp).widthIn(max=560.dp).clip(RoundedCornerShape(14.dp)).background(panel).padding(horizontal=14.dp,vertical=12.dp))
        AnimatedVisibility(menuState,Modifier.fillMaxSize(),enter=fadeIn(Motion.fade()),exit=fadeOut(Motion.fade())){Box(Modifier.fillMaxSize()){
            val glass=if(canGlass)backdrop else null
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(contentPadding.calculateTopPadding().let{PaddingValues(start=16.dp,end=16.dp,top=it+4.dp)}),verticalAlignment=Alignment.CenterVertically){
                val back=remember{MutableInteractionSource()}
                Box(Modifier.size(44.dp).pressScale(back,.92f).paperGlass(CircleShape,glass,colors).clickable(back,null,role=Role.Button,onClick=onBack).testTag("novel.reader.back"),contentAlignment=Alignment.Center){Icon(Icons.Outlined.ChevronLeft,"返回",tint=ink,modifier=Modifier.size(28.dp))}
                Spacer(Modifier.weight(1f))
                if(chapters.isNotEmpty())Row(Modifier.paperGlass(RoundedCornerShape(50),glass,colors).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically){
                    if(hasReviews) GlassTextButton("段评",color=ink){showReviews=true;closeMenu()}
                    GlassTextButton("换源",color=ink){onSwitch(current)}
                    PlainIconButton(Icons.Outlined.DownloadForOffline,"下载",tint=ink){onDownload(current)}
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(top=contentPadding.calculateTopPadding()+56.dp,start=12.dp,end=12.dp,bottom=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                if(tab!="listen")Box(Modifier.fillMaxWidth().padding(end=4.dp,bottom=12.dp),contentAlignment=Alignment.CenterEnd){
                    val listen=remember{MutableInteractionSource()}
                    Box(Modifier.size(48.dp).pressScale(listen,.94f).glassSurface(CircleShape,backdrop=glass,dark=true,shadowRadius=10.dp,activeOverlay=ListenInk).clickable(listen,null,role=Role.Button){selectPanel("listen")}.testTag("novel.listen"),contentAlignment=Alignment.Center){Text("听",fontSize=20.sp,fontWeight=FontWeight.Medium,color=Color.White)}
                }
                app.iduanju.music.ReaderMusicMiniBar(Modifier.widthIn(max=640.dp).fillMaxWidth().padding(bottom=8.dp))
                Column(Modifier.weight(1f,fill=false).widthIn(max=640.dp).fillMaxWidth().paperGlass(RoundedCornerShape(28.dp),glass,colors,18.dp)){
                    Box(Modifier.weight(1f,fill=false).fillMaxWidth().animateContentSize(DS.panel())){
                    val panelScroll=remember(tab){ScrollState(0)}
                    if(tab.isNotEmpty())Column(Modifier.fillMaxWidth().then(if(tab=="toc"||tab=="fonts")Modifier else Modifier.verticalScroll(panelScroll)).padding(start=18.dp,end=18.dp,top=18.dp,bottom=6.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        @Composable fun header(t:String){Row(verticalAlignment=Alignment.CenterVertically){PlainIconButton(Icons.Outlined.ChevronLeft,"返回设置",Modifier.testTag("novel.panel.back"),tint=ink,iconSize=20.dp){tab="font"};Text(t,fontSize=15.sp,fontWeight=FontWeight.SemiBold,color=ink,modifier=Modifier.weight(1f))}}
                        @Composable fun pill(t:String,tag:String="",fill:Boolean=false,onClick:()->Unit){Box(Modifier.then(if(fill)Modifier.fillMaxWidth() else Modifier).clip(CircleShape).background(colors.glass).pressClick(.97f,onClick=onClick).heightIn(min=44.dp).padding(horizontal=14.dp,vertical=8.dp).testTag(tag),contentAlignment=Alignment.Center){Text(t,color=ink,fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center)}}
                        when(tab){
                            "font"->{
                                ReaderPillSlider((settings.font-12)/18,{change(settings.copy(font=(12+it*18).roundToInt().toFloat()))},settings,"${settings.font.toInt()}","A","A",Modifier.testTag("novel.font.size"),name="字号")
                                Row(horizontalArrangement=Arrangement.spacedBy(14.dp)){ReaderPillSlider(settings.margin,{change(settings.copy(margin=it))},settings,"边距","","大",Modifier.weight(1f).testTag("novel.margin"));ReaderPillSlider(settings.spacing,{change(settings.copy(spacing=it))},settings,"行距","紧","松",Modifier.weight(1f).testTag("novel.spacing"))}
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.weight(1f)){pill((fontNames.firstOrNull{it.first==settings.fontID}?.second?:"字体样式")+" ›","novel.fonts",true){tab="fonts"}}
                                    Box(Modifier.weight(1f)){pill(if(settings.indent)"首行缩进 ›" else "不缩进 ›","novel.indent",true){change(settings.copy(indent=!settings.indent))}}
                                    Box(Modifier.weight(1f)){pill((turns.first{it.first==settings.turn}.second)+" ›","novel.turn",true){tab="turn"}}
                                }
                            }
                            "turn"->{
                                header("翻页方式")
                                turns.chunked(3).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                    row.forEach{(id,name)->val on=settings.turn==id;val src=remember(id){MutableInteractionSource()};Box(Modifier.weight(1f).pressScale(src).clip(CircleShape).background(if(on)colors.accentSoft else colors.glass).then(if(on)Modifier.border(1.dp,ReaderAccent,CircleShape) else Modifier).selectable(on,src,null,true,Role.RadioButton){autoTurn=false;change(settings.copy(turn=id))}.heightIn(min=40.dp).padding(horizontal=12.dp,vertical=6.dp).testTag("novel.turn.$id"),contentAlignment=Alignment.Center){Text(name,color=if(on)ReaderAccent else ink,fontSize=13.sp,lineHeight=16.sp,fontWeight=if(on)FontWeight.SemiBold else FontWeight.Normal)}}
                                    repeat(3-row.size){Spacer(Modifier.weight(1f))}
                                }}
                                Text("仿真：像纸书一样卷页；滑动：两页并排滑过；覆盖：当前页盖在下一页上被拖走；上下滚动：整本书连续滚动。左右轻点也能翻页。",color=secondary,fontSize=11.sp,lineHeight=14.sp)
                            }
                            "light"->{ReaderPillSlider(brightness,{brightness=it;activity?.window?.let{w->w.attributes=w.attributes.apply{screenBrightness=brightness.coerceAtLeast(.01f)}}},settings,"","☀","",Modifier.testTag("novel.brightness"),name="亮度");Text("颜色",fontSize=12.sp,color=secondary);Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){(0..3).forEach{i->Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(ReaderPalette.of(i).bg)).border(if(i==settings.theme)1.5.dp else .5.dp,if(i==settings.theme)ReaderAccent else Color.Gray.copy(alpha=.2f),RoundedCornerShape(12.dp)).selectable(i==settings.theme,role=Role.RadioButton){change(settings.copy(theme=i))}.semantics{contentDescription=themeNames[i]}.testTag("novel.theme.$i"),contentAlignment=Alignment.Center){if(i==3)Icon(Icons.Outlined.NightsStay,null,tint=Color(0xFF4D7CFE))}}}}
                            "toc"->ReaderTocPanel(chapters,current,lastOffset,(texts[current]?:body).length,pages,settings,::jump,onSearch)
                            "info"->ReaderInfoPanel(bookTitle,author,cover,chapters.size,sourceName,addedAt,elapsedBefore+android.os.SystemClock.elapsedRealtime()-readStart,settings)
                            "progress"->{
                                val fraction=if(chapters.isEmpty())0f else ((current*1000+lastOffset).toFloat()/(chapters.size*1000)).coerceIn(0f,1f)
                                // A drag only previews; the chapter is loaded once, when the finger lifts.
                                var scrub by remember{mutableStateOf<Float?>(null)}
                                val target=scrub?.let{(it*chapters.size).toInt().coerceAtMost(chapters.lastIndex)}
                                ReaderProgressStats(scrub?:fraction,target?:current,chapters.size,elapsedBefore+android.os.SystemClock.elapsedRealtime()-readStart,settings)
                                Row(Modifier.fillMaxWidth().clip(CircleShape).background(Color(pal.track)),verticalAlignment=Alignment.CenterVertically){PlainIconButton(Icons.Outlined.ChevronLeft,"上一章",tint=ink,enabled=current>0){jump(current-1)};ReaderPillSlider(scrub?:fraction,{scrub=it},settings,"","","",Modifier.weight(1f).testTag("novel.progress.slider"),transparent=true,name="阅读进度",onDone={scrub?.let{v->jump((v*chapters.size).toInt().coerceAtMost(chapters.lastIndex))};scrub=null});PlainIconButton(Icons.Outlined.ChevronRight,"下一章",tint=ink,enabled=current<chapters.lastIndex){jump(current+1)}}
                                Row(horizontalArrangement=Arrangement.spacedBy(14.dp)){
                                    Box(Modifier.weight(1f)){pill("回到本章开头",fill=true){jump(current)}}
                                    if(settings.turn!="scroll")Box(Modifier.weight(1f)){pill(if(autoTurn)"关闭自动翻页" else "开启自动翻页",fill=true){autoTurn=!autoTurn;if(autoTurn)closeMenu()}}
                                }
                            }
                            "fonts" -> {
                                header("字体样式")
                                fontError?.let{Text(it,color=colors.danger,fontSize=12.sp)}
                                val installed=remember(fontRevision,fontBusy){fontNames.map{it.first}.filterTo(HashSet()){java.io.File(context.filesDir,"novel-fonts/$it.font").isFile}}
                                Column(Modifier.weight(1f,fill=false).heightIn(max=330.dp).verticalScroll(panelScroll)) {
                                    fontNames.forEach { (id,name) ->
                                        Row(Modifier.fillMaxWidth().pressClick(.985f,enabled=fontBusy==null) {
                                            if(id in listOf("system","songti","kaiti","yuanti") || java.io.File(context.filesDir,"novel-fonts/$id.font").isFile) {
                                                change(settings.copy(fontID=id));fontRevision++
                                            } else {
                                                fontBusy=id;fontError=null
                                                scope.launch {
                                                    try {ReaderFontDownload.fetch(context,id);fontRevision++}
                                                    catch(e:CancellationException){throw e}
                                                    catch(e:Exception){fontError=e.message}
                                                    finally {fontBusy=null}
                                                }
                                            }
                                        }.heightIn(min=48.dp).padding(vertical=10.dp).testTag("novel.font.$id"),verticalAlignment=Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(name,color=ink,fontSize=17.sp)
                                                Text(if(id in listOf("system","songti","kaiti","yuanti"))"系统字型 · 平台原生字体" else "SIL OFL 1.1 · 首次使用下载",fontSize=11.sp,color=secondary)
                                            }
                                            if(fontBusy==id)GlassSpinner(size=18.dp,color=ReaderAccent,stroke=2.dp)
                                            else if(settings.fontID==id)Icon(Icons.Outlined.Check,null,tint=ReaderAccent)
                                            else if(id in listOf("wenkai","sourceserif","sourcesans") && id !in installed)Text("下载",color=ReaderAccent,fontSize=12.sp,fontWeight=FontWeight.Medium)
                                        }
                                    }
                                }
                            }
                            "listen"->{header("听书");listenError?.let{Text(it,color=colors.danger,fontSize=12.sp)};ReaderPillSlider((rate-.5f)/1.5f,{rate=.5f+it*1.5f;speech?.setSpeechRate(rate)},settings,"语速","慢","快");Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){pill(if(speaking)"暂停朗读" else "开始朗读"){val t=speech;if(speaking){t?.stop();speaking=false;app.iduanju.music.AppAudioArbiter.release(app.iduanju.music.AppAudioArbiter.Owner.NOVEL_TTS)}else if(t!=null){val result=t.setLanguage(Locale.SIMPLIFIED_CHINESE);if(result<0)listenError="当前设备未安装中文语音，可在系统文字转语音设置中安装" else{app.iduanju.music.AppAudioArbiter.claim(app.iduanju.music.AppAudioArbiter.Owner.NOVEL_TTS);t.setSpeechRate(rate);val value=texts[current]?:body;val chunks=value.chunked(TextToSpeech.getMaxSpeechInputLength());chunks.forEachIndexed{i,part->t.speak(part,if(i==0)TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,null,"$bookKey:$current:$i")};speaking=true}}};pill("停止"){val was=speaking;speech?.stop();speaking=false;if(was)app.iduanju.music.AppAudioArbiter.release(app.iduanju.music.AppAudioArbiter.Owner.NOVEL_TTS)}}}
                        }
                    }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal=6.dp).heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically){
                        listOf("toc" to Icons.Outlined.FormatListBulleted,"info" to Icons.Outlined.Explore,"progress" to null,"light" to Icons.Outlined.WbSunny,"font" to null).forEach{(id,icon)->
                            val on=tab==id||(id=="font"&&tab in listOf("fonts","turn"));val color=if(on)ReaderAccent else ink;val src=remember(id){MutableInteractionSource()}
                            Box(Modifier.weight(1f).heightIn(min=48.dp).pressScale(src,.92f).selectable(on,src,null,true,Role.Tab){selectPanel(id)}.semantics{contentDescription=when(id){"toc"->"目录";"info"->"书籍";"progress"->"进度";"light"->"亮度";else->"字体"}}.testTag("novel.menu.$id"),contentAlignment=Alignment.Center){
                                if(id=="font")Text("A",fontSize=23.sp,fontWeight=FontWeight.Light,color=color)
                                else if(icon==null)Canvas(Modifier.size(26.dp)){drawCircle(color,size.minDimension*.32f,style=androidx.compose.ui.graphics.drawscope.Stroke(width=1.2.dp.toPx()));drawLine(color,androidx.compose.ui.geometry.Offset(0f,size.height/2),androidx.compose.ui.geometry.Offset(size.width,size.height/2),strokeWidth=1.2.dp.toPx())}
                                else Icon(icon,null,tint=color,modifier=Modifier.size(25.dp))
                            }
                        }
                    }
                }
            }
        }}
        }
        if (showReviews && source != null && current in chapters.indices) {
            NovelReviewSheet(source, chapters[current], bookUrl, bookTitle) { showReviews = false }
        }
    }
}

/** The reader's floating surface: the shared glass, washed with the paper's panel colour so text on it stays readable. */
@Composable
private fun Modifier.paperGlass(shape:CornerBasedShape,backdrop:Backdrop?,colors:IdjColors,shadow:Dp=10.dp)=glassSurface(shape,backdrop=backdrop,dark=colors.dark,base=colors.surface,shadowRadius=shadow,activeOverlay=colors.surface.copy(alpha=.84f),fallbackAlpha=2f)

/** Pill track/label thumb matching existing iOS PillSlider, with accessible adjustable semantics. [onDone] runs when a drag or tap ends. */
@Composable
private fun ReaderPillSlider(value:Float,onValue:(Float)->Unit,s:ReaderSettings,label:String="",leading:String="",trailing:String="",modifier:Modifier=Modifier,transparent:Boolean=false,name:String=label,onDone:()->Unit={}){
    val p=ReaderPalette.of(s.theme)
    val handle by rememberUpdatedState(onValue);val done by rememberUpdatedState(onDone)
    // The label sits inside a fixed 40 dp thumb, so it does not follow the font scale.
    val thumbText=with(LocalDensity.current){13.dp.toSp()}
    Box(modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(if(transparent)Color.Transparent else Color(p.track)).semantics{if(name.isNotEmpty())contentDescription=name;progressBarRangeInfo=ProgressBarRangeInfo(value.coerceIn(0f,1f),0f..1f);setProgress{onValue(it.coerceIn(0f,1f));onDone();true}}.pointerInput(Unit){
        fun at(x:Float)=((x-22.dp.toPx())/(size.width-44.dp.toPx()).coerceAtLeast(1f)).coerceIn(0f,1f)
        awaitEachGesture{
            val down=awaitFirstDown()
            val drag=awaitHorizontalTouchSlopOrCancellation(down.id){c,_->c.consume()}
            if(drag!=null){handle(at(drag.position.x));horizontalDrag(drag.id){c->c.consume();handle(at(c.position.x))};done()}
            else if(currentEvent.changes.all{!it.pressed&&!it.isConsumed}){handle(at(down.position.x));done()}
        }
    }){
        Row(Modifier.fillMaxSize().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(leading,color=Color(p.ink),fontSize=13.sp);Text(trailing,color=Color(p.ink),fontSize=15.sp)}
        Box(Modifier.align(BiasAlignment(value.coerceIn(0f,1f)*2f-1f,0f)).padding(3.dp).size(38.dp).shadow(3.dp,CircleShape).background(Color(p.thumb),CircleShape),contentAlignment=Alignment.Center){if(label.isNotEmpty())Text(label,color=Color(p.ink),fontSize=thumbText,fontWeight=FontWeight.Medium,maxLines=1,softWrap=false)}
    }
}
