package com.hsbcpulse.cashbook.pro;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（纯 Java，零依赖）。
 *
 * Android 自带 org.json，但那份实现在纯 JVM 单测里不可用（android.jar 是桩）。
 * 为了让「规则解析」既能上手机又能被 LogicTest/RuleEngineTest 覆盖，这里手写一份。
 * 只做解析（读），不做序列化；对象 → LinkedHashMap，数组 → ArrayList，
 * 数字 → Double，字符串 → String，true/false → Boolean，null → null。
 */
public final class MiniJson {

    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
    }

    public static Object parse(String json) {
        if (json == null) throw new IllegalArgumentException("JSON 为空");
        MiniJson p = new MiniJson(json);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (p.pos < p.src.length()) throw p.err("多余内容");
        return v;
    }

    // ---------- 解析核心 ----------

    private Object readValue() {
        if (pos >= src.length()) throw err("意外结束");
        char c = src.charAt(pos);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            skipWs();
            if (peek() != '"') throw err("对象键必须是字符串");
            String key = readString();
            skipWs();
            if (peek() != ':') throw err("缺少冒号");
            pos++;
            skipWs();
            map.put(key, readValue());
            skipWs();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == '}') { pos++; return map; }
            throw err("对象未正确闭合");
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWs();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            skipWs();
            list.add(readValue());
            skipWs();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == ']') { pos++; return list; }
            throw err("数组未正确闭合");
        }
    }

    private String readString() {
        pos++; // "
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw err("字符串未闭合");
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            if (pos >= src.length()) throw err("转义未完成");
            char e = src.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > src.length()) throw err("\\u 转义不完整");
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default: throw err("未知转义 \\" + e);
            }
        }
    }

    private Double readNumber() {
        int start = pos;
        if (peek() == '-' || peek() == '+') pos++;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++;
            else break;
        }
        if (start == pos) throw err("不是合法数字");
        try {
            return Double.valueOf(src.substring(start, pos));
        } catch (NumberFormatException e) {
            throw err("数字格式错误");
        }
    }

    // ---------- 工具 ----------

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') pos++;
            else break;
        }
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private void expect(String word) {
        if (!src.startsWith(word, pos)) throw err("期望 " + word);
        pos += word.length();
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException(msg + "（位置 " + pos + "）");
    }

    // ---------- 便捷读取 ----------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String str(Map<String, Object> m, String k, String def) {
        if (m == null) return def;
        Object v = m.get(k);
        return v instanceof String ? (String) v : def;
    }

    public static double num(Map<String, Object> m, String k, double def) {
        if (m == null) return def;
        Object v = m.get(k);
        if (v instanceof Double) return (Double) v;
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (Exception ignored) { }
        }
        return def;
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        if (m == null) return def;
        Object v = m.get(k);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    public static List<String> strList(Map<String, Object> m, String k) {
        List<String> out = new ArrayList<>();
        if (m == null) return out;
        List<Object> arr = asList(m.get(k));
        if (arr == null) return out;
        for (Object o : arr) {
            if (o instanceof String) out.add((String) o);
        }
        return out;
    }
}
