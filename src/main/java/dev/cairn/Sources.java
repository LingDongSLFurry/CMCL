package dev.cairn;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Download routing only. Authentication and community APIs never use this router. */
public final class Sources {
    public enum Kind { AUTO, METADATA, CLIENT, LIBRARY, ASSET, JAVA_METADATA, JAVA_FILE }
    public static final List<String> FIELDS=List.of("manifest","metadata","client","libraries","assets","javaIndex","javaMetadata","javaFiles");
    public static final Map<String,String> OPTIONS;
    static {
        Map<String,String> options=new LinkedHashMap<>();
        for (String field : FIELDS) options.put(switch (field) {
            case "javaIndex" -> "java-index"; case "javaMetadata" -> "java-metadata"; case "javaFiles" -> "java-files"; default -> field;
        },field);
        OPTIONS=Collections.unmodifiableMap(options);
    }
    private Sources() {}
    public static Map<String,Object> official() {
        return Json.map("manifest",Minecraft.MANIFEST,"metadata","https://piston-meta.mojang.com",
                "client","https://piston-data.mojang.com","libraries","https://libraries.minecraft.net",
                "assets","https://resources.download.minecraft.net","javaIndex",JavaRuntime.INDEX,
                "javaMetadata","https://piston-meta.mojang.com","javaFiles","https://piston-data.mojang.com","allowHttp",false);
    }
    public static Map<String,Object> create(String base,boolean allowHttp) {
        String normalized=baseUri(base,allowHttp).toString().replaceAll("/+$","");
        Map<String,Object> p=Json.map("manifest",normalized+URI.create(Minecraft.MANIFEST).getRawPath(),
                "metadata",normalized,"client",normalized,"libraries",normalized,"assets",normalized,
                "javaIndex",normalized+URI.create(JavaRuntime.INDEX).getRawPath(),"javaMetadata",normalized,"javaFiles",normalized,"allowHttp",allowHttp);
        validateProfile(p); return p;
    }
    private static URI baseUri(String value,boolean allowHttp) {
        Util.require(value != null && !value.isBlank(),"下载源地址不能为空");
        URI u=URI.create(value);
        Util.require(u.getHost() != null && u.getUserInfo() == null && u.getRawQuery() == null && u.getFragment() == null,
                "源地址需要完整主机名，不能包含用户名、密码、查询参数或片段");
        Util.require("https".equalsIgnoreCase(u.getScheme()) || allowHttp && "http".equalsIgnoreCase(u.getScheme()),
                "下载源默认需要 HTTPS；局域网 HTTP 源请显式添加 --allow-http");
        Util.require(u.getPort() != 0 && u.getPort() <= 65535,"无效源端口");
        return u;
    }
    public static void validateProfile(Map<String,Object> profile) {
        for (String k : profile.keySet()) Util.require(FIELDS.contains(k) || k.equals("allowHttp"),"未知下载源字段: "+k);
        Util.require(profile.get("allowHttp") instanceof Boolean,"下载源 allowHttp 必须为布尔值");
        boolean allowHttp=Json.bool(profile,"allowHttp",false);
        for (String field : FIELDS) {
            Util.require(profile.get(field) instanceof String,"下载源缺少字符串字段: "+field);
            baseUri(profile.get(field).toString(),allowHttp);
        }
    }
    public static void validateProfiles(Object value) {
        for (var e : Json.obj(value).entrySet()) {
            Util.id(e.getKey()); Util.require(!e.getKey().equals("official"),"内置 official 源不能被覆盖");
            validateProfile(Json.obj(e.getValue()));
        }
    }
    public static void validateSelection(Map<String,Object> config) {
        validateProfiles(config.getOrDefault("sources",Json.map()));
        String name=Json.str(config,"source","official");
        Util.require(name.equals("official") || Json.child(config,"sources").containsKey(name),"下载源未配置: "+name);
    }
    public static Selection active(Map<String,Object> config) { return select(config,Json.str(config,"source","official")); }
    public static Selection select(Map<String,Object> config,String name) {
        if (name.equals("official")) return new Selection("official",official());
        Object raw=Json.child(config,"sources").get(name); Util.require(raw instanceof Map,"下载源不存在: "+name);
        Map<String,Object> profile=Json.obj(Json.copy(raw)); validateProfile(profile); return new Selection(name,profile);
    }
    public record Selection(String id,Map<String,Object> profile) {
        public boolean custom() { return !id.equals("official"); }
        public String fingerprint() { return id+":"+Util.sha1(Json.stringify(profile).getBytes(StandardCharsets.UTF_8)); }
        public String label() { return custom() ? id+"（自定义）" : "Mojang 官方"; }
        public URI resolve(String original,Kind kind) {
            if (!custom()) return Net.uri(original,Net.Policy.OFFICIAL);
            URI canonical;
            try { canonical=Net.uri(original,Net.Policy.OFFICIAL); }
            catch (IllegalArgumentException e) { return validateTarget(URI.create(original.replace(" ","%20"))); }
            String field;
            if (canonical.toString().equals(Minecraft.MANIFEST)) return validateTarget(URI.create(Json.str(profile,"manifest","")));
            if (canonical.toString().equals(JavaRuntime.INDEX)) return validateTarget(URI.create(Json.str(profile,"javaIndex","")));
            field=switch (kind) {
                case METADATA -> "metadata"; case CLIENT -> "client"; case LIBRARY -> "libraries"; case ASSET -> "assets";
                case JAVA_METADATA -> "javaMetadata"; case JAVA_FILE -> "javaFiles"; case AUTO -> infer(canonical);
            };
            String base=Json.str(profile,field,"").replaceAll("/+$","");
            return validateTarget(URI.create(base+canonical.getRawPath()+(canonical.getRawQuery() == null ? "" : "?"+canonical.getRawQuery())));
        }
        private String infer(URI u) {
            return switch (u.getHost().toLowerCase(Locale.ROOT)) {
                case "piston-meta.mojang.com","launchermeta.mojang.com" -> "metadata";
                case "libraries.minecraft.net" -> "libraries";
                case "resources.download.minecraft.net" -> "assets";
                default -> "client";
            };
        }
        public URI validateTarget(URI u) {
            Util.require(u.getHost() != null && u.getUserInfo() == null && u.getFragment() == null,"无效下载地址");
            if ("https".equalsIgnoreCase(u.getScheme())) return u;
            // HTTP is opt-in and bounded to the configured source origins/path prefixes.
            boolean configured=false;
            if (Json.bool(profile,"allowHttp",false) && "http".equalsIgnoreCase(u.getScheme())) {
                for (String field : FIELDS) {
                    URI base=URI.create(Json.str(profile,field,""));
                    String prefix=base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$","");
                    String path=u.getRawPath() == null ? "" : u.getRawPath();
                    if (Objects.equals(base.getScheme(),u.getScheme()) && Objects.equals(base.getHost(),u.getHost()) && base.getPort() == u.getPort()
                            && (prefix.isEmpty() || path.equals(prefix) || path.startsWith(prefix+"/"))) { configured=true; break; }
                }
            }
            Util.require(configured,"拒绝非 HTTPS 或未配置的 HTTP 下载地址");
            return u;
        }
        public URI redirect(URI target) { return custom() ? validateTarget(target) : Net.uri(target.toString(),Net.Policy.OFFICIAL); }
    }
}
