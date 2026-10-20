package dev.cairn;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Accounts {
    private static final String OAUTH="https://login.microsoftonline.com/consumers/oauth2/v2.0/";
    private final Store store;
    private final Net net;
    private final Ui ui;
    public Accounts(Store store,Net net,Ui ui) { this.store=store; this.net=net; this.ui=ui; }
    public void offline(String name) throws Exception {
        Util.require(name.matches("[A-Za-z0-9_]{1,16}"),"离线名必须为 1~16 位字母、数字或下划线");
        String uuid=UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8)).toString().replace("-","");
        save(Json.map("id","offline-"+name,"type","offline","name",name,"uuid",uuid,"accessToken","0","expires",0));
        ui.note("离线账户已保存。正版服务器需要微软账户；购买: https://www.minecraft.net/store/minecraft-java-bedrock-edition-pc");
    }
    public synchronized void save(Map<String,Object> account) throws Exception {
        List<Object> accounts=new ArrayList<>(store.accounts());
        String id=Json.str(account,"id","");
        accounts.removeIf(o -> Json.str(Json.obj(o),"id","").equals(id)); accounts.add(account); store.accounts(accounts);
        store.set("account",id);
    }
    public List<Object> list() throws Exception {
        List<Object> out=new ArrayList<>();
        for (Object raw : store.accounts()) {
            Map<String,Object> a=Json.obj(raw); out.add(Json.map("id",a.get("id"),"type",a.get("type"),"name",a.get("name"),"uuid",a.get("uuid"),"selected",a.get("id").equals(store.text("account"))));
        }
        return out;
    }
    public Map<String,Object> find(String id) throws Exception {
        String target=id == null || id.isBlank() ? store.text("account") : id;
        for (Object raw : store.accounts()) {
            Map<String,Object> a=Json.obj(raw);
            if (target.equals(a.get("id")) || target.equals(a.get("name"))) return a;
        }
        throw new IOException("账户不存在。account offline <玩家名> 或 account microsoft");
    }
    public void use(String id) throws Exception { store.set("account",Json.str(find(id),"id","")); }
    public void remove(String id) throws Exception {
        String actual=Json.str(find(id),"id","");
        List<Object> list=new ArrayList<>(store.accounts()); list.removeIf(a -> actual.equals(Json.obj(a).get("id"))); store.accounts(list);
        if (actual.equals(store.text("account"))) store.set("account","");
    }
    public void microsoft() throws Exception {
        String client=store.text("msClientId");
        if (client.isBlank()) client=System.getenv().getOrDefault("CAIRN_MS_CLIENT_ID","");
        Util.require(!client.isBlank(),"请配置自己的、获准使用 Minecraft 服务的 Microsoft 公共客户端 ID: config set msClientId <ID>。应用需启用设备代码/公共客户端流。");
        Map<String,Object> device=check(net.form(OAUTH+"devicecode",Json.map("client_id",client,"scope","XboxLive.signin offline_access")));
        ui.frame("Microsoft 设备代码登录",List.of("在浏览器访问: "+Json.str(device,"verification_uri","https://microsoft.com/devicelogin"),"输入代码: "+Json.str(device,"user_code",""),"启动器不会打开图形窗口，等待网页授权……"));
        String code=Json.str(device,"device_code","");
        long expires=System.currentTimeMillis()+Json.number(device,"expires_in",900)*1000;
        int interval=Math.max(5,Json.integer(device,"interval",5));
        while (System.currentTimeMillis() < expires) {
            Thread.sleep(interval*1000L);
            Net.Response r=net.form(OAUTH+"token",Json.map("client_id",client,"grant_type","urn:ietf:params:oauth:grant-type:device_code","device_code",code));
            Map<String,Object> data=r.object();
            if (r.status() == 200) { save(finishMicrosoft(data,client)); ui.note("微软登录成功"); return; }
            String err=Json.str(data,"error","");
            if (err.equals("authorization_pending")) continue;
            if (err.equals("slow_down")) { interval+=5; continue; }
            throw new IOException("微软登录失败: "+err+" · "+Json.str(data,"error_description",""));
        }
        throw new IOException("设备代码已过期，请重新登录");
    }
    private Map<String,Object> check(Net.Response r) throws IOException {
        Map<String,Object> m=r.object();
        if (r.status() < 200 || r.status() >= 300) throw new IOException("OAuth HTTP "+r.status()+": "+Json.str(m,"error","")+" "+Json.str(m,"error_description",""));
        return m;
    }
    private Map<String,Object> finishMicrosoft(Map<String,Object> oauth,String client) throws Exception {
        String ms=Json.str(oauth,"access_token","");
        Map<String,Object> xbox=net.postJson("https://user.auth.xboxlive.com/user/authenticate",
                Json.map("Properties",Json.map("AuthMethod","RPS","SiteName","user.auth.xboxlive.com","RpsTicket","d="+ms),
                        "RelyingParty","http://auth.xboxlive.com","TokenType","JWT"),Map.of("Accept","application/json","x-xbl-contract-version","1"));
        Map<String,Object> xsts;
        try {
            xsts=net.postJson("https://xsts.auth.xboxlive.com/xsts/authorize",Json.map("Properties",Json.map("SandboxId","RETAIL","UserTokens",List.of(xbox.get("Token"))),
                    "RelyingParty","rp://api.minecraftservices.com/","TokenType","JWT"),Map.of("Accept","application/json","x-xbl-contract-version","1"));
        } catch (IOException e) { throw new IOException(e.getMessage()+"。请检查 Xbox 资料、家庭组/年龄限制及客户端的 Minecraft API 资格",e); }
        Map<String,Object> claims=Json.child(xsts,"DisplayClaims");
        Util.require(!Json.list(claims,"xui").isEmpty(),"Xbox 未返回用户标识");
        String uhs=Json.str(Json.obj(Json.list(claims,"xui").get(0)),"uhs","");
        Map<String,Object> mc=net.postJson("https://api.minecraftservices.com/authentication/login_with_xbox",
                Json.map("identityToken","XBL3.0 x="+uhs+";"+Json.str(xsts,"Token","")),Map.of());
        String token=Json.str(mc,"access_token","");
        Map<String,Object> entitlement=net.request("GET","https://api.minecraftservices.com/entitlements/mcstore",null,
                Map.of("Authorization","Bearer "+token),Net.Policy.UPSTREAM).object();
        Util.require(!Json.list(entitlement,"items").isEmpty(),"该微软账户未返回 Minecraft 所有权（含订阅状态）；请确认已购买并建立 Java 版角色");
        Map<String,Object> profile=net.request("GET","https://api.minecraftservices.com/minecraft/profile",null,
                Map.of("Authorization","Bearer "+token),Net.Policy.UPSTREAM).object();
        return Json.map("id","ms-"+Json.str(profile,"id",""),"type","microsoft","name",profile.get("name"),"uuid",profile.get("id"),
                "accessToken",token,"refreshToken",Json.str(oauth,"refresh_token",""),"clientId",client,
                "expires",System.currentTimeMillis()+Json.number(mc,"expires_in",86400)*1000,"profile",profile,"xuid",xuid(claims));
    }
    private String xuid(Map<String,Object> claims) {
        return Json.list(claims,"xui").isEmpty() ? "" : Json.str(Json.obj(Json.list(claims,"xui").get(0)),"xid","");
    }
    public Map<String,Object> ready(String id,boolean refresh) throws Exception {
        Map<String,Object> a=find(id); String type=Json.str(a,"type","");
        if (type.equals("microsoft") && (refresh || System.currentTimeMillis()+120000 >= Json.number(a,"expires",0))) {
            String client=Json.str(a,"clientId","");
            Map<String,Object> oauth=check(net.form(OAUTH+"token",Json.map("client_id",client,"grant_type","refresh_token","refresh_token",Json.str(a,"refreshToken",""),"scope","XboxLive.signin offline_access")));
            if (!oauth.containsKey("refresh_token")) oauth.put("refresh_token",a.get("refreshToken"));
            a=finishMicrosoft(oauth,client); save(a);
        } else if (type.equals("yggdrasil") && (refresh || System.currentTimeMillis()+120000 >= Json.number(a,"expires",0))) {
            Map<String,Object> r=net.postJson(Json.str(a,"server","")+"authserver/refresh",Json.map("accessToken",a.get("accessToken"),"clientToken",a.get("clientToken"),"requestUser",true),Map.of());
            a.put("accessToken",r.get("accessToken")); a.put("expires",System.currentTimeMillis()+12*3600*1000L); save(a);
        }
        return a;
    }
    public void yggdrasil(String server,String username,String profileId) throws Exception {
        server=Util.urlBase(Net.uri(server,Net.Policy.UPSTREAM).toString());
        char[] password=ui.secret("认证密码: ");
        try {
            String clientToken=UUID.randomUUID().toString().replace("-","");
            Map<String,Object> r=net.postJson(server+"authserver/authenticate",Json.map("agent",Json.map("name","Minecraft","version",1),
                    "username",username,"password",new String(password),"clientToken",clientToken,"requestUser",true),Map.of());
            List<Object> profiles=Json.list(r,"availableProfiles");
            Map<String,Object> profile=Json.child(r,"selectedProfile");
            if (!profileId.isBlank()) {
                Map<String,Object> found=null;
                for (Object p : profiles) if (profileId.equals(Json.obj(p).get("id")) || profileId.equals(Json.obj(p).get("name"))) found=Json.obj(p);
                Util.require(found != null,"服务未返回指定角色: "+profileId); profile=found;
            }
            if (profile.isEmpty() && profiles.size() == 1) profile=Json.obj(profiles.get(0));
            if (profile.isEmpty()) {
                ui.data(profiles); throw new IOException("服务返回多个角色，请附上角色 UUID/名称重新登录");
            }
            if (!Objects.equals(Json.child(r,"selectedProfile").get("id"),profile.get("id"))) {
                r=net.postJson(server+"authserver/refresh",Json.map("accessToken",r.get("accessToken"),"clientToken",Json.str(r,"clientToken",clientToken),
                        "selectedProfile",profile,"requestUser",true),Map.of());
            }
            String key="auth-"+Util.sha1(server.getBytes(StandardCharsets.UTF_8)).substring(0,8)+"-"+Json.str(profile,"id","");
            save(Json.map("id",key,"type","yggdrasil","server",server,"name",profile.get("name"),"uuid",profile.get("id"),"accessToken",r.get("accessToken"),
                    "clientToken",Json.str(r,"clientToken",clientToken),"expires",System.currentTimeMillis()+12*3600*1000L));
            ui.note("第三方账户已保存；启动前请通过 config set authlibInjector 配置合法的认证注入器（实例设置支持此键）");
        } finally { Arrays.fill(password,'\0'); }
    }
    public void skinDownload(String player,Path output) throws Exception {
        Map<String,Object> a;
        if (player.isBlank()) a=ready("",false);
        else {
            Map<String,Object> p=Json.obj(net.json("https://api.mojang.com/users/profiles/minecraft/"+Util.enc(player),Net.Policy.UPSTREAM));
            a=Json.map("uuid",p.get("id"));
        }
        Map<String,Object> profile=Json.obj(net.json("https://sessionserver.mojang.com/session/minecraft/profile/"+Util.enc(Json.str(a,"uuid","")),Net.Policy.UPSTREAM));
        for (Object raw : Json.list(profile,"properties")) {
            Map<String,Object> prop=Json.obj(raw);
            if (!Json.str(prop,"name","").equals("textures")) continue;
            Map<String,Object> textures=Json.obj(Json.parse(new String(Base64.getDecoder().decode(Json.str(prop,"value","")),StandardCharsets.UTF_8)));
            String url=Json.str(Json.child(Json.child(textures,"textures"),"SKIN"),"url","");
            Util.require(!url.isBlank(),"此玩家未设置皮肤");
            Util.require(!Files.exists(output),"输出已存在");
            net.download(new Net.File(url,output,"",-1,Net.Policy.UPSTREAM),null); return;
        }
        throw new IOException("未找到玩家皮肤");
    }
    public void skinUrl(String variant,String url) throws Exception {
        Util.require(Set.of("classic","slim").contains(variant),"皮肤模型: classic | slim");
        Map<String,Object> a=ready("",false); Util.require("microsoft".equals(a.get("type")),"更换皮肤需要微软账户");
        net.postJson("https://api.minecraftservices.com/minecraft/profile/skins",Json.map("variant",variant,"url",Net.uri(url,Net.Policy.UPSTREAM).toString()),
                Map.of("Authorization","Bearer "+Json.str(a,"accessToken","")));
    }
    public void skinUpload(String variant,Path file) throws Exception {
        Util.require(Set.of("classic","slim").contains(variant),"皮肤模型: classic | slim");
        Util.require(Files.isRegularFile(file) && Files.size(file) <= 1024*1024,"皮肤须为小于 1 MiB 的 PNG 文件");
        byte[] png=Files.readAllBytes(file);
        Util.require(png.length >= 24 && Arrays.equals(Arrays.copyOf(png,8),new byte[]{(byte)137,80,78,71,13,10,26,10}),"皮肤不是 PNG");
        java.nio.ByteBuffer meta=java.nio.ByteBuffer.wrap(png); int width=meta.getInt(16),height=meta.getInt(20);
        Util.require(width == 64 && (height == 32 || height == 64),"皮肤尺寸须为 64×32 或 64×64");
        Map<String,Object> a=ready("",false); Util.require("microsoft".equals(a.get("type")),"更换皮肤需要微软账户");
        String boundary="CairnSkin"+UUID.randomUUID().toString().replace("-","");
        ByteArrayOutputStream body=new ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n"+variant+"\r\n--"+boundary+
                "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(png); body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        net.postBytes("https://api.minecraftservices.com/minecraft/profile/skins",body.toByteArray(),
                Map.of("Authorization","Bearer "+Json.str(a,"accessToken",""),"Content-Type","multipart/form-data; boundary="+boundary));
    }
    public void skinReset() throws Exception {
        Map<String,Object> a=ready("",false); Util.require("microsoft".equals(a.get("type")),"重置皮肤需要微软账户");
        net.request("DELETE","https://api.minecraftservices.com/minecraft/profile/skins/active",null,Map.of("Authorization","Bearer "+Json.str(a,"accessToken","")),Net.Policy.UPSTREAM);
    }
}
