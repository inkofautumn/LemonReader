package cn.jiangcheng.lemon.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.VelocityTracker
import android.view.Choreographer
import android.widget.OverScroller
import cn.jiangcheng.lemon.reader.pagecurl.IBookCurlRenderer
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ReadingStyle(var size: Float = 23f, var line: Float = 1.7f, var paragraph: Float = 12f,
    var margin: Float = 24f, var paper: Int = Color.rgb(233,228,205), var ink: Int = Color.rgb(29,28,23),
    var night: Boolean = false, var family: String = "sans-serif", var mode: String = "覆盖", var warmth: Int = 0, var topGap: Float = -1f, var bottomGap: Float = -1f)

class ReadingView(context: Context) : View(context) {
    var style = ReadingStyle()
    var bookTitle=""
    var chapters = emptyList<Chapter>()
    var highlights = emptyList<Note>()
        set(value){if(field==value)return;field=value;highlightBoxes.clear();clearCachedPage();clearPageBitmapPool();invalidate()}
    var locatedText:Note?=null
        set(value){if(field==value)return;field=value;highlightBoxes.clear();clearCachedPage();invalidate()}
    private val highlightBoxes=mutableMapOf<Int,Pair<StaticLayout,List<RectF>>>()
    var chapter = 0
        private set
    private var layout: StaticLayout? = null
    private var headingLayout: StaticLayout? = null
    private var page = 0
    private val pages = mutableListOf<Int>()
    private var scrollYText = 0f
    private var pendingOffset = 0
    private val density = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val uiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var selectionStart = -1
    private var selectionEnd = -1
    private var selectionChapter = -1
    private var selectionHandle=0
    private var touchMoved=false
    private var longPressSelection=false
    private var selectionEdgeLatch=0
    private var selectionPointerX=0f
    private var selectionPointerY=0f
    private var selectionAutoScrollDirection=0
    private var selectionAutoScrollFrame=0L
    private var selectionDismissGesture=false
    var onSelectionHidden:()->Unit={}
    fun hasSelection()=selectionChapter>=0&&selectionStart>=0&&selectionEnd>selectionStart
    private data class TextHit(val chapter:Int,val offset:Int)
    private data class VerticalSegment(val chapter:Int,val ready:PreparedChapter,val top:Float){
        val bodyTop:Float get()=top
    }
    private var automaticVertical=false
    private fun verticalMode()=style.mode=="上下"||automaticVertical
    private val verticalScroller=OverScroller(context)
    private var verticalScrollerY=0
    private val verticalFling=object:Runnable{override fun run(){
        if(!verticalMode()||!verticalScroller.computeScrollOffset())return
        val current=verticalScroller.currY;val delta=current-verticalScrollerY;verticalScrollerY=current
        if(delta!=0){scrollVerticalBy(delta.toFloat());publish();postInvalidateOnAnimation()}
        if(!verticalScroller.isFinished)postOnAnimation(this)else onUserInteractionFinished()
    }}
    private val selectionAutoScroll=object:Choreographer.FrameCallback{override fun doFrame(frameTimeNanos:Long){
        if(selectionHandle==0||selectionAutoScrollDirection==0||!verticalMode()){selectionAutoScrollFrame=0L;return}
        val elapsed=if(selectionAutoScrollFrame==0L)0f else ((frameTimeNanos-selectionAutoScrollFrame)/1_000_000_000f).coerceAtMost(.05f)
        selectionAutoScrollFrame=frameTimeNanos
        val edge=52*density
        val penetration=if(selectionAutoScrollDirection<0)((top+edge-selectionPointerY)/edge).coerceIn(.15f,1f)else((selectionPointerY-(bottom-edge))/edge).coerceIn(.15f,1f)
        if(elapsed>0f){
            val before=chapter
            scrollVerticalBy(selectionAutoScrollDirection*(80f+360f*penetration)*density*elapsed,selectionChapter)
            if(chapter!=before&&chapter!=selectionChapter){selectionAutoScrollDirection=0;selectionAutoScrollFrame=0L;return}
            updateSelectionFromPointer();publish();postInvalidateOnAnimation()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }}
    private fun rangeBoxes(l:StaticLayout,start:Int,end:Int)=TextRangeGeometry.lineBoxes(l,start,end)
    private fun selectionLayout():Triple<StaticLayout,Float,Float>?{
        if(!hasSelection())return null
        if(verticalMode()){
            val segment=verticalSegments().firstOrNull{it.chapter==selectionChapter}?:return null
            return Triple(segment.ready.body,contentLeft,segment.top+headingHeight)
        }
        if(selectionChapter!=chapter)return null
        return Triple(layout?:return null,contentLeft,top-startY())
    }
    private data class SelectionHandleGeometry(val x:Float,val top:Float,val bottom:Float,val knobY:Float)
    private fun selectionRangeOnScreen():Pair<Int,Int>?{
        if(!hasSelection())return null
        if(verticalMode())return selectionStart to selectionEnd
        if(selectionChapter!=chapter)return null
        val visibleStart=position().offset
        val visibleEnd=boundary().offset
        val start=max(selectionStart,visibleStart)
        val end=min(selectionEnd,visibleEnd)
        return if(end>start)start to end else null
    }
    private fun selectionEndpointVisible(start:Boolean):Boolean{
        if(!hasSelection())return false
        if(verticalMode())return verticalSegments().any{it.chapter==selectionChapter}
        if(selectionChapter!=chapter)return false
        val first=position().offset
        val last=boundary().offset
        return if(start)selectionStart in first until last else selectionEnd>first&&selectionEnd<=last
    }
    private fun handleGeometry(start:Boolean):SelectionHandleGeometry?{
        if(!selectionEndpointVisible(start))return null
        val(l,left,bodyTop)=selectionLayout()?:return null
        // Derive both handles from the exact visible boxes that are painted.
        // A separate one-character lookup lets paragraph indents, hidden
        // source whitespace and soft-wrap affinity drift away from the edge.
        val boxes=rangeBoxes(l,selectionStart.coerceIn(0,l.text.length),selectionEnd.coerceIn(0,l.text.length))
        val endpoint=(if(start)boxes.firstOrNull() else boxes.lastOrNull())?.rect?:return null
        val x=left+if(start)endpoint.left else endpoint.right
        val cellTop=bodyTop+endpoint.top
        val cellBottom=bodyTop+endpoint.bottom
        // Keep the knob centre on the selected corner. Offsetting it beyond
        // the line makes the stem and knob look detached at large font sizes.
        return SelectionHandleGeometry(x,cellTop,cellBottom,if(start)cellTop else cellBottom)
    }
    private fun handlePoint(start:Boolean):PointF {
        val geometry=handleGeometry(start)?:return PointF(-1000f,-1000f)
        return PointF(geometry.x,if(start)geometry.top else geometry.bottom)
    }
    fun selectionBounds():RectF {
        val(l,left,bodyTop)=selectionLayout()?:return RectF()
        val range=selectionRangeOnScreen()?:return RectF()
        return TextRangeGeometry.bounds(l,range.first,range.second).also{
            it.offset(left,bodyTop);it.top=it.top.coerceAtLeast(top);it.bottom=it.bottom.coerceAtMost(bottom)
        }
    }
    private var downX = 0f
    private var downY = 0f
    private var chapterOverscroll = 0f
    private var oldBitmap: Bitmap? = null
    /** The page being entered during a tap-triggered animation.  Keeping a
     * snapshot here means animation frames only move two bitmaps; they do not
     * re-layout and redraw a full page on every frame. */
    private var incomingBitmap: Bitmap? = null
    private var progress = 1f
    private var animationDirection = 1
    private var draggingPage = false
    private var swipeTracking = false
    private var previewingPage = false
    private var dragOrigin = Position(0,0)
    private data class PageTarget(val position: Position, val chapter: Int, val page: Int)
    private var dragOriginBitmap: Bitmap? = null
    private var dragPreviousBitmap: Bitmap? = null
    private var dragNextBitmap: Bitmap? = null
    private var dragDistance = 0f
    private var dragTouchY = 0f
    private var dragCurlAnchorY = 0f
    private var dragCurlMinDeltaY = 0f
    private var dragCurlMode = IBookCurlRenderer.CornerMode.Landscape
    private var dragCurlInitialized = false
    private val pageCurlRenderer=IBookCurlRenderer()
    private var pageCurlWidth=0
    private var pageCurlHeight=0
    private var dragTarget: PageTarget? = null
    // The visible neighbour follows the current finger side, including a
    // reversal across the touch-down point in the same gesture.
    private var dragDirection = 0
    private var dragSettling = false
    private var dragAnimator: ValueAnimator? = null
    private var pageAnimator: ValueAnimator? = null
    private var horizontalGestureRejected = false
    var backgroundImage: Bitmap? = null
        set(value){if(field===value)return;field=value;clearCachedPage();invalidate()}
    var onMenu: () -> Unit = {}
    var onPosition: (Position, Position) -> Unit = { _, _ -> }
    var onSelect: (Int, Int, Int) -> Unit = { _, _, _ -> }
    var onSummary: (Int) -> Unit = {}
    var onBookmark:()->Unit={}
    var onUserInteraction:()->Unit={}
    var onUserInteractionFinished:()->Unit={}
    var onManualPageTurn:()->Unit={}
    /** Default is intentionally responsive; an explicitly saved user value is
     * still applied by ReaderScreen. */
    /** A high default makes a short, deliberate swipe commit naturally. */
    var sensitivity=.9f
    /** Fraction of the view width required for a slow drag to commit. */
    /** A slow drag needs a visible fraction of the page before committing;
     * quick flicks still use the velocity path in SwipePolicy. */
    var reboundThreshold=.28f
    /** 0.6 is slow, 1.4 is fast; the default deliberately settles more gently. */
    var animationSpeed=1f
    private var downTime=0L
    private var lastTouchX=0f
    private var lastTouchTime=0L
    private var peakHorizontalVelocity=0f
    private var peakHorizontalVelocityDirection=0f
    private var horizontalIntentDx=0f
    private var horizontalIntentDy=0f
    private var velocityTracker: VelocityTracker? = null
    /** Reuse page surfaces instead of allocating a full-screen bitmap per gesture. */
    private val pageBitmapPool = ArrayDeque<Bitmap>()
    // A full-screen ARGB surface is sizeable on a phone.  Two surfaces are
    // enough for a drag (origin + neighbour) and avoid retaining four copies
    // after the gesture has ended.
    private val maxPooledPageBitmaps = 2
    private var cachedPageBitmap: Bitmap? = null
    private var cachedPageKey: String? = null
    private var snapshotGeneration = 0
    private var snapshotRunnable: Runnable? = null
    private data class PreparedChapter(val index:Int,val source:List<Chapter>,val signature:String,
        val body:StaticLayout,val heading:StaticLayout,val starts:List<Int>)
    private val preparedChapters=mutableMapOf<Int,PreparedChapter>()
    private val preparingChapters=mutableSetOf<Int>()
    @Volatile private var chapterLayoutGeneration=0

    private data class HorizontalMetrics(val left: Float, val width: Int) {
        val right: Float get() = left + width
    }

    /**
     * The text column is centred as a whole. Text alignment inside the column
     * remains natural/justified; no line is individually centred.
     */
    private fun horizontalMetrics(viewWidth: Int = width, format: ReadingStyle = style): HorizontalMetrics {
        val requestedMargin = (format.margin * density).coerceAtLeast(0f)
        val available = (viewWidth.toFloat() - requestedMargin * 2f).coerceAtLeast(40f)
        val contentWidth = available.coerceAtMost(viewWidth.toFloat().coerceAtLeast(40f)).toInt().coerceAtLeast(40)
        val left = ((viewWidth - contentWidth) / 2f).coerceAtLeast(0f)
        return HorizontalMetrics(left, contentWidth)
    }

    private val contentLeft get() = horizontalMetrics().left
    private val contentRight get() = horizontalMetrics().right

    private class HiddenDisplayWhitespace : android.text.style.ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?) = 0
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) = Unit
    }
    private fun currentPageKey():String = listOf(
        bookTitle,chapter,page,width,height,style.size,style.line,style.paragraph,style.margin,
        style.paper,style.ink,style.night,style.family,style.mode,style.warmth,
        System.identityHashCode(backgroundImage),highlights.size,locatedText?.start ?: -1,
        locatedText?.end ?: -1
    ).joinToString("|")

    private fun clearCachedPage(){
        snapshotGeneration++
        snapshotRunnable?.let{removeCallbacks(it)}
        snapshotRunnable=null
        releasePageBitmap(cachedPageBitmap);cachedPageBitmap=null;cachedPageKey=null
    }

    /** Warm one current-page surface while the reader is idle.  This removes
     * the first full-page capture from the initial finger movement; if the
     * user starts dragging immediately, beginPageDrag simply falls back to a
     * synchronous capture as before. */
    private fun scheduleCurrentPageSnapshot(){
        clearCachedPage()
        if(style.mode in listOf("无动画","上下")||width<=0||height<=0)return
        val generation= snapshotGeneration
        val key=currentPageKey()
        val task=Runnable{
            snapshotRunnable=null
            if(generation!=snapshotGeneration||draggingPage||dragAnimator!=null||pageAnimator!=null||progress<1f)return@Runnable
            val bitmap=captureCurrentPage() ?: return@Runnable
            if(generation==snapshotGeneration&&!draggingPage&&dragAnimator==null&&pageAnimator==null&&progress>=1f){
                releasePageBitmap(cachedPageBitmap);cachedPageBitmap=bitmap;cachedPageKey=key
            }else releasePageBitmap(bitmap)
        }
        snapshotRunnable=task
        postDelayed(task,120L)
    }

    private fun takeCachedCurrentPage():Bitmap?{
        if(cachedPageKey!=currentPageKey()){
            if(cachedPageBitmap!=null)clearCachedPage()
            return null
        }
        val bitmap=cachedPageBitmap?:return null
        cachedPageBitmap=null;cachedPageKey=null
        snapshotRunnable?.let{removeCallbacks(it)};snapshotRunnable=null
        return bitmap
    }
    fun pageBookmarks():List<Note>{val p=position();val end=boundary();val limit=if(end.chapter==p.chapter)end.offset else chapters.getOrNull(p.chapter)?.text?.length?:p.offset;return highlights.filter{it.type=="书签"&&!it.changed&&it.chapter==p.chapter&&it.start>=p.offset&&(it.start<limit||it.start==limit&&limit==chapters.getOrNull(p.chapter)?.text?.length)}}
    private fun verticalGaps(format:ReadingStyle,h:Int):Pair<Float,Float>{
        val heightDp=h/density
        val adaptive=(heightDp*.016f).coerceIn(8f,18f)
        return (38f+if(format.topGap<0)adaptive else format.topGap.coerceIn(0f,80f)) * density to
            (34f+if(format.bottomGap<0)adaptive else format.bottomGap.coerceIn(0f,80f)) * density
    }
    fun defaultVerticalGap()=(height/density*.016f).coerceIn(8f,18f)
    private val baseTop get()=verticalGaps(style,height).first
    private val headingHeight get()=(style.size*2.6f+24f)*density
    val top get() = baseTop+if(page==0&&!verticalMode())headingHeight else 0f
    val bottom get() = height - verticalGaps(style,height).second
    val textHeight get() = (bottom - top).toInt().coerceAtLeast(100)
    val paper get() = if(style.night) Color.rgb(25,25,24) else style.paper
    val ink get() = if(style.night) Color.rgb(180,180,173) else style.ink
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent):Boolean{verticalScroller.forceFinished(true);removeCallbacks(verticalFling);return true}
        override fun onLongPress(e: MotionEvent) {
            if(touchMoved||swipeTracking||draggingPage)return
            if(e.y !in top..bottom)return
            val hit=if(verticalMode())verticalHit(e.x,e.y)else TextHit(chapter,offsetAt(e.x,e.y))
            val visibleStart=position().let{if(it.chapter==hit.chapter)it.offset else 0}
            val visibleEnd=boundary().let{if(it.chapter==hit.chapter)it.offset else chapters[hit.chapter].text.length}
            val range=TextSelection.visibleSentence(chapters.getOrNull(hit.chapter)?.text.orEmpty(),hit.offset,visibleStart,visibleEnd)
            selectionChapter=hit.chapter
            selectionStart = range.first
            selectionEnd = range.second
            longPressSelection=true
            postInvalidateOnAnimation()
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Once the finger has moved, this is no longer a tap.  Letting a
            // diagonal/short drag fall through to the tap zones can issue a
            // second turn (most visibly, an unexpected step to the previous
            // page after a horizontal swipe).
            if(touchMoved)return true
            if(e.y<top&&e.x>width-48*density){onBookmark();return true}
            if(e.y > bottom && isChapterEnd()) { onSummary(chapter); return true }
            if(e.y < top && chapter > 0 && page == 0) { onSummary(chapter-1); return true }
            if(verticalMode())onMenu()
            else if (e.x < width * .28) turn(-1) else if(e.x > width * .72) turn(1) else onMenu()
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if(selectionStart >= 0) return true
            if(verticalMode()) {
                val start=e1?:e2;val totalX=e2.x-start.x;val totalY=e2.y-start.y
                if(abs(totalY)>=abs(totalX)*.45f&&abs(totalY)>3*density){scrollVerticalBy(distanceY);publish();postInvalidateOnAnimation()}
                return true
            }
            // Horizontal page gestures are handled directly from MotionEvent
            // so every input sample can update the page at the next display
            // frame. GestureDetector remains responsible for taps, long-press
            // selection, and the vertical scrolling mode.
            return false
        }
        override fun onFling(e1:MotionEvent?,e2:MotionEvent,velocityX:Float,velocityY:Float):Boolean{
            if(!verticalMode()||selectionStart>=0||abs(velocityY)<abs(velocityX)*.55f)return verticalMode()
            verticalScroller.forceFinished(true);verticalScrollerY=0
            verticalScroller.fling(0,0,0,(-velocityY).toInt(),0,0,-2_000_000_000,2_000_000_000)
            removeCallbacks(verticalFling);postOnAnimation(verticalFling)
            return true
        }
    })
    init { isFocusable=true; contentDescription="小说正文，轻点两侧翻页，轻点中间打开菜单，长按选中文字" }

    fun setBook(data: List<Chapter>, position: Position) {
        // A book replacement can arrive while a page is settling (for
        // example after an import update).  Drop both visual surfaces before
        // installing the new layout so an old animation cannot draw over the
        // new book or keep its bitmaps alive.
        interruptPageTransition()
        clearCachedPage()
        invalidatePreparedChapters()
        chapters=data
        clearPageBitmapPool()
        go(position,false)
    }
    fun go(position: Position, animated: Boolean = false) {
        if(chapters.isEmpty()) return
        val pos = ReaderCore.clamp(position,chapters)
        chapter=pos.chapter;pendingOffset=pos.offset;reflow();invalidate()
    }
    private var paginationSource:List<Chapter>?=null
    private var paginationStyle=""
    private var paginationGeneration=0
    private var bookPageCounts:IntArray?=null
    private var pagePrefix=IntArray(0)
    private val clockFormat=java.text.SimpleDateFormat("HH:mm",java.util.Locale.CHINA)
    private var clockMinute=-1L
    private var clockText=""
    private var batteryReadAt=-30000L
    private var batteryPercent=100
    private fun footerClock():String {
        val now=System.currentTimeMillis()
        if(now/60000!=clockMinute){clockMinute=now/60000;clockText=clockFormat.format(java.util.Date(now))}
        return clockText
    }
    private fun footerBattery():Int {
        val now=android.os.SystemClock.uptimeMillis()
        if(now-batteryReadAt>=30000){batteryReadAt=now;batteryPercent=(context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0,100)}
        return batteryPercent
    }
    private var signatureStyle:ReadingStyle?=null
    private var signatureWidth=-1
    private var signatureHeight=-1
    private var signatureText=""
    private fun currentLayoutSignature():String {
        if(signatureStyle!=style||signatureWidth!=width||signatureHeight!=height){
            signatureStyle=style.copy();signatureWidth=width;signatureHeight=height
            signatureText=chapterLayoutSignature(style,width,height)
        }
        return signatureText
    }
    companion object {
        private val paginationExecutor=java.util.concurrent.Executors.newSingleThreadExecutor()
        private val chapterLayoutExecutor=java.util.concurrent.Executors.newSingleThreadExecutor()
    }
    private fun chapterLayoutSignature(format:ReadingStyle,w:Int,h:Int)=
        listOf(w,h,format.size,format.line,format.paragraph,format.margin,format.family,
            format.mode,format.topGap,format.bottomGap,format.ink,format.night).joinToString("|")
    private fun invalidatePreparedChapters(){
        chapterLayoutGeneration++
        preparedChapters.clear()
        highlightBoxes.clear()
        preparingChapters.clear()
    }
    private fun buildChapter(index:Int,source:List<Chapter>,format:ReadingStyle,w:Int,h:Int,signature:String):PreparedChapter{
        val item=source[index]
        val body=makeLayout(item.text,format,w,item.title)
        return PreparedChapter(index,source,signature,body,makeHeadingLayout(item.title,format,w),pageLines(body,format,h))
    }
    private fun preparedChapter(index:Int):PreparedChapter{
        val signature=currentLayoutSignature()
        preparedChapters[index]?.takeIf{it.source===chapters&&it.signature==signature}?.let{return it}
        return buildChapter(index,chapters,style.copy(),width,height,signature).also{preparedChapters[index]=it}
    }
    private val chapterGap get()=max(24f,style.paragraph+12f)*density
    private fun verticalBlockHeight(index:Int)=headingHeight+preparedChapter(index).body.height+chapterGap
    private fun installPrepared(index:Int){
        val ready=preparedChapter(index)
        chapter=ready.index;layout=ready.body;headingLayout=ready.heading
        pages.clear();pages.addAll(ready.starts)
    }
    private fun verticalSegments():List<VerticalSegment>{
        if(chapters.isEmpty()||width<=0||height<=0)return emptyList()
        val result=mutableListOf<VerticalSegment>()
        var index=chapter;var y=baseTop-scrollYText
        while(index in chapters.indices&&y<bottom){
            val ready=preparedChapter(index);result.add(VerticalSegment(index,ready,y))
            y+=headingHeight+ready.body.height+chapterGap;index++
        }
        return result
    }
    private fun normalizeVerticalOffset(){
        if(!verticalMode()||chapters.isEmpty())return
        while(chapter<chapters.lastIndex){
            val block=verticalBlockHeight(chapter)
            if(scrollYText<block)break
            scrollYText-=block;installPrepared(chapter+1)
        }
        while(scrollYText<0f&&chapter>0){
            installPrepared(chapter-1);scrollYText+=verticalBlockHeight(chapter)
        }
        if(chapter==0)scrollYText=scrollYText.coerceAtLeast(0f)
        if(chapter==chapters.lastIndex){
            val maxOffset=max(0f,verticalBlockHeight(chapter)-(bottom-baseTop))
            scrollYText=scrollYText.coerceAtMost(maxOffset)
        }
    }
    private fun scrollVerticalBy(delta:Float,limitChapter:Int=-1):Boolean{
        if(!verticalMode()||chapters.isEmpty()||delta==0f)return false
        val beforeChapter=chapter;val before=scrollYText
        if(limitChapter>=0){
            if(chapter!=limitChapter)return false
            val block=verticalBlockHeight(chapter)
            val maxOffset=max(0f,block-(bottom-baseTop))
            scrollYText=(scrollYText+delta).coerceIn(0f,maxOffset)
        }else{
            scrollYText+=delta;normalizeVerticalOffset()
        }
        pendingOffset=verticalPosition().offset
        if(chapter!=beforeChapter)prepareAdjacentChapters()
        return chapter!=beforeChapter||abs(scrollYText-before)>.01f
    }
    private fun verticalPosition():Position{
        val ready=preparedChapter(chapter)
        if(scrollYText<=headingHeight)return Position(chapter,0)
        val y=(scrollYText-headingHeight).toInt().coerceIn(0,(ready.body.height-1).coerceAtLeast(0))
        val line=ready.body.getLineForVertical(y)
        return Position(chapter,ready.body.getLineStart(line).coerceAtMost(chapters[chapter].text.length))
    }
    private fun verticalBoundary():Position{
        val segments=verticalSegments();if(segments.isEmpty())return Position(chapter,pendingOffset)
        val segment=segments.lastOrNull{it.top<bottom}?:segments.first()
        val bodyTop=segment.top+headingHeight
        if(bodyTop>=bottom)return Position(segment.chapter,0)
        val y=(bottom-bodyTop).toInt().coerceIn(0,(segment.ready.body.height-1).coerceAtLeast(0))
        var line=segment.ready.body.getLineForVertical(y)
        while(line>0&&glyphBottom(segment.ready.body,line)>y)line--
        return Position(segment.chapter,segment.ready.body.getLineEnd(line).coerceAtMost(chapters[segment.chapter].text.length))
    }
    private fun verticalHit(x:Float,y:Float):TextHit{
        val segment=verticalSegments().firstOrNull{item->
            val blockBottom=item.top+headingHeight+item.ready.body.height+chapterGap
            y>=item.top&&y<blockBottom
        }?:verticalSegments().lastOrNull()?:return TextHit(chapter,0)
        val bodyY=(y-(segment.top+headingHeight)).toInt()
        if(bodyY<=0)return TextHit(segment.chapter,0)
        val l=segment.ready.body;val line=l.getLineForVertical(bodyY.coerceIn(0,(l.height-1).coerceAtLeast(0)))
        return TextHit(segment.chapter,JustifiedTextCoordinates.offsetForHorizontal(l,line,x-contentLeft))
    }
    private fun prepareAdjacentChapters(){
        if(width<=0||height<=0||chapters.isEmpty())return
        val source=chapters;val format=style.copy();val w=width;val h=height
        val signature=chapterLayoutSignature(format,w,h);val generation=chapterLayoutGeneration
        val radius=if(verticalMode())2 else 1
        preparedChapters.keys.filter{it !in (chapter-radius)..(chapter+radius)}.forEach{preparedChapters.remove(it)}
        for(index in (1..radius).flatMap{listOf(chapter+it,chapter-it)}){
            if(index !in source.indices||index in preparingChapters)continue
            if(preparedChapters[index]?.let{it.source===source&&it.signature==signature}==true)continue
            preparingChapters.add(index)
            chapterLayoutExecutor.execute{
                val ready=runCatching{
                    if(generation==chapterLayoutGeneration)buildChapter(index,source,format,w,h,signature)else null
                }.getOrNull()
                post{
                    preparingChapters.remove(index)
                    if(ready!=null&&generation==chapterLayoutGeneration&&source===chapters&&
                        signature==chapterLayoutSignature(style,width,height))preparedChapters[index]=ready
                }
            }
        }
    }
    private fun makeLayout(source:String,format:ReadingStyle,w:Int,title:String=""):StaticLayout {
        val textPaint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{
            textSize=format.size*this@ReadingView.density
            typeface=Typeface.create(format.family,Typeface.NORMAL)
            isSubpixelText=true
            isLinearText=true
        }
        val decorated=SpannableString(source)
        // Keep every source offset intact while preventing indentation and
        // line-edge whitespace from consuming visible width. This also
        // handles CRLF input without changing the stored chapter text.
        ReaderDisplayText.edgeWhitespaceRanges(source).forEach { range ->
            if (range.start < range.end) decorated.setSpan(HiddenDisplayWhitespace(),range.start,range.end,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        var start=0
        for(paragraph in source.split('\n')){
            if(paragraph.trim()==title.trim()&&title.isNotBlank()&&source.take(start).isBlank()){
                val end=(start+paragraph.length+1).coerceAtMost(source.length)
                decorated.setSpan(android.text.style.ForegroundColorSpan(Color.TRANSPARENT),start,end,0)
                decorated.setSpan(object:LineHeightSpan{override fun chooseHeight(text:CharSequence,begin:Int,finish:Int,spanstartv:Int,v:Int,fm:Paint.FontMetricsInt){fm.top=0;fm.ascent=0;fm.descent=0;fm.bottom=0}},start,end,0)
            }else if(paragraph.isNotEmpty()){
                val end=start+paragraph.length
                // The source indentation is already hidden by the ranges
                // above; this is the single visual first-line indent.
                decorated.setSpan(LeadingMarginSpan.Standard((textPaint.textSize*2).toInt(),0),start,end,0)
                decorated.setSpan(object:LineHeightSpan{override fun chooseHeight(text:CharSequence,begin:Int,finish:Int,spanstartv:Int,v:Int,fm:Paint.FontMetricsInt){if(finish>=end){val extra=(format.paragraph*density).toInt();fm.descent+=extra;fm.bottom+=extra}}},start,end,0)
            }else if(start<source.length)decorated.setSpan(object:LineHeightSpan{override fun chooseHeight(text:CharSequence,begin:Int,finish:Int,spanstartv:Int,v:Int,fm:Paint.FontMetricsInt){fm.top=0;fm.ascent=0;fm.descent=0;fm.bottom=0}},start,start+1,0)
            start+=paragraph.length+1
        }
        val metrics=horizontalMetrics(w,format)
        val builder=StaticLayout.Builder.obtain(decorated,0,decorated.length,textPaint,metrics.width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f,format.line)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
        // Inter-character justification is available on Android 15/API 35,
        // which is the compile/target level. Older devices retain natural
        // ragged-right lines rather than receiving a risky custom renderer.
        if(Build.VERSION.SDK_INT>=35)builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_CHARACTER)
        return builder.build()
    }
    private fun makeHeadingLayout(title:String,format:ReadingStyle,w:Int):StaticLayout {
        val hp=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color=if(format.night)Color.rgb(180,180,173)else format.ink
            textSize=format.size*1.22f*density
            typeface=Typeface.create(format.family,Typeface.BOLD)
        }
        return StaticLayout.Builder.obtain(title,0,title.length,hp,horizontalMetrics(w,format).width)
            .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
    }
    private fun glyphBottom(l:StaticLayout,line:Int)=l.getLineBaseline(line)+l.paint.fontMetricsInt.descent
    private fun pageLines(l:StaticLayout,format:ReadingStyle,h:Int):List<Int>{
        val result=mutableListOf(0);var first=0
        val gaps=verticalGaps(format,h)
        val normal=(h-gaps.first-gaps.second).toInt().coerceAtLeast(100)
        val opening=(normal-(format.size*2.6f+24f)*density).toInt().coerceAtLeast(100)
        for(line in 1 until l.lineCount){if(glyphBottom(l,line)-l.getLineTop(first)>if(first==0)opening else normal){result.add(line);first=line}}
        return result
    }
    private fun countBookPages(){
        val format=style.copy();val signature="${width}:${height}:${format.size}:${format.line}:${format.paragraph}:${format.margin}:${format.family}:${format.mode}:${format.topGap}:${format.bottomGap}"
        if(paginationSource===chapters&&paginationStyle==signature)return
        paginationSource=chapters;paginationStyle=signature;bookPageCounts=null
        val generation=++paginationGeneration;val source=chapters;val w=width;val h=height
        paginationExecutor.execute{
            val counts=IntArray(source.size)
            for(i in source.indices){if(generation!=paginationGeneration)return@execute;counts[i]=pageLines(makeLayout(source[i].text,format,w,source[i].title),format,h).size}
            post{if(generation==paginationGeneration){bookPageCounts=counts;pagePrefix=IntArray(counts.size+1);for(i in counts.indices)pagePrefix[i+1]=pagePrefix[i]+counts[i];invalidate()}}
        }
    }
    override fun onDetachedFromWindow(){
        paginationGeneration++
        paginationSource=null
        invalidatePreparedChapters()
        val activeDrag=dragAnimator;dragAnimator=null;activeDrag?.cancel()
        val activePage=pageAnimator;pageAnimator=null;activePage?.cancel()
        clearDragBitmaps()
        releasePageBitmap(oldBitmap);oldBitmap=null
        releasePageBitmap(incomingBitmap);incomingBitmap=null
        clearCachedPage()
        removeCallbacks(verticalFling)
        verticalScroller.forceFinished(true)
        Choreographer.getInstance().removeFrameCallback(selectionAutoScroll)
        selectionAutoScrollDirection=0;selectionAutoScrollFrame=0L
        velocityTracker?.recycle();velocityTracker=null
        while(pageBitmapPool.isNotEmpty())pageBitmapPool.removeFirst().recycle()
        super.onDetachedFromWindow()
    }
    private fun installChapter(ready:PreparedChapter,offset:Int){
        chapter=ready.index
        layout=ready.body
        headingLayout=ready.heading
        pages.clear();pages.addAll(ready.starts)
        val line=ready.body.getLineForOffset(offset.coerceIn(0,chapters[chapter].text.length))
        page=(pages.indexOfLast { it<=line }).coerceAtLeast(0)
        pendingOffset=offset
        scrollYText=(ready.body.getLineTop(line).toFloat()+if(verticalMode()&&offset>0)headingHeight else 0f)
            .coerceAtMost(max(0f,ready.body.height+headingHeight-textHeight))
    }
    fun reflow() {
        if(width <= 0 || chapters.isEmpty()) return
        clearCachedPage()
        paint.textSize=style.size*density;paint.color=ink;paint.typeface=Typeface.create(style.family,Typeface.NORMAL)
        installChapter(preparedChapter(chapter),pendingOffset)
        countBookPages();publish();invalidate()
        if(!previewingPage)scheduleCurrentPageSnapshot()
        if(!previewingPage)prepareAdjacentChapters()
    }
    fun applyStyle() { pendingOffset=position().offset;invalidatePreparedChapters();reflow() }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(oldw>0||oldh>0)interruptPageTransition()
        invalidatePreparedChapters()
        clearPageBitmapPool()
        pendingOffset=position().offset
        reflow()
    }
    private fun startY(): Int = if(verticalMode()) (scrollYText-headingHeight).toInt().coerceAtLeast(0) else layout?.getLineTop(pages.getOrElse(page){0}) ?: 0
    fun position(): Position {
        if(verticalMode())return verticalPosition()
        val l=layout ?: return Position(chapter,pendingOffset)
        return Position(chapter,l.getLineStart(l.getLineForVertical(startY())))
    }
    fun boundary(): Position {
        if(verticalMode())return verticalBoundary()
        val l=layout ?: return position()
        val limit=startY()+textHeight
        var line=l.getLineForVertical(limit.coerceAtMost(l.height-1))
        while(line>0 && glyphBottom(l,line)>limit) line--
        if(page<pages.lastIndex) line=min(line,pages[page+1]-1)
        return Position(chapter,l.getLineEnd(line).coerceAtMost(chapters[chapter].text.length))
    }
    private fun publish() { pendingOffset=position().offset;if(!previewingPage)onPosition(position(),boundary()) }
    fun isChapterEnd():Boolean { val end=boundary();return end.chapter>chapter||end.offset >= (chapters.getOrNull(chapter)?.text?.length ?: 0) }
    private var automaticTransition=0
    fun setAutomaticVertical(enabled:Boolean){
        if(automaticVertical==enabled)return
        val anchor=position();automaticVertical=enabled
        chapter=anchor.chapter;pendingOffset=anchor.offset;invalidatePreparedChapters();reflow()
    }
    fun automaticVerticalStep(pixels:Float):Boolean{
        if(!verticalMode())return false
        val moved=scrollVerticalBy(pixels)
        if(moved){publish();postInvalidateOnAnimation()}
        return moved
    }
    fun verticalViewportHeight()=(bottom-baseTop).coerceAtLeast(1f)
    fun hasPage(direction:Int):Boolean {
        if(chapters.isEmpty())return false
        val last = if(verticalMode()) chapter==chapters.lastIndex&&scrollYText>=max(0f,verticalBlockHeight(chapter)-(bottom-baseTop)) else page==pages.lastIndex
        val first = if(verticalMode()) chapter==0&&scrollYText<=0 else page==0
        return !((direction>0&&last&&chapter==chapters.lastIndex)||(direction<0&&first&&chapter==0))
    }
    /** automaticAxis: 0 follows the reading style, 1 slides horizontally, 2 slides vertically. */
    fun turn(direction:Int,automaticAxis:Int=0):Boolean {
        if(chapters.isEmpty() || progress<1f) return false
        if(verticalMode()&&automaticAxis!=1){
            val moved=scrollVerticalBy(direction*verticalViewportHeight())
            if(moved){selectionChapter=-1;selectionStart=-1;selectionEnd=-1;publish();postInvalidateOnAnimation();if(automaticAxis==0)onManualPageTurn()}
            return moved
        }
        val animatedMode=automaticAxis!=0 || style.mode !in listOf("无动画","上下")
        val snapshot = if(animatedMode) (takeCachedCurrentPage() ?: captureCurrentPage()) else null
        if(animatedMode && snapshot==null){
            // A low-memory device should still be able to turn the page even
            // when a temporary animation surface cannot be allocated.
            if(movePage(direction)){selectionStart=-1;publish();invalidate();if(automaticAxis==0)onManualPageTurn();return true}
            return false
        }
        if(!movePage(direction)){releasePageBitmap(snapshot);return false}
        selectionStart=-1;publish();if(automaticAxis==0)onManualPageTurn()
        if(snapshot!=null) {
            oldBitmap=snapshot;animationDirection=direction;automaticTransition=automaticAxis
            // Capture the destination once before starting the animation.
            // ValueAnimator is already display-vsync driven; the expensive
            // StaticLayout draw must happen before it starts so every frame
            // only composites bitmap surfaces.
            incomingBitmap=captureCurrentPage()
            if(incomingBitmap==null){
                releasePageBitmap(oldBitmap);oldBitmap=null;automaticTransition=0;invalidate();scheduleCurrentPageSnapshot();return true
            }
            animatePage(0f,1f,false)
        } else invalidate()
        return true
    }
    private fun movePage(direction:Int):Boolean {
        if(verticalMode())return scrollVerticalBy(direction*verticalViewportHeight())
        val last = page==pages.lastIndex
        val first = page==0
        if((direction>0&&last&&chapter==chapters.lastIndex)||(direction<0&&first&&chapter==0)) return false
        if(direction>0) {
            if(last) { chapter++;pendingOffset=0;reflow() }
            else page++
        } else {
            if(first) { chapter--;pendingOffset=chapters[chapter].text.length;reflow() }
            else page--
        }
        return true
    }

    /** Return an adjacent page without changing the current reading state. */
    private fun pageTarget(direction:Int):PageTarget? {
        if(verticalMode()||chapters.isEmpty())return null
        if(direction>0){
            if(page<pages.lastIndex){
                val targetPage=page+1
                val offset=layout?.getLineStart(pages[targetPage])?:return null
                return PageTarget(Position(chapter,offset),chapter,targetPage)
            }
            if(chapter<chapters.lastIndex)return PageTarget(Position(chapter+1,0),chapter+1,-1)
        }else{
            if(page>0){
                val targetPage=page-1
                val offset=layout?.getLineStart(pages[targetPage])?:return null
                return PageTarget(Position(chapter,offset),chapter,targetPage)
            }
            if(chapter>0)return PageTarget(Position(chapter-1,chapters[chapter-1].text.length),chapter-1,-1)
        }
        return null
    }

    private fun obtainPageBitmap(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val reusable = pageBitmapPool.iterator().asSequence().firstOrNull { !it.isRecycled && it.width == width && it.height == height }
        if (reusable != null) {
            pageBitmapPool.remove(reusable)
            return reusable
        }
        return runCatching { Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888) }.getOrNull()
    }

    private fun releasePageBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        if (bitmap.width == width && bitmap.height == height && pageBitmapPool.size < maxPooledPageBitmaps) {
            pageBitmapPool.addLast(bitmap)
        } else {
            bitmap.recycle()
        }
    }

    private fun clearPageBitmapPool(){
        while(pageBitmapPool.isNotEmpty())pageBitmapPool.removeFirst().recycle()
    }

    private fun captureCurrentPage():Bitmap? {
        val bitmap=obtainPageBitmap() ?: return null
        return try {
            drawPage(Canvas(bitmap))
            bitmap
        } catch (_:Throwable) {
            releasePageBitmap(bitmap)
            null
        }
    }

    /** Capture a neighbour without rebuilding the active chapter. */
    private fun capturePageTarget(target:PageTarget):Bitmap? {
        if(target.chapter==chapter&&target.page>=0){
            val savedPage=page;val savedOffset=pendingOffset;val savedScroll=scrollYText
            page=target.page;pendingOffset=target.position.offset
            val bitmap=captureCurrentPage()
            page=savedPage;pendingOffset=savedOffset;scrollYText=savedScroll
            return bitmap
        }
        val savedChapter=chapter;val savedPage=page;val savedOffset=pendingOffset;val savedScroll=scrollYText
        val savedLayout=layout;val savedHeading=headingLayout;val savedPages=pages.toList()
        val savedPreview=previewingPage
        previewingPage=true
        return try {
            installChapter(preparedChapter(target.chapter),target.position.offset)
            captureCurrentPage()
        } finally {
            chapter=savedChapter;page=savedPage;pendingOffset=savedOffset;scrollYText=savedScroll
            layout=savedLayout;headingLayout=savedHeading;pages.clear();pages.addAll(savedPages)
            previewingPage=savedPreview
        }
    }

    private fun clearDragBitmaps(){
        releasePageBitmap(dragOriginBitmap);dragOriginBitmap=null
        releasePageBitmap(dragPreviousBitmap);dragPreviousBitmap=null
        releasePageBitmap(dragNextBitmap);dragNextBitmap=null
        dragDistance=0f;dragTouchY=height*.72f;dragCurlAnchorY=height*.72f;dragCurlMinDeltaY=0f
        dragCurlMode=IBookCurlRenderer.CornerMode.Landscape;dragCurlInitialized=false
        dragDirection=0;dragTarget=null;dragSettling=false
    }

    private fun drawContinuousDrag(canvas:Canvas){
        val origin=dragOriginBitmap?:return
        canvas.drawColor(paper)
        val dx=dragDistance
        if(style.mode=="仿真"){
            val direction=if(dx<0f)1 else -1
            val incoming=if(direction>0)dragNextBitmap else dragPreviousBitmap
            drawPageCurl(canvas,origin,incoming,direction,
                (abs(dx)/width.coerceAtLeast(1)).coerceIn(0f,1f),dragTouchY,
                dragCurlAnchorY,dragCurlMode,dragCurlMinDeltaY,!dragCurlInitialized)
            dragCurlInitialized=true
        }else if(style.mode=="覆盖"){
            canvas.drawBitmap(origin,0f,0f,null)
            if(dx<0f)dragNextBitmap?.let{canvas.drawBitmap(it,width+dx,0f,null)}
            else if(dx>0f)dragPreviousBitmap?.let{canvas.drawBitmap(it,-width+dx,0f,null)}
        }else{
            canvas.drawBitmap(origin,dx,0f,null)
            if(dx<0f)dragNextBitmap?.let{canvas.drawBitmap(it,width+dx,0f,null)}
            else if(dx>0f)dragPreviousBitmap?.let{canvas.drawBitmap(it,-width+dx,0f,null)}
        }
    }

    private fun drawSimulationTransition(canvas:Canvas,old:Bitmap,incoming:Bitmap?,direction:Int,amount:Float,touchY:Float=height*.72f){
        val mode=when{
            touchY<height*.34f->IBookCurlRenderer.CornerMode.TopRightCorner
            touchY>height*.66f->IBookCurlRenderer.CornerMode.BottomRightCorner
            else->IBookCurlRenderer.CornerMode.Landscape
        }
        val anchor=when(mode){
            IBookCurlRenderer.CornerMode.TopRightCorner->height*.08f
            IBookCurlRenderer.CornerMode.BottomRightCorner->height*.92f
            IBookCurlRenderer.CornerMode.Landscape->height*.5f
        }
        drawPageCurl(canvas,old,incoming,direction,amount,touchY,anchor,mode,
            abs(touchY-anchor).coerceAtLeast(height*.08f),true)
    }

    private fun drawPageCurl(canvas:Canvas,old:Bitmap,incoming:Bitmap?,direction:Int,amount:Float,
        touchY:Float,anchorY:Float,mode:IBookCurlRenderer.CornerMode,minDeltaY:Float,restart:Boolean){
        val next=incoming
        val value=amount.coerceIn(0f,1f)
        if(next==null){canvas.drawColor(paper);if(value<.999f)canvas.drawBitmap(old,0f,0f,null);return}
        if(value<=.001f){canvas.drawBitmap(old,0f,0f,null);return}
        if(value>=.999f){canvas.drawBitmap(next,0f,0f,null);return}
        canvas.save()
        if(direction<0){canvas.translate(width.toFloat(),0f);canvas.scale(-1f,1f)}
        if(pageCurlWidth!=width||pageCurlHeight!=height){
            pageCurlWidth=width;pageCurlHeight=height
            pageCurlRenderer.setPageSize(width.toFloat(),height.toFloat())
        }
        pageCurlRenderer.setBackTintColor(paper)
        pageCurlRenderer.setMirrorTexture(direction<0)
        pageCurlRenderer.setPages(old,next)
        if(restart)pageCurlRenderer.initControlPosition(width.toFloat(),anchorY.coerceIn(1f,height-1f),mode)
        // The mature renderer completes a page at x=-width. Mapping the
        // normalized gesture across [width,-width] keeps the last frames on
        // the same curve instead of replacing them with a flat slide.
        val stableTouchY=when(mode){
            IBookCurlRenderer.CornerMode.TopRightCorner->max(touchY,anchorY+minDeltaY)
            IBookCurlRenderer.CornerMode.BottomRightCorner->min(touchY,anchorY-minDeltaY)
            IBookCurlRenderer.CornerMode.Landscape->anchorY
        }.coerceIn(1f,height-1f)
        pageCurlRenderer.updateTouchPosition(width*(1f-2f*value),stableTouchY)
        pageCurlRenderer.render(canvas)
        canvas.restore()
    }

    private fun finishPageDrag(commit:Boolean){
        if(!draggingPage||dragSettling)return
        val direction=if(dragDistance<0f)1 else -1
        val target=if(commit)dragTarget else null
        val shouldCommit=target!=null
        // Commit the logical reading position as soon as the gesture is
        // accepted.  The animation below is only a visual transition.  If a
        // second finger-down arrives before it finishes, onTouchEvent cancels
        // the animator; keeping the old position until animation end would
        // make that reverse gesture start from the page before this turn.
        target?.let(::commitPageTarget)
        if(shouldCommit)onManualPageTurn()
        val start=dragDistance
        val end=if(shouldCommit){if(direction>0)-width.toFloat() else width.toFloat()}else 0f
        dragSettling=true
        val active=dragAnimator;dragAnimator=null;active?.cancel()
        dragAnimator=ValueAnimator.ofFloat(start,end).apply {
            duration=((110f+200f*(abs(end-start)/width.coerceAtLeast(1)).coerceIn(0f,1f)) /
                animationSpeed.coerceIn(.6f,1.4f)).toLong()
            interpolator=DecelerateInterpolator(1.15f)
            addUpdateListener { dragDistance=it.animatedValue as Float;postInvalidateOnAnimation() }
            addListener(object:AnimatorListenerAdapter(){
                override fun onAnimationEnd(animation:Animator){
                    if(dragAnimator!==animation)return
                    draggingPage=false;swipeTracking=false;dragAnimator=null
                    clearDragBitmaps();publish();postInvalidateOnAnimation();scheduleCurrentPageSnapshot()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            })
            start()
        }
    }

    /** Apply a committed turn; adjacent chapters reuse their prepared layout. */
    private fun commitPageTarget(target:PageTarget){
        if(target.chapter==chapter&&target.page>=0){
            page=target.page
            pendingOffset=target.position.offset
            normalizePageAnchor()
            publish()
            postInvalidateOnAnimation()
        }else{
            go(target.position,false)
        }
    }

    /**
     * Reconcile the logical page with its exact layout anchor after a settle
     * is interrupted. This prevents a reverse gesture from inheriting a stale
     * line offset and choosing the page before the intended adjacent page.
     */
    private fun normalizePageAnchor(){
        val l=layout ?: return
        if(verticalMode()||pages.isEmpty())return
        page=page.coerceIn(0,pages.lastIndex)
        pendingOffset=l.getLineStart(pages[page]).coerceIn(0,l.text.length)
    }

    private fun animatePage(from:Float,to:Float,restore:Boolean) {
        progress=from
        pageAnimator?.cancel()
        pageAnimator=ValueAnimator.ofFloat(from,to).apply {
            duration=((120f+200f*abs(to-from).coerceIn(0f,1f)) /
                animationSpeed.coerceIn(.6f,1.4f)).toLong()
            interpolator=DecelerateInterpolator(1.35f)
            addUpdateListener {
                progress=it.animatedValue as Float
                if(it.animatedFraction>=1f) {
                    if(restore)go(dragOrigin)
                    releasePageBitmap(oldBitmap);oldBitmap=null;progress=1f;previewingPage=false;publish();pageAnimator=null
                    releasePageBitmap(incomingBitmap);incomingBitmap=null
                    automaticTransition=0
                    scheduleCurrentPageSnapshot()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    /** Start a drag; the opposite neighbour is captured only if it is shown. */
    private fun beginPageDrag(direction:Int,initialDy:Float):Boolean {
        if(draggingPage||style.mode in listOf("无动画","上下")) return draggingPage
        dragOrigin=position()
        val origin=takeCachedCurrentPage() ?: captureCurrentPage() ?: return false
        dragOriginBitmap=origin
        dragPreviousBitmap=null
        dragNextBitmap=null
        dragDistance=0f
        dragTouchY=(downY+initialDy).coerceIn(1f,height-1f)
        dragCurlAnchorY=downY.coerceIn(1f,height-1f)
        dragCurlMode=when{
            initialDy>6*density->IBookCurlRenderer.CornerMode.TopRightCorner
            initialDy< -6*density->IBookCurlRenderer.CornerMode.BottomRightCorner
            else->IBookCurlRenderer.CornerMode.Landscape
        }
        dragCurlMinDeltaY=if(dragCurlMode==IBookCurlRenderer.CornerMode.Landscape)0f
            else abs(initialDy).coerceIn(24*density,height*.28f)
        dragCurlInitialized=false
        dragDirection=0
        dragTarget=null
        draggingPage=true
        dragSettling=false
        parent?.requestDisallowInterceptTouchEvent(true)
        showDragSide(direction)
        postInvalidateOnAnimation()
        return true
    }

    private fun showDragSide(direction:Int):Boolean {
        val target=pageTarget(direction) ?: return false
        val existing=if(direction>0)dragNextBitmap else dragPreviousBitmap
        val adjacent=existing ?: capturePageTarget(target) ?: return false
        if(direction>0)dragNextBitmap=adjacent else dragPreviousBitmap=adjacent
        dragDirection=direction
        dragTarget=target
        return true
    }

    private fun cancelGestureDetector(event:MotionEvent) {
        val cancel=MotionEvent.obtain(event)
        cancel.action=MotionEvent.ACTION_CANCEL
        gestures.onTouchEvent(cancel)
        cancel.recycle()
    }

    /** Stop a visual transition without changing the already committed page. */
    private fun interruptPageTransition():Boolean {
        val interrupted=dragAnimator!=null||pageAnimator!=null||draggingPage
        val activeDrag=dragAnimator;dragAnimator=null;activeDrag?.cancel()
        val activePage=pageAnimator;pageAnimator=null;activePage?.cancel()
        draggingPage=false;swipeTracking=false;dragSettling=false
        clearDragBitmaps()
        releasePageBitmap(oldBitmap);oldBitmap=null
        releasePageBitmap(incomingBitmap);incomingBitmap=null
        progress=1f;previewingPage=false;automaticTransition=0
        normalizePageAnchor()
        return interrupted
    }

    private fun updatePageDrag(dx:Float,y:Float=dragTouchY) {
        if(!draggingPage) return
        dragTouchY=y.coerceIn(0f,height.toFloat())
        val direction=when { dx<0f -> 1;dx>0f -> -1;else -> 0 }
        if(direction==0){dragDistance=0f;dragDirection=0;dragTarget=null}
        else if((direction==dragDirection&&dragTarget!=null)||showDragSide(direction))
            dragDistance=dx.coerceIn(-width.toFloat(),width.toFloat())
        else {dragDistance=0f;dragDirection=0;dragTarget=null}
        postInvalidateOnAnimation()
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        if(draggingPage&&dragOriginBitmap!=null){drawContinuousDrag(canvas);return}
        val old=oldBitmap
        if(old==null || progress>=1f) { drawPage(canvas);return }
        val incoming=incomingBitmap
        if(automaticTransition==2){
            val shift=height*progress*animationDirection
            if(incoming!=null)canvas.drawBitmap(incoming,0f,height*animationDirection-shift,null)
            else {canvas.save();canvas.translate(0f,height*animationDirection-shift);drawPage(canvas);canvas.restore()}
            canvas.drawBitmap(old,0f,-shift,null)
            return
        }
        val shift=width*progress*animationDirection
        if(automaticTransition==1||style.mode=="平移") {
            if(incoming!=null)canvas.drawBitmap(incoming,width*animationDirection-shift,0f,null)
            else {canvas.save();canvas.translate(width*animationDirection-shift,0f);drawPage(canvas);canvas.restore()}
            canvas.drawBitmap(old,-shift,0f,null)
        } else if(style.mode=="仿真") drawSimulationTransition(canvas,old,incoming,animationDirection,progress)
        else { if(incoming!=null)canvas.drawBitmap(incoming,0f,0f,null) else drawPage(canvas);canvas.drawBitmap(old,-shift,0f,null) }
    }
    private fun drawPage(canvas:Canvas) {
        canvas.drawColor(paper)
        backgroundImage?.let { uiPaint.alpha=if(style.night) 35 else 70;canvas.drawBitmap(it,null,Rect(0,0,width,height),uiPaint);uiPaint.alpha=255 }
        if(style.warmth>0) canvas.drawColor(Color.argb(style.warmth,210,150,50))
        if(verticalMode()){drawVerticalPage(canvas);return}
        val l=layout ?: return
        paint.color=ink;l.paint.color=ink
        uiPaint.color=ink;uiPaint.alpha=100;uiPaint.textSize=12*density
        val title=chapters.getOrNull(chapter)?.title.orEmpty()
        canvas.drawText((if(page==0)bookTitle.ifBlank{title}else title).take(27),contentLeft,34*density,uiPaint)
        if(pageBookmarks().isNotEmpty()){
            uiPaint.color=if(style.night)0xffd8bb75.toInt()else 0xffb78131.toInt();uiPaint.alpha=255
            val x=width-36*density;val ribbon=Path().apply{moveTo(x,0f);lineTo(x+18*density,0f);lineTo(x+18*density,48*density);lineTo(x+9*density,40*density);lineTo(x,48*density);close()};canvas.drawPath(ribbon,uiPaint);uiPaint.color=ink;uiPaint.alpha=100
        }
        if(chapter>0&&page==0) canvas.drawText("上章回顾",width-83*density,52*density,uiPaint)
        if((page==0&&style.mode!="上下")||(style.mode=="上下"&&scrollYText<headingHeight)){
            headingLayout?.let { heading ->
                canvas.save();canvas.clipRect(0f,baseTop,width.toFloat(),bottom);canvas.translate(contentLeft,baseTop+8*density-if(style.mode=="上下")scrollYText else 0f);heading.draw(canvas);canvas.restore()
            }
        }
        val lastLine=pages.getOrNull(page+1)?.minus(1) ?: (l.lineCount-1)
        val visibleBottom=if(style.mode=="上下")bottom else min(bottom,top+l.getLineBottom(lastLine)-startY())
        canvas.save();canvas.clipRect(0f,top,width.toFloat(),visibleBottom)
        canvas.translate(contentLeft,top-startY())
        for(note in highlights+listOfNotNull(locatedText)) {
            if(note.chapter==chapter&&!note.changed&&note.end>note.start&&note.end<=l.text.length) {
                uiPaint.color=Color.argb(if(style.night)45 else 55,196,163,70)
                rangeBoxes(l,note.start,note.end).forEach{canvas.drawRect(it.rect,uiPaint)}
            }
        }
        if(selectionChapter==chapter&&selectionStart>=0&&selectionEnd>=0) {
            uiPaint.color=Color.argb(if(style.night)65 else 48,196,151,60)
            selectionRangeOnScreen()?.let{range->rangeBoxes(l,range.first,range.second).forEach{canvas.drawRect(it.rect,uiPaint)}}
        }
        l.draw(canvas);canvas.restore()
        if(hasSelection()){
            uiPaint.color=if(style.night)0xffcfb574.toInt()else 0xffb68a3b.toInt();uiPaint.strokeWidth=1.5f*density
            for(start in listOf(true,false)){
                val geometry=handleGeometry(start);if(geometry!=null&&geometry.bottom>=top-8*density&&geometry.top<=bottom+8*density){
                    canvas.drawLine(geometry.x,geometry.top,geometry.x,geometry.bottom,uiPaint)
                    canvas.drawCircle(geometry.x,geometry.knobY,5*density,uiPaint)
                }
            }
        }
        uiPaint.color=ink;uiPaint.alpha=100;uiPaint.textSize=12*density
        val pageInChapter=if(style.mode=="上下")(pages.indexOfLast{it<=(layout?.getLineForVertical(startY())?:0)}).coerceAtLeast(0)else page
        val progressText=bookPageCounts?.let{counts->"${pagePrefix[chapter]+pageInChapter+1}/${pagePrefix.last()}"}?:"正在计算页数…"
        canvas.drawText(progressText,contentLeft,height-22*density,uiPaint)
        val clock=footerClock()
        canvas.drawText(clock,width-103*density,height-22*density,uiPaint)
        val battery=footerBattery()
        uiPaint.style=Paint.Style.STROKE;uiPaint.strokeWidth=density
        val batteryBox=RectF(width-48*density,height-33*density,width-27*density,height-23*density)
        canvas.drawRoundRect(batteryBox,2*density,2*density,uiPaint)
        uiPaint.style=Paint.Style.FILL
        canvas.drawRect(batteryBox.left+2*density,batteryBox.top+2*density,batteryBox.left+2*density+17*density*battery/100,batteryBox.bottom-2*density,uiPaint)
        canvas.drawRect(batteryBox.right,batteryBox.top+3*density,batteryBox.right+2*density,batteryBox.bottom-3*density,uiPaint)
        if(isChapterEnd()) { uiPaint.textSize=13*density;canvas.drawText("本章总结  ·  与说书人聊聊",contentLeft,height-40*density,uiPaint) }
        uiPaint.alpha=255
    }
    private fun drawVerticalPage(canvas:Canvas){
        val segments=verticalSegments();if(segments.isEmpty())return
        paint.color=ink;uiPaint.color=ink;uiPaint.alpha=100;uiPaint.textSize=12*density
        val current=verticalPosition();val currentTitle=chapters.getOrNull(current.chapter)?.title.orEmpty()
        canvas.drawText(currentTitle.take(27),contentLeft,34*density,uiPaint)
        canvas.save();canvas.clipRect(0f,baseTop,width.toFloat(),bottom)
        for(segment in segments){
            val ready=segment.ready;ready.body.paint.color=ink;ready.heading.paint.color=ink
            canvas.save();canvas.translate(contentLeft,segment.top+8*density);ready.heading.draw(canvas);canvas.restore()
            val bodyTop=segment.top+headingHeight
            canvas.save();canvas.translate(contentLeft,bodyTop)
            val boxes=highlightBoxes[segment.chapter]?.takeIf{it.first===ready.body}?.second?:
                (highlights+listOfNotNull(locatedText)).filter{it.chapter==segment.chapter&&!it.changed&&it.end>it.start&&it.end<=ready.body.text.length}
                    .flatMap{note->rangeBoxes(ready.body,note.start,note.end).map{it.rect}}.also{rects->
                        highlightBoxes.keys.filter{it !in (chapter-2)..(chapter+2)}.forEach{highlightBoxes.remove(it)}
                        highlightBoxes[segment.chapter]=ready.body to rects
                    }
            uiPaint.color=Color.argb(if(style.night)45 else 55,196,163,70)
            for(rect in boxes)if(rect.bottom+bodyTop>=baseTop&&rect.top+bodyTop<=bottom)canvas.drawRect(rect,uiPaint)
            if(selectionChapter==segment.chapter&&selectionEnd>selectionStart){
                uiPaint.color=Color.argb(if(style.night)65 else 48,196,151,60)
                rangeBoxes(ready.body,selectionStart.coerceIn(0,ready.body.text.length),selectionEnd.coerceIn(0,ready.body.text.length)).forEach{canvas.drawRect(it.rect,uiPaint)}
            }
            ready.body.draw(canvas);canvas.restore()
        }
        canvas.restore()
        if(hasSelection()){
            uiPaint.color=if(style.night)0xffcfb574.toInt()else 0xffb68a3b.toInt();uiPaint.strokeWidth=1.5f*density
            for(start in listOf(true,false)){val geometry=handleGeometry(start);if(geometry!=null&&geometry.bottom>=baseTop-8*density&&geometry.top<=bottom+8*density){canvas.drawLine(geometry.x,geometry.top,geometry.x,geometry.bottom,uiPaint);canvas.drawCircle(geometry.x,geometry.knobY,5*density,uiPaint)}}
        }
        uiPaint.color=ink;uiPaint.alpha=100;uiPaint.textSize=12*density
        val ready=preparedChapter(current.chapter);val line=ready.body.getLineForOffset(current.offset.coerceIn(0,ready.body.text.length));val pageInChapter=(ready.starts.indexOfLast{it<=line}).coerceAtLeast(0)
        val progressText=bookPageCounts?.let{counts->"${pagePrefix[current.chapter]+pageInChapter+1}/${pagePrefix.last()}"}?:"正在计算页数…"
        canvas.drawText(progressText,contentLeft,height-22*density,uiPaint)
        val clock=footerClock();canvas.drawText(clock,width-103*density,height-22*density,uiPaint)
        val battery=footerBattery()
        uiPaint.style=Paint.Style.STROKE;uiPaint.strokeWidth=density;val batteryBox=RectF(width-48*density,height-33*density,width-27*density,height-23*density);canvas.drawRoundRect(batteryBox,2*density,2*density,uiPaint)
        uiPaint.style=Paint.Style.FILL;canvas.drawRect(batteryBox.left+2*density,batteryBox.top+2*density,batteryBox.left+2*density+17*density*battery/100,batteryBox.bottom-2*density,uiPaint);canvas.drawRect(batteryBox.right,batteryBox.top+3*density,batteryBox.right+2*density,batteryBox.bottom-3*density,uiPaint);uiPaint.alpha=255
    }
    private fun offsetAt(x:Float,y:Float):Int {
        val l=layout ?: return 0
        val line=l.getLineForVertical((y-top+startY()).toInt().coerceIn(0,l.height))
        return JustifiedTextCoordinates.offsetForHorizontal(l,line,x-contentLeft)
    }
    private fun updateSelectionFromPointer(){
        if(selectionHandle==0||selectionChapter !in chapters.indices)return
        val hit=if(verticalMode())verticalHit(selectionPointerX,selectionPointerY)
            else TextHit(chapter,offsetAt(selectionPointerX,selectionPointerY))
        if(hit.chapter!=selectionChapter)return
        val at=TextSelection.boundary(chapters[selectionChapter].text,hit.offset)
        if(selectionHandle==1&&at<selectionEnd)selectionStart=at
        else if(selectionHandle==2&&at>selectionStart)selectionEnd=at
    }
    private fun moveSelectionPage(direction:Int):Boolean{
        val target=pageTarget(direction)?:return false
        if(target.chapter!=selectionChapter)return false
        commitPageTarget(target)
        val text=chapters[selectionChapter].text
        val targetOffset=if(direction<0)position().offset else boundary().offset
        val at=TextSelection.boundary(text,targetOffset)
        if(selectionHandle==1)selectionStart=min(at,(selectionEnd-1).coerceAtLeast(0))
        else if(selectionHandle==2)selectionEnd=max(at,(selectionStart+1).coerceAtMost(text.length))
        onManualPageTurn()
        return true
    }
    private fun stopSelectionAutoScroll(){
        selectionAutoScrollDirection=0;selectionAutoScrollFrame=0L
        Choreographer.getInstance().removeFrameCallback(selectionAutoScroll)
    }
    fun clearSelection() {
        stopSelectionAutoScroll();selectionChapter=-1;selectionStart=-1;selectionEnd=-1;selectionHandle=0
        selectionEdgeLatch=0;onSelectionHidden();invalidate()
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.action==MotionEvent.ACTION_DOWN)onUserInteraction()
        if(selectionDismissGesture){
            if(event.action==MotionEvent.ACTION_UP||event.action==MotionEvent.ACTION_CANCEL){
                selectionDismissGesture=false;onUserInteractionFinished()
            }
            return true
        }
        if(event.action==MotionEvent.ACTION_DOWN&&hasSelection()){
            val a=handlePoint(true);val b=handlePoint(false)
            fun near(p:PointF)=kotlin.math.hypot(event.x-p.x,event.y-p.y)<30*density
            selectionHandle=if(near(a))1 else if(near(b))2 else 0
            if(selectionHandle==0){
                // A tap while text is selected has exactly one meaning:
                // dismiss the selection. Consume the whole gesture so its UP
                // cannot also open the reader menu or turn a page.
                clearSelection();selectionDismissGesture=true;return true
            }
            else {
                onSelectionHidden()
                selectionEdgeLatch=0;selectionPointerX=event.x;selectionPointerY=event.y
                stopSelectionAutoScroll();parent?.requestDisallowInterceptTouchEvent(true);return true
            }
        }
        if(selectionHandle!=0){
            if(event.action==MotionEvent.ACTION_MOVE){
                selectionPointerX=event.x;selectionPointerY=event.y
                if(verticalMode()){
                    val edge=52*density
                    val direction=when{event.y<top+edge->-1;event.y>bottom-edge->1;else->0}
                    updateSelectionFromPointer()
                    if(direction!=selectionAutoScrollDirection){
                        stopSelectionAutoScroll();selectionAutoScrollDirection=direction
                        if(direction!=0)Choreographer.getInstance().postFrameCallback(selectionAutoScroll)
                    }
                }else{
                    // The page-turn corners live outside the text column. A
                    // handle placed on the first or last visible glyph does
                    // not turn a page. Holding a corner fires once; leaving
                    // the corner rearms it, so the same handle can reverse.
                    val outside=4*density
                    val previousZone=event.x<width*.28f&&event.y<height*.22f&&
                        (event.x<contentLeft-outside||event.y<top-outside)
                    val nextZone=event.x>width*.72f&&event.y>height*.78f&&
                        (event.x>contentRight+outside||event.y>bottom+outside)
                    val zone=if(previousZone)-1 else if(nextZone)1 else 0
                    var turned=false
                    if(zone==0)selectionEdgeLatch=0
                    else if(zone!=selectionEdgeLatch){
                        selectionEdgeLatch=zone
                        turned=moveSelectionPage(zone)
                    }
                    if(zone==0&&!turned)updateSelectionFromPointer()
                }
                postInvalidateOnAnimation()
            }
            if(event.action==MotionEvent.ACTION_UP||event.action==MotionEvent.ACTION_CANCEL){
                stopSelectionAutoScroll();selectionEdgeLatch=0;selectionHandle=0;parent?.requestDisallowInterceptTouchEvent(false)
                if(hasSelection())onSelect(selectionChapter,selectionStart,selectionEnd);onUserInteractionFinished()
            }
            return true
        }
        if(event.action==MotionEvent.ACTION_DOWN){
            // A new gesture may begin while the previous page is still
            // settling. Reconcile the logical anchor before accepting the
            // new pointer so an immediate reverse gesture starts from the
            // page that is actually visible instead of a stale neighbour.
            val interruptedTransition=interruptPageTransition()
            downX=event.x;downY=event.y;downTime=event.eventTime
            lastTouchX=event.x;lastTouchTime=event.eventTime;peakHorizontalVelocity=0f;peakHorizontalVelocityDirection=0f
            horizontalIntentDx=0f;horizontalIntentDy=0f
            velocityTracker?.recycle()
            velocityTracker=VelocityTracker.obtain().also{it.addMovement(event)}
            swipeTracking=false
            draggingPage=false
            horizontalGestureRejected=false
            touchMoved=interruptedTransition
            longPressSelection=false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        if(event.action==MotionEvent.ACTION_MOVE){
            velocityTracker?.addMovement(event)
            val dx=event.x-downX;val dy=event.y-downY
            val dt=event.eventTime-lastTouchTime
            if(dt>0L){
                val velocity=(event.x-lastTouchX)*1000f/dt
                if(abs(velocity)>peakHorizontalVelocity){peakHorizontalVelocity=abs(velocity);peakHorizontalVelocityDirection=if(velocity>0f)1f else -1f}
            }
            lastTouchX=event.x;lastTouchTime=event.eventTime
            if(kotlin.math.hypot(dx.toDouble(),dy.toDouble())>8*density||SwipePolicy.isHorizontal(dx,dy,density))touchMoved=true
            // A long press may have fired just before a slow swipe crossed
            // the touch slop. Once horizontal intent is visible, that
            // provisional selection must be discarded so the gesture turns
            // the page instead of drawing an underline.
            if(longPressSelection&&SwipePolicy.isHorizontal(dx,dy,density)){
                clearSelection()
                longPressSelection=false
            }
        }
        // Read the raw touch stream for page movement. GestureDetector waits
        // for its scroll callback and can drop diagonal samples; direct
        // handling keeps the page locked to the finger at display cadence.
        if(event.action==MotionEvent.ACTION_MOVE&&!verticalMode()&&style.mode!="无动画"&&selectionStart<0){
            val dx=event.x-downX;val dy=event.y-downY
            if(!swipeTracking&&SwipePolicy.isHorizontal(dx,dy,density)){
                horizontalIntentDx=dx;horizontalIntentDy=dy
                val direction=if(dx<0f)1 else -1
                cancelGestureDetector(event)
                swipeTracking=beginPageDrag(direction,dy)
                if(!swipeTracking)horizontalGestureRejected=true
            }
            if(swipeTracking){
                updatePageDrag(dx,event.y)
                return true
            }
        }
        if(!swipeTracking&&!horizontalGestureRejected) gestures.onTouchEvent(event)
        if(event.action==MotionEvent.ACTION_UP) {
            if(selectionStart>=0) { onSelect(selectionChapter,min(selectionStart,selectionEnd),max(selectionStart,selectionEnd));velocityTracker?.recycle();velocityTracker=null;onUserInteractionFinished();return true }
            velocityTracker?.addMovement(event)
            velocityTracker?.computeCurrentVelocity(1000)
            val trackedVelocity=velocityTracker?.xVelocity?:0f
            if(abs(trackedVelocity)>peakHorizontalVelocity){
                peakHorizontalVelocity=abs(trackedVelocity)
                peakHorizontalVelocityDirection=if(trackedVelocity>0f)1f else -1f
            }
            velocityTracker?.recycle();velocityTracker=null
            val releaseDt=event.eventTime-lastTouchTime
            if(releaseDt>0L){
                val velocity=(event.x-lastTouchX)*1000f/releaseDt
                if(abs(velocity)>peakHorizontalVelocity){peakHorizontalVelocity=abs(velocity);peakHorizontalVelocityDirection=if(velocity>0f)1f else -1f}
            }
            val intentDx=if(horizontalIntentDx!=0f)horizontalIntentDx else event.x-downX
            val intentDy=if(horizontalIntentDx!=0f)horizontalIntentDy else event.y-downY
            if(draggingPage)updatePageDrag(event.x-downX,event.y)
            // The final finger side determines the page, even when it is
            // opposite to the direction that started this gesture.
            val gestureDx=if(draggingPage)dragDistance else event.x-downX
            val commit=SwipePolicy.commits(gestureDx,event.y-downY,event.eventTime-downTime,width,density,sensitivity,peakHorizontalVelocity,intentDx,intentDy,peakHorizontalVelocityDirection,reboundThreshold)
            if(draggingPage) {
                finishPageDrag(commit)
                onUserInteractionFinished()
                return true
            }
            // Animated modes commit through finishPageDrag above.  Keeping a
            // direct ACTION_UP turn only for the explicit no-animation mode
            // prevents a failed/partially-cancelled drag from being converted
            // into an extra tap-style page turn.
            if(commit&&style.mode=="无动画"&&!horizontalGestureRejected) turn(if(event.x<downX)1 else -1)
            performClick()
            onUserInteractionFinished()
        }
        if(event.action==MotionEvent.ACTION_CANCEL){
            velocityTracker?.recycle();velocityTracker=null
            if(draggingPage){finishPageDrag(false);onUserInteractionFinished();return true}
            onUserInteractionFinished()
        }
        return true
    }
    override fun performClick():Boolean { super.performClick();return true }
}
