package com.example.personalovertimerecord.utils

/**
 * api.apihubs.cn 节假日接口的「节日 ID → 名称」映射。
 *
 * 映射来源：接口开启 cn=1 后返回的 holiday_cn / holiday_or_cn 字段，
 * 用 2024 年与 2026 年全年数据交叉核对（同一节日在不同年份 ID 固定）。
 *
 * 说明：
 * - 接口以两位数字枚举表示节日，10 表示非节假日，99 表示全部节假日；
 * - 接口自身并未提供完整的字典清单，未在数据中出现过的 ID（如 1-13、20、24-43 等）无从确认，
 *   故本表只收录已实测到的节日，查询未知 ID 时返回 null，调用方应优先使用接口 cn=1 返回的名称。
 */
object HolidayIdNames {

    /** 非节假日 */
    const val NONE = 10

    /** 查询参数 holiday=99 时表示全部节假日 */
    const val ALL = 99

    private val NAMES: Map<Int, String> = mapOf(
        // 1 月
        14 to "中国人民警察节",
        22 to "元旦",
        // 2 月
        15 to "除夕",
        17 to "情人节",
        16 to "元宵节",
        // 3 月
        18 to "龙头节",
        19 to "妇女节",
        21 to "植树节",
        // 4 月
        23 to "愚人节",
        44 to "清明节",
        45 to "复活节",
        // 5 月
        55 to "劳动节",
        56 to "青年节",
        58 to "母亲节",
        59 to "护士节",
        // 6 月
        61 to "儿童节",
        62 to "父亲节",
        66 to "端午节",
        // 7 月
        67 to "建党节",
        // 8 月
        68 to "建军节",
        69 to "中国医师节",
        70 to "七夕节",
        71 to "中元节",
        // 9 月
        72 to "教师节",
        78 to "中国农民丰收节",
        77 to "中秋节",
        // 10 月
        88 to "国庆节",
        89 to "重阳节",
        90 to "老年节",
        // 11 月
        91 to "记者节",
        92 to "寒衣节",
        93 to "大学生节",
        95 to "感恩节",
        96 to "下元节",
        // 12 月
        97 to "圣诞节"
    )

    /** 按 ID 取节日名称；未收录或非节假日返回 null */
    fun nameOf(id: Int): String? = NAMES[id]

    /** 是否为某个具体节日（排除 10=非节假日、99=全部节假日） */
    fun isFestival(id: Int): Boolean = NAMES.containsKey(id)
}
