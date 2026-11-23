package com.lifehub.app.plugins

import com.lifehub.app.plugin.runtime.dataSourceKey

import com.lifehub.app.plugins.testing.FakePluginStorage
import com.lifehub.pluginapi.PluginAction
import com.lifehub.pluginapi.PluginActionContext
import com.lifehub.pluginapi.PluginActionRequest
import com.lifehub.pluginapi.PluginActionResult
import com.lifehub.pluginapi.PluginDataResult
import com.lifehub.pluginapi.PluginPageDataContext
import com.lifehub.pluginapi.PluginPageDataRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelPluginTest {

    private val storage = FakePluginStorage()
    private val json = Json { ignoreUnknownKeys = true }

    private val chinaMap = """
        {
          "aspectRatio": 1.3,
          "regions": [
            {"id":"330000","name":"浙江省","level":"province","label":"浙江","labelX":0.5,"labelY":0.5,
             "interactive":false,"rings":[[0.0,0.0,1.0,0.0,1.0,1.0,0.0,0.5]]},
            {"id":"330100","name":"杭州市","level":"city","labelX":0.4,"labelY":0.4,
             "provinceId":"330000","provinceName":"浙江省",
             "rings":[[0.1,0.1,0.5,0.1,0.5,0.5,0.1,0.5]]},
            {"id":"330200","name":"宁波市","level":"city","labelX":0.7,"labelY":0.4,
             "provinceId":"330000","provinceName":"浙江省",
             "rings":[[0.55,0.1,0.9,0.1,0.9,0.5,0.55,0.5]]}
          ]
        }
    """.trimIndent()

    private val provinceMap = """
        {
          "aspectRatio": 1.2,
          "provinceId": "330000",
          "provinceName": "浙江省",
          "regions": [
            {"id":"330000","name":"浙江省","level":"province","interactive":false,
             "rings":[[0.0,0.0,1.0,0.0,1.0,1.0,0.0,1.0]]},
            {"id":"330100","name":"杭州市","level":"city","label":"杭州市","labelX":0.3,"labelY":0.3,
             "provinceId":"330000","provinceName":"浙江省",
             "rings":[[0.1,0.1,0.5,0.1,0.5,0.8,0.1,0.8]]},
            {"id":"330200","name":"宁波市","level":"city","label":"宁波市","labelX":0.7,"labelY":0.3,
             "provinceId":"330000","provinceName":"浙江省",
             "rings":[[0.55,0.1,0.9,0.1,0.9,0.8,0.55,0.8]]}
          ]
        }
    """.trimIndent()

    private val hangzhouMap = """
        {
          "aspectRatio": 1.2,
          "cityId": "330100",
          "cityName": "杭州市",
          "provinceId": "330000",
          "provinceName": "浙江省",
          "regions": [
            {"id":"330102","name":"上城区","level":"county","label":"上城区","labelX":0.3,"labelY":0.3,
             "rings":[[0.1,0.1,0.4,0.1,0.4,0.4,0.1,0.4]]},
            {"id":"330106","name":"西湖区","level":"county","label":"西湖区","labelX":0.7,"labelY":0.7,
             "rings":[[0.5,0.5,0.9,0.5,0.9,0.9,0.5,0.9]]}
          ]
        }
    """.trimIndent()

    private val ningboMap = """
        {
          "aspectRatio": 1.1,
          "cityId": "330200",
          "cityName": "宁波市",
          "provinceId": "330000",
          "provinceName": "浙江省",
          "regions": [
            {"id":"330203","name":"海曙区","level":"county","label":"海曙区","labelX":0.5,"labelY":0.5,
             "rings":[[0.2,0.2,0.8,0.2,0.8,0.8,0.2,0.8]]}
          ]
        }
    """.trimIndent()

    private val dongguanMap = """
        {
          "aspectRatio": 1.1,
          "cityId": "441900",
          "cityName": "东莞市",
          "provinceId": "440000",
          "provinceName": "广东省",
          "subdivision": "town",
          "regions": [
            {"id":"441900o1","name":"虎门镇","level":"county","label":"虎门","labelX":0.3,"labelY":0.3,
             "rings":[[0.1,0.1,0.4,0.1,0.4,0.4,0.1,0.4]]},
            {"id":"441900o2","name":"长安镇","level":"county","label":"长安","labelX":0.7,"labelY":0.7,
             "rings":[[0.5,0.5,0.9,0.5,0.9,0.9,0.5,0.9]]}
          ]
        }
    """.trimIndent()

    private val regionIndex = """
        {
          "regions": [
            ["330000","浙江省","p","330000","浙江省","",""],
            ["330100","杭州市","c","330000","浙江省","330100","杭州市"],
            ["330102","上城区","d","330000","浙江省","330100","杭州市"],
            ["360000","江西省","p","360000","江西省","",""],
            ["360700","赣州市","c","360000","江西省","360700","赣州市"],
            ["360733","会昌县","d","360000","江西省","360700","赣州市"]
          ]
        }
    """.trimIndent()

    private val assets = mapOf(
        "data/china.json" to chinaMap,
        "data/provinces/330000.json" to provinceMap,
        "data/cities/330100.json" to hangzhouMap,
        "data/cities/330200.json" to ningboMap,
        "data/cities/441900.json" to dongguanMap,
        "data/regions.json" to regionIndex,
    )

    @Test
    fun ratingCommentAndLightAreIndependent() {
        // 未点亮也可以评分和评论。
        setRating("330106", 4)
        addComment("330106", "西湖区", "330100", "杭州市", "先写评论，还没点亮")
        var header = loadHeader("330106", "西湖区")
        assertTrue(header.periodLabel.contains("未点亮"))
        assertTrue(header.periodLabel.contains("评分 4.0"))
        assertTrue(header.periodLabel.contains("1 条评论"))

        var city = loadCityMap("330100", "杭州市")
        assertFalse(city.geo!!.subtitle.contains("平均评分"))
        assertFalse(city.geo!!.regions.single { it.id == "330106" }.lit)

        light("330106", "西湖区", "330100", "杭州市")
        city = loadCityMap("330100", "杭州市")
        assertTrue(city.geo!!.subtitle.contains("平均评分 4.0"))
        assertTrue(city.geo!!.regions.single { it.id == "330106" }.lit)
        assertTrue(city.geo!!.regions.single { it.id == "330106" }.label.contains("4.0"))
        assertEquals("4", city.geo!!.regions.single { it.id == "330106" }.params["rating"])

        header = loadHeader("330106", "西湖区")
        assertTrue(header.periodLabel.contains("已点亮"))
        assertTrue(header.periodLabel.contains("评分 4.0"))

        unlight("330106")
        header = loadHeader("330106", "西湖区")
        assertTrue(header.periodLabel.contains("未点亮"))
        assertTrue(header.periodLabel.contains("评分 4.0"))
        city = loadCityMap("330100", "杭州市")
        assertFalse(city.geo!!.subtitle.contains("平均评分"))
        assertEquals(1, load(TravelPlugin.SOURCE_PLACE_REVIEWS, mapOf("regionId" to "330106")).items.size)
    }

    @Test
    fun cityAndProvinceRatingsAggregateOnlyLitRatedChildren() {
        light("330106", "西湖区", "330100", "杭州市")
        setRating("330106", 5)
        light("330102", "上城区", "330100", "杭州市")
        setRating("330102", 3)
        light("330203", "海曙区", "330200", "宁波市")
        setRating("330203", 5)

        var province = loadProvinceMap()
        assertTrue(province.geo!!.subtitle.contains("平均评分 4.5"))
        assertEquals("杭州市 4.0", province.geo!!.regions.single { it.id == "330100" }.label)
        assertEquals("宁波市 5.0", province.geo!!.regions.single { it.id == "330200" }.label)

        var china = load(TravelPlugin.SOURCE_CHINA_MAP).geo!!
        assertTrue(china.regions.single { it.id == "330000" }.label.contains("4.5"))

        setRating("330102", 1)
        province = loadProvinceMap()
        assertTrue(province.geo!!.subtitle.contains("平均评分 4.0"))
        assertTrue(province.geo!!.regions.single { it.id == "330100" }.label.contains("3.0"))

        // 未点亮的下属区县即使有评分，也不计入城市均分。
        unlight("330102")
        province = loadProvinceMap()
        assertTrue(province.geo!!.subtitle.contains("平均评分 5.0"))
        assertTrue(province.geo!!.regions.single { it.id == "330100" }.label.contains("5.0"))

        china = load(TravelPlugin.SOURCE_CHINA_MAP).geo!!
        assertTrue(china.regions.single { it.id == "330000" }.label.contains("5.0"))
    }

    @Test
    fun multipleCommentsAreIndependentFromRatingAndCanBeDeleted() {
        setRating("330106", 5)
        addComment("330106", "西湖区", "330100", "杭州市", "第一条评论")
        addComment("330106", "西湖区", "330100", "杭州市", "第二条评论")

        val comments = load(TravelPlugin.SOURCE_PLACE_REVIEWS, mapOf("regionId" to "330106"))
        assertEquals(2, comments.items.size)
        val first = comments.items.first()
        assertTrue(first.title.contains("评论"))

        val deleted = deleteComment(first.id)
        assertTrue(deleted is PluginActionResult.Refresh)
        assertEquals(1, load(TravelPlugin.SOURCE_PLACE_REVIEWS, mapOf("regionId" to "330106")).items.size)

        // 删除评论不影响地区评分。
        val header = loadHeader("330106", "西湖区")
        assertTrue(header.periodLabel.contains("评分 5.0"))
        assertTrue(header.periodLabel.contains("1 条评论"))
    }

    @Test
    fun emptyCommentIsRejectedAndRatingMustBeOneToFive() {
        val empty = addComment("330106", "西湖区", "330100", "杭州市", "   ")
        assertTrue(empty is PluginActionResult.Failure)

        val badRating = setRating("330106", 0)
        assertTrue(badRating is PluginActionResult.Failure)
        val tooHigh = setRating("330106", 6)
        assertTrue(tooHigh is PluginActionResult.Failure)
    }

    @Test
    fun commentsListShowsRegionRating() {
        setRating("330106", 4)
        addComment("330106", "西湖区", "330100", "杭州市", "沿着湖边走了一圈")
        addComment("330106", "西湖区", "330100", "杭州市", "第二次来")

        val comments = load(TravelPlugin.SOURCE_REVIEWS)
        assertEquals(2, comments.items.size)
        assertTrue(comments.items.all { it.title.contains("浙江省 · 杭州市 · 西湖区") })
        assertTrue(comments.items.all { it.footer.contains("地区评分 4 星") })

        val search = load(TravelPlugin.SOURCE_SEARCH, mapOf("query" to "湖边"))
        assertEquals(1, search.items.size)
        assertTrue(search.items.single().footer.contains("地区评分 4 星"))

        val noRating = load(TravelPlugin.SOURCE_SEARCH, mapOf("query" to "宁波"))
        assertTrue(noRating.items.isEmpty())
    }

    @Test
    fun migrationV1NoteBecomesCommentWithoutRating() {
        val legacy = LegacyTravelPlace(
            id = "330106",
            name = "西湖区",
            cityId = "330100",
            cityName = "杭州市",
            provinceId = "330000",
            provinceName = "浙江省",
            note = "旧版备注",
            lit = true,
            visitedAt = 100L,
            updatedAt = 200L,
        )
        storage.insert("travel_places", json.encodeToString(legacy), legacy.id)

        TravelPlugin.migration.migrate(storage, 1, 3)

        val record = storage.get("travel_reviews", "legacy-330106")
        assertNotNull(record)
        val comment = json.decodeFromString<TravelReview>(record!!.payload)
        assertEquals("旧版备注", comment.text)
        assertTrue(storage.get("travel_ratings", "330106") == null)
        assertTrue(loadHeader("330106", "西湖区").periodLabel.contains("未评分"))

        TravelPlugin.migration.migrate(storage, 1, 3)
        assertEquals(1, storage.count("travel_reviews"))
    }

    @Test
    fun migrationV2ScoresBecomeOneRegionRating() {
        val v2a = LegacyTravelReview(
            id = "r1",
            regionId = "330106",
            regionName = "西湖区",
            cityId = "330100",
            cityName = "杭州市",
            provinceId = "330000",
            provinceName = "浙江省",
            score = 4,
            text = "第一次",
            createdAt = 1L,
            updatedAt = 1L,
        )
        val v2b = v2a.copy(id = "r2", score = 3, text = "第二次", createdAt = 2L, updatedAt = 2L)
        storage.insert("travel_reviews", json.encodeToString(v2a), v2a.id)
        storage.insert("travel_reviews", json.encodeToString(v2b), v2b.id)

        TravelPlugin.migration.migrate(storage, 2, 3)

        val rating = storage.get("travel_ratings", "330106")
        assertNotNull(rating)
        assertEquals(4, json.decodeFromString<TravelRating>(rating!!.payload).rating)
        assertEquals(2, storage.count("travel_reviews"))
        val rewritten = json.decodeFromString<TravelReview>(
            storage.get("travel_reviews", "r1")!!.payload,
        )
        assertEquals("第一次", rewritten.text)

        // 重复执行不会覆盖已有评分或重复评论。
        TravelPlugin.migration.migrate(storage, 2, 3)
        assertEquals(1, storage.count("travel_ratings"))
        assertEquals(2, storage.count("travel_reviews"))
    }

    @Test
    fun rankingAndLevelOneRemindersUseRatedPlacesOnly() {
        light("330203", "海曙区", "330200", "宁波市")
        setRating("330203", 5)
        // 杭州只点亮、不评分：应出现在提醒中，但不进入任何榜单。
        light("330102", "上城区", "330100", "杭州市")

        val overview = load(TravelPlugin.SOURCE_RANK_OVERVIEW)
        assertEquals("1", overview.metrics[0].value)
        assertEquals("1", overview.metrics[1].value)
        assertEquals("1", overview.metrics[2].value)
        assertEquals("1", overview.metrics[3].value)
        assertTrue(overview.periodLabel.contains("还有 1 个"))

        val provinces = load(TravelPlugin.SOURCE_RANK_PROVINCES)
        assertEquals(1, provinces.items.size)
        assertEquals("浙江", provinces.items.single().title)
        assertEquals("5.0", provinces.items.single().trailing)

        val cities = load(TravelPlugin.SOURCE_RANK_CITIES)
        assertEquals(1, cities.items.size)
        assertEquals("宁波市", cities.items.single().title)
        assertEquals("5.0", cities.items.single().trailing)

        val counties = load(TravelPlugin.SOURCE_RANK_COUNTIES)
        assertEquals(1, counties.items.size)
        assertEquals("海曙区", counties.items.single().title)

        val china = load(TravelPlugin.SOURCE_CHINA_MAP).geo!!
        assertTrue(china.regions.single { it.id == "330100" }.label.isEmpty())
        assertTrue(china.regions.single { it.id == "330200" }.label.isEmpty())
        assertTrue(china.regions.single { it.id == "330000" }.label.contains("5.0"))
        assertFalse(china.subtitle.contains("平均评分"))
        assertTrue(china.subtitle.contains("1 个城市待评分"))

        val province = loadProvinceMap().geo!!
        assertTrue(province.subtitle.contains("1 个市待评分"))
        val city = loadCityMap("330100", "杭州市").geo!!
        assertTrue(city.subtitle.contains("1 个区县待评分"))

        val summary = load(TravelPlugin.SOURCE_SUMMARY)
        assertEquals(3, summary.metrics.size)
        assertTrue(summary.metrics.none { it.label == "平均评分" })
    }

    @Test
    fun townLevelCityUsesStreetAndTownWording() {
        light("441900o1", "虎门镇", "441900", "东莞市")
        val city = loadCityMap("441900", "东莞市").geo!!
        assertTrue(city.subtitle.contains("已点亮 1/2 个街道/镇"))
        assertFalse(city.subtitle.contains("个区县"))
        assertTrue(city.regions.single { it.id == "441900o1" }.lit)
        assertTrue(city.regions.single { it.id == "441900o1" }.label.contains("虎门"))
    }

    @Test
    fun provinceAndCityMapsRejectInvalidIds() {
        val province = load(
            TravelPlugin.SOURCE_PROVINCE_MAP,
            mapOf("provinceId" to "../china"),
        )
        assertTrue(province.isError)

        val city = load(
            TravelPlugin.SOURCE_CITY_MAP,
            mapOf("cityId" to "../china"),
        )
        assertTrue(city.isError)
    }

    @Test
    fun regionSearchFindsPlacesAndLightsFromResult() {
        val first = load(
            TravelPlugin.SOURCE_REGION_SEARCH,
            mapOf("query" to "上城"),
        )
        assertEquals(1, first.items.size)
        val item = first.items.single()
        assertEquals("上城区", item.title)
        assertTrue(item.subtitle.contains("未点亮"))
        assertEquals(2, item.actions.size)
        assertEquals("点亮", item.actions.first().label)

        val host = item.actions.first().action as PluginAction.Host
        val lit = action(host.actionId, host.params)
        assertTrue(lit is PluginActionResult.Refresh)

        val after = load(
            TravelPlugin.SOURCE_REGION_SEARCH,
            mapOf("query" to "上城"),
        )
        val litItem = after.items.single()
        assertTrue(litItem.subtitle.contains("已点亮"))
        assertEquals("取消点亮", litItem.actions.first().label)
    }

    @Test
    fun regionSearchActionRequiresQueryAndNavigates() {
        val blank = action(TravelPlugin.ACTION_SEARCH_REGIONS, emptyMap())
        assertTrue(blank is PluginActionResult.ShowMessage)
        val nav = action(
            TravelPlugin.ACTION_SEARCH_REGIONS,
            mapOf("query" to "会昌"),
        )
        assertTrue(nav is PluginActionResult.Navigate)
        assertEquals(
            TravelPlugin.PAGE_SEARCH_REGION,
            (nav as PluginActionResult.Navigate).pageId,
        )
        assertEquals("会昌", nav.params["query"])
    }

    private fun light(
        regionId: String,
        regionName: String,
        cityId: String,
        cityName: String,
    ): PluginActionResult = action(
        actionId = TravelPlugin.ACTION_LIGHT,
        params = regionParams(regionId, regionName, cityId, cityName),
    )

    private fun unlight(regionId: String): PluginActionResult = action(
        actionId = TravelPlugin.ACTION_UNLIGHT,
        params = mapOf("regionId" to regionId),
    )

    private fun setRating(regionId: String, rating: Int): PluginActionResult = action(
        actionId = TravelPlugin.ACTION_SET_RATING,
        params = mapOf("regionId" to regionId, "rating" to rating.toString()),
    )

    private fun addComment(
        regionId: String,
        regionName: String,
        cityId: String,
        cityName: String,
        text: String,
    ): PluginActionResult = action(
        actionId = TravelPlugin.ACTION_ADD_COMMENT,
        params = regionParams(regionId, regionName, cityId, cityName) +
            mapOf("comment" to text),
    )

    private fun deleteComment(id: String): PluginActionResult = action(
        actionId = TravelPlugin.ACTION_DELETE_COMMENT,
        params = mapOf("id" to id),
    )

    private fun regionParams(
        regionId: String,
        regionName: String,
        cityId: String,
        cityName: String,
    ): Map<String, String> = mapOf(
        "regionId" to regionId,
        "regionName" to regionName,
        "level" to "county",
        "cityId" to cityId,
        "cityName" to cityName,
        "provinceId" to "330000",
        "provinceName" to "浙江省",
    )

    private fun action(actionId: String, params: Map<String, String>): PluginActionResult {
        val handler = TravelPlugin.actionHandlers.getValue(actionId)
        return handler.handle(
            PluginActionContext(
                request = PluginActionRequest(
                    pluginId = TravelPlugin.PLUGIN_ID,
                    pageId = "test",
                    actionId = actionId,
                    params = params,
                ),
                storage = storage,
                assetReader = { path -> assets[path] },
            ),
        )
    }

    private fun loadHeader(regionId: String, regionName: String): PluginDataResult = load(
        TravelPlugin.SOURCE_PLACE_HEADER,
        mapOf(
            "regionId" to regionId,
            "regionName" to regionName,
            "cityName" to "杭州市",
            "provinceName" to "浙江省",
        ),
    )

    private fun loadProvinceMap(): PluginDataResult = load(
        TravelPlugin.SOURCE_PROVINCE_MAP,
        mapOf("provinceId" to "330000", "provinceName" to "浙江省"),
    )

    private fun loadCityMap(cityId: String, cityName: String): PluginDataResult = load(
        TravelPlugin.SOURCE_CITY_MAP,
        mapOf(
            "cityId" to cityId,
            "cityName" to cityName,
            "provinceId" to "330000",
            "provinceName" to "浙江省",
        ),
    )

    private fun load(
        sourceId: String,
        params: Map<String, String> = emptyMap(),
    ): PluginDataResult {
        val handler = TravelPlugin.dataHandlers.getValue(
            dataSourceKey(TravelPlugin.PLUGIN_ID, sourceId),
        )
        return handler.load(
            PluginPageDataContext(
                request = PluginPageDataRequest(
                    pluginId = TravelPlugin.PLUGIN_ID,
                    pageId = "test",
                    sourceId = sourceId,
                    params = params,
                ),
                storage = storage,
                assetReader = { path -> assets[path] },
            ),
        )
    }
}
