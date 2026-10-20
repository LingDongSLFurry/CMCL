package dev.cairn;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class Multiplayer implements AutoCloseable {
    private final Store store;
    private final Ui ui;
    private Process link;
    public Multiplayer(Store store,Ui ui) { this.store=store; this.ui=ui; }
    public Map<String,Object> ping(String address) throws Exception {
        Launch.HostPort hp=Launch.hostPort(address,25565); long start=System.nanoTime();
        try (Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress(hp.host(),hp.port()),5000); socket.setSoTimeout(5000);
            DataOutputStream out=new DataOutputStream(socket.getOutputStream()); DataInputStream in=new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream packet=new ByteArrayOutputStream(); DataOutputStream p=new DataOutputStream(packet);
            writeVarInt(p,0); writeVarInt(p,-1); writeString(p,hp.host()); p.writeShort(hp.port()); writeVarInt(p,1);
            writeVarInt(out,packet.size()); out.write(packet.toByteArray()); out.write(new byte[]{1,0}); out.flush();
            int length=readVarInt(in); Util.require(length > 0 && length <= 2*1024*1024,"无效服务器响应长度");
            byte[] response=in.readNBytes(length); Util.require(response.length == length,"服务器响应不完整");
            DataInputStream r=new DataInputStream(new ByteArrayInputStream(response)); Util.require(readVarInt(r) == 0,"无效状态响应");
            int n=readVarInt(r); Util.require(n >= 0 && n <= response.length,"无效状态长度");
            byte[] json=r.readNBytes(n); Util.require(json.length == n,"状态数据不完整");
            Map<String,Object> status=Json.obj(Json.parse(new String(json,StandardCharsets.UTF_8)));
            status.remove("favicon"); status.put("latencyMs",(System.nanoTime()-start)/1_000_000); status.put("address",address); return status;
        }
    }
    public static void writeVarInt(DataOutputStream out,int value) throws IOException {
        do { int b=value & 0x7f; value >>>=7; if (value != 0) b|=0x80; out.writeByte(b); } while (value != 0);
    }
    public static int readVarInt(DataInputStream in) throws IOException {
        int value=0,position=0,b; do { b=in.readUnsignedByte(); value|=(b&0x7f)<<position; position+=7; if (position > 35) throw new IOException("VarInt 过长"); } while ((b&0x80) != 0);
        return value;
    }
    private static void writeString(DataOutputStream out,String s) throws IOException { byte[] bytes=s.getBytes(StandardCharsets.UTF_8); writeVarInt(out,bytes.length); out.write(bytes); }
    public List<Object> lan(int seconds) throws Exception {
        Util.require(seconds >= 1 && seconds <= 15,"扫描时间 1~15 秒"); Set<String> seen=new LinkedHashSet<>(); List<Object> list=new ArrayList<>();
        InetAddress group=InetAddress.getByName("224.0.2.60"); long end=System.currentTimeMillis()+seconds*1000L;
        try (MulticastSocket socket=new MulticastSocket(null)) {
            socket.setReuseAddress(true); socket.bind(new InetSocketAddress(4445)); socket.setSoTimeout(500);
            // Join on each multicast-capable active interface to handle VPNs and multi-homed machines.
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try { if (ni.isUp() && ni.supportsMulticast()) socket.joinGroup(new InetSocketAddress(group,4445),ni); } catch (IOException ignored) {}
            }
            while (System.currentTimeMillis() < end) {
                byte[] bytes=new byte[2048]; DatagramPacket p=new DatagramPacket(bytes,bytes.length);
                try {
                    socket.receive(p); String text=new String(p.getData(),p.getOffset(),p.getLength(),StandardCharsets.UTF_8);
                    String port=between(text,"[AD]","[/AD]"), motd=between(text,"[MOTD]","[/MOTD]");
                    if (!port.matches("\\d{1,5}")) continue; String address=p.getAddress().getHostAddress()+":"+port;
                    if (seen.add(address)) list.add(Json.map("address",address,"motd",motd));
                } catch (SocketTimeoutException ignored) {}
            }
        }
        return list;
    }
    private String between(String s,String a,String b) { int start=s.indexOf(a),end=s.indexOf(b); return start < 0 || end <= start ? "" : s.substring(start+a.length(),end); }
    public Map<String,Object> link(String role,String network,String secret,String peer,String address) throws Exception {
        Util.require(link == null || !link.isAlive(),"已有联机进程");
        Path exe=Path.of(store.text("easyTier")); Util.require(Files.isRegularFile(exe),"请从 EasyTier 官方发行页安装 CLI 后 config set easyTier <easytier-core路径>");
        Util.require(Set.of("host","join").contains(role),"link host | join");
        Util.require(network.matches("[A-Za-z0-9_-]{3,60}") && secret.length() >= 8,"房间名 3~60 位；密码至少 8 位");
        URI endpoint=URI.create(peer); Util.require(Set.of("tcp","udp","ws","wss","quic").contains(endpoint.getScheme()) && endpoint.getHost() != null,"请填写有效的自定义 EasyTier 节点地址");
        List<String> cmd=new ArrayList<>(List.of(exe.toAbsolutePath().toString(),"--network-name",network,"--network-secret",secret,"--private-mode","true","-p",peer,"--rpc-portal","127.0.0.1:0"));
        if (role.equals("host")) cmd.addAll(List.of("-i","10.126.0.1"));
        else {
            cmd.add("-d");
            Launch.HostPort local=Launch.hostPort(address,25565);
            // A guest connects to localhost:<port>, forwarded to the host's agreed virtual address.
            cmd.addAll(List.of("--port-forward","tcp://127.0.0.1:"+local.port()+"/10.126.0.1:"+local.port()));
        }
        Path log=store.home.resolve("logs").resolve("link-"+Util.timestamp()+".log");
        link=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (link.waitFor(700,TimeUnit.MILLISECONDS)) throw new IOException("EasyTier 启动失败: "+Util.tail(log,20));
        Map<String,Object> state=Json.map("pid",link.pid(),"processStart",link.toHandle().info().startInstant().map(Instant::toString).orElse(""),
                "role",role,"network",network,"peer",peer,"log",log.toString());
        Util.atomic(store.home.resolve("link.json"),Json.pretty(state)); ui.note("联机模块已启动；需要相同房间名、密码与节点，连接结果请看 link status。"); return state;
    }
    public Map<String,Object> status() throws Exception {
        Path file=store.home.resolve("link.json"); if (!Files.exists(file)) return Json.map("alive",false);
        Map<String,Object> s=Json.obj(Json.read(file)); s.put("alive",Launch.alive(s)); return s;
    }
    public void stop() throws Exception {
        Map<String,Object> s=status();
        if (Json.bool(s,"alive",false)) ProcessHandle.of(Json.number(s,"pid",0)).ifPresent(ProcessHandle::destroy);
    }
    @Override public void close() {}
}
