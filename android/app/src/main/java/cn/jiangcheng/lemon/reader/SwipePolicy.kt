package cn.jiangcheng.lemon.reader

import kotlin.math.abs
import kotlin.math.max

object SwipePolicy {
    /**
     * Only controls when visual dragging begins. The commit threshold below
     * stays higher, so short movements follow the finger and then spring back.
     */
    private const val DRAG_START_SLOP_DP = .75f
    private const val HORIZONTAL_INTENT_RATIO = .12f
    private const val FAST_MIN_DISTANCE_DP = 7f
    // A slow drag should visibly follow the finger and then spring back unless
    // it crosses a substantial part of the page.  Fast flicks use the velocity
    // branch below, so this does not make deliberate short flicks feel heavy.
    private const val DEFAULT_REBOUND_THRESHOLD = .28f
    private const val MIN_REBOUND_THRESHOLD = .08f
    private const val MAX_REBOUND_THRESHOLD = .40f

    /**
     * The reader follows the finger horizontally. A gesture is considered a
     * page gesture as soon as it has a real horizontal component; only an
     * almost perfectly vertical movement remains a vertical gesture. This is
     * deliberately much more permissive than the old 1.3 ratio so diagonal
     * swipes behave like the native browser reader.
     */
    fun isHorizontal(dx:Float,dy:Float,density:Float):Boolean {
        val distance=abs(dx)
        return distance>DRAG_START_SLOP_DP*density && distance>=abs(dy)*HORIZONTAL_INTENT_RATIO
    }
    /**
     * A page can be committed by distance or by a quick flick.  The optional
     * intent and peak velocity values let the caller retain the first
     * horizontal direction and the fastest part of the gesture instead of
     * judging only the final sample, which is what makes a short diagonal
     * flick feel responsive while a slow short drag still springs back.
     */
    fun commits(dx:Float,dy:Float,elapsed:Long,width:Int,density:Float,sensitivity:Float,
        peakVelocityPxPerSec:Float=0f,intentDx:Float=dx,intentDy:Float=dy,
        peakVelocityDirection:Float=0f,reboundThresholdRatio:Float=DEFAULT_REBOUND_THRESHOLD):Boolean{
        val distance=abs(dx);val level=sensitivity.coerceIn(0f,1f)
        if(!isHorizontal(intentDx,intentDy,density))return false
        val velocity=distance*1000f/elapsed.coerceAtLeast(1)
        val releaseDirection=when{dx>0f->1f;dx<0f->-1f;else->0f}
        val peakAligned=peakVelocityDirection==0f||releaseDirection==0f||peakVelocityDirection==releaseDirection
        val observedVelocity=max(velocity,if(peakAligned)peakVelocityPxPerSec else 0f)
        // The threshold is expressed in dp/s and converted to px/s.  A
        // deliberate short flick therefore commits without making a slow
        // drag jump pages.
        val fastVelocity=(340f-120f*level)*density
        val fast=distance>FAST_MIN_DISTANCE_DP*density&&observedVelocity>fastVelocity
        val configured=reboundThresholdRatio.coerceIn(MIN_REBOUND_THRESHOLD,MAX_REBOUND_THRESHOLD)
        // Keep the sensitivity control meaningful while making the rebound
        // distance independently adjustable: lower sensitivity asks for a
        // little more travel, higher sensitivity a little less.
        val thresholdRatio=(configured*(1.25f-.25f*level)).coerceIn(MIN_REBOUND_THRESHOLD,MAX_REBOUND_THRESHOLD)
        val threshold=max(18f*density,width*thresholdRatio)
        return distance>threshold||fast
    }
}
