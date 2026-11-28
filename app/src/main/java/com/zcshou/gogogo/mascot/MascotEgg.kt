package com.zcshou.gogogo.mascot

/**
 * 彩蛋拍立得图鉴。
 *
 * 资源按名字从 `drawable-nodpi` 解析；**未绘制的图不隐藏格子**，
 * 而是显示深海轮廓占位卡 + 解锁提示，避免图鉴出现「空洞」或崩溃。
 */
enum class MascotEgg(
    val resName: String,
    val caption: String,
    val showCaption: Boolean = true,
    val title: String = "",
    val unlockHint: String = ""
) {
    WELCOME_ARMS(
        "egg_welcome_arms",
        "哦鲸鲸！以后的路线就交给我来领跑啦！",
        title = "初次见面",
        unlockHint = "首次开启伴侣模式"
    ),
    DRIVE(
        "egg_drive",
        "配速拉满，已自动切换老司机模式！",
        title = "老司机",
        unlockHint = "以快于 5'00\"/km 的均速跑完一圈"
    ),
    FLASHLIGHT(
        "egg_flashlight",
        "夜跑探照灯已开，前方安全鲸！",
        title = "夜航探照灯",
        unlockHint = "深夜 23:00–05:00 完成一次跑圈"
    ),
    DESK_TAIL(
        "egg_desk_tail",
        "赛博摸鱼中，顺便 rua 下尾巴～",
        title = "赛博摸鱼",
        unlockHint = "单次托管跑圈 ≥ 20 分钟"
    ),
    TOAST(
        "egg_toast",
        "谢谢老板！今晚加餐吐司配磷虾！",
        title = "清晨吐司",
        unlockHint = "清晨 06:00–09:00 完成一次跑圈"
    ),
    RICE_BOWL(
        "egg_rice_bowl",
        "就吃大白米饭吗…投喂点小鱼干吧鲸…",
        title = "白米饭",
        unlockHint = "访问「支持云迹」页"
    ),
    SULKING(
        "egg_sulking",
        "不带我玩了？那我也背过身去…",
        title = "闹别扭",
        unlockHint = "把伴侣模式切回关闭"
    ),
    POKE_PRESSURE(
        "egg_poke_pressure",
        "再戳就要把配速调成博尔特了！",
        showCaption = false,
        title = "戳头压力",
        unlockHint = "连戳鲸鲸 4 次"
    ),
    SLACKING_WHALE(
        "egg_slacking_whale",
        "主打一个赛博自律，精神长跑！",
        showCaption = false,
        title = "摸鱼鲸",
        unlockHint = "完成任意一次白天跑圈"
    ),
    KEYBOARD(
        "egg_keyboard",
        "正在疯狂敲代码帮你打掩护…",
        title = "键盘侠鲸",
        unlockHint = "体检页全部绿灯，或开启开发者选项"
    )
}
