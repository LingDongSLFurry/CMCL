package dev.cairn;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Small strict JSON codec. No runtime dependencies. */
public final class Json {
    private Json() {}
    public static Map<String,Object> obj(Object o) {
        if (!(o instanceof Map<?,?> m)) throw new IllegalArgumentException("预期 JSON 对象");
        @SuppressWarnings("unchecked") Map<String,Object> v = (Map<String,Object>) m;
        return v;
    }
    public static List<Object> arr(Object o) {
        if (!(o instanceof List<?> l)) throw new IllegalArgumentException("预期 JSON 数组");
        @SuppressWarnings("unchecked") List<Object> v = (List<Object>) l;
        return v;
    }
    public static Map<String,Object> map(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("key/value pairs");
        Map<String,Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i].toString(), kv[i+1]);
        return m;
    }
    public static String str(Map<String,Object> m, String k, String fallback) {
        Object v = m.get(k); return v == null ? fallback : String.valueOf(v);
    }
    public static int integer(Map<String,Object> m, String k, int fallback) {
        Object v = m.get(k); return v == null ? fallback : Integer.parseInt(v.toString());
    }
    public static long number(Map<String,Object> m, String k, long fallback) {
        Object v = m.get(k); return v == null ? fallback : Long.parseLong(v.toString());
    }
    public static boolean bool(Map<String,Object> m, String k, boolean fallback) {
        Object v = m.get(k); return v == null ? fallback : Boolean.parseBoolean(v.toString());
    }
    public static Map<String,Object> child(Map<String,Object> m, String k) {
        return m.get(k) instanceof Map ? obj(m.get(k)) : new LinkedHashMap<>();
    }
    public static List<Object> list(Map<String,Object> m, String k) {
        return m.get(k) instanceof List ? arr(m.get(k)) : new ArrayList<>();
    }
    public static Object copy(Object o) { return parse(stringify(o)); }
    public static Object read(Path p) throws IOException { return parse(Files.readString(p, StandardCharsets.UTF_8)); }
    public static Object parse(String s) { return new Parser(s).parse(); }
    public static String stringify(Object o) { StringBuilder b = new StringBuilder(); write(o,b,0,false); return b.toString(); }
    public static String pretty(Object o) { StringBuilder b = new StringBuilder(); write(o,b,0,true); return b.append('\n').toString(); }
    private static void write(Object o, StringBuilder b, int depth, boolean pretty) {
        if (depth > 256) throw new IllegalArgumentException("JSON 嵌套过深");
        if (o == null) b.append("null");
        else if (o instanceof String s) {
            b.append('"');
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> { if (c < 32) b.append(String.format("\\u%04x",(int)c)); else b.append(c); }
                }
            }
            b.append('"');
        } else if (o instanceof Boolean || o instanceof Number) b.append(o);
        else if (o instanceof Map<?,?> m) {
            b.append('{'); int n = 0;
            for (var e : m.entrySet()) {
                if (n++ > 0) b.append(',');
                newline(b,depth+1,pretty); write(e.getKey().toString(),b,depth+1,pretty);
                b.append(pretty ? ": " : ":"); write(e.getValue(),b,depth+1,pretty);
            }
            if (!m.isEmpty()) newline(b,depth,pretty);
            b.append('}');
        } else if (o instanceof Collection<?> l) {
            b.append('['); int n = 0;
            for (Object v : l) {
                if (n++ > 0) b.append(',');
                newline(b,depth+1,pretty); write(v,b,depth+1,pretty);
            }
            if (!l.isEmpty()) newline(b,depth,pretty);
            b.append(']');
        } else throw new IllegalArgumentException("无法序列化 " + o.getClass());
    }
    private static void newline(StringBuilder b, int d, boolean p) { if (p) b.append('\n').append("  ".repeat(d)); }
    private static final class Parser {
        final String s; int p;
        Parser(String s) { this.s = s.startsWith("\ufeff") ? s.substring(1) : s; }
        Object parse() { Object v = value(0); ws(); if (p != s.length()) fail("多余内容"); return v; }
        void ws() { while (p < s.length() && " \t\r\n".indexOf(s.charAt(p)) >= 0) p++; }
        boolean take(char c) { ws(); if (p < s.length() && s.charAt(p) == c) { p++; return true; } return false; }
        void need(char c) { if (!take(c)) fail("缺少 " + c); }
        void fail(String msg) { throw new IllegalArgumentException("JSON @" + p + ": " + msg); }
        Object value(int d) {
            if (d > 256) { fail("嵌套过深"); }
            ws(); if (p >= s.length()) { fail("意外结束"); }
            char c = s.charAt(p);
            if (c == '"') return string();
            if (c == '{') {
                p++; Map<String,Object> m = new LinkedHashMap<>();
                if (take('}')) return m;
                do { ws(); if (p >= s.length() || s.charAt(p) != '"') fail("对象键必须为字符串");
                    String k = string(); need(':'); if (m.containsKey(k)) fail("重复键 " + k);
                    m.put(k,value(d+1));
                } while (take(','));
                need('}'); return m;
            }
            if (c == '[') {
                p++; List<Object> a = new ArrayList<>(); if (take(']')) return a;
                do { a.add(value(d+1)); } while (take(','));
                need(']'); return a;
            }
            for (String lit : List.of("true","false","null")) {
                if (s.startsWith(lit,p)) { p += lit.length(); return switch (lit) { case "true" -> true; case "false" -> false; default -> null; }; }
            }
            int start = p;
            if (p < s.length() && s.charAt(p) == '-') p++;
            if (p < s.length() && s.charAt(p) == '0') p++;
            else { int first = p; while (p < s.length() && Character.isDigit(s.charAt(p))) p++; if (p == first) fail("无效值"); }
            if (p < s.length() && s.charAt(p) == '.') {
                p++; int first = p; while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
                if (first == p) fail("无效小数");
            }
            if (p < s.length() && "eE".indexOf(s.charAt(p)) >= 0) {
                p++; if (p < s.length() && "+-".indexOf(s.charAt(p)) >= 0) p++;
                int first = p; while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
                if (first == p) fail("无效指数");
            }
            try {
                String n = s.substring(start,p);
                return n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0 ? Long.valueOf(n) : new BigDecimal(n);
            } catch (NumberFormatException e) { fail("无效数字"); return null; }
        }
        String string() {
            p++; StringBuilder b = new StringBuilder();
            while (p < s.length()) {
                char c = s.charAt(p++);
                if (c == '"') return b.toString();
                if (c < 32) fail("控制字符");
                if (c != '\\') { b.append(c); continue; }
                if (p >= s.length()) fail("转义不完整");
                char e = s.charAt(p++);
                switch (e) {
                    case '"','\\','/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (p+4 > s.length()) fail("Unicode 转义不完整");
                        try { b.append((char) Integer.parseInt(s.substring(p,p+4),16)); }
                        catch (NumberFormatException ex) { fail("Unicode 转义错误"); }
                        p += 4;
                    }
                    default -> fail("未知转义");
                }
            }
            fail("字符串未结束"); return null;
        }
    }
}
