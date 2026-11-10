package com.hsbcpulse.cashbook.pro;

/** 卡片模型（多卡支持，v2.0.0 引入）。每张卡独立记账、独立迎新窗口、独立年度额度。 */
public class Card {

    public long id = -1;
    public String name = "";
    /** 下卡日期 yyyy-MM-dd，迎新 60 日窗口由此起算；空串表示未设置。 */
    public String issueDate = "";
    public long createdAt = 0;

    public Card() {}

    public Card(long id, String name, String issueDate) {
        this.id = id;
        this.name = name;
        this.issueDate = issueDate == null ? "" : issueDate;
    }

    public boolean hasIssueDate() {
        return issueDate != null && issueDate.length() >= 10;
    }

    public String displayName() {
        return name == null || name.trim().isEmpty() ? "未命名卡片" : name.trim();
    }
}
