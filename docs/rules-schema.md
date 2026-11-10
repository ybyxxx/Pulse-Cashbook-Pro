# 返现规则格式（服务端下发契约 v1）

> 客户端版本：Pulse 账本 Pro 2.1.0+ · schemaVersion = 1
> 目的地：服务端一份 HTTPS 可访问的 JSON 文档（静态文件或接口返回，结构相同）
> 客户端行为：启动时读本地缓存 → 无缓存用内置规则 → 后台拉取并校验，通过才替换

## 一、顶层结构

```json
{
  "schemaVersion": 1,
  "version": "2026.11.1",
  "updatedAt": "2026-11-10",
  "baseRate": 0.004,
  "diningThreshold": 1200,
  "activities": [ ... ]
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `schemaVersion` | int | 是 | 固定 `1`。客户端只认自己支持的版本，取值不符直接拒绝整份规则 |
| `version` | string | 是 | 规则版本号，建议 `年.月.序号`（如 `2026.11.1`）。设置页展示、用于判断是否有更新 |
| `updatedAt` | string | 否 | 发布日期，`yyyy-MM-dd`，仅展示 |
| `baseRate` | number | 是 | 基础返现率，小数形式：`0.004` = 0.4%。合理范围 0 ~ 0.1 |
| `diningThreshold` | number | 否 | 餐饮门槛（月度签账），默认 1200。**仅用于界面文案**，实际门槛由活动自己的 `minMonthlySpend` 决定 |
| `activities` | array | 是 | 活动列表，至少 1 条 |

## 二、活动字段

```json
{
  "id": "pay_bonus_2026",
  "name": "支付加成 2%",
  "type": "PAY_BONUS",
  "rate": 0.02,
  "startsOn": "2026-01-01",
  "endsOn": "2026-12-31",
  "capScope": "YEAR",
  "capAmount": 80000,
  "capLimitType": "SPEND",
  "minMonthlySpend": 0,
  "diningOnly": false,
  "payMethods": ["applepay", "unionqr", "jumpunion"],
  "confirmMethods": ["jumpunion"],
  "excludeMethods": [],
  "requiresOptIn": false,
  "enabled": true
}
```

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | string | 是 | — | 唯一标识，全表不可重复。客户端的额度累计按 id 记账，**改名等于新活动** |
| `name` | string | 否 | 同 id | 展示名 |
| `type` | enum | 是 | — | `PAY_BONUS` / `SHOP_WORLD` / `DINING`，决定返现分项归到哪一格 |
| `rate` | number | 是 | 0 | 加成费率，`0.02` = 2%。范围 0 ~ 0.5 |
| `startsOn` | date | 否 | 空=不限 | 生效起始日（含当日），`yyyy-MM-dd` |
| `endsOn` | date | 否 | 空=不限 | 生效结束日（含当日） |
| `capScope` | enum | 否 | `NONE` | `NONE` 不封顶 / `YEAR` 年度 / `MONTH` 月度 |
| `capAmount` | number | 否 | 0 | 额度上限。`SPEND` 时单位是签账额，`REBATE` 时单位是返现金额 |
| `capLimitType` | enum | 否 | `SPEND` | `SPEND` 限制可享加成的签账额 / `REBATE` 直接限制返现额 |
| `minMonthlySpend` | number | 否 | 0 | 当月累计签账达到该值活动才生效（0=不要求）。餐饮门槛放这里 |
| `diningOnly` | bool | 否 | false | 只对勾选「餐饮」的订单生效 |
| `payMethods` | string[] | 否 | 空=不限 | 适用支付方式白名单。取值：`applepay` / `unionqr` / `alipaywechat` / `jumpunion` / `onlinecard` |
| `confirmMethods` | string[] | 否 | 空 | 这些支付方式需用户勾选「自行确认」后才适用 |
| `excludeMethods` | string[] | 否 | 空 | 排除的支付方式；若同时出现在 `confirmMethods`，以「确认后可用」为准 |
| `requiresOptIn` | bool | 否 | false | 需要用户报名（对应主页「赏世界年度报名」开关） |
| `enabled` | bool | 否 | true | 关掉即整条失效，规则保留 |

## 三、资格判断（客户端逐条执行）

订单要同时满足以下条件，该活动才算：

1. `enabled = true`；
2. 订单日期落在 `startsOn ~ endsOn`（含首尾，任一侧为空则不限制）；
3. 若 `requiresOptIn = true`，用户已打开该年度的报名开关；
4. 若 `diningOnly = true`，订单必须勾选餐饮；
5. 当月累计净签账 ≥ `minMonthlySpend`（为 0 则跳过）；
6. 支付方式通过：在 `payMethods`（空则不限）内，且不在 `excludeMethods`（除非同时在 `confirmMethods` 并已确认），`confirmMethods` 内的必须已确认。

**基础返现**始终按 `baseRate × 净签账` 计算，不受活动条件影响。

## 四、额度语义

- `SPEND`：先算「可享加成的签账额」= min(本单净额, 额度剩余)，返现 = 该签账额 × 费率。适合「限前 8 万消费」这类规则。
- `REBATE`：返现 = min(本单净额 × 费率, 额度剩余)。适合「每月最多返 80」这类规则。
- `YEAR` 的额度在自然年内累计；`MONTH` 的额度按自然月各自独立。
- 额度按**卡片 + 活动 id**分别累计（多卡互不影响）。
- 规则更新后客户端会立即作废已缓存的返现结果并重算。

## 五、当前生效活动的等价写法（可直接作为首版）

见 `BuiltinRules.java` 中的 `JSON` 常量，与 v1.5.0 口径完全一致：

| id | 类型 | 费率 | 有效期 | 额度 | 其他 |
|---|---|---|---|---|---|
| `pay_bonus` | PAY_BONUS | 2% | 长期 | 年度 80,000 签账 | 仅 applepay / unionqr / jumpunion(确认) |
| `shop_world` | SHOP_WORLD | 2% | 长期 | 年度 100,000 签账 | 需报名；支付宝/微信需确认 |
| `dining` | DINING | 3% | 长期 | 月度 80 返现 | 仅餐饮；当月满 1,200 |

## 六、新增活动的两种典型写法

**限时活动（未开始先下发）**

```json
{
  "id": "double_11_2026", "name": "双十一加码 5%", "type": "PAY_BONUS",
  "rate": 0.05,
  "startsOn": "2026-11-01", "endsOn": "2026-11-15",
  "capScope": "MONTH", "capAmount": 200, "capLimitType": "REBATE",
  "payMethods": ["applepay", "unionqr"],
  "enabled": true
}
```

**改额度（不动代码）**

把 `pay_bonus` 的 `capAmount` 从 `80000` 改成新值，同时把顶层 `version` 改为新版本号即可。

## 七、客户端校验（不通过就整份丢弃、继续用旧规则）

- `schemaVersion` 必须为 1；
- `activities` 非空；
- `id` 非空且不重复；
- `rate` 在 0 ~ 0.5，`baseRate` 在 0 ~ 0.1；
- `type` / `capScope` / `capLimitType` 取值在白名单内；
- 日期能解析，且 `startsOn <= endsOn`。

## 八、发布方式

| 方式 | 做法 | 适用 |
|---|---|---|
| 静态 JSON | 把文档放到任意 HTTPS 地址（对象存储 / CDN / GitHub Raw），设置里填该地址 | 最简单，改规则=覆盖文件 |
| 数据库 + 接口 | 规则存表，接口返回同一份 JSON 文档 | 需要后台改行、保留历史 |

客户端只需一个 HTTPS GET，支持 `ETag` 条件请求（304 不重复下载），响应上限 256 KB，超时 8 秒，失败静默保留旧规则。

> 注意：WorkBuddy 云数据库的数据面是为「已发布的 Web / 小程序」设计的（走 JS SDK + Origin 校验），
> 原生 Android 不能直连。若要「在数据库里改行」，需在云数据库前加一个只读转发接口
> （返回上面的 JSON），客户端地址指向该接口即可，契约不变。
