package com.leimao.tv.core.database.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 混合备份的端到端识别（SET-14）。
 *
 * 单个函数的用例只能说明「规则对」，说明不了「一整份混着直播和影视的备份导进来，
 * 每一条都分对了」。这份用例走的是真实链路：`SourceJson.decode` 逐条解析 → 定类型。
 *
 * 用户的原话是「源导入的时候，要支持混合导入，自动识别影视和直播」—— 判据就是
 * 下面这份文件导完，直播源和影视源各在各的位置，一条都不用他手选。
 */
class MixedImportTest {
    private val mixed =
        """
        {"version":1,"sources":[
          {"type":"LIVE","name":"采集站","url":"https://a.example.com/api.php/provide/vod"},
          {"type":"VOD","name":"直播源","url":"https://b.example.com/live.m3u"},
          {"name":"无类型 txt","url":"https://c.example.com/list.txt"},
          {"name":"无类型采集","url":"https://d.example.com/api.php/provide/vod/from/x"},
          {"type":"VOD","name":"MoonTV","url":"https://e.example.com/vod.txt","format":"moontv"},
          {"type":"LIVE","name":"直播","url":"https://f.example.com/iptv/playlist.txt"},
          {"type":"直播","name":"中文类型","url":"https://g.example.com/channels.txt"}
        ]}
        """.trimIndent()

    @Test
    fun `一份混合备份里直播与影视各归各位`() {
        val byName = SourceJson.decode(mixed).associateBy { it.name }

        // ① 声明写成 LIVE、地址却是采集接口 → 必须纠正成影视，否则永远刷不出频道
        assertEquals(SourceType.VOD, byName.getValue("采集站").type)
        // ② 反方向同理：声明 VOD、地址是 .m3u → 直播
        assertEquals(SourceType.LIVE, byName.getValue("直播源").type)
        // ③ 没写 type 时靠地址软特征兜：.txt 是频道清单
        assertEquals(SourceType.LIVE, byName.getValue("无类型 txt").type)
        // ④ 没写 type 但地址是采集接口
        assertEquals(SourceType.VOD, byName.getValue("无类型采集").type)
        // ⑤ 聚合源清单里的真实形态：影视源挂在 .txt 上。软特征不得推翻声明
        assertEquals(SourceType.VOD, byName.getValue("MoonTV").type)
        // ⑥ 声明 LIVE + 直播特征，两个方向一致
        assertEquals(SourceType.LIVE, byName.getValue("直播").type)
        // ⑦ 中文写法「直播」要认
        assertEquals(SourceType.LIVE, byName.getValue("中文类型").type)
    }

    @Test
    fun `识别类型不影响其它字段`() {
        val moon = SourceJson.decode(mixed).first { it.name == "MoonTV" }
        // 改类型只是纠正分类，format 是另一条信息（决定走哪个适配器），不能被顺手改掉
        assertEquals(SourceFormat.MOONTV, moon.format)
        assertEquals("https://e.example.com/vod.txt", moon.url)
        assertTrue(moon.enabled)
    }

    @Test
    fun `同一份混合备份导两次不该变成两份`() {
        val first = SourceJson.decode(mixed)
        val (fresh, skipped) = SourceJson.filterNew(SourceJson.decode(mixed), first.map { it.url }.toSet())
        // 幂等：第二次一条都不新增，全按「地址已存在」跳过
        assertEquals(0, fresh.size)
        assertEquals(first.size, skipped)
    }
}
