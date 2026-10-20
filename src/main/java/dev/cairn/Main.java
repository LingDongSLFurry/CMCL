package dev.cairn;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Main {
    public static final String VERSION="0.1.0";
    public static void main(String[] args) {
        System.setProperty("java.awt.headless","true");
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out),true,StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err),true,StandardCharsets.UTF_8));
        int result=0;
        try {
            List<String> a=new ArrayList<>(Arrays.asList(args)); Path home=null; String command=null,script=null;
            boolean plain=false,json=false;
            while (!a.isEmpty() && a.get(0).startsWith("-")) {
                String flag=a.remove(0);
                switch (flag) {
                    case "--home" -> { Util.require(!a.isEmpty(),"--home 缺少路径"); home=Path.of(a.remove(0)); }
                    case "--plain" -> plain=true;
                    case "--json" -> json=true;
                    case "-c","--command" -> { Util.require(!a.isEmpty(),"-c 缺少指令"); command=a.remove(0); }
                    case "--script" -> { Util.require(!a.isEmpty(),"--script 缺少路径"); script=a.remove(0); }
                    case "--version","-v" -> { System.out.println("Cairn "+VERSION+" / Java 17+ / terminal only"); return; }
                    case "--help","-h" -> { System.out.println("java -jar cairn.jar [--home <数据目录>] [--plain] [--json] [指令]\n"+Help.all()); return; }
                    case "--self-test" -> { SelfTest.run(); return; }
                    default -> throw new IllegalArgumentException("未知全局参数: "+flag);
                }
            }
            if (home == null) {
                String env=System.getenv("CAIRN_HOME");
                if (env != null && !env.isBlank()) home=Path.of(env);
                else {
                    Path location=Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
                    home=(Files.isRegularFile(location) ? location.getParent() : Path.of("").toAbsolutePath()).resolve("cairn-data");
                }
            }
            try (Store store=new Store(home); Shell shell=new Shell(store)) {
                shell.ui.plain=plain;
                shell.ui.json=json;
                if (script != null) result=shell.script(Path.of(script));
                else if (command != null) { Util.require(a.isEmpty(),"指令参数重复"); result=shell.execute(command); }
                else if (!a.isEmpty()) result=shell.execute(a.stream().map(Shell::quote).collect(java.util.stream.Collectors.joining(" ")));
                else shell.repl();
                try { shell.jobs.waitAll(); } catch (Exception e) { shell.ui.note(e.getMessage()); result=1; }
            }
        } catch (Exception e) {
            System.err.println("错误: "+Ui.clean(Util.redact(e.getMessage() == null ? e.toString() : e.getMessage()))); result=1;
        }
        if (result != 0) System.exit(result);
    }
}
