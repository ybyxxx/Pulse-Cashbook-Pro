package com.hsbcpulse.cashbook.pro;

/** 订单模型。金额为 double，HKD 与 CNY 按 1:1 统计；返现/迎新均按净额（金额 − 退款）计算。 */
public class Order {
    // 支付方式（单项，v1.4.0 合并后 5 选 1）
    public static final String PAY_NONE = "";
    public static final String PAY_APPLE = "applepay";
    public static final String PAY_UNION_QR = "unionqr";
    public static final String PAY_ALIPAY_WECHAT = "alipaywechat";
    /** 跳转云闪付（微信/App 跳转合并）。需自行确认后享 +2%。 */
    public static final String PAY_JUMP_UNION = "jumpunion";
    /** 在线支付 / 实体卡（合并）。无加成。 */
    public static final String PAY_ONLINE_CARD = "onlinecard";

    // 旧版本遗留值（仅用于数据库迁移/读取兼容）
    private static final String LEGACY_WECHAT_UNION = "wechat_union";
    private static final String LEGACY_APP_UNION = "app_union";
    private static final String LEGACY_ONLINE = "online";
    private static final String LEGACY_PHYSICAL = "physical";

    public long id = -1;
    /** 所属卡片 id（多卡支持）。1 为默认卡。 */
    public long cardId = 1;
    public String date = "";      // yyyy-MM-dd
    public double amount = 0;
    /** 累计退款金额（部分退款），0 表示未退款。 */
    public double refundAmount = 0;
    public String currency = "CNY"; // HKD / CNY，默认 CNY
    public String note = "";
    public String payMethod = PAY_NONE;
    /**
     * 自行确认标记（语义随支付方式不同）：
     * - 跳转云闪付：确认后可享支付加成 +2%
     * - 支付宝/微信：确认后可参与赏世界 +2%（默认不参与）
     */
    public boolean payConfirmed = false;
    public boolean dining = false;
    public long createdAt = 0;
    public java.util.List<String> attachments = new java.util.ArrayList<>();

    /** 净签账额（金额 − 退款，不小于 0）。返现与迎新统计均以此计算。 */
    public double netAmount() {
        double net = amount - refundAmount;
        return net > 0 ? Cashback.round2(net) : 0;
    }

    /** 是否已有退款。 */
    public boolean hasRefund() {
        return refundAmount > 0;
    }

    /** 归一化旧版支付方式值（v3 迁移前数据兼容）。 */
    public static String normalizePayMethod(String m) {
        if (m == null) return PAY_NONE;
        switch (m) {
            case LEGACY_WECHAT_UNION:
            case LEGACY_APP_UNION:
                return PAY_JUMP_UNION;
            case LEGACY_ONLINE:
            case LEGACY_PHYSICAL:
                return PAY_ONLINE_CARD;
            default:
                return m;
        }
    }

    /** 该支付方式是否需要「自行确认」（跳转云闪付确认支付加成；支付宝/微信确认赏世界资格）。 */
    public boolean needsConfirm() {
        return PAY_JUMP_UNION.equals(payMethod) || PAY_ALIPAY_WECHAT.equals(payMethod);
    }

    /** 是否享有支付加成 +2%。Apple Pay / 云闪付QR 自动享有；跳转云闪付需自行确认。支付宝/微信永不享有。 */
    public boolean payBonusEligible() {
        return PAY_APPLE.equals(payMethod)
                || PAY_UNION_QR.equals(payMethod)
                || (PAY_JUMP_UNION.equals(payMethod) && payConfirmed);
    }

    /** 是否可享赏世界 +2%。支付宝/微信默认不参与，需自行确认；其余支付方式均可参与。 */
    public boolean shopWorldEligible() {
        if (PAY_ALIPAY_WECHAT.equals(payMethod)) return payConfirmed;
        return true;
    }

    public boolean hasMethod() {
        return payMethod != null && !payMethod.isEmpty();
    }

    public String methodLabel() {
        return methodLabel(payMethod);
    }

    public static String methodLabel(String m) {
        String n = normalizePayMethod(m);
        switch (n) {
            case PAY_APPLE: return "Apple Pay";
            case PAY_UNION_QR: return "云闪付QR";
            case PAY_ALIPAY_WECHAT: return "支付宝/微信";
            case PAY_JUMP_UNION: return "跳转云闪付";
            case PAY_ONLINE_CARD: return "在线支付/实体卡";
            default: return "未选择";
        }
    }

    /** 列表标签用短名。 */
    public String methodShortLabel() {
        switch (normalizePayMethod(payMethod)) {
            case PAY_APPLE: return "ApplePay";
            case PAY_UNION_QR: return "云闪付QR";
            case PAY_ALIPAY_WECHAT: return "支付宝/微信";
            case PAY_JUMP_UNION: return "跳转云闪付";
            case PAY_ONLINE_CARD: return "在线/实体卡";
            default: return "";
        }
    }

    public String currencySymbol() {
        return "HKD".equals(currency) ? "HK$" : "¥";
    }

    public String formatAmount() {
        return currencySymbol() + String.format(java.util.Locale.US, "%.2f", amount);
    }
}
