package io.github.maximemet.supercalc.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 历史记录库。
 *
 * 表结构和行为都照参考实现（`HistoryDbHelper`）来：库名和表名都是 `supercalc`，
 * 字段是 `id / time / expr / latex / type / result`，记录按 id 倒序取，
 * 取出来之后按类型翻译成历史页要的 JSON。
 *
 * 参考实现还会把记录传到服务器（已下线）并缓存位图，这两块我们不抄。
 */
class HistoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL(CREATE_TABLE)
    }

    /** 记一条。`result` 只在自动结果（type=5）时才带。 */
    fun add(expr: String?, latex: String?, type: Int, result: String? = null) {
        if (latex.isNullOrEmpty()) return
        runCatching {
            val values = ContentValues().apply {
                put("time", System.currentTimeMillis())
                put("expr", expr)
                put("latex", latex)
                put("type", type)
                put("result", result)
            }
            writableDatabase.insert(TABLE, null, values)
        }
    }

    fun clear() {
        runCatching { writableDatabase.delete(TABLE, null, null) }
    }

    /**
     * 给历史页 WebView 的一页数据，JSON 数组，新记录在前。
     *
     * 和参考实现的 `readJsonArray` 一样：
     *
     *   * `readRecord` 是**分批**读的——每批取 `limit` 行，凑够 `limit` 条可显示的记录、
     *     或者把库读空、或者 retry 用完才停（老库里堆着一堆不显示的 type=1 记录时，
     *     一批 15 行可能只出一条，所以要接着往下捞）；
     *   * 每批开头 `shouldShowAutoResult` 复位，所以「自动结果只显示第一条」只在
     *     每一批内部生效；别的类型（含不显示的 type=1）会把它重新打开；
     *   * 最后把连续重复的（公式, 结果）压成一条。
     */
    fun page(endId: Long?, limit: Int): String {
        // 参考实现里 retry 从 5 开始，进循环先减再判，最多 5 轮；
        // 每显示一条自动结果时若 retry 已经见底，就补一次（readRecord 的 .line 375）。
        val rows = ArrayList<Row>()
        var lastId = endId
        var retry = RETRY_LIMIT
        var exhausted = false
        while (rows.size < limit && !exhausted) {
            retry--
            if (retry < 0) break

            var shouldShowAutoResult = true
            runCatching {
                readableDatabase.query(
                    TABLE,
                    null,
                    if (lastId != null) "id < $lastId" else null,
                    null,
                    null,
                    null,
                    "id desc",
                    limit.toString(),
                ).use { cursor ->
                    if (cursor.count == 0) {
                        exhausted = true
                        return@use
                    }
                    val idCol = cursor.getColumnIndexOrThrow("id")
                    val timeCol = cursor.getColumnIndexOrThrow("time")
                    val latexCol = cursor.getColumnIndexOrThrow("latex")
                    val typeCol = cursor.getColumnIndexOrThrow("type")
                    val resultCol = cursor.getColumnIndexOrThrow("result")

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val time = cursor.getLong(timeCol)
                        var latex = (cursor.getString(latexCol) ?: continue).replace("ewline", "ewline ")
                        val type = cursor.getInt(typeCol)
                        // 参考实现每读一行就先记下 lastId，下一批从它继续
                        lastId = id

                        val result: String = if (type == HistoryType.HAS_RESULT) {
                            if (!shouldShowAutoResult) continue
                            shouldShowAutoResult = false
                            if (retry <= 1) retry++
                            // 多行输入只留最后一行（就是自动结果对应的那一行）
                            val p = latex.lastIndexOf("\\newline")
                            if (p >= 0) {
                                latex = latex.substring(p + "\\newline".length)
                                if (latex.startsWith("*")) latex = latex.substring(1)
                            }
                            cursor.getString(resultCol).orEmpty()
                        } else {
                            shouldShowAutoResult = true
                            val label = HistoryType.label(type) ?: continue
                            " $label"
                        }

                        rows.add(Row(id, dateOf(time), latex, result))
                    }
                }
            }
        }

        val out = JSONArray()
        var lastFormula: String? = null
        var lastResult: String? = null
        for (row in rows) {
            // 连续重复的两条只留一条
            if (row.formula == lastFormula && row.result == lastResult) continue
            out.put(
                JSONObject().apply {
                    put("id", row.id)
                    put("date", row.date)
                    put("formula", row.formula)
                    put("result", row.result)
                },
            )
            lastFormula = row.formula
            lastResult = row.result
        }
        return out.toString()
    }

    private data class Row(val id: Long, val date: String, val formula: String, val result: String)

    private fun dateOf(millis: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        return "${c.get(Calendar.YEAR)}年${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
    }

    companion object {
        private const val DB_NAME = "supercalc"
        private const val DB_VERSION = 10
        private const val TABLE = "supercalc"
        /** 参考实现 readRecord 里的 retry 初值。 */
        private const val RETRY_LIMIT = 5
        private const val CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS supercalc (id integer primary key autoincrement," +
                "time INTEGER, expr varchar(200), latex varchar(200), type INTEGER, result varchar(120))"

        @Volatile
        private var instance: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            instance ?: synchronized(this) {
                instance ?: HistoryStore(context).also { instance = it }
            }
    }
}

/**
 * 记录类型 → 历史页上显示的方法名。
 *
 * 号码和文案都来自参考实现的 `MethodInterpret`，注意这里用的是**历史页**那套文案，
 * 和按钮上的文案不一定相同（比如按钮写「求解方程组」，历史记录写「解方程组」）。
 */
object HistoryType {
    const val NORMAL = 0
    const val ON_STOP = 1
    const val ON_NEWLINE = 2
    const val HAS_RESULT = 5
    const val INTEGRATE = 10
    const val DIFF = 11
    const val EXPAND = 12
    const val FACTOR = 13
    const val DINTE = 14
    const val CALC = 15
    const val LIMIT = 19
    const val SOLVE = 21
    const val SOLVE2 = 22
    const val SOLVEINEQ = 23
    const val SOLVEINEQ2 = 24
    const val DRAW = 25

    private val labels = mapOf(
        INTEGRATE to "积分",
        DIFF to "求导",
        EXPAND to "多项式展开",
        FACTOR to "多项式分解",
        DINTE to "定积分",
        CALC to "计算",
        LIMIT to "计算极限",
        SOLVE to "求解方程",
        SOLVE2 to "解方程组",
        SOLVEINEQ to "解不等式",
        SOLVEINEQ2 to "解不等式组",
        DRAW to "绘制图像",
    )

    fun label(type: Int): String? = labels[type]
}
