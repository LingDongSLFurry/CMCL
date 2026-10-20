package dev.cairn;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** Match a real console's code page; keep redirected output UTF-8 by default. */
public final class Terminal {
    private static volatile Charset current=StandardCharsets.UTF_8;
    private Terminal() {}
    public static Charset choose(String requested,Charset consoleCharset) {
        if (requested != null && !requested.isBlank() && !requested.equalsIgnoreCase("auto")) return Charset.forName(requested);
        return consoleCharset == null ? StandardCharsets.UTF_8 : consoleCharset;
    }
    public static void configure(String requested) {
        Console console=System.console();
        current=choose(requested,console == null ? null : console.charset());
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out),true,current));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err),true,current));
    }
    public static Charset charset() { return current; }
    public static boolean canEncode(String text) { return current.newEncoder().canEncode(text); }
    public static boolean asciiPreferred() { return !current.equals(StandardCharsets.UTF_8) || !canEncode("┌─┐│└┘›"); }
    public static boolean supportsAnsi(Map<String,String> env,boolean windows) {
        String term=env.getOrDefault("TERM","").toLowerCase(Locale.ROOT);
        if (term.equals("dumb") || env.containsKey("NO_COLOR")) return false;
        if (!windows) return !term.isBlank();
        return !env.getOrDefault("WT_SESSION","").isBlank() || !env.getOrDefault("ANSICON","").isBlank()
                || env.getOrDefault("ConEmuANSI","").equalsIgnoreCase("ON")
                || Set.of("vscode","wezterm").contains(env.getOrDefault("TERM_PROGRAM","").toLowerCase(Locale.ROOT))
                || term.startsWith("xterm") || term.startsWith("screen") || term.startsWith("tmux");
    }
    public static boolean supportsAnsi() { return supportsAnsi(System.getenv(),Util.WINDOWS); }
}
