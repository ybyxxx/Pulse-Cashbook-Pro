# Pulse 账本 Pro

汇丰 Pulse 信用卡返现记账 Android 应用（Pro 分支）。本地记账，返现规则由服务端下发。

- 包名 `com.hsbcpulse.cashbook.pro`，可与主线版本同时安装，数据互不干扰
- 纯 Java + 系统控件，无 Gradle、无 AndroidX、无第三方依赖
- 数据只存本机 SQLite / SharedPreferences，联网仅用于拉取规则 JSON

## 功能

- **多卡片**：每张卡独立的账单、迎新窗口与年度额度，主页一键切换
- **返现规则可下发**：活动费率、额度、有效期来自 `rules.json`，新增活动无需更新 App
- **账单导出**：CSV（Excel / WPS 可开）与 JSON 备份，免存储权限
- **数据恢复**：从 JSON 备份导入，支持合并去重 / 恢复为新卡片 / 覆盖当前卡片
- **返现明细**：构成环图、每日柱状图、额度消耗提示
- **迎新追踪**：下卡日起 60 日窗口、8,000 签账门槛、QR 加码
- **卡片信息**：指纹验证 + Android Keystore 加密存储

## 数据恢复

「设置 → 数据恢复」选一份 JSON 备份，先看摘要（版本 / 导出时间 / 来源卡片 / 笔数），再选恢复方式：

| 方式 | 行为 |
|---|---|
| 合并到当前卡片 | 按「日期+金额+创建时间」去重，只补缺失的，可反复导入 |
| 恢复为新卡片 | 新建一张卡（同名自动加序号）并切过去，现有数据不动 |
| 覆盖当前卡片 | 先清空当前卡片账单（含截图）再导入，需二次确认 |

导入按事务写入，格式异常的条目逐条跳过并提示条数。注意：**备份不含截图附件**，恢复后附件需要重新添加。

## 构建

```bash
# 需要 Android SDK platform-35 + build-tools 35.0.0 与 JDK 17（路径见 build.sh 顶部）
bash build.sh
# 产物：PulseCashbookPro.apk
```

## 测试

纯 JVM，不需要设备：

```bash
javac --release 11 -encoding UTF-8 -d build/test-classes \
  src/com/hsbcpulse/cashbook/pro/{Order,Cashback,Welcome,CsvExport,BuiltinRules,RuleSet,RewardActivity,RuleEngine,MiniJson}.java \
  test/*.java
java -cp build/test-classes LogicTest        # 56
java -cp build/test-classes WelcomeTest      # 24
java -cp build/test-classes ExportTest       # 28
java -cp build/test-classes RuleEngineTest   # 50
```

## 返现规则

规则源：`rules.json`（本仓库根目录），客户端通过 HTTPS 拉取并校验后生效，格式见
[`docs/rules-schema.md`](docs/rules-schema.md)。

- 活动支持 `startsOn` / `endsOn`，可提前下发未开始的活动，到期自动生效、过期自动失效
- 额度支持 `SPEND`（限签账额）与 `REBATE`（限返现额），作用域 `YEAR` / `MONTH`
- 校验不通过或网络失败时，继续使用上次缓存的规则或内置规则（`BuiltinRules`），不影响记账

改规则的流程：编辑 `rules.json` → 提升 `version` → 提交推送。客户端启动时（距上次同步超过 12 小时）
自动拉取，也可在「设置 → 返现规则 → 检查更新」手动触发。

## 免责声明

返现金额为估算值，以银行实际入账为准。本工具与汇丰银行无关联。
