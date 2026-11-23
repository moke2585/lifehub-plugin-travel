# 旅迹插件（LifeHub）

[LifeHub](https://github.com/moke2585/lifehub) 的第一方旅行足迹插件。按“中国 → 省 → 市 → 区县”逐级点亮地区，为地区打五星评分、写下多条旅行评论，并生成评分榜与高清地图。

| 项 | 值 |
|---|---|
| 插件 ID | `com.lifehub.plugin.travel` |
| 名称 | 旅迹 |
| 版本 | 3.0.0 |
| 插件 SDK | 1（`minHostVersion=1`） |
| 数据 schema | 3 |
| 页面 | 7 |
| 能力 | `ui.action`、`storage.private` |

## 功能

- 全国地图：同时绘制省界与市界，点击省份进入省页；南海诸岛九段线插图。
- 逐级下钻：34 个省级区域、370 个城市；城市页展示区县边界，东莞、中山、儋州、嘉峪关、天门、石河子、五家渠、北屯、铁门关、昆玉等直筒子市/省直辖单位展示乡镇街道。
- 点亮：已点亮地区以深绿色填充；一级页只显示省名与省评分。
- 地区评分：五星控件，同一地区只保留一个评分并可修改；未点亮也可以评分。
- 评分聚合：市评分 = 下属已点亮且有评分的区县均分；省评分 = 下属已点亮且有评分的市均分。
- 旅行评论：一个地区可有多条文字评论，可删除；不影响点亮与评分。
- 评分榜：分省榜、市榜、县区榜，只收录已评分地区，并提示已点亮未评分数量。
- 地区搜索：按名称直达省/市/区县，可直接点亮或进入评分、评论。
- 地图缩放：双指缩放、拖动、倍率滑块与复位。
- 导出：一级页导出 3288×2747 PNG，左下角标注导出日期，保存到系统相册。

## 页面

| 页面 ID | 标题 |
|---|---|
| `map` | 旅行足迹 |
| `province` | 省份足迹 |
| `city` | 城市足迹 |
| `place` | 地区详情 |
| `reviews` | 我的评论 |
| `ranking` | 评分榜 |
| `region_search` | 搜索地区 |

## 数据

数据通过宿主 `PluginStorage` 保存，随宿主备份导出：

| 集合 | 内容 |
|---|---|
| `travel_places` | 已点亮地区 |
| `travel_ratings` | 地区五星评分 |
| `travel_reviews` | 旅行评论 |

`dataSchemaVersion = 3`：v1 备注转为未评分评论，v2 随评评分聚合为地区评分，点亮记录保持不变；重复迁移不会产生重复数据。

## 地图资产与授权

```text
src/main/assets/plugins/com.lifehub.plugin.travel/data/
├── china.json          # 全国省界/市界
├── regions.json        # 地区搜索索引
├── provinces/          # 省级地图
└── cities/             # 城市/区县地图
```

- 省、市、区县边界主要来自 [阿里云 DataV GeoAtlas](https://geo.datav.aliyun.com/areas_v3/bound/)。
- 缺少区县的直筒子市与省直辖县级单位（东莞、中山、儋州、嘉峪关、天门、石河子、五家渠、北屯、铁门关、昆玉）使用 [OpenStreetMap](https://www.openstreetmap.org/copyright) 的乡镇街道边界（© OpenStreetMap contributors，[ODbL](https://opendatacommons.org/licenses/odbl/)）。
- 所有地图均为离线资产，插件不声明网络权限，运行时无在线请求。

## 构建与测试

本仓库不能单独构建：`plugin-api`、`plugin-runtime` 与宿主构建脚本位于同级宿主仓库。请把本仓库与 `lifehub` 放在同一父目录。

```powershell
cd ..\lifehub

# 插件单元测试（12 项）
.\gradlew.bat :plugins:travel:testDebugUnitTest

# 插件包校验
.\gradlew.bat :dev-harness:validatePlugins

# 构建包含本插件的调试 APK（含全部地图资产）
.\gradlew.bat :app:assembleDebug
```

## 分发形态

旅迹当前以宿主内置声明式插件分发。地图资产与 `geoMap` 页面数据的体积可能超过 APK 插件 512 KiB 的 Binder 单次载荷上限，因此暂不打包为 APK 插件；如后续拆分资产读取协议，再评估独立分发。契约见 [APK 插件契约 v1](https://github.com/moke2585/lifehub/blob/main/docs/apk-plugin-v1.md)。

开发文档：[插件开发指南](https://github.com/moke2585/lifehub/blob/main/docs/plugin-development.md)
