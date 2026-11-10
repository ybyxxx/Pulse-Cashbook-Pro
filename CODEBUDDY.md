# PulseCashbook Pro — 工程交接文档（分支）

> 汇丰 Pulse 信用卡返现记账 Android 应用 —— **Pro 分支**。本地记账 + 规则服务端下发。
> 当前版本 v2.2.1（build 24） · minSdk 26 / targetSdk 35 · 包名 `com.hsbcpulse.cashbook.pro`
>
> **与主线的关系**：本目录是从主线 `PulseCashbook/`（v1.5.0 / build 9）完整复制的独立分支，
> 改包名后可与主线 APK 同时安装、数据互不干扰。主线不因分支改动而变更。

## 〇、分支相对主线的差异

| 项 | 主线 v1.5.0 | 分支 v2.2.1 |
|---|---|---|
| 包名 | `com.hsbcpulse.cashbook` | `com.hsbcpulse.cashbook.pro` |
| 应用名 | Pulse 返现记账 | Pulse 账本 Pro |
| 版本 | 1.5.0 / build 9 | 2.2.1 / build 24 |
| 数据库 | v3（orders + attachments） | **v4**（+ cards 表，orders.card_id） |
| 多卡片 | 不支持（单卡） | **支持**：卡片增删改、主页切换、按卡隔离账单/迎新/额度 |
| 导出 | 无 | **CSV + JSON**（ACTION_CREATE_DOCUMENT，免权限） |
| 返现规则 | 写死在 `Cashback` 里 | **规则集驱动**：`RuleSet`/`RewardActivity`/`RuleEngine`，服务端下发 + 内置兜底 |
| 网络 | 无 | 仅 HTTPS GET 拉规则（`RulesStore`），不上传任何数据 |
| 统计性能 | 每次刷新重复 computeYear + 逐单查附件 | **年度结果缓存 + 轻量查询** |
| 测试 | LogicTest 56 + WelcomeTest 24 | 上述 80 项 + ExportTest 28 + RuleEngineTest 50 + RulesFileTest 39 + **ImportTest 35** |
| 产物 | `PulseCashbook.apk` | `PulseCashbookPro.apk` |

## 一、架构总览

```
PulseCashbook-New/
├── AndroidManifest.xml          # 9 Activity，allowBackup=false
├── build.sh                     # 手动构建管线（aapt2→javac→d8→zipalign→apksigner）
├── debug.keystore               # 调试签名（自动生成）
├── src/com/hsbcpulse/cashbook/pro/
│   ├── MainActivity.java        # 主页：卡片切换/月导航/日归类列表/赏世界开关/迎新面板
│   ├── EditOrderActivity.java   # 记一笔/编辑：5选1支付/退款/快捷按钮/实时返现预览
│   ├── CashbackDetailActivity.java  # 返现明细页（环图+柱图）
│   ├── CardsActivity.java       # 【新增】卡片管理：增删改、设下卡日期、切换当前卡
│   ├── Card.java                # 【新增】卡片模型
│   ├── ExportHelper.java        # 【新增】导出（Android 侧：SAF 选择器 + 写文件）
│   ├── ImportHelper.java        # 【新增】恢复（SAF 选择器 + 读流 + 落库 + 去重）
│   ├── ImportParser.java        # 【新增】JSON 备份解析与逐行校验（纯 JVM）
│   ├── CsvExport.java           # 【新增】CSV/JSON 内容生成（纯 JVM 可测）
│   ├── Welcome.java             # 迎新活动纯逻辑引擎（60日窗口/8000签账/QR加码）
│   ├── Cashback.java            # 返现门面（保留旧 API，内部委托 RuleEngine）
│   ├── RuleSet.java             # 【新增】规则集：解析 + 校验 + 内置兜底
│   ├── RewardActivity.java      # 【新增】单条活动：费率/有效期/额度/资格
│   ├── RuleEngine.java          # 【新增】规则驱动计算（年度批量/单笔/额度占用）
│   ├── BuiltinRules.java        # 【新增】内置规则 JSON（= v1.5 口径，断网兜底）
│   ├── RulesStore.java          # 【新增】规则同步：HTTP GET + 校验 + 缓存 + 注入
│   ├── MiniJson.java            # 【新增】零依赖 JSON 解析（org.json 不能进单测）
│   ├── Order.java               # 订单模型（含 cardId/支付资格判断/旧值映射/净额）
│   ├── CashbookDb.java          # SQLite v4（cards + orders + attachments，含返现缓存）
│   ├── Prefs.java               # SharedPreferences（主题/赏世界/快捷按钮/迎新开关/当前卡）
│   ├── SecureStore.java         # Android Keystore AES-GCM 加密存储
│   ├── Themer.java              # 6套主题色/状态栏/卡片着色
│   ├── OrderAdapter.java        # ListView 双视图（DayRow/Order）+ 滑动删除
│   ├── SwipeRevealTouchListener.java  # 左滑揭示删除按钮
│   ├── DonutView.java           # Canvas 环形图（返现构成）
│   ├── DailyBarsView.java       # Canvas 每日柱状图
│   ├── ImageUtil.java           # 截图缩略/保存/删除
│   ├── AboutActivity.java       # 关于页（更新日志）
│   ├── SettingsActivity.java    # 设置（主题/卡片管理/卡片备份/迎新/导出/关于）
│   ├── CardInfoActivity.java    # 卡片信息备份（指纹验证+Keystore加密）
│   └── ViewImageActivity.java   # 截图查看
├── res/layout/ (12 XML)
├── res/values/strings.xml       # 全部文案集中管理
└── test/
    ├── LogicTest.java           # 返现引擎回归（56项，锚定 v1.5 数值）
    ├── WelcomeTest.java         # 迎新引擎测试（24项）
    ├── ExportTest.java          # 导出格式测试（28项）
    ├── RuleEngineTest.java      # 规则引擎测试（50项：生效期/额度/脏数据/注入）
    ├── RulesFileTest.java       # 仓库 rules.json 校验（39项：可解析 + 与内置口径逐字段一致）
    └── ImportTest.java          # 恢复测试（35项：往返一致 / 脏数据跳过 / 去重键）
```

## 二、技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 语言 | Java 11（`--release 11`） | 纯 Java，无 Kotlin |
| UI | android.widget.* 纯框架控件 | **禁止 AndroidX / Support Library** |
| 数据 | SQLite（SQLiteOpenHelper v4：cards / orders / attachments） | 本地单文件 DB，`pulse_cashbook_pro.db` |
| 加密 | Android Keystore AES-GCM | 卡片敏感信息（卡号/CVV/有效期） |
| 图表 | Canvas 自绘（DonutView / DailyBarsView） | 不引入图表库 |
| 网络 | `HttpURLConnection` + `MiniJson` | 仅拉取规则 JSON；无 OkHttp/Retrofit；不引 org.json（纯 JVM 单测不可用） |
| 构建 | 手动管线 aapt2 + javac + d8 + apksigner | **禁止 Gradle** |
| JDK | Microsoft JDK 17.0.13（构建用） | 位于 `android-toolchain/jdk-extract/` |
| Android SDK | platform-35 + build-tools 35.0.0 | 位于 `android-toolchain/sdk/` |

## 三、常用命令

```bash
# 构建 APK（Git Bash）
cd PulseCashbook-New && bash build.sh
# 产物：PulseCashbook-New/PulseCashbookPro.apk

# 运行纯 JVM 测试（不需要 Android 设备）
cd PulseCashbook-New
JDK="C:/Users/GAFA2279/WorkBuddy/2026-11-09-12-47-17/android-toolchain/jdk-extract/jdk-17.0.13+11"
$JDK/bin/javac.exe -encoding UTF-8 --release 11 \
  -d build/test-classes \
  src/com/hsbcpulse/cashbook/pro/Order.java \
  src/com/hsbcpulse/cashbook/pro/Cashback.java \
  src/com/hsbcpulse/cashbook/pro/Welcome.java \
  src/com/hsbcpulse/cashbook/pro/CsvExport.java \
  src/com/hsbcpulse/cashbook/pro/BuiltinRules.java \
  src/com/hsbcpulse/cashbook/pro/RuleSet.java \
  src/com/hsbcpulse/cashbook/pro/RewardActivity.java \
  src/com/hsbcpulse/cashbook/pro/RuleEngine.java \
  src/com/hsbcpulse/cashbook/pro/MiniJson.java \
  src/com/hsbcpulse/cashbook/pro/ImportParser.java \
  test/LogicTest.java test/WelcomeTest.java test/ExportTest.java test/RuleEngineTest.java test/RulesFileTest.java test/ImportTest.java
$JDK/bin/java.exe -cp build/test-classes LogicTest
$JDK/bin/java.exe -cp build/test-classes WelcomeTest
$JDK/bin/java.exe -cp build/test-classes ExportTest
$JDK/bin/java.exe -cp build/test-classes RuleEngineTest
$JDK/bin/java.exe -cp build/test-classes RulesFileTest   # 需在项目根目录运行
$JDK/bin/java.exe -cp build/test-classes ImportTest

# 验证 APK 版本
TC="C:/Users/GAFA2279/WorkBuddy/2026-11-09-12-47-17/android-toolchain"
$TC/sdk/build-tools/35.0.0/aapt2.exe dump badging PulseCashbookPro.apk | head -3
```

## 四、编码约定

1. **版本号**：必须同时修改 `AndroidManifest.xml`（versionCode/versionName）和 `build.sh`（--version-code/--version-name），两处保持一致。
2. **findViewById 链式调用**：API 26+ 泛型推断失败，必须先赋值局部变量再调方法：
   ```java
   TextView tv = findViewById(R.id.tv_xxx);  // ✓
   // findViewById(R.id.tv_xxx).setText(...);  // ✗ 编译失败
   ```
3. **程序化视图 LayoutParams**：`addView(view)` 必须传入 LayoutParams，漏传退化为 wrap_content 导致零高度。
4. **中文编码**：javac/java 加 `-J-Duser.language=en -J-Duser.country=US` 防 GBK 乱码。
5. **路径**：javac/java 用绝对路径调 `.exe`（Git Bash PATH 解析失败 exit 127）；aapt2 也用绝对路径。
6. **金额口径**：全链路使用净额（`netAmount() = amount − refundAmount`），返现/统计/迎新均以此计算。
7. **事件分发**：叠放可点击 View 需按前景遮挡状态切换 `setClickable()`，防点击穿透。
8. **签名**：debug.keystore 自动创建（alias=androiddebugkey, pass=android），勿手动删除。
9. **多卡片（v2.0.0 新增）**：
   - 所有订单查询必须带 `cardId`（`getOrdersByMonth(month, cardId)` 等），不要图省事查全表，否则跨卡串数据。
   - 新建订单时 `Order.cardId` 取 `Prefs.getCurrentCardId()`；编辑时沿用 `order.cardId`，不要改写。
   - 迎新窗口、支付/赏世界年度额度、餐饮月上限**均按卡独立**，因为 `computeYear` 只接收单卡订单。
   - 删除卡片会连带删除该卡全部订单与截图，且必须保留至少一张卡。
10. **统计缓存（v2.0.0 新增）**：
    - 年度返现结果统一走 `db.yearResults(year, cardId, shopWorldActive)`，不要再直接调 `Cashback.computeYear`。
    - 缓存靠静态 `REVISION` 失效：任何写操作（insert/update/delete/restore/卡片增删改）都会 `incrementAndGet()`，
      因此跨 Activity 的 CashbookDb 实例也不会读到脏数据。新增写方法时**务必**补 `REVISION.incrementAndGet()`。
    - 统计场景用 `*Light` 查询（不加载附件），列表展示才需要附件。
11. **导出**：内容生成放 `CsvExport`（纯 JVM，可单测）；Android 侧（`ExportHelper`）只负责取数据、拉起
    `ACTION_CREATE_DOCUMENT`、写流。**不要**为导出申请存储权限。
12. **返现规则（v2.1.0 起）**：
    - 规则只有一份来源：`RuleSet`。**禁止**在 Activity 或 `Cashback` 里新写费率/额度常量；
      需要新活动就在规则 JSON 里加一条 `RewardActivity`。
    - 计算一律走 `Cashback.*`（内部委托 `RuleEngine`）；`computeYear` 只接收单卡订单，额度天然按卡隔离。
    - 新活动类型必须同步三处：`RewardActivity.TYPE_*`、`RuleEngine.slotOf()`、`RuleSet.validate()`。
    - 规则更新后必须让缓存失效：`Cashback.setRules()` 会递增 `rulesGeneration`，
      `CashbookDb.yearResults` 已按代次自动清缓存——新增加缓存的地方也要照此判断。
    - 服务端返回脏数据一律整份丢弃（`RuleSet.validate` 抛异常），继续用旧规则，绝不让错误规则参与计算。
    - 规则 JSON 格式见 `docs/rules-schema.md`，改动格式必须提升 `schemaVersion`。
    - 网络仅用于 HTTPS GET 规则文件；**不得**上传任何本地数据（账单、卡号、设备信息）。
13. **数据恢复（v2.2.0 新增）**：
    - 只认 `CsvExport.toJson` 产出的 JSON 备份；CSV 是给人看/分析用的，不做导入。
    - 解析放 `ImportParser`（纯 JVM），Android 侧 `ImportHelper` 只做读流/落库/文案。
    - 去重键见 `ImportParser.key()`（日期+金额+创建时间）。**改动导出字段时同步改这里**，否则重复导入会翻倍。
    - 批量写入必须走 `insertOrders()` 的单事务；覆盖模式先 `clearOrders()` 并清理附件文件，需二次确认。
    - 备份不含截图附件（路径是本机私有的），恢复后附件为空是预期行为，不要试图从备份还原图片。
14. **AlertDialog 陷阱（v2.2.1 踩过）**：
    - `setMessage()` 与 `setItems()` **不能同时用**——列表会被 message 顶掉，界面只剩按钮（恢复功能曾因此在真机上不可用）。
    - 需要「说明 + 多选一 + 确认」时，用 `setView()` 自定义视图（TextView + RadioGroup）+ `setPositiveButton()`，
      或拆成两步对话框；不要在同一个 Builder 上混用 message/items。
    - 有破坏性的选项（覆盖恢复、删除）必须走独立二次确认，不要在列表里点一下就直接执行。


## 五、明确禁止事项

| 禁止 | 原因 |
|------|------|
| **禁止引入 Gradle** | 项目刻意用手动管线构建，引入 Gradle 会破坏工具链约定 |
| **禁止使用 AndroidX / 依赖库** | 零依赖原则，手动构建时无需处理依赖解析 |
| **禁止网络请求（规则拉取除外）** | 除 `RulesStore` 的 HTTPS GET 规则文件外不得联网；禁止上传任何本地数据 |
| **禁止 allowBackup=true** | 防止卡片敏感信息经系统备份泄露 |
| **禁止直接 rm/build 目录之外的文件** | build.sh 会自动清理 build/，勿手动操作 |
| **禁止在测试中引入 Android 依赖** | LogicTest/WelcomeTest 必须纯 JVM 可运行 |
| **禁止删除 debug.keystore** | 签名一致性，删除会导致 APK 覆盖安装签名不匹配 |
