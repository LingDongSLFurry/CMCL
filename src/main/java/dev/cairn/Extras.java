package dev.cairn;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.sound.sampled.*;

public final class Extras implements AutoCloseable {
    private final Store store;
    private Clip clip;
    public Extras(Store store) { this.store=store; }
    public void music(Path input,float volume) throws Exception {
        Util.require(Files.isRegularFile(input),"音频不存在"); stopMusic();
        // The JRE provides PCM/WAV/AIFF/AU support without opening an application window.
        try (AudioInputStream audio=AudioSystem.getAudioInputStream(input.toFile())) {
            Util.require(audio.getFrameLength() >= 0 && audio.getFrameLength()*audio.getFormat().getFrameSize() <= 128L*1024*1024,"音频超过 128 MiB 或长度未知");
            clip=AudioSystem.getClip(); clip.open(audio);
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain=(FloatControl)clip.getControl(FloatControl.Type.MASTER_GAIN);
                gain.setValue(volume <= 0 ? gain.getMinimum() : Math.max(gain.getMinimum(),Math.min(gain.getMaximum(),(float)(20*Math.log10(volume)))));
            }
            clip.loop(Clip.LOOP_CONTINUOUSLY); clip.start();
        }
    }
    public void stopMusic() { if (clip != null) { clip.stop(); clip.close(); clip=null; } }
    public void offlineSkin(String instance,Path png) throws Exception {
        instance=store.selected(instance); Util.require(Files.size(png) <= 1024*1024,"皮肤文件超过 1 MiB");
        byte[] bytes=Files.readAllBytes(png); Util.require(bytes.length >= 24 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10}),"预期 PNG 皮肤");
        ByteBuffer header=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN); int width=header.getInt(16),height=header.getInt(20);
        Util.require(width == 64 && (height == 32 || height == 64),"皮肤尺寸须为 64×32 或 64×64");
        Path game=store.gameDir(instance), out=Util.within(game,"resourcepacks/cairn-offline-skin.zip"); Files.createDirectories(out.getParent());
        Path tmp=Files.createTempFile(out.getParent(),".skin-",".zip");
        try {
            try (ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(tmp))) {
                write(zip,"pack.mcmeta",Json.stringify(Json.map("pack",Json.map("pack_format",15,"supported_formats",List.of(1,9999),"description","Cairn local offline skin"))).getBytes(StandardCharsets.UTF_8));
                for (String name : List.of("steve","alex")) write(zip,"assets/minecraft/textures/entity/"+name+".png",bytes);
                for (String shape : List.of("wide","slim")) for (String name : List.of("alex","ari","efe","kai","makena","noor","steve","sunny","zuri"))
                    write(zip,"assets/minecraft/textures/entity/player/"+shape+"/"+name+".png",bytes);
            }
            if (Files.exists(out)) store.trash(out,game);
            Util.move(tmp,out,false);
        } finally { Files.deleteIfExists(tmp); }
        Path options=game.resolve("options.txt"); List<String> lines=Files.exists(options) ? new ArrayList<>(Files.readAllLines(options,StandardCharsets.UTF_8)) : new ArrayList<>();
        List<Object> packs=new ArrayList<>(); boolean had=false;
        for (int i=0;i<lines.size();i++) if (lines.get(i).startsWith("resourcePacks:")) {
            try { packs.addAll(Json.arr(Json.parse(lines.get(i).substring("resourcePacks:".length())))); } catch (RuntimeException ignored) {}
            if (!packs.contains("file/cairn-offline-skin.zip")) packs.add("file/cairn-offline-skin.zip");
            lines.set(i,"resourcePacks:"+Json.stringify(packs)); had=true; break;
        }
        if (!had) lines.add("resourcePacks:"+Json.stringify(List.of("file/cairn-offline-skin.zip")));
        if (Files.exists(options)) {
            Path backup=store.instance(instance).resolve("backups/options-"+Util.timestamp()+".txt"); Files.createDirectories(backup.getParent()); Files.copy(options,backup);
        }
        Util.atomic(options,String.join("\n",lines)+"\n");
    }
    private void write(ZipOutputStream zip,String name,byte[] data) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(data); zip.closeEntry(); }
    public Map<String,Object> memory() {
        var bean=java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        Map<String,Object> out=Json.map("launcherHeapUsed",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),"processors",Runtime.getRuntime().availableProcessors());
        if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
            out.put("physicalTotal",os.getTotalMemorySize()); out.put("physicalFree",os.getFreeMemorySize());
        }
        return out;
    }
    @Override public void close() { stopMusic(); }
}
