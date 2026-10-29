package cn.jiangcheng.lemon

/** A share has its own task when launched with NEW_TASK, or belongs to its caller's
 * task otherwise. Finishing a rejected share cannot reveal an old reader task. */
class ImportShareActivity : MainActivity()
