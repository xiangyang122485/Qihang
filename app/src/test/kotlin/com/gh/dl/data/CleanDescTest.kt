package com.gh.dl.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [cleanDesc] 回归测试。
 *
 * 样本取自线上真实数据：GitHub 仓库 Dujltqzv/Some-Many-Books 的 description
 * 原文 55700 字符，前缀「个人收藏书籍列表 PDF下载 百度云 电子书下载 ==」之后为
 * 编码错误产生的无效字符序列（此处保留至无效字符块前 200 字，避免测试文件过大）。
 * 此前 UI 直接将原文显示于 Text 组件中，导致界面出现大量无效字符。
 */
class CleanDescTest {

    /** 含无效字符的真实数据：应仅保留可读前缀，无效字符块整体丢弃 */
    @Test
    fun realGarbledSample_keepsOnlyReadablePrefix() {
        val raw = "个人收藏书籍列表　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　 　 　 　 　 　 　 　 　 　 　 PDF下载 百度云 电子书下载 ==礠緢舝揽躋瑥玮獺捈遍縤湑玤怃顷橲誕鞀覄琤镾忺鬵齶陛飙驨庤憗诡熇荝蹿巾鴡饌斄碀遯遠傪囷詊偍澐匁庾曾戢悒猄迵徬摵宬甂孺郳糣螨吮覘綔氊豀纴嫤惗跚銸莻箤惉輘芴蛣謙蘲脐冇穖謥貛璕掸災廟釨儼滘茧懦蝾谘睃玿鱪飄雓棽歗闸譐争癒搉谠栝陶林焉訨寵贶盼珲洝箒赤瓱鵸磕韡溋阩棉兤诚厐噆熙狿蝕垧岊嶳邏銟甚榞鱗粿濜堘殤腙驡饅騀窍剷蜐喽帎鏹苟茙銲墏烅颓秎麗碦桝鐤轊滓滯鷝忮紤壄讴潢杗霅眤拗稧燽諦镎韴磰矝秖癯僰劅潓鼐句乭甈黕頛漬藝觚饔膪摺朚蜹冹灥沚植樝毉返嫎诶负秩骐黏羮拙蟳贗彥堀傏嫜晵阪巖浿晙嗋壝鋫氎嬅饡鐧呈况浂蚰犸濫鄕籹片 结尾中文"
        val out = cleanDesc(raw)
        assertEquals("个人收藏书籍列表 PDF下载 百度云 电子书下载", out)
        // 附加验证：结果中不应残留任何私有 Unicode 区字符
        assertTrue("结果不应含私有区字符", out.none { it in '\uE000'..'\uF8FF' })
        assertTrue("结果长度应显著小于原文", out.length < 100)
    }

    /** 全角空格 / 私有区字符 / 控制字符均应被清除或折叠 */
    @Test
    fun stripsInvisibleAndPrivateUseChars() {
        assertEquals("a b", cleanDesc("a\u3000\u3000\u00a0b"))
        assertEquals("ab", cleanDesc("a\uE857b"))
        assertEquals("ab", cleanDesc("a\u200B\uFEFFb"))
        assertEquals("a b", cleanDesc("a\tb\r\n"))
    }

    /** 正常描述必须原样保留，不应被截断逻辑误处理 */
    @Test
    fun keepsNormalDescriptionsIntact() {
        val zh = "科技爱好者周刊，每周五发布"
        assertEquals(zh, cleanDesc(zh))
        val en = "A library for parsing PCAP files."
        assertEquals(en, cleanDesc(en))
        // 30 字连续汉字（实测正常中文描述最长约 29 字），阈值 60，不应被截断
        val longCjkNoPunct = "中".repeat(30)
        assertEquals(longCjkNoPunct, cleanDesc(longCjkNoPunct))
    }

    /** 超长但正常的描述：按 maxLen 截断并添加省略号，尽量在边界处收尾 */
    @Test
    fun truncatesLongNormalDescription() {
        val raw = ("词 " ).repeat(200).trim()
        val out = cleanDesc(raw, maxLen = 60)
        assertTrue("结果应被截短", out.length <= 62)
        assertTrue("结果应以省略号结尾", out.endsWith("…"))
    }

    /** 空描述返回空串（调用方据此显示「暂无描述」） */
    @Test
    fun blankStaysBlank() {
        assertEquals("", cleanDesc(""))
        assertEquals("", cleanDesc("\u3000\u3000 \t"))
    }
}
