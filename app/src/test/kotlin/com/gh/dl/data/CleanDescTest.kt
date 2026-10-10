package com.gh.dl.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [cleanDesc] 回归测试。
 *
 * 样本取自线上真实数据：GitHub 仓库 Dujltqzv/Some-Many-Books 的 description
 * 原文 55700 字符，前缀「个人收藏书籍列表 PDF下载 百度云 电子书下载 ==」之后是一整块
 * GBK 被按别的编码存进来的乱码（这里保留到乱码块前 200 字，避免测试文件过大）。
 * 之前 UI 直接把原文塞进 Text，就是你截图里那一屏乱码。
 */
class CleanDescTest {

    /** 真实脏数据：应只保留可读前缀，乱码块整体丢弃 */
    @Test
    fun realGarbledSample_keepsOnlyReadablePrefix() {
        val raw = "个人收藏书籍列表　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　　 　 　 　 　 　 　 　 　 　 　 PDF下载 百度云 电子书下载 ==礠緢舝揽躋瑥玮獺捈遍縤湑玤怃顷橲誕鞀覄琤镾忺鬵齶陛飙驨庤憗诡熇荝蹿巾鴡饌斄碀遯遠傪囷詊偍澐匁庾曾戢悒猄迵徬摵宬甂孺郳糣螨吮覘綔氊豀纴嫤惗跚銸莻箤惉輘芴蛣謙蘲脐冇穖謥貛璕掸災廟釨儼滘茧懦蝾谘睃玿鱪飄雓棽歗闸譐争癒搉谠栝陶林焉訨寵贶盼珲洝箒赤瓱鵸磕韡溋阩棉兤诚厐噆熙狿蝕垧岊嶳邏銟甚榞鱗粿濜堘殤腙驡饅騀窍剷蜐喽帎鏹苟茙銲墏烅颓秎麗碦桝鐤轊滓滯鷝忮紤壄讴潢杗霅眤拗稧燽諦镎韴磰矝秖癯僰劅潓鼐句乭甈黕頛漬藝觚饔膪摺朚蜹冹灥沚植樝毉返嫎诶负秩骐黏羮拙蟳贗彥堀傏嫜晵阪巖浿晙嗋壝鋫氎嬅饡鐧呈况浂蚰犸濫鄕籹片 结尾中文"
        val out = cleanDesc(raw)
        assertEquals("个人收藏书籍列表 PDF下载 百度云 电子书下载", out)
        // 双保险：结果里不能残留任何乱码字符
        assertTrue("结果不应含乱码", out.none { it in '\uE000'..'\uF8FF' })
        assertTrue("结果长度应远小于原文", out.length < 100)
    }

    /** 全角空格 / 私有区字符 / 控制字符都要被清掉或折叠 */
    @Test
    fun stripsInvisibleAndPrivateUseChars() {
        assertEquals("a b", cleanDesc("a\u3000\u3000\u00a0b"))
        assertEquals("ab", cleanDesc("a\uE857b"))
        assertEquals("ab", cleanDesc("a\u200B\uFEFFb"))
        assertEquals("a b", cleanDesc("a\tb\r\n"))
    }

    /** 正常描述必须原样保留，不能被「乱码截断」误伤 */
    @Test
    fun keepsNormalDescriptionsIntact() {
        val zh = "科技爱好者周刊，每周五发布"
        assertEquals(zh, cleanDesc(zh))
        val en = "A library for parsing PCAP files."
        assertEquals(en, cleanDesc(en))
        // 30 字连续汉字（实测正常中文描述最长约 29 字），阈值 60，不该被切
        val longCjkNoPunct = "中".repeat(30)
        assertEquals(longCjkNoPunct, cleanDesc(longCjkNoPunct))
    }

    /** 超长但正常的描述：按 maxLen 截断并加省略号，且尽量在边界收尾 */
    @Test
    fun truncatesLongNormalDescription() {
        val raw = ("词 " ).repeat(200).trim()
        val out = cleanDesc(raw, maxLen = 60)
        assertTrue("应被截短", out.length <= 62)
        assertTrue("应带省略号", out.endsWith("…"))
    }

    /** 空描述返回空串（调用方据此显示「暂无描述」） */
    @Test
    fun blankStaysBlank() {
        assertEquals("", cleanDesc(""))
        assertEquals("", cleanDesc("\u3000\u3000 \t"))
    }
}
