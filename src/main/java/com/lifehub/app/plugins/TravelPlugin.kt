package com.lifehub.app.plugins

import com.lifehub.app.plugin.runtime.PluginModule
import com.lifehub.app.plugin.runtime.dataSourceKey
import com.lifehub.app.plugin.runtime.readAllRecords

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import com.lifehub.pluginapi.PluginAction
import com.lifehub.pluginapi.PluginActionContext
import com.lifehub.pluginapi.PluginActionHandler
import com.lifehub.pluginapi.PluginActionResult
import com.lifehub.pluginapi.PluginConfirmDialog
import com.lifehub.pluginapi.PluginDataAction
import com.lifehub.pluginapi.PluginDataItem
import com.lifehub.pluginapi.PluginDataLimits
import com.lifehub.pluginapi.PluginDataMigration
import com.lifehub.pluginapi.PluginDataResult
import com.lifehub.pluginapi.PluginGeoLevel
import com.lifehub.pluginapi.PluginGeoMapData
import com.lifehub.pluginapi.PluginGeoPoint
import com.lifehub.pluginapi.PluginGeoRegion
import com.lifehub.pluginapi.PluginMetric
import com.lifehub.pluginapi.PluginPageDataContext
import com.lifehub.pluginapi.PluginPageDataHandler
import com.lifehub.pluginapi.PluginStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 地图资产中的紧凑环坐标：`[x0,y0,x1,y1,...]`，坐标已归一化到 0..1。 */
@Serializable
internal data class TravelRegionAsset(
    val id: String,
    val name: String,
    val level: String = "county",
    val label: String = "",
    val labelX: Float = 0.5f,
    val labelY: Float = 0.5f,
    val interactive: Boolean = true,
    val provinceId: String = "",
    val provinceName: String = "",
    val parentId: String = "",
    val parentName: String = "",
    val rings: List<FloatArray> = emptyList(),
)

@Serializable
internal data class TravelMapAsset(
    val aspectRatio: Float = 1.3f,
    val cityId: String = "",
    val cityName: String = "",
    val provinceId: String = "",
    val provinceName: String = "",
    /** 为空表示区县；`town` 表示直筒子市/省直辖县级单位使用街道镇作为最细一级。 */
    val subdivision: String = "",
    val regions: List<TravelRegionAsset> = emptyList(),
)

/** 离线地区索引：`[id, name, level, provinceId, provinceName, cityId, cityName]`。 */
@Serializable
internal data class TravelRegionIndex(
    val regions: List<List<String>> = emptyList(),
)

/** 一个已点亮的地区；点亮与评分、评论完全独立。 */
@Serializable
internal data class TravelPlace(
    val id: String,
    val name: String,
    val level: String = "county",
    val cityId: String = "",
    val cityName: String = "",
    val provinceId: String = "",
    val provinceName: String = "",
    val lit: Boolean = true,
    val visitedAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** 针对整个地区的一次评分，每个地区只保留最新一条。 */
@Serializable
internal data class TravelRating(
    val regionId: String,
    val rating: Int,
    val updatedAt: Long = 0L,
)

/** 一条地区评论，只包含文字，不含评分。 */
@Serializable
internal data class TravelReview(
    val id: String,
    val regionId: String,
    val regionName: String,
    val level: String = "county",
    val cityId: String = "",
    val cityName: String = "",
    val provinceId: String = "",
    val provinceName: String = "",
    val text: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** v1 数据中的旧备注，仅用于迁移到评论集合。 */
@Serializable
internal data class LegacyTravelPlace(
    val id: String,
    val name: String,
    val level: String = "county",
    val cityId: String = "",
    val cityName: String = "",
    val provinceId: String = "",
    val provinceName: String = "",
    val note: String = "",
    val lit: Boolean = true,
    val visitedAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** v2 评论中的随评评分，仅用于迁移到地区评分。 */
@Serializable
internal data class LegacyTravelReview(
    val id: String,
    val regionId: String,
    val regionName: String,
    val level: String = "county",
    val cityId: String = "",
    val cityName: String = "",
    val provinceId: String = "",
    val provinceName: String = "",
    val score: Int = 0,
    val text: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * 旅迹：中国 → 省 → 市 → 区县逐级下钻。
 * 点亮、地区评分、评论三者独立；市评分 = 已点亮且有评分的区县均分，
 * 省评分 = 已点亮且有评分的市均分。
 */
object TravelPlugin : PluginModule {
    const val PLUGIN_ID: String = "com.lifehub.plugin.travel"

    override val pluginId: String = PLUGIN_ID

    const val SOURCE_SUMMARY: String = "travel_summary"
    const val SOURCE_CHINA_MAP: String = "travel_china_map"
    const val SOURCE_PROVINCE_MAP: String = "travel_province_map"
    const val SOURCE_CITY_MAP: String = "travel_city_map"
    const val SOURCE_PLACE_HEADER: String = "travel_place_header"
    const val SOURCE_PLACE_REVIEWS: String = "travel_place_reviews"
    const val SOURCE_REVIEWS: String = "travel_reviews"
    const val SOURCE_SEARCH: String = "travel_search"
    const val SOURCE_REGION_SEARCH: String = "travel_region_search"
    const val SOURCE_RANK_OVERVIEW: String = "travel_rank_overview"
    const val SOURCE_RANK_PROVINCES: String = "travel_rank_provinces"
    const val SOURCE_RANK_CITIES: String = "travel_rank_cities"
    const val SOURCE_RANK_COUNTIES: String = "travel_rank_counties"

    const val PAGE_MAP: String = "map"
    const val PAGE_PROVINCE: String = "province"
    const val PAGE_CITY: String = "city"
    const val PAGE_PLACE: String = "place"
    const val PAGE_REVIEWS: String = "reviews"
    const val PAGE_RANKING: String = "ranking"
    const val PAGE_SEARCH_REGION: String = "region_search"

    const val ACTION_SET_RATING: String = "$PLUGIN_ID.set_rating"
    const val ACTION_ADD_COMMENT: String = "$PLUGIN_ID.add_comment"
    const val ACTION_DELETE_COMMENT: String = "$PLUGIN_ID.delete_comment"
    const val ACTION_LIGHT: String = "$PLUGIN_ID.light"
    const val ACTION_UNLIGHT: String = "$PLUGIN_ID.unlight"
    const val ACTION_SEARCH_REGIONS: String = "$PLUGIN_ID.search_regions"
    const val ACTION_EXPORT_MAP: String = "$PLUGIN_ID.export_map"

    private const val PLACES_COLLECTION = "travel_places"
    private const val RATINGS_COLLECTION = "travel_ratings"
    private const val REVIEWS_COLLECTION = "travel_reviews"
    private const val MAX_COMMENT_LENGTH = 1_000
    private const val MAX_NAME_LENGTH = 80
    private const val MAX_RANK_ITEMS = 20
    private const val PROVINCE_MAP_PREFIX = "data/provinces/"
    private const val CITY_MAP_PREFIX = "data/cities/"
    private const val CHINA_MAP_ASSET = "data/china.json"
    private const val REGION_INDEX_ASSET = "data/regions.json"
    private const val MAX_SEARCH_RESULTS = 50

    private val levelPattern = Regex("^[a-z]{3,12}$")
    private val adcodePattern = Regex("^\\d{6}$")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
    private val assetCache = java.util.concurrent.ConcurrentHashMap<String, TravelMapAsset>()

    private val confirmDeleteComment = PluginConfirmDialog(
        title = "删除评论",
        message = "删除后这条评论会被移除，不影响地区点亮状态和地区评分。",
        confirmLabel = "删除",
        cancelLabel = "取消",
    )

    /** v1/v2 → v3：旧备注转为评论，v2 随评评分聚合为地区评分。 */
    val migration: PluginDataMigration = PluginDataMigration { storage, fromVersion, toVersion ->
        val now = System.currentTimeMillis()
        if (fromVersion < 2 && toVersion >= 2) {
            storage.readAllRecords(PLACES_COLLECTION).forEach { record ->
                val legacy = runCatching {
                    json.decodeFromString<LegacyTravelPlace>(record.payload)
                }.getOrNull() ?: return@forEach
                if (legacy.note.isBlank()) return@forEach
                val commentId = "legacy-${legacy.id}"
                if (storage.get(REVIEWS_COLLECTION, commentId) != null) return@forEach
                val comment = TravelReview(
                    id = commentId,
                    regionId = legacy.id,
                    regionName = legacy.name,
                    level = legacy.level,
                    cityId = legacy.cityId,
                    cityName = legacy.cityName,
                    provinceId = legacy.provinceId,
                    provinceName = legacy.provinceName,
                    text = legacy.note,
                    createdAt = legacy.updatedAt.takeIf { it > 0L } ?: now,
                    updatedAt = legacy.updatedAt.takeIf { it > 0L } ?: now,
                )
                storage.insert(REVIEWS_COLLECTION, json.encodeToString(comment), commentId)
            }
        }
        if (fromVersion < 3 && toVersion >= 3) {
            val scoresByRegion = mutableMapOf<String, MutableList<Int>>()
            storage.readAllRecords(REVIEWS_COLLECTION).forEach { record ->
                val legacy = runCatching {
                    json.decodeFromString<LegacyTravelReview>(record.payload)
                }.getOrNull() ?: return@forEach
                if (legacy.score in 1..5) {
                    scoresByRegion.getOrPut(legacy.regionId) { mutableListOf() }.add(legacy.score)
                }
                val comment = TravelReview(
                    id = legacy.id,
                    regionId = legacy.regionId,
                    regionName = legacy.regionName,
                    level = legacy.level,
                    cityId = legacy.cityId,
                    cityName = legacy.cityName,
                    provinceId = legacy.provinceId,
                    provinceName = legacy.provinceName,
                    text = legacy.text,
                    createdAt = legacy.createdAt,
                    updatedAt = legacy.updatedAt,
                )
                storage.update(REVIEWS_COLLECTION, record.id, json.encodeToString(comment))
            }
            scoresByRegion.forEach { (regionId, scores) ->
                if (storage.get(RATINGS_COLLECTION, regionId) != null) return@forEach
                val rating = TravelRating(
                    regionId = regionId,
                    rating = scores.average().roundToInt().coerceIn(1, 5),
                    updatedAt = now,
                )
                storage.insert(RATINGS_COLLECTION, json.encodeToString(rating), regionId)
            }
        }
    }

    override val actionHandlers: Map<String, PluginActionHandler> = mapOf(
        ACTION_SET_RATING to PluginActionHandler { context -> setRating(context) },
        ACTION_ADD_COMMENT to PluginActionHandler { context -> addComment(context) },
        ACTION_DELETE_COMMENT to PluginActionHandler { context -> deleteComment(context) },
        ACTION_LIGHT to PluginActionHandler { context -> light(context) },
        ACTION_UNLIGHT to PluginActionHandler { context -> unlight(context) },
        ACTION_SEARCH_REGIONS to PluginActionHandler { context -> openRegionSearch(context) },
        ACTION_EXPORT_MAP to PluginActionHandler { context -> exportChinaMap(context) },
    )

    override val dataHandlers: Map<String, PluginPageDataHandler> = mapOf(
        dataSourceKey(PLUGIN_ID, SOURCE_SUMMARY) to PluginPageDataHandler { context ->
            loadSummary(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_CHINA_MAP) to PluginPageDataHandler { context ->
            loadChinaMap(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_PROVINCE_MAP) to PluginPageDataHandler { context ->
            loadProvinceMap(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_CITY_MAP) to PluginPageDataHandler { context ->
            loadCityMap(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_PLACE_HEADER) to PluginPageDataHandler { context ->
            loadPlaceHeader(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_PLACE_REVIEWS) to PluginPageDataHandler { context ->
            loadPlaceComments(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_REVIEWS) to PluginPageDataHandler { context ->
            loadComments(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_SEARCH) to PluginPageDataHandler { context ->
            searchComments(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_REGION_SEARCH) to PluginPageDataHandler { context ->
            searchRegions(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_RANK_OVERVIEW) to PluginPageDataHandler { context ->
            loadRankOverview(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_RANK_PROVINCES) to PluginPageDataHandler { context ->
            loadRankProvinces(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_RANK_CITIES) to PluginPageDataHandler { context ->
            loadRankCities(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_RANK_COUNTIES) to PluginPageDataHandler { context ->
            loadRankCounties(context)
        },
    )

    // ---------- actions ----------

    private fun setRating(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val params = context.request.params
        val regionId = params["regionId"]?.trim().orEmpty()
        if (regionId.isBlank() || regionId.length > 32) {
            return PluginActionResult.Failure("地区信息缺失")
        }
        val rating = params["rating"]?.toIntOrNull() ?: 0
        if (rating !in 1..5) return PluginActionResult.Failure("请选择 1–5 星")
        val now = System.currentTimeMillis()
        val record = TravelRating(regionId = regionId, rating = rating, updatedAt = now)
        val existing = storage.get(RATINGS_COLLECTION, regionId)
        return try {
            if (existing != null) {
                storage.update(RATINGS_COLLECTION, regionId, json.encodeToString(record))
            } else {
                storage.insert(RATINGS_COLLECTION, json.encodeToString(record), regionId)
            }
            PluginActionResult.Refresh("已更新评分")
        } catch (error: Exception) {
            PluginActionResult.Failure("评分失败：${error.message ?: "未知错误"}")
        }
    }

    private fun addComment(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val params = context.request.params
        val regionId = params["regionId"]?.trim().orEmpty()
        if (regionId.isBlank() || regionId.length > 32) {
            return PluginActionResult.Failure("地区信息缺失")
        }
        val regionName = params["regionName"]?.trim().orEmpty()
        if (regionName.isBlank() || regionName.length > MAX_NAME_LENGTH) {
            return PluginActionResult.Failure("地区名称不合法")
        }
        val text = params["comment"].orEmpty().trim()
        if (text.isBlank()) return PluginActionResult.Failure("评论内容不能为空")
        if (text.length > MAX_COMMENT_LENGTH) {
            return PluginActionResult.Failure("评论最多 $MAX_COMMENT_LENGTH 个字")
        }
        val level = params["level"]?.trim().orEmpty()
            .takeIf { levelPattern.matches(it) }
            ?: "county"
        val cityId = params["cityId"]?.trim().orEmpty()
        if (cityId.isNotBlank() && !adcodePattern.matches(cityId)) {
            return PluginActionResult.Failure("城市信息不合法")
        }
        val now = System.currentTimeMillis()
        val review = TravelReview(
            id = UUID.randomUUID().toString(),
            regionId = regionId,
            regionName = regionName,
            level = level,
            cityId = cityId,
            cityName = params["cityName"]?.trim().orEmpty(),
            provinceId = params["provinceId"]?.trim().orEmpty(),
            provinceName = params["provinceName"]?.trim().orEmpty(),
            text = text,
            createdAt = now,
            updatedAt = now,
        )
        return try {
            storage.insert(REVIEWS_COLLECTION, json.encodeToString(review), review.id)
            PluginActionResult.Refresh(
                message = "已发布评论",
                clearInputIds = listOf("comment"),
            )
        } catch (error: Exception) {
            PluginActionResult.Failure("发布评论失败：${error.message ?: "未知错误"}")
        }
    }

    private fun deleteComment(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val id = context.request.params["id"]?.trim().orEmpty()
        if (id.isBlank()) return PluginActionResult.Failure("缺少评论 ID")
        return if (storage.delete(REVIEWS_COLLECTION, id)) {
            PluginActionResult.Refresh("已删除评论")
        } else {
            PluginActionResult.Failure("评论不存在")
        }
    }

    private fun light(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val params = context.request.params
        val regionId = params["regionId"]?.trim().orEmpty()
        if (regionId.isBlank() || regionId.length > 32) {
            return PluginActionResult.Failure("地区信息缺失")
        }
        val regionName = params["regionName"]?.trim().orEmpty()
        if (regionName.isBlank() || regionName.length > MAX_NAME_LENGTH) {
            return PluginActionResult.Failure("地区名称不合法")
        }
        val existing = storage.get(PLACES_COLLECTION, regionId)?.let { record ->
            runCatching { json.decodeFromString<TravelPlace>(record.payload) }.getOrNull()
        }
        if (existing?.lit == true) return PluginActionResult.ShowMessage("这里已经点亮")
        val level = params["level"]?.trim().orEmpty()
            .takeIf { levelPattern.matches(it) }
            ?: "county"
        val cityId = params["cityId"]?.trim().orEmpty()
        if (cityId.isNotBlank() && !adcodePattern.matches(cityId)) {
            return PluginActionResult.Failure("城市信息不合法")
        }
        val now = System.currentTimeMillis()
        val place = TravelPlace(
            id = regionId,
            name = regionName,
            level = level,
            cityId = cityId.ifBlank { existing?.cityId.orEmpty() },
            cityName = params["cityName"]?.trim().orEmpty().ifBlank { existing?.cityName.orEmpty() },
            provinceId = params["provinceId"]?.trim().orEmpty()
                .ifBlank { existing?.provinceId.orEmpty() },
            provinceName = params["provinceName"]?.trim().orEmpty()
                .ifBlank { existing?.provinceName.orEmpty() },
            lit = true,
            visitedAt = existing?.visitedAt?.takeIf { it > 0L } ?: now,
            updatedAt = now,
        )
        return try {
            if (existing != null) {
                storage.update(PLACES_COLLECTION, regionId, json.encodeToString(place))
            } else {
                storage.insert(PLACES_COLLECTION, json.encodeToString(place), regionId)
            }
            PluginActionResult.Refresh("已点亮")
        } catch (error: Exception) {
            PluginActionResult.Failure("点亮失败：${error.message ?: "未知错误"}")
        }
    }

    private fun unlight(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val regionId = context.request.params["regionId"]?.trim().orEmpty()
        if (regionId.isBlank()) return PluginActionResult.Failure("地区信息缺失")
        if (storage.get(PLACES_COLLECTION, regionId) == null) {
            return PluginActionResult.ShowMessage("这里还没有点亮")
        }
        return if (storage.delete(PLACES_COLLECTION, regionId)) {
            PluginActionResult.Refresh("已取消点亮")
        } else {
            PluginActionResult.ShowMessage("这里还没有点亮")
        }
    }

    private fun openRegionSearch(context: PluginActionContext): PluginActionResult {
        val query = context.request.params["query"]?.trim().orEmpty()
        if (query.isBlank()) return PluginActionResult.ShowMessage("请输入要搜索的地名")
        return PluginActionResult.Navigate(
            PAGE_SEARCH_REGION,
            mapOf("query" to query.take(40)),
        )
    }

    private fun exportChinaMap(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val reader = context.assetReader
            ?: return PluginActionResult.Failure("插件资产不可用")
        val raw = reader(CHINA_MAP_ASSET)
            ?: return PluginActionResult.Failure("内置中国地图资产不可用")
        val asset = assetCache[CHINA_MAP_ASSET]
            ?: runCatching { json.decodeFromString<TravelMapAsset>(raw) }.getOrNull()
            ?: return PluginActionResult.Failure("内置中国地图资产解析失败")
        assetCache[CHINA_MAP_ASSET] = asset
        return runCatching {
            val png = renderChinaMapPng(asset, readSnapshot(storage))
            val fileDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            PluginActionResult.ExportImage(
                fileName = "旅迹-中国足迹-$fileDate.png",
                pngBytes = png,
            )
        }.getOrElse { error ->
            PluginActionResult.Failure("导出失败：${error.message ?: "未知错误"}")
        }
    }

    // ---------- data sources ----------

    private fun loadSummary(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return errorResult("插件未获得私有存储授权")
        val snapshot = readSnapshot(storage)
        val cityCount = snapshot.litCityIds.count { it.isNotBlank() }
        return PluginDataResult(
            metrics = listOf(
                PluginMetric(label = "到过的城市", value = cityCount.toString()),
                PluginMetric(label = "点亮区域", value = snapshot.litPlaces.size.toString()),
                PluginMetric(label = "旅行评论", value = snapshot.reviews.size.toString()),
            ),
            emptyTitle = if (snapshot.places.isEmpty()) "还没有点亮任何地方" else "",
            emptyMessage = if (snapshot.places.isEmpty()) {
                "打开地图，逐级点亮你去过的省市与区县。"
            } else {
                ""
            },
        )
    }

    private fun loadChinaMap(context: PluginPageDataContext): PluginDataResult {
        val asset = loadAsset(context, CHINA_MAP_ASSET)
            ?: return errorResult("内置中国地图资产不可用")
        val snapshot = readSnapshot(context.storage)
        val cityCount = snapshot.litCityIds.count { it.isNotBlank() }
        val regions = asset.regions.mapNotNull { region ->
            when (region.geoLevel()) {
                PluginGeoLevel.PROVINCE -> region.toGeoRegion(
                    level = PluginGeoLevel.PROVINCE,
                    lit = false,
                    interactive = true,
                    label = region.label.ifBlank { region.name } +
                        ratingSuffix(snapshot.provinceRating(region.id)),
                    params = mapOf(
                        "provinceId" to region.id,
                        "provinceName" to region.name,
                    ),
                )

                PluginGeoLevel.CITY -> region.toGeoRegion(
                    level = PluginGeoLevel.CITY,
                    lit = region.id in snapshot.litCityIds,
                    // 全国地图上点击省份进入省页，市只负责显示轮廓与点亮状态，不显示市名与评分。
                    interactive = false,
                    label = "",
                    params = region.regionParams(),
                )

                PluginGeoLevel.COUNTY -> null
            }
        }
        val unratedCityCount = snapshot.litCityIds.count { cityId ->
            cityId.isNotBlank() && snapshot.cityRating(cityId) == null
        }
        val subtitle = if (cityCount == 0) {
            "点击省份，查看去过的城市"
        } else {
            buildString {
                append("已点亮 ").append(cityCount).append(" 座城市")
                if (unratedCityCount > 0) {
                    append(" · ").append(unratedCityCount).append(" 个城市待评分")
                }
            }
        }
        return PluginDataResult(
            geo = PluginGeoMapData(
                title = "旅迹·中国",
                subtitle = subtitle,
                aspectRatio = asset.aspectRatio,
                regions = regions,
            ),
        )
    }

    private fun loadProvinceMap(context: PluginPageDataContext): PluginDataResult {
        val params = context.request.params
        val provinceId = params["provinceId"]?.trim().orEmpty()
            .ifBlank { params["regionId"]?.trim().orEmpty() }
        if (!adcodePattern.matches(provinceId)) {
            return errorResult("省份信息缺失，请返回地图重新选择")
        }
        val asset = loadAsset(context, "$PROVINCE_MAP_PREFIX$provinceId.json")
            ?: return errorResult("缺少这张省份地图")
        val snapshot = readSnapshot(context.storage)
        val provinceName = params["provinceName"]?.trim().orEmpty()
            .ifBlank { asset.provinceName }
        val cityRegions = asset.regions.filter { it.geoLevel() == PluginGeoLevel.CITY }
        val litCount = cityRegions.count { it.id in snapshot.litCityIds }
        val regions = asset.regions.mapNotNull { region ->
            when (region.geoLevel()) {
                PluginGeoLevel.PROVINCE -> region.toGeoRegion(
                    level = PluginGeoLevel.PROVINCE,
                    lit = false,
                    interactive = false,
                    label = "",
                    params = emptyMap(),
                )

                PluginGeoLevel.CITY -> {
                    val lit = region.id in snapshot.litCityIds
                    region.toGeoRegion(
                        level = PluginGeoLevel.CITY,
                        lit = lit,
                        interactive = true,
                        label = region.label.ifBlank { region.name } +
                            if (lit) ratingSuffix(snapshot.cityRating(region.id)) else "",
                        params = region.regionParams(),
                    )
                }

                PluginGeoLevel.COUNTY -> null
            }
        }
        val rating = snapshot.provinceRating(provinceId)
        val unratedCityCount = cityRegions.count { region ->
            region.id in snapshot.litCityIds && snapshot.cityRating(region.id) == null
        }
        val subtitle = buildString {
            append("已点亮 ").append(litCount).append("/").append(cityRegions.size).append(" 个市")
            rating?.let { append(" · 平均评分 ").append(formatRating(it)) }
            if (unratedCityCount > 0) {
                append(" · ").append(unratedCityCount).append(" 个市待评分")
            }
        }
        return PluginDataResult(
            geo = PluginGeoMapData(
                title = provinceName.ifBlank { asset.provinceName },
                subtitle = subtitle,
                aspectRatio = asset.aspectRatio,
                regions = regions,
            ),
        )
    }

    private fun loadCityMap(context: PluginPageDataContext): PluginDataResult {
        val params = context.request.params
        val cityId = params["cityId"]?.trim().orEmpty()
        if (!adcodePattern.matches(cityId)) {
            return errorResult("城市信息缺失，请返回地图重新选择")
        }
        val asset = loadAsset(context, "$CITY_MAP_PREFIX$cityId.json")
            ?: return errorResult("缺少这张城市地图")
        val snapshot = readSnapshot(context.storage)
        val cityName = params["cityName"]?.trim().orEmpty()
            .ifBlank { asset.cityName }
        val provinceName = params["provinceName"]?.trim().orEmpty()
            .ifBlank { asset.provinceName }
        val provinceId = params["provinceId"]?.trim().orEmpty()
            .ifBlank { asset.provinceId }
        val regions = asset.regions.map { region ->
            val lit = region.id in snapshot.litIds
            PluginGeoRegion(
                id = region.id,
                name = region.name,
                level = PluginGeoLevel.COUNTY,
                parentId = cityId,
                parentName = cityName,
                provinceId = provinceId,
                provinceName = provinceName,
                lit = lit,
                interactive = true,
                label = region.label.ifBlank { region.name } +
                    if (lit) ratingSuffix(snapshot.regionRating(region.id)) else "",
                labelX = region.labelX,
                labelY = region.labelY,
                params = buildMap {
                    put("cityId", cityId)
                    put("cityName", cityName)
                    if (provinceId.isNotBlank()) put("provinceId", provinceId)
                    if (provinceName.isNotBlank()) put("provinceName", provinceName)
                    snapshot.ratings[region.id]?.let { put("rating", it.toString()) }
                },
                rings = region.rings.map { it.toGeoPoints() },
            )
        }
        val litCount = regions.count { it.lit }
        val rating = snapshot.cityRating(cityId)
        val leafLabel = if (asset.subdivision == "town") "街道/镇" else "区县"
        val unratedCountyCount = regions.count { region ->
            region.lit && snapshot.regionRating(region.id) == null
        }
        val subtitle = buildString {
            if (provinceName.isNotBlank()) append(provinceName).append(" · ")
            append("已点亮 ").append(litCount).append("/").append(regions.size)
                .append(" 个").append(leafLabel)
            rating?.let { append(" · 平均评分 ").append(formatRating(it)) }
            if (unratedCountyCount > 0) {
                append(" · ").append(unratedCountyCount).append(" 个").append(leafLabel).append("待评分")
            }
        }
        return PluginDataResult(
            geo = PluginGeoMapData(
                title = cityName.ifBlank { asset.cityName },
                subtitle = subtitle,
                aspectRatio = asset.aspectRatio,
                regions = regions,
            ),
        )
    }

    private fun loadPlaceHeader(context: PluginPageDataContext): PluginDataResult {
        val params = context.request.params
        val regionId = params["regionId"]?.trim().orEmpty()
        if (regionId.isBlank()) return errorResult("地区信息缺失")
        val regionName = params["regionName"]?.trim().orEmpty().ifBlank { "这片区域" }
        val cityName = params["cityName"]?.trim().orEmpty()
        val provinceName = params["provinceName"]?.trim().orEmpty()
        val snapshot = context.storage?.let { readSnapshot(it) }
        val lit = snapshot?.litIds?.contains(regionId) == true
        val rating = snapshot?.regionRating(regionId)
        val commentCount = snapshot?.regionCommentCount(regionId) ?: 0
        val location = listOf(provinceName, cityName)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
            .ifBlank { "旅行足迹" }
        val state = buildString {
            append(if (lit) "已点亮" else "未点亮")
            append(" · ")
            append(if (rating != null) "评分 ${formatRating(rating)}" else "未评分")
            append(" · ")
            append(commentCount)
            append(" 条评论")
        }
        return PluginDataResult(
            metrics = listOf(
                PluginMetric(label = location, value = regionName),
            ),
            periodLabel = state,
        )
    }

    private fun loadPlaceComments(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return errorResult("插件未获得私有存储授权")
        val regionId = context.request.params["regionId"]?.trim().orEmpty()
        if (regionId.isBlank()) return errorResult("地区信息缺失")
        val items = readReviews(storage)
            .filter { it.regionId == regionId }
            .sortedByDescending { it.createdAt }
            .take(PluginDataLimits.MAX_ITEMS)
            .map { review ->
                PluginDataItem(
                    id = review.id,
                    title = review.text.take(360).ifBlank { "未填写评论" },
                    subtitle = formatDate(review.createdAt),
                    titleMaxLines = 4,
                    subtitleMaxLines = 1,
                    roomy = true,
                    swipeLeftAction = deleteCommentAction(review.id),
                    actions = listOf(deleteCommentAction(review.id)),
                )
            }
        return PluginDataResult(
            items = items,
            emptyTitle = "还没有评论",
            emptyMessage = "写点什么，记录你在这里的经历与感受。",
        )
    }

    private fun loadComments(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return errorResult("插件未获得私有存储授权")
        val snapshot = readSnapshot(storage)
        val items = snapshot.reviews
            .sortedByDescending { it.createdAt }
            .take(PluginDataLimits.MAX_ITEMS)
            .map { it.toCommentItem(snapshot.ratings, compact = false) }
        return PluginDataResult(
            items = items,
            emptyTitle = "还没有旅行评论",
            emptyMessage = "打开地图，点亮去过的省市与区县，写下第一条评论。",
        )
    }

    private fun searchComments(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return errorResult("插件未获得私有存储授权")
        val query = context.request.params["query"]?.trim().orEmpty()
        if (query.isBlank()) {
            return PluginDataResult(
                emptyTitle = "输入关键词",
                emptyMessage = "搜索城市、区县或评论内容。",
            )
        }
        val snapshot = readSnapshot(storage)
        val limit = (context.request.params["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 20)
        val items = snapshot.reviews
            .filter { review ->
                review.regionName.contains(query, ignoreCase = true) ||
                    review.cityName.contains(query, ignoreCase = true) ||
                    review.provinceName.contains(query, ignoreCase = true) ||
                    review.text.contains(query, ignoreCase = true)
            }
            .sortedByDescending { it.createdAt }
            .take(limit)
            .map { it.toCommentItem(snapshot.ratings, compact = true) }
        return PluginDataResult(
            items = items,
            emptyTitle = "没有匹配的评论",
            emptyMessage = "换个地区名或关键词试试。",
        )
    }

    // ---------- region search ----------

    private fun searchRegions(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return errorResult("插件未获得私有存储授权")
        val query = context.request.params["query"]?.trim().orEmpty()
        if (query.isBlank()) {
            return PluginDataResult(
                emptyTitle = "输入地名开始搜索",
                emptyMessage = "支持省、市、区县与镇街名称，例如 杭州、会昌县、虎门镇。",
            )
        }
        val raw = context.assetReader?.invoke(REGION_INDEX_ASSET)
            ?: return errorResult("地区索引不可用")
        val index = runCatching { json.decodeFromString<TravelRegionIndex>(raw) }
            .getOrElse { return errorResult("地区索引解析失败") }
        val snapshot = readSnapshot(storage)
        val items = index.regions
            .asSequence()
            .filter { entry -> entry.size >= 7 && entry[1].contains(query, ignoreCase = true) }
            .sortedWith(
                compareBy<List<String>>(
                    { entry -> searchMatchRank(entry[1], query) },
                    { entry -> entry[1].length },
                    { entry -> searchLevelRank(entry[2]) },
                ),
            )
            .take(MAX_SEARCH_RESULTS)
            .map { entry -> entry.toSearchItem(snapshot) }
            .toList()
        return PluginDataResult(
            items = items,
            emptyTitle = "没有找到「$query」",
            emptyMessage = "换个名称试试，或直接打开地图逐级点亮。",
        )
    }

    private fun searchMatchRank(name: String, query: String): Int = when {
        name.equals(query, ignoreCase = true) -> 0
        name.startsWith(query, ignoreCase = true) -> 1
        else -> 2
    }

    private fun searchLevelRank(level: String): Int = when (level) {
        "p" -> 0
        "c" -> 1
        else -> 2
    }

    private fun List<String>.toSearchItem(snapshot: TravelSnapshot): PluginDataItem {
        val id = this[0]
        val name = this[1]
        val level = this[2]
        val provinceId = this[3]
        val provinceName = this[4]
        val cityId = this[5]
        val cityName = this[6]
        val lit = when (level) {
            "d" -> id in snapshot.litIds
            "c" -> id in snapshot.litCityIds
            else -> false
        }
        val rating = when (level) {
            "d" -> snapshot.regionRating(id)
            "c" -> snapshot.cityRating(id)
            else -> snapshot.provinceRating(id)
        }
        val location = listOf(provinceName, cityName)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
        val subtitle = buildString {
            if (location.isNotBlank()) append(location)
            if (level == "d") {
                if (isNotEmpty()) append(" · ")
                append(if (lit) "已点亮" else "未点亮")
            }
            if (rating != null) {
                if (isNotEmpty()) append(" · ")
                append("评分 ").append(formatRating(rating))
            }
        }.ifBlank { "旅行足迹" }
        val placeParams = searchPlaceParams(
            id = id,
            name = name,
            provinceId = provinceId,
            provinceName = provinceName,
            cityId = cityId,
            cityName = cityName,
            lit = lit,
            rating = rating,
        )
        val openAction = when (level) {
            "p" -> PluginAction.Navigate(
                PAGE_PROVINCE,
                mapOf("provinceId" to id, "provinceName" to name),
            )
            "c" -> PluginAction.Navigate(
                PAGE_CITY,
                mapOf(
                    "cityId" to id,
                    "cityName" to name,
                    "provinceId" to provinceId,
                    "provinceName" to provinceName,
                ),
            )
            else -> PluginAction.Navigate(PAGE_PLACE, placeParams)
        }
        val actions = when (level) {
            "d" -> buildList {
                val lightAction = if (lit) ACTION_UNLIGHT else ACTION_LIGHT
                add(
                    PluginDataAction(
                        label = if (lit) "取消点亮" else "点亮",
                        action = PluginAction.Host(lightAction, placeParams),
                    ),
                )
                add(
                    PluginDataAction(
                        label = if (rating != null) "修改评分" else "评分/评论",
                        action = openAction,
                    ),
                )
            }
            else -> listOf(
                PluginDataAction(
                    label = if (level == "p") "查看省地图" else "查看市地图",
                    action = openAction,
                ),
            )
        }
        return PluginDataItem(
            id = "search-$level-$id",
            title = name,
            subtitle = subtitle,
            leadingText = when (level) {
                "p" -> "省"
                "c" -> "市"
                else -> "区"
            },
            trailing = rating?.let { formatRating(it) }
                ?: if (level == "d" && !lit) "未点亮" else "",
            clickAction = openAction,
            actions = actions,
        )
    }

    private fun searchPlaceParams(
        id: String,
        name: String,
        provinceId: String,
        provinceName: String,
        cityId: String,
        cityName: String,
        lit: Boolean,
        rating: Double?,
    ): Map<String, String> = buildMap {
        put("regionId", id)
        put("regionName", name)
        put("level", "county")
        put("lit", if (lit) "1" else "0")
        if (provinceId.isNotBlank()) put("provinceId", provinceId)
        if (provinceName.isNotBlank()) put("provinceName", provinceName)
        put("cityId", cityId.ifBlank { id })
        put("cityName", cityName.ifBlank { name })
        rating?.let { put("rating", it.roundToInt().toString()) }
    }

    // ---------- ranking ----------

    private fun loadRankOverview(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return errorResult("插件未获得私有存储授权")
        val snapshot = readSnapshot(storage)
        val ratedRegionCount = snapshot.ratedLitPlaces.size
        val unratedRegionCount = snapshot.unratedLitPlaces.size
        val ratedCityCount = snapshot.cityRankEntries().size
        val ratedProvinceCount = snapshot.provinceRankEntries().size
        val hint = when {
            unratedRegionCount > 0 ->
                "还有 $unratedRegionCount 个已点亮地区没有评分，去地图补上吧"
            snapshot.litPlaces.isEmpty() -> "点亮地区后，就可以给它们打分并上榜"
            else -> "所有已点亮地区都已评分"
        }
        return PluginDataResult(
            metrics = listOf(
                PluginMetric(label = "已评分的地区", value = ratedRegionCount.toString()),
                PluginMetric(label = "已点亮未评分", value = unratedRegionCount.toString()),
                PluginMetric(label = "评分的城市", value = ratedCityCount.toString()),
                PluginMetric(label = "评分的省份", value = ratedProvinceCount.toString()),
            ),
            periodLabel = hint,
        )
    }

    private fun loadRankProvinces(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return errorResult("插件未获得私有存储授权")
        return PluginDataResult(
            items = readSnapshot(storage)
                .provinceRankEntries()
                .take(MAX_RANK_ITEMS)
                .toRankItems(PAGE_PROVINCE),
            emptyTitle = "还没有评分的省份",
            emptyMessage = "先给已点亮的城市评分，省份评分会自动生成。",
        )
    }

    private fun loadRankCities(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return errorResult("插件未获得私有存储授权")
        return PluginDataResult(
            items = readSnapshot(storage)
                .cityRankEntries()
                .take(MAX_RANK_ITEMS)
                .toRankItems(PAGE_CITY),
            emptyTitle = "还没有评分的城市",
            emptyMessage = "进入城市，给已点亮的区县评分后，城市会进入榜单。",
        )
    }

    private fun loadRankCounties(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return errorResult("插件未获得私有存储授权")
        return PluginDataResult(
            items = readSnapshot(storage)
                .countyRankEntries()
                .take(MAX_RANK_ITEMS)
                .toRankItems(PAGE_PLACE),
            emptyTitle = "还没有评分的区县",
            emptyMessage = "点亮区县并提交星级评分后，会出现在这里。",
        )
    }

    private fun TravelSnapshot.provinceRankEntries(): List<TravelRankEntry> =
        litPlaces.filter { it.provinceId.isNotBlank() }
            .groupBy { it.provinceId }
            .mapNotNull { (provinceId, places) ->
                val rating = provinceRating(provinceId) ?: return@mapNotNull null
                val cityIds = places.map { it.cityId }.filter { it.isNotBlank() }.distinct()
                val ratedCityCount = cityIds.count { cityRating(it) != null }
                if (ratedCityCount == 0) return@mapNotNull null
                val first = places.first()
                TravelRankEntry(
                    id = provinceId,
                    name = shortProvinceName(first.provinceName),
                    subtitle = "$ratedCityCount 个城市已评分",
                    rating = rating,
                    ratingCount = ratedCityCount,
                    provinceId = provinceId,
                    provinceName = first.provinceName,
                    cityId = "",
                    cityName = "",
                )
            }
            .sortedWith(rankComparator())

    private fun TravelSnapshot.cityRankEntries(): List<TravelRankEntry> =
        litPlaces.filter { it.cityId.isNotBlank() }
            .groupBy { it.cityId }
            .mapNotNull { (cityId, places) ->
                val rating = cityRating(cityId) ?: return@mapNotNull null
                val ratedCount = places.count { regionRating(it.id) != null }
                val first = places.first()
                TravelRankEntry(
                    id = cityId,
                    name = first.cityName,
                    subtitle = "${first.provinceName} · $ratedCount 个区县已评分",
                    rating = rating,
                    ratingCount = ratedCount,
                    provinceId = first.provinceId,
                    provinceName = first.provinceName,
                    cityId = cityId,
                    cityName = first.cityName,
                )
            }
            .sortedWith(rankComparator())

    private fun TravelSnapshot.countyRankEntries(): List<TravelRankEntry> =
        litPlaces.mapNotNull { place ->
            val rating = regionRating(place.id) ?: return@mapNotNull null
            TravelRankEntry(
                id = place.id,
                name = place.name,
                subtitle = listOf(place.provinceName, place.cityName)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                rating = rating,
                ratingCount = 1,
                provinceId = place.provinceId,
                provinceName = place.provinceName,
                cityId = place.cityId,
                cityName = place.cityName,
                regionName = place.name,
            )
        }.sortedWith(rankComparator())

    private fun rankComparator(): Comparator<TravelRankEntry> =
        compareByDescending<TravelRankEntry> { it.rating }
            .thenByDescending { it.ratingCount }
            .thenBy { it.name }

    private fun List<TravelRankEntry>.toRankItems(page: String): List<PluginDataItem> =
        mapIndexed { index, entry ->
            val rank = index + 1
            val leading = when (rank) {
                1 -> "🥇"
                2 -> "🥈"
                3 -> "🥉"
                else -> rank.toString()
            }
            val clickAction = when (page) {
                PAGE_PROVINCE -> PluginAction.Navigate(
                    PAGE_PROVINCE,
                    mapOf(
                        "provinceId" to entry.provinceId,
                        "provinceName" to entry.provinceName,
                    ),
                )

                PAGE_CITY -> PluginAction.Navigate(
                    PAGE_CITY,
                    mapOf(
                        "cityId" to entry.cityId,
                        "cityName" to entry.cityName,
                        "provinceId" to entry.provinceId,
                        "provinceName" to entry.provinceName,
                    ),
                )

                else -> PluginAction.Navigate(
                    PAGE_PLACE,
                    buildMap {
                        put("regionId", entry.id)
                        put("regionName", entry.regionName)
                        put("level", "county")
                        if (entry.cityId.isNotBlank()) put("cityId", entry.cityId)
                        if (entry.cityName.isNotBlank()) put("cityName", entry.cityName)
                        if (entry.provinceId.isNotBlank()) put("provinceId", entry.provinceId)
                        if (entry.provinceName.isNotBlank()) {
                            put("provinceName", entry.provinceName)
                        }
                        put("rating", entry.rating.roundToInt().toString())
                    },
                )
            }
            PluginDataItem(
                id = "$page-${entry.id}",
                title = entry.name,
                subtitle = entry.subtitle,
                leadingText = leading,
                trailing = formatRating(entry.rating),
                clickAction = clickAction,
            )
        }

    private data class TravelRankEntry(
        val id: String,
        val name: String,
        val subtitle: String,
        val rating: Double,
        val ratingCount: Int,
        val provinceId: String,
        val provinceName: String,
        val cityId: String,
        val cityName: String,
        val regionName: String = "",
    )

    // ---------- helpers ----------

    private fun readSnapshot(storage: PluginStorage?): TravelSnapshot {
        if (storage == null) {
            return TravelSnapshot(emptyList(), emptyMap(), emptyList())
        }
        return TravelSnapshot(
            places = readPlaces(storage),
            ratings = readRatings(storage),
            reviews = readReviews(storage),
        )
    }

    private fun readPlaces(storage: PluginStorage): List<TravelPlace> =
        storage.readAllRecords(PLACES_COLLECTION).mapNotNull { record ->
            runCatching { json.decodeFromString<TravelPlace>(record.payload) }.getOrNull()
        }

    private fun readRatings(storage: PluginStorage): Map<String, Int> =
        storage.readAllRecords(RATINGS_COLLECTION).mapNotNull { record ->
            runCatching { json.decodeFromString<TravelRating>(record.payload) }.getOrNull()
        }.associate { it.regionId to it.rating }

    private fun readReviews(storage: PluginStorage): List<TravelReview> =
        storage.readAllRecords(REVIEWS_COLLECTION).mapNotNull { record ->
            runCatching { json.decodeFromString<TravelReview>(record.payload) }.getOrNull()
        }

    private fun loadAsset(context: PluginPageDataContext, path: String): TravelMapAsset? {
        assetCache[path]?.let { return it }
        val reader = context.assetReader ?: return null
        val raw = reader(path) ?: return null
        val asset = runCatching { json.decodeFromString<TravelMapAsset>(raw) }.getOrNull()
            ?: return null
        assetCache[path] = asset
        return asset
    }

    private fun TravelRegionAsset.geoLevel(): PluginGeoLevel = when (level) {
        "province" -> PluginGeoLevel.PROVINCE
        "city" -> PluginGeoLevel.CITY
        else -> PluginGeoLevel.COUNTY
    }

    private fun TravelRegionAsset.regionParams(): Map<String, String> = buildMap {
        put("cityId", id)
        put("cityName", name)
        if (provinceId.isNotBlank()) put("provinceId", provinceId)
        if (provinceName.isNotBlank()) put("provinceName", provinceName)
    }

    private fun TravelRegionAsset.toGeoRegion(
        level: PluginGeoLevel,
        lit: Boolean,
        interactive: Boolean,
        label: String,
        params: Map<String, String>,
    ): PluginGeoRegion = PluginGeoRegion(
        id = id,
        name = name,
        level = level,
        parentId = parentId,
        parentName = parentName,
        provinceId = provinceId,
        provinceName = provinceName,
        lit = lit,
        interactive = interactive,
        label = label,
        labelX = labelX,
        labelY = labelY,
        params = params,
        rings = rings.map { it.toGeoPoints() },
    )

    private fun FloatArray.toGeoPoints(): List<PluginGeoPoint> {
        if (size < 6) return emptyList()
        val points = ArrayList<PluginGeoPoint>(size / 2)
        var index = 0
        while (index + 1 < size) {
            points += PluginGeoPoint(x = this[index], y = this[index + 1])
            index += 2
        }
        return points
    }

    private fun deleteCommentAction(reviewId: String): PluginDataAction = PluginDataAction(
        label = "删除",
        action = PluginAction.Host(
            ACTION_DELETE_COMMENT,
            mapOf("id" to reviewId),
            confirm = confirmDeleteComment,
        ),
    )

    private fun TravelReview.toCommentItem(
        ratings: Map<String, Int>,
        compact: Boolean,
    ): PluginDataItem {
        val location = listOf(provinceName, cityName, regionName)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
        val rating = ratings[regionId]
        return PluginDataItem(
            id = id,
            title = location,
            subtitle = commentPreview(text, if (compact) 120 else 200)
                .ifBlank { "未填写评论" },
            leadingText = "📍",
            footer = buildString {
                append(formatDate(createdAt))
                append(" · ")
                append(if (rating != null) "地区评分 ${rating} 星" else "地区未评分")
            },
            titleMaxLines = 1,
            subtitleMaxLines = 1,
            clickAction = PluginAction.Navigate(PAGE_PLACE, regionParams(rating)),
            swipeLeftAction = deleteCommentAction(id),
            actions = listOf(
                PluginDataAction(
                    label = "查看",
                    action = PluginAction.Navigate(PAGE_PLACE, regionParams(rating)),
                ),
                deleteCommentAction(id),
            ),
        )
    }

    private fun TravelReview.regionParams(rating: Int?): Map<String, String> = buildMap {
        put("regionId", regionId)
        put("regionName", regionName)
        put("level", level)
        if (cityId.isNotBlank()) put("cityId", cityId)
        if (cityName.isNotBlank()) put("cityName", cityName)
        if (provinceId.isNotBlank()) put("provinceId", provinceId)
        if (provinceName.isNotBlank()) put("provinceName", provinceName)
        if (rating != null) put("rating", rating.toString())
    }

    private fun ratingSuffix(value: Double?): String =
        value?.let { " ${formatRating(it)}" }.orEmpty()

    private fun formatRating(value: Double): String =
        String.format(Locale.US, "%.1f", value)

    // ---------- 高清地图导出 ----------

    private fun renderChinaMapPng(asset: TravelMapAsset, snapshot: TravelSnapshot): ByteArray {
        val width = 3072
        val aspect = asset.aspectRatio
            .takeIf { it.isFinite() && it > 0.5f }
            ?.coerceIn(0.8f, 2.2f)
            ?: 1.3f
        val mapHeight = (width / aspect).roundToInt()
        val outerPadding = (width * 0.035f).roundToInt()
        val titleHeight = (width * 0.09f).roundToInt()
        val totalWidth = width + outerPadding * 2
        val totalHeight = outerPadding * 2 + titleHeight + mapHeight
        val bitmap = createBitmap(totalWidth, totalHeight)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(243, 247, 245))

        val left = outerPadding.toFloat()
        val mapTop = (outerPadding + titleHeight).toFloat()
        val mapRect = RectF(left, mapTop, left + width, mapTop + mapHeight)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(31, 61, 50)
            textSize = width * 0.026f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("旅迹·中国", left, outerPadding + width * 0.030f, titlePaint)
        val litCityCount = snapshot.litCityIds.count { it.isNotBlank() }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(96, 115, 106)
            textSize = width * 0.0125f
        }
        canvas.drawText(
            "已点亮 $litCityCount 座城市 · ${snapshot.litPlaces.size} 个地区",
            left,
            outerPadding + width * 0.057f,
            subtitlePaint,
        )

        val seaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232, 243, 239) }
        val corner = width * 0.012f
        canvas.drawRoundRect(mapRect, corner, corner, seaPaint)

        canvas.withClip(mapRect) {
        val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(247, 250, 248) }
        val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(96, 168, 133) }
        val thinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width * 0.00055f
            color = Color.rgb(190, 208, 199)
        }
        val litLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width * 0.0009f
            color = Color.rgb(66, 128, 100)
        }
        val provincePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width * 0.0016f
            color = Color.rgb(82, 108, 96)
        }

        asset.regions.forEach { region ->
            if (region.level == "province") return@forEach
            val lit = region.id in snapshot.litCityIds
            val path = region.toExportPath(mapRect)
            // 无名区域（九段线与插图边框）只描边，不填充。
            if (region.name.isNotBlank()) {
                canvas.drawPath(path, if (lit) litPaint else landPaint)
            }
            canvas.drawPath(path, if (lit) litLinePaint else thinPaint)
        }
        asset.regions.forEach { region ->
            if (region.level != "province") return@forEach
            canvas.drawPath(region.toExportPath(mapRect), provincePaint)
        }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = Color.rgb(40, 60, 52)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            style = Paint.Style.STROKE
            strokeWidth = width * 0.0026f
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        asset.regions
            .filter { it.level == "province" && it.label.isNotBlank() }
            .forEach { region ->
                val textSize = width * 0.0125f * region.exportAreaFactor()
                labelPaint.textSize = textSize
                haloPaint.textSize = textSize
                val text = region.label + ratingSuffix(snapshot.provinceRating(region.id))
                val x = mapRect.left + region.labelX * mapRect.width()
                val y = mapRect.top + region.labelY * mapRect.height() + textSize * 0.35f
                canvas.drawText(text, x, y, haloPaint)
                canvas.drawText(text, x, y, labelPaint)
            }

        // 左下角导出日期水印。
        val dateText = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/M/d"))
        val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(77, 96, 87)
            textSize = width * 0.014f
        }
        val padding = width * 0.014f
        val boxHeight = width * 0.024f
        val box = RectF(
            mapRect.left + padding,
            mapRect.bottom - padding - boxHeight,
            mapRect.left + padding + watermarkPaint.measureText(dateText) + width * 0.016f,
            mapRect.bottom - padding,
        )
        canvas.drawRoundRect(
            box,
            boxHeight * 0.25f,
            boxHeight * 0.25f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(216, 255, 255, 255) },
        )
        canvas.drawText(
            dateText,
            box.left + width * 0.008f,
            box.bottom - boxHeight * 0.28f,
            watermarkPaint,
        )
        }

        canvas.drawRoundRect(
            mapRect,
            corner,
            corner,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = width * 0.0008f
                color = Color.rgb(178, 198, 189)
            },
        )

        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        }
    }

    private fun TravelRegionAsset.toExportPath(rect: RectF): Path {
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD
        rings.forEach { ring ->
            var index = 0
            var started = false
            while (index + 1 < ring.size) {
                val x = rect.left + ring[index].coerceIn(0f, 1f) * rect.width()
                val y = rect.top + ring[index + 1].coerceIn(0f, 1f) * rect.height()
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
                index += 2
            }
            if (started) path.close()
        }
        return path
    }

    private fun TravelRegionAsset.exportAreaFactor(): Float {
        var minX = 1f
        var maxX = 0f
        var minY = 1f
        var maxY = 0f
        rings.forEach { ring ->
            var index = 0
            while (index + 1 < ring.size) {
                minX = minOf(minX, ring[index])
                maxX = maxOf(maxX, ring[index])
                minY = minOf(minY, ring[index + 1])
                maxY = maxOf(maxY, ring[index + 1])
                index += 2
            }
        }
        val area = ((maxX - minX).coerceAtLeast(0f)) * ((maxY - minY).coerceAtLeast(0f))
        return (0.9f + 0.6f * sqrt(area.coerceIn(0f, 1f))).coerceIn(0.9f, 1.35f)
    }

    private fun shortProvinceName(name: String): String {
        val suffixes = listOf(
            "维吾尔自治区",
            "壮族自治区",
            "回族自治区",
            "特别行政区",
            "自治区",
            "省",
            "市",
        )
        return suffixes.firstOrNull { name.endsWith(it) }
            ?.let { name.dropLast(it.length) }
            ?: name
    }

    private fun formatDate(millis: Long): String {
        if (millis <= 0L) return ""
        return Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(dateFormatter)
    }

    private fun commentPreview(text: String, limit: Int): String {
        val singleLine = text.replace('\n', ' ').trim()
        return if (singleLine.length <= limit) singleLine else singleLine.take(limit) + "…"
    }

    private fun errorResult(message: String): PluginDataResult = PluginDataResult(
        emptyTitle = "无法加载",
        emptyMessage = message,
        isError = true,
    )

    private class TravelSnapshot(
        val places: List<TravelPlace>,
        val ratings: Map<String, Int>,
        val reviews: List<TravelReview>,
    ) {
        val litPlaces: List<TravelPlace> = places.filter { it.lit }
        val litIds: Set<String> = litPlaces.mapTo(mutableSetOf()) { it.id }
        val litCityIds: Set<String> = litPlaces.mapTo(mutableSetOf()) { it.cityId }
        val ratedLitPlaces: List<TravelPlace> = litPlaces.filter { ratings[it.id] != null }
        val unratedLitPlaces: List<TravelPlace> = litPlaces.filter { ratings[it.id] == null }
        private val commentsByRegion: Map<String, List<TravelReview>> =
            reviews.groupBy { it.regionId }

        fun regionRating(regionId: String): Double? =
            ratings[regionId]?.toDouble()

        fun regionCommentCount(regionId: String): Int =
            commentsByRegion[regionId]?.size ?: 0

        fun cityRating(cityId: String): Double? =
            litPlaces.filter { it.cityId == cityId }
                .mapNotNull { regionRating(it.id) }
                .averageOrNull()

        fun provinceRating(provinceId: String): Double? =
            litPlaces.filter { it.provinceId == provinceId }
                .map { it.cityId }
                .filter { it.isNotBlank() }
                .distinct()
                .mapNotNull { cityRating(it) }
                .averageOrNull()

        fun overallRating(): Double? =
            litPlaces.mapNotNull { regionRating(it.id) }.averageOrNull()
    }
}

private fun List<Double>.averageOrNull(): Double? =
    if (isEmpty()) null else average()
