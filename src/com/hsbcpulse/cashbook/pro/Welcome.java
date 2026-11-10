package com.hsbcpulse.cashbook.pro;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 迎新活动（HSBC Pulse 基本迎新 + 「HSBCPULSE」额外加码）计算引擎。
 *
 * 规则：
 * - 自下卡日起首 60 个历日（含当日）为合资格窗口；
 * - 基础迎新：窗口内累积合资格签账（HKD=CNY 1:1，按净额；支付宝/微信交易不计入）满 8,000 → $800「奖赏钱」；
 * - 额外加码（在基础之上叠加）：窗口内透过 Reward+/云闪付 App 二维码付款，
 *   单一签账满 50 元，每次可获 $10「奖赏钱」，最多 28 次（合共 $280）；
 *   加码同样以达成 8,000 签账为前提。
 * - 合计最高 $1,080；过窗口后不再累计。
 */
public final class Welcome {

    public static final double SPEND_TARGET = 8000.0;
    public static final double BASE_REWARD = 800.0;
    public static final double QR_MIN = 50.0;
    public static final int QR_MAX_TIMES = 28;
    public static final double QR_REBATE = 10.0;
    public static final double MAX_REWARD = BASE_REWARD + QR_MAX_TIMES * QR_REBATE; // 1080
    public static final int WINDOW_DAYS = 60; // 含当日

    private Welcome() {}

    /** 计算结果。 */
    public static class Result {
        public boolean hasIssueDate = false;
        public boolean inWindow = false;   // 今天仍在窗口内
        public boolean expired = false;    // 今天已过窗口
        public long daysLeft = 0;          // 剩余天数（含今天）
        public String windowStart = "";
        public String windowEnd = "";
        public double spend = 0;           // 窗口内累计签账
        public boolean spendMet = false;
        public int qrCount = 0;            // 合资格 QR 笔数（未封顶）
        public int qrCounted = 0;          // 封顶后计入笔数
        public double reward = 0;          // 预计迎新奖赏
    }

    /**
     * 该笔是否为合资格二维码付款（条款 2d：Reward+ 或云闪付 App 二维码）。
     * 云闪付QR 自动计入；跳转云闪付需自行确认；Apple Pay 非二维码不计。
     */
    public static boolean isQrQualified(Order o) {
        if (o == null || o.payMethod == null) return false;
        if (Order.PAY_UNION_QR.equals(o.payMethod)) return true;
        return Order.PAY_JUMP_UNION.equals(o.payMethod) && o.payConfirmed;
    }

    /** compute：orders 为全部订单（不限月份），issueDate 格式 yyyy-MM-dd，today 为当天。 */
    public static Result compute(List<Order> orders, String issueDate, LocalDate today) {
        Result r = new Result();
        if (issueDate == null || issueDate.length() < 10) return r;
        r.hasIssueDate = true;

        LocalDate start = LocalDate.parse(issueDate);
        LocalDate end = start.plusDays(WINDOW_DAYS - 1); // 含当日共 60 日
        r.windowStart = start.toString();
        r.windowEnd = end.toString();
        r.inWindow = !today.isBefore(start) && !today.isAfter(end);
        r.expired = today.isAfter(end);
        r.daysLeft = r.inWindow ? (ChronoUnit.DAYS.between(today, end) + 1) : 0;

        if (orders != null) {
            for (Order o : orders) {
                if (o == null || o.date == null || o.date.length() < 10) continue;
                // 支付宝/微信交易不计入迎新签账统计
                if (Order.PAY_ALIPAY_WECHAT.equals(o.payMethod)) continue;
                LocalDate d = LocalDate.parse(o.date);
                if (d.isBefore(start) || d.isAfter(end)) continue;
                double net = o.netAmount();
                r.spend += net;
                if (isQrQualified(o) && net >= QR_MIN) {
                    r.qrCount++;
                }
            }
        }
        r.spend = Cashback.round2(r.spend);
        r.spendMet = r.spend >= SPEND_TARGET;
        r.qrCounted = Math.min(r.qrCount, QR_MAX_TIMES);
        // 达标后：基础 $800 + QR 加码 $10×N
        r.reward = r.spendMet ? (BASE_REWARD + r.qrCounted * QR_REBATE) : 0;
        return r;
    }
}
