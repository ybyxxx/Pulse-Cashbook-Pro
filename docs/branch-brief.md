# 分支产品交接简报 — PulseCashbook 主线

> 版本 v1.5.0 · 2026-11-10 · 为分支产品（衍生 App）提供复用基线

## 一、主线产品定位

**Pulse Cashbook**（Pulse 返现记账）是汇丰 Pulse 信用卡的本地返现追踪工具。核心价值：用户录入每笔消费后，自动根据支付方式、活动资格、年度额度，计算可享返现金额，并提供月度/明细维度统计。

关键特征：
- **纯本地**：数据仅存设备 SQLite + SharedPreferences，零网络
- **规则引擎**：返现计算完全可测试（纯 JVM 80 项单测覆盖）
- **活动感知**：三项年度限时活动（支付加成/赏世界/餐饮）各有独立额度与资格判断

## 二、核心数据模型与接口契约

### 2.1 Order 模型（`Order.java`）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | long | DB 自增主键，-1 = 新建 |
| `date` | String | yyyy-MM-dd |
| `amount` | double | 原始金额（HKD:CNY 1:1） |
| `refundAmount` | double | 累计退款额 |
| `currency` | String | "CNY"（默认）/ "HKD" |
| `note` | String | 备注 |
| `payMethod` | String | 5 选 1：`applepay`/`unionqr`/`alipaywechat`/`jumpunion`/`onlinecard` |
| `payConfirmed` | boolean | 语义随支付方式：jumpunion→确认支付加成；alipaywechat→确认赏世界资格 |
| `dining` | boolean | 餐饮类标记 |

**核心方法契约**：
- `netAmount()` = max(0, amount − refundAmount)，全链路计算基准
- `payBonusEligible()` = applepay/unionqr 自动 | jumpunion+confirmed
- `shopWorldEligible()` = 非 alipaywechat 自动 | alipaywechat+confirmed
- `normalizePayMethod(String)` = 旧值映射（wechat_union/app_union→jumpunion; online/physical→onlinecard）

### 2.2 返现引擎（`Cashback.java`）

**费率常量**：BASE 0.4% / PAY 2% / WORLD 2% / DINING 3% / THRESHOLD 1200

**额度常量**：PAY_CAP 80,000（年度）/ WORLD_CAP 100,000（年度）/ DINING_MONTHLY_CAP 80（月度）

**关键接口**：
```java
// 批量计算（年度额度感知）— 分支产品如需替换返现规则，优先改这里
Map<Long, OrderResult> computeYear(List<Order> yearOrders, boolean shopWorldActive)

// 编辑预览用：查询指定日期前的额度消耗
double[] capUsageBefore(sortedYearOrders, date, excludeId, shopWorldActive, monthThresholdMet)
// 返回 [payUsed, worldUsed, diningUsedThisMonth]

// 单笔计算（6 参数，考虑各项额度剩余）
double cashbackOf(order, thresholdMet, shopWorldActive, payRemaining, worldRemaining, diningRemaining)

// 明细文案（含额度状态）
String breakdown(order, thresholdMet, shopWorldActive, payRemaining, worldRemaining, diningRemaining)
```

**OrderResult**：`{ double cashback; double[] components = [base, pay, world, dining]; }`

### 2.3 迎新引擎（`Welcome.java`）

```java
Result compute(List<Order> orders, String issueDate, LocalDate today)
// 60 日窗口；签账满 8000→$800；QR 单笔≥50 每笔 $10×28 封顶；最高 $1,080
// 支付宝/微信不计入签账；退款按净额扣减
```

### 2.4 数据库 Schema（`CashbookDb.java` v3）

```sql
orders: _id | date | amount | refund_amount | currency | note | pay_method | pay_confirmed | dining | created_at
attachments: _id | order_id | path
```
迁移链：v1→v2（pay_method 归一）→ v3（refund_amount + 支付方式合并）

### 2.5 配置存储（`Prefs.java`）

| Key | 类型 | 说明 |
|-----|------|------|
| `shopworld_<year>` | boolean | 赏世界年度报名 |
| `theme_index` | int | 主题色（6 套） |
| `card_issue_date` | String | 下卡日期（迎新起算） |
| `welcome_enabled` | boolean | 迎新活动开关 |
| `quick_buttons` | String(JSON) | 自定义快捷按钮 |

## 三、已定技术决策及否决原因

| 决策 | 选型 | 否决的替代方案 | 原因 |
|------|------|---------------|------|
| 构建系统 | 手动管线（aapt2+javac+d8+apksigner） | Gradle | 无网络沙箱环境；零依赖原则；管线完全可控 |
| UI 框架 | 纯 android.widget | AndroidX / Jetpack Compose | 无依赖原则；手动构建不引入外部库 |
| 数据库 | SQLiteOpenHelper | Room / Realm | 零依赖；Schema 简单（2 表）；无需编译期注解 |
| 图表 | Canvas 自绘 | MPAndroidChart 等 | 零依赖；需求简单（环图+柱图） |
| 敏感数据 | Android Keystore AES-GCM | 明文 / SharedPreferences | 卡号/CVV 必须加密；设备级密钥 |
| 返现计算 | 纯 JVM 独立类（Cashback） | 内嵌 UI 逻辑 | 可测试性（80 项单测不需 Android 设备） |
| 批量计算 | computeYear 按日期升序 + 额度累计 | 逐笔独立计算 | 年度额度依赖累计，单笔无法独立计算 |

## 四、分支产品预期复用与差异清单

### ✅ 直接复用（零改动或微调）

| 模块 | 复用度 | 说明 |
|------|--------|------|
| `Cashback.java` 返现引擎 | ★★★ | 分支产品如改费率/额度，只改常量；如改计算逻辑，改 computeYear |
| `CashbookDb.java` | ★★★ | Schema 可按需扩展列（递增 DB_VERSION + onUpgrade） |
| `Order.java` | ★★★ | 如新增支付方式，扩展 PAY_* 常量 + normalizePayMethod 映射 |
| `Prefs.java` | ★★★ | 新增 key 即可 |
| `Themer.java` | ★★★ | 6 套主题色可扩展或替换色值 |
| `SecureStore.java` | ★★★ | Keystore 加密封装通用 |
| `build.sh` 构建管线 | ★★☆ | 如分支产品包名/版本不同，改 Manifest + build.sh 版本参数 |
| `SwipeRevealTouchListener.java` | ★★★ | 通用滑动揭示组件 |
| `DonutView/DailyBarsView` | ★★☆ | 通用 Canvas 图表，数据接口 setData() |
| `ImageUtil.java` | ★★★ | 截图处理通用 |

### ⚠️ 需改造

| 模块 | 差异点 |
|------|--------|
| `MainActivity.java` | 主页布局/统计面板/迎新面板（分支产品可能不需要迎新） |
| `EditOrderActivity.java` | 支付方式选项、快捷按钮预设、返现预览文案 |
| `Welcome.java` | 迎新规则（分支产品如无迎新活动可整体删除） |
| `CashbackDetailActivity.java` | 构成图维度（分支产品可能不需要 4 项分拆） |
| `strings.xml` | 全部文案需替换为分支产品名称/规则描述 |

### ❌ 不复用

| 模块 | 原因 |
|------|------|
| `CardInfoActivity.java` | 卡片备份是 Pulse 专属功能 |
| `AboutActivity.java` | 更新日志/免责声明需重写 |
| `SettingsActivity.java` | 设置项因产品而异 |

### 分支产品开发建议路径

1. **Fork 代码库** → 修改包名（Manifest + Java package + build.sh）
2. **裁剪**：删除/替换 Welcome、CardInfo、About 模块
3. **改规则**：在 Cashback.java 调费率常量和 computeYear 逻辑
4. **改 UI**：strings.xml 全量替换 + 布局按需调整
5. **测试**：LogicTest/WelcomeTest 中跟分支规则相关的测试需同步修改
