package com.hsbcpulse.cashbook.pro;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一套完整的返现规则（服务端下发 / 内置兜底）。
 *
 * 设计要点：
 * - 带 schemaVersion，客户端只认自己支持的版本，避免新格式把老客户端算错；
 * - 带 version 字符串，用于「是否有更新」判断与页面展示；
 * - activities 允许包含起始日期在未来的活动（提前下发），到期自动生效；
 * - 解析与校验都在纯 JVM 完成，服务端返回脏数据时直接拒绝，继续沿用旧规则。
 */
public class RuleSet {

    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    /** 规则版本号，如 2026.11.1；内置为 builtin-1.5.0。 */
    public String version = "";
    public String updatedAt = "";
    /** 基础返现率，默认 0.4%。 */
    public double baseRate = 0.004;
    /** 餐饮加码的月度签账门槛（用于界面文案，活动本身用 minMonthlySpend）。 */
    public double diningThreshold = 1200;
    public List<RewardActivity> activities = new ArrayList<>();

    public RuleSet() {}

    // ---------- 构造 ----------

    /** 内置默认规则（永不失败；解析异常属于代码错误）。 */
    public static RuleSet builtin() {
        try {
            return parse(BuiltinRules.JSON);
        } catch (Exception e) {
            // 兜底：最小可用规则，避免整页返现归零
            RuleSet rs = new RuleSet();
            rs.version = BuiltinRules.VERSION;
            return rs;
        }
    }

    /** 解析并校验。失败抛 IllegalArgumentException（调用方应保留旧规则）。 */
    public static RuleSet parse(String json) {
        Object root = MiniJson.parse(json);
        Map<String, Object> m = MiniJson.asMap(root);
        if (m == null) throw new IllegalArgumentException("规则根节点必须是对象");

        RuleSet rs = new RuleSet();
        rs.schemaVersion = (int) MiniJson.num(m, "schemaVersion", SCHEMA_VERSION);
        rs.version = MiniJson.str(m, "version", "");
        rs.updatedAt = MiniJson.str(m, "updatedAt", "");
        rs.baseRate = MiniJson.num(m, "baseRate", 0.004);
        rs.diningThreshold = MiniJson.num(m, "diningThreshold", 1200);

        List<Object> arr = MiniJson.asList(m.get("activities"));
        if (arr != null) {
            for (Object o : arr) {
                Map<String, Object> am = MiniJson.asMap(o);
                if (am == null) continue;
                rs.activities.add(activity(am));
            }
        }
        rs.validate();
        return rs;
    }

    private static RewardActivity activity(Map<String, Object> m) {
        RewardActivity a = new RewardActivity();
        a.id = MiniJson.str(m, "id", "").trim();
        a.name = MiniJson.str(m, "name", a.id);
        a.type = MiniJson.str(m, "type", RewardActivity.TYPE_PAY_BONUS).trim();
        a.rate = MiniJson.num(m, "rate", 0);
        a.startsOn = MiniJson.str(m, "startsOn", "");
        a.endsOn = MiniJson.str(m, "endsOn", "");
        a.capScope = MiniJson.str(m, "capScope", RewardActivity.SCOPE_NONE).trim();
        a.capAmount = MiniJson.num(m, "capAmount", 0);
        a.capLimitType = MiniJson.str(m, "capLimitType", RewardActivity.LIMIT_SPEND).trim();
        a.minMonthlySpend = MiniJson.num(m, "minMonthlySpend", 0);
        a.diningOnly = MiniJson.bool(m, "diningOnly", false);
        a.payMethods = MiniJson.strList(m, "payMethods");
        a.confirmMethods = MiniJson.strList(m, "confirmMethods");
        a.excludeMethods = MiniJson.strList(m, "excludeMethods");
        a.requiresOptIn = MiniJson.bool(m, "requiresOptIn", false);
        a.enabled = MiniJson.bool(m, "enabled", true);
        return a;
    }

    // ---------- 校验 ----------

    /** 校验规则合法性；不合法直接抛异常，避免把错误规则算进账里。 */
    public void validate() {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的 schemaVersion: " + schemaVersion);
        }
        if (baseRate < 0 || baseRate > 0.1) {
            throw new IllegalArgumentException("baseRate 超出合理范围: " + baseRate);
        }
        if (activities.isEmpty()) {
            throw new IllegalArgumentException("activities 不能为空");
        }
        Set<String> ids = new HashSet<>();
        for (RewardActivity a : activities) {
            if (a.id.isEmpty()) throw new IllegalArgumentException("活动缺少 id");
            if (!ids.add(a.id)) throw new IllegalArgumentException("活动 id 重复: " + a.id);
            if (a.rate < 0 || a.rate > 0.5) {
                throw new IllegalArgumentException("活动 " + a.id + " 费率异常: " + a.rate);
            }
            if (!RewardActivity.TYPE_PAY_BONUS.equals(a.type)
                    && !RewardActivity.TYPE_SHOP_WORLD.equals(a.type)
                    && !RewardActivity.TYPE_DINING.equals(a.type)) {
                throw new IllegalArgumentException("活动 " + a.id + " 类型未知: " + a.type);
            }
            if (!a.startsOn.isEmpty()) checkDate(a.id, "startsOn", a.startsOn);
            if (!a.endsOn.isEmpty()) checkDate(a.id, "endsOn", a.endsOn);
            if (!a.startsOn.isEmpty() && !a.endsOn.isEmpty()
                    && a.startsOn.compareTo(a.endsOn) > 0) {
                throw new IllegalArgumentException("活动 " + a.id + " 结束日期早于开始日期");
            }
            if (!RewardActivity.SCOPE_NONE.equals(a.capScope)
                    && !RewardActivity.SCOPE_YEAR.equals(a.capScope)
                    && !RewardActivity.SCOPE_MONTH.equals(a.capScope)) {
                throw new IllegalArgumentException("活动 " + a.id + " 额度作用域未知: " + a.capScope);
            }
            if (!RewardActivity.LIMIT_SPEND.equals(a.capLimitType)
                    && !RewardActivity.LIMIT_REBATE.equals(a.capLimitType)) {
                throw new IllegalArgumentException("活动 " + a.id + " 额度类型未知: " + a.capLimitType);
            }
            if (!RewardActivity.SCOPE_NONE.equals(a.capScope) && a.capAmount < 0) {
                throw new IllegalArgumentException("活动 " + a.id + " 额度为负");
            }
        }
    }

    private static void checkDate(String id, String field, String date) {
        if (date.length() != 10 || date.charAt(4) != '-' || date.charAt(7) != '-') {
            throw new IllegalArgumentException("活动 " + id + " 的 " + field + " 格式应为 yyyy-MM-dd");
        }
        try {
            Integer.parseInt(date.substring(0, 4));
            Integer.parseInt(date.substring(5, 7));
            Integer.parseInt(date.substring(8, 10));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("活动 " + id + " 的 " + field + " 不是合法日期");
        }
    }

    // ---------- 查询 ----------

    public List<RewardActivity> ofType(String type) {
        List<RewardActivity> out = new ArrayList<>();
        for (RewardActivity a : activities) {
            if (a.enabled && a.type.equals(type)) out.add(a);
        }
        return out;
    }

    public RewardActivity findById(String id) {
        for (RewardActivity a : activities) {
            if (a.id.equals(id)) return a;
        }
        return null;
    }

    /** 是否需要用户报名（任一活动要求报名即为 true），用于决定是否显示年度开关。 */
    public boolean hasOptInActivity() {
        for (RewardActivity a : activities) {
            if (a.enabled && a.requiresOptIn) return true;
        }
        return false;
    }

    /** 规则摘要，用于设置页展示（例：3 个活动 · 基础 0.4%）。 */
    public String summary() {
        return activities.size() + " 个活动 · 基础 " + RuleEngine.pct(baseRate) + "%";
    }
}
