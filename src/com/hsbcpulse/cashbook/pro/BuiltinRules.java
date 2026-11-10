package com.hsbcpulse.cashbook.pro;

/**
 * 内置默认规则集（与 v1.5.0 主线口径完全一致）。
 *
 * 作用：
 * 1) 首次启动、断网、规则文件异常时的兜底，保证返现照常计算；
 * 2) 纯 JVM 单测的基准（LogicTest 的 56 项断言全部围绕这套数值）。
 *
 * 服务端规则与这里字段一致，只是数值/活动条数可随时调整。
 */
public final class BuiltinRules {

    public static final String VERSION = "builtin-1.5.0";

    private BuiltinRules() {}

    public static final String JSON =
            "{\n" +
            "  \"schemaVersion\": 1,\n" +
            "  \"version\": \"" + VERSION + "\",\n" +
            "  \"updatedAt\": \"2026-11-10\",\n" +
            "  \"baseRate\": 0.004,\n" +
            "  \"diningThreshold\": 1200,\n" +
            "  \"activities\": [\n" +
            "    {\n" +
            "      \"id\": \"pay_bonus\",\n" +
            "      \"name\": \"支付加成 2%\",\n" +
            "      \"type\": \"PAY_BONUS\",\n" +
            "      \"rate\": 0.02,\n" +
            "      \"startsOn\": \"2000-01-01\",\n" +
            "      \"endsOn\": \"2099-12-31\",\n" +
            "      \"capScope\": \"YEAR\",\n" +
            "      \"capAmount\": 80000,\n" +
            "      \"capLimitType\": \"SPEND\",\n" +
            "      \"payMethods\": [\"applepay\", \"unionqr\", \"jumpunion\"],\n" +
            "      \"confirmMethods\": [\"jumpunion\"],\n" +
            "      \"requiresOptIn\": false,\n" +
            "      \"enabled\": true\n" +
            "    },\n" +
            "    {\n" +
            "      \"id\": \"shop_world\",\n" +
            "      \"name\": \"赏世界 2%\",\n" +
            "      \"type\": \"SHOP_WORLD\",\n" +
            "      \"rate\": 0.02,\n" +
            "      \"startsOn\": \"2000-01-01\",\n" +
            "      \"endsOn\": \"2099-12-31\",\n" +
            "      \"capScope\": \"YEAR\",\n" +
            "      \"capAmount\": 100000,\n" +
            "      \"capLimitType\": \"SPEND\",\n" +
            "      \"payMethods\": [],\n" +
            "      \"excludeMethods\": [\"alipaywechat\"],\n" +
            "      \"confirmMethods\": [\"alipaywechat\"],\n" +
            "      \"requiresOptIn\": true,\n" +
            "      \"enabled\": true\n" +
            "    },\n" +
            "    {\n" +
            "      \"id\": \"dining\",\n" +
            "      \"name\": \"餐饮加码 3%\",\n" +
            "      \"type\": \"DINING\",\n" +
            "      \"rate\": 0.03,\n" +
            "      \"startsOn\": \"2000-01-01\",\n" +
            "      \"endsOn\": \"2099-12-31\",\n" +
            "      \"capScope\": \"MONTH\",\n" +
            "      \"capAmount\": 80,\n" +
            "      \"capLimitType\": \"REBATE\",\n" +
            "      \"minMonthlySpend\": 1200,\n" +
            "      \"diningOnly\": true,\n" +
            "      \"payMethods\": [],\n" +
            "      \"requiresOptIn\": false,\n" +
            "      \"enabled\": true\n" +
            "    }\n" +
            "  ]\n" +
            "}\n";
}
