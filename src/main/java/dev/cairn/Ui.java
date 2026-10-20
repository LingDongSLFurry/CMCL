package dev.cairn;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Terminal-only view layer; does not load AWT, Swing, JavaFX or a browser. */
public final class Ui {
    private final Store store;
    private final BufferedReader input = new BufferedReader(new InputStreamReader(System.in,Terminal.charset()));
    public boolean json,plain;
    public Ui(Store store) { this.store = store; }
    public synchronized void note(String s) { if (!json) System.err.println(blue(plain || Terminal.asciiPreferred() ? "> " : "› ") + clean(Util.redact(s))); }
    public synchronized void out(String s) { System.out.println(json ? Json.stringify(Json.map("text",clean(s))) : clean(s)); }
    public static String clean(String s) {
        // Untrusted project names and logs must not inject terminal control sequences.
        return s.replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]","").replaceAll("\\x1B\\][^\\x07]*(?:\\x07|\\x1B\\\\)","")
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]","");
    }
    public String blue(String s) {
        boolean color = !plain && (store.text("color").equals("on") || store.text("color").equals("auto") && System.console() != null && Terminal.supportsAnsi());
        return color ? "\u001b[94m"+s+"\u001b[0m" : s;
    }
    public synchronized void frame(String title,List<String> lines) {
        if (json) { System.out.println(Json.stringify(Json.map("title",title,"lines",lines))); return; }
        boolean ascii = plain || Boolean.TRUE.equals(store.get("ascii")) || Terminal.asciiPreferred();
        String top = ascii ? "+" : "┌", bottom = ascii ? "+" : "└", side = ascii ? "|" : "│", bar = ascii ? "-" : "─";
        int width = 78;
        System.out.println(blue(top+bar.repeat(width-2)+(ascii ? "+" : "┐")));
        System.out.println(blue(side)+" "+fit(clean(title),width-4)+" "+blue(side));
        System.out.println(blue(ascii ? "+"+bar.repeat(width-2)+"+" : "├"+bar.repeat(width-2)+"┤"));
        for (String line : lines) {
            String clean = clean(line);
            if (displayWidth(clean) > width-4) {
                while (displayWidth(clean) > width-4) {
                    int pos = cut(clean,width-4);
                    System.out.println(blue(side)+" "+fit(clean.substring(0,pos),width-4)+" "+blue(side)); clean = clean.substring(pos);
                }
            }
            System.out.println(blue(side)+" "+fit(clean,width-4)+" "+blue(side));
        }
        System.out.println(blue(bottom+bar.repeat(width-2)+(ascii ? "+" : "┘")));
    }
    private static int cut(String s,int width) {
        int p = 0,w = 0;
        while (p < s.length()) { int c = s.codePointAt(p), n = charWidth(c); if (w+n > width) break; w += n; p += Character.charCount(c); }
        return p;
    }
    public static int displayWidth(String s) { return s.codePoints().map(Ui::charWidth).sum(); }
    private static int charWidth(int c) {
        if (Character.getType(c) == Character.NON_SPACING_MARK) return 0;
        return c >= 0x1100 && (c <= 0x115f || c >= 0x2e80 && c <= 0xa4cf || c >= 0xac00 && c <= 0xd7a3
                || c >= 0xf900 && c <= 0xfaff || c >= 0xfe10 && c <= 0xfe6f || c >= 0xff01 && c <= 0xff60 || c >= 0x1f300) ? 2 : 1;
    }
    public static String fit(String s,int width) { int end = cut(s,width); String t = s.substring(0,end); return t+" ".repeat(Math.max(0,width-displayWidth(t))); }
    public void table(String[] head,List<List<String>> rows) {
        if (json) {
            List<Object> items = new ArrayList<>();
            for (List<String> row : rows) { Map<String,Object> m = Json.map(); for (int i = 0; i < head.length; i++) m.put(head[i],row.get(i)); items.add(m); }
            System.out.println(Json.stringify(items)); return;
        }
        int[] widths = new int[head.length];
        for (int i = 0; i < head.length; i++) widths[i] = Math.min(55,displayWidth(head[i]));
        for (List<String> row : rows) for (int i = 0; i < head.length; i++) widths[i] = Math.min(55,Math.max(widths[i],displayWidth(clean(row.get(i)))));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < head.length; i++) b.append(fit(head[i],widths[i])).append("  ");
        System.out.println(blue(b.toString()));
        System.out.println((plain || Boolean.TRUE.equals(store.get("ascii")) || Terminal.asciiPreferred() ? "-" : "─").repeat(Math.min(150,Arrays.stream(widths).sum()+2*head.length)));
        for (List<String> row : rows) {
            b.setLength(0); for (int i = 0; i < head.length; i++) b.append(fit(clean(row.get(i)),widths[i])).append("  ");
            System.out.println(b);
        }
        if (rows.isEmpty()) System.out.println("(空)");
    }
    public String read(String prompt) throws IOException {
        System.out.print(blue(plain || Terminal.asciiPreferred() ? prompt.replace("›",">") : prompt)); System.out.flush();
        return System.console() != null ? System.console().readLine() : input.readLine();
    }
    public char[] secret(String prompt) throws IOException {
        if (System.console() != null) return System.console().readPassword("%s",prompt);
        System.err.println(prompt+"（当前为管道输入；请通过标准输入提供，勿写在命令参数中）");
        String s = input.readLine(); return s == null ? new char[0] : s.toCharArray();
    }
    public void data(Object o) { System.out.println(json ? Json.stringify(o) : Json.pretty(o)); }
}
