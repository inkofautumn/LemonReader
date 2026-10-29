package cn.jiangcheng.lemon.reader
import org.junit.Assert.*
import org.junit.Test
class SwipePolicyTest {
    @Test fun tinyHorizontalMovementStartsVisualDrag(){assertTrue(SwipePolicy.isHorizontal(3f,0f,1f))}
    @Test fun sensitivityChangesDistance(){assertFalse(SwipePolicy.commits(120f,1f,1000,1000,1f,0f,reboundThresholdRatio=.10f));assertTrue(SwipePolicy.commits(120f,1f,1000,1000,1f,1f,reboundThresholdRatio=.10f))}
    @Test fun quickFlickWorksInBothDirections(){assertTrue(SwipePolicy.commits(60f,2f,20,1000,1f,.5f));assertTrue(SwipePolicy.commits(-60f,2f,20,1000,1f,.5f))}
    @Test fun shortFastDiagonalFlickCommits(){assertTrue(SwipePolicy.commits(-24f,-100f,40,1080,3f,.9f,1100f))}
    @Test fun slowShortDiagonalDragSpringsBack(){assertFalse(SwipePolicy.commits(-24f,-100f,700,1080,3f,.9f))}
    @Test fun reversePeakDoesNotCommitOppositeDirection(){assertFalse(SwipePolicy.commits(32f,0f,80,1080,3f,.9f,1400f,32f,0f,-1f,.18f))}
    @Test fun diagonalHorizontalIntentTurnsPage(){assertTrue(SwipePolicy.commits(180f,150f,260,1000,1f,1f));assertTrue(SwipePolicy.commits(60f,300f,260,592,1f,1f))}
    @Test fun almostVerticalScrollDoesNotTurn(){assertFalse(SwipePolicy.commits(8f,300f,10,1000,1f,1f))}
    @Test fun smallJitterAndSlowShortDragsCancel(){assertFalse(SwipePolicy.commits(5f,0f,1,1000,1f,1f));assertFalse(SwipePolicy.commits(60f,2f,500,1000,1f,1f))}
}
