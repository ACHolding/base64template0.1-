/*
 * MINECRAFT INSTALLER — DO NOT USE WIP
 * Unofficial work-in-progress. Not affiliated with Mojang or Microsoft.
 * Single-file JDK + Swing port of baseclient.py
 */
import javax.imageio.ImageIO;
import javax.net.ssl.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class base64client {

    static final String USER_AGENT = "MinecraftInstaller-WIP/0.0 DO-NOT-USE";
    static final String APP_NAME = "Minecraft Installer — DO NOT USE WIP";
    static final String BRAND_COPY = "DO NOT USE WIP";
    static final String OFFLINE_ACCOUNT = "Offline Account (DO NOT USE WIP)";
    static final String MICROSOFT_ACCOUNT = "Microsoft Account";
    static final String MOJANG_ACCOUNT = "Mojang Account (Legacy)";
    static final String SKIN_SERVER = "https://mc-heads.net";
    static final String ASSETS_URL = "https://resources.download.minecraft.net";
    static final String[] VERSION_MANIFEST_URLS = {
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
    };
    static final int DOWNLOAD_CHUNK = 1024 * 1024;
    static final int LIB_WORKERS = 32;
    static final int ASSET_WORKERS = 48;
    static final String CLASSPATH_SEP = Util.isWindows() ? ";" : ":";
    static final Path GAME_DIR = Util.defaultGameDir();

    // ===================== Minimal JSON =====================
    static final class J {
        abstract static class Val {
            J.Obj asObj() { throw new IllegalStateException("not object"); }
            J.Arr asArr() { throw new IllegalStateException("not array"); }
            String asStr() { throw new IllegalStateException("not string"); }
            boolean asBool() { throw new IllegalStateException("not bool"); }
            Number asNum() { throw new IllegalStateException("not number"); }
            boolean isNull() { return false; }
            String getStr(String k, String d) { return d; }
            J.Val get(String k) { return Null.INSTANCE; }
            boolean has(String k) { return false; }
        }

        static final class Null extends Val {
            static final Null INSTANCE = new Null();
            boolean isNull() { return true; }
            public String toString() { return "null"; }
        }

        static final class Bool extends Val {
            final boolean v;
            Bool(boolean v) { this.v = v; }
            boolean asBool() { return v; }
            public String toString() { return Boolean.toString(v); }
        }

        static final class Num extends Val {
            final Number v;
            Num(Number v) { this.v = v; }
            Number asNum() { return v; }
            public String toString() { return v.toString(); }
        }

        static final class Str extends Val {
            final String v;
            Str(String v) { this.v = v; }
            String asStr() { return v; }
            public String toString() { return v; }
        }

        static final class Obj extends Val {
            final LinkedHashMap<String, Val> map = new LinkedHashMap<>();
            Obj asObj() { return this; }
            Val get(String k) { Val v = map.get(k); return v == null ? Null.INSTANCE : v; }
            boolean has(String k) { return map.containsKey(k); }
            String getStr(String k, String d) {
                Val v = map.get(k);
                return (v instanceof Str) ? ((Str) v).v : d;
            }
            long getLong(String k, long d) {
                Val v = map.get(k);
                return (v instanceof Num) ? ((Num) v).v.longValue() : d;
            }
            int getInt(String k, int d) {
                Val v = map.get(k);
                return (v instanceof Num) ? ((Num) v).v.intValue() : d;
            }
            boolean getBool(String k, boolean d) {
                Val v = map.get(k);
                return (v instanceof Bool) ? ((Bool) v).v : d;
            }
            Obj getObj(String k) {
                Val v = map.get(k);
                return (v instanceof Obj) ? (Obj) v : null;
            }
            Arr getArr(String k) {
                Val v = map.get(k);
                return (v instanceof Arr) ? (Arr) v : null;
            }
            void put(String k, Val v) { map.put(k, v); }
            public String toString() { return stringify(this); }
        }

        static final class Arr extends Val {
            final ArrayList<Val> list = new ArrayList<>();
            Arr asArr() { return this; }
            int size() { return list.size(); }
            Val get(int i) { return list.get(i); }
            void add(Val v) { list.add(v); }
            public String toString() { return stringify(this); }
        }

        static Val parse(String s) {
            return new Parser(s).parseValue();
        }

        static Obj parseObj(String s) {
            Val v = parse(s);
            if (!(v instanceof Obj)) throw new RuntimeException("Expected JSON object");
            return (Obj) v;
        }

        static String stringify(Val v) {
            StringBuilder sb = new StringBuilder();
            write(sb, v, 0, true);
            return sb.toString();
        }

        static String stringifyPretty(Val v) {
            StringBuilder sb = new StringBuilder();
            write(sb, v, 0, false);
            return sb.toString();
        }

        private static void write(StringBuilder sb, Val v, int indent, boolean compact) {
            if (v instanceof Null || v == null) {
                sb.append("null");
            } else if (v instanceof Bool) {
                sb.append(((Bool) v).v);
            } else if (v instanceof Num) {
                sb.append(((Num) v).v);
            } else if (v instanceof Str) {
                sb.append('"').append(escape(((Str) v).v)).append('"');
            } else if (v instanceof Arr) {
                Arr a = (Arr) v;
                sb.append('[');
                if (!compact && a.size() > 0) sb.append('\n');
                for (int i = 0; i < a.size(); i++) {
                    if (!compact) indent(sb, indent + 1);
                    write(sb, a.get(i), indent + 1, compact);
                    if (i < a.size() - 1) sb.append(',');
                    if (!compact) sb.append('\n');
                }
                if (!compact && a.size() > 0) indent(sb, indent);
                sb.append(']');
            } else if (v instanceof Obj) {
                Obj o = (Obj) v;
                sb.append('{');
                if (!compact && !o.map.isEmpty()) sb.append('\n');
                int i = 0;
                int n = o.map.size();
                for (Map.Entry<String, Val> e : o.map.entrySet()) {
                    if (!compact) indent(sb, indent + 1);
                    sb.append('"').append(escape(e.getKey())).append('"').append(compact ? ":" : ": ");
                    write(sb, e.getValue(), indent + 1, compact);
                    if (i++ < n - 1) sb.append(',');
                    if (!compact) sb.append('\n');
                }
                if (!compact && !o.map.isEmpty()) indent(sb, indent);
                sb.append('}');
            }
        }

        private static void indent(StringBuilder sb, int n) {
            for (int i = 0; i < n; i++) sb.append("  ");
        }

        private static String escape(String s) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            return sb.toString();
        }

        static final class Parser {
            final String s;
            int i;

            Parser(String s) { this.s = s; }

            Val parseValue() {
                skip();
                if (i >= s.length()) throw new RuntimeException("Unexpected end of JSON");
                char c = s.charAt(i);
                if (c == '{') return parseObject();
                if (c == '[') return parseArray();
                if (c == '"') return new Str(parseString());
                if (c == 't') { expect("true"); return new Bool(true); }
                if (c == 'f') { expect("false"); return new Bool(false); }
                if (c == 'n') { expect("null"); return Null.INSTANCE; }
                if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                throw new RuntimeException("Unexpected char at " + i + ": " + c);
            }

            Obj parseObject() {
                expect("{");
                Obj o = new Obj();
                skip();
                if (peek('}')) { i++; return o; }
                while (true) {
                    skip();
                    String key = parseString();
                    skip();
                    expect(":");
                    Val val = parseValue();
                    o.put(key, val);
                    skip();
                    if (peek('}')) { i++; break; }
                    expect(",");
                }
                return o;
            }

            Arr parseArray() {
                expect("[");
                Arr a = new Arr();
                skip();
                if (peek(']')) { i++; return a; }
                while (true) {
                    a.add(parseValue());
                    skip();
                    if (peek(']')) { i++; break; }
                    expect(",");
                }
                return a;
            }

            String parseString() {
                expect("\"");
                StringBuilder sb = new StringBuilder();
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (c == '"') return sb.toString();
                    if (c == '\\') {
                        if (i >= s.length()) throw new RuntimeException("Bad escape");
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"': case '\\': case '/': sb.append(e); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u':
                                if (i + 4 > s.length()) throw new RuntimeException("Bad unicode");
                                int code = Integer.parseInt(s.substring(i, i + 4), 16);
                                sb.append((char) code);
                                i += 4;
                                break;
                            default: throw new RuntimeException("Bad escape: " + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                throw new RuntimeException("Unterminated string");
            }

            Num parseNumber() {
                int start = i;
                if (peek('-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                boolean frac = false, exp = false;
                if (peek('.')) {
                    frac = true;
                    i++;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                }
                if (peek('e') || peek('E')) {
                    exp = true;
                    i++;
                    if (peek('+') || peek('-')) i++;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                }
                String num = s.substring(start, i);
                if (frac || exp) return new Num(Double.parseDouble(num));
                try {
                    return new Num(Long.parseLong(num));
                } catch (NumberFormatException e) {
                    return new Num(Double.parseDouble(num));
                }
            }

            void skip() {
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                    else break;
                }
            }

            boolean peek(char c) {
                return i < s.length() && s.charAt(i) == c;
            }

            void expect(String lit) {
                skip();
                if (!s.startsWith(lit, i)) throw new RuntimeException("Expected " + lit + " at " + i);
                i += lit.length();
            }
        }
    }

    // ===================== Util =====================
    static final class Util {
        static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }

        static boolean isMac() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return os.contains("mac") || os.contains("darwin");
        }

        static Path defaultGameDir() {
            String home = System.getProperty("user.home");
            if (isWindows()) {
                String appdata = System.getenv("APPDATA");
                if (appdata != null && !appdata.isBlank()) {
                    return Paths.get(appdata, ".minecraft");
                }
                return Paths.get(home, "AppData", "Roaming", ".minecraft");
            }
            if (isMac()) {
                return Paths.get(home, "Library", "Application Support", "minecraft");
            }
            return Paths.get(home, ".minecraft");
        }

        static String getOsName() {
            if (isWindows()) return "windows";
            if (isMac()) return "osx";
            return "linux";
        }

        static String getArch() {
            String machine = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (machine.equals("x86_64") || machine.equals("amd64")) return "x64";
            if (machine.equals("aarch64") || machine.equals("arm64")) return "arm64";
            return "x86";
        }

        static String findJava() {
            List<Path> candidates = new ArrayList<>();
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome != null && !javaHome.isBlank()) {
                candidates.add(Paths.get(javaHome, "bin", isWindows() ? "java.exe" : "java"));
            }
            if (isWindows()) {
                candidates.add(Paths.get("C:/Program Files/Java/jdk-17/bin/java.exe"));
                candidates.add(Paths.get("C:/Program Files/Java/jdk-21/bin/java.exe"));
                candidates.add(Paths.get("C:/Program Files/Eclipse Adoptium/jdk-17/bin/java.exe"));
                candidates.add(Paths.get("C:/Program Files/Eclipse Adoptium/jdk-21/bin/java.exe"));
                candidates.add(Paths.get("C:/Program Files/BellSoft/LibericaJDK-21/bin/java.exe"));
                candidates.add(Paths.get("C:/Program Files/BellSoft/LibericaJDK-17/bin/java.exe"));
            } else if (isMac()) {
                candidates.add(Paths.get("/opt/homebrew/opt/openjdk@17/bin/java"));
                candidates.add(Paths.get("/opt/homebrew/opt/openjdk@21/bin/java"));
                candidates.add(Paths.get("/opt/homebrew/opt/openjdk/bin/java"));
                candidates.add(Paths.get("/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java"));
                candidates.add(Paths.get("/usr/bin/java"));
            } else {
                candidates.add(Paths.get("/usr/lib/jvm/java-17-openjdk/bin/java"));
                candidates.add(Paths.get("/usr/lib/jvm/java-17-openjdk-amd64/bin/java"));
                candidates.add(Paths.get("/usr/bin/java"));
            }
            // current runtime
            String javaHomeProp = System.getProperty("java.home");
            if (javaHomeProp != null) {
                candidates.add(0, Paths.get(javaHomeProp, "bin", isWindows() ? "java.exe" : "java"));
            }
            for (Path p : candidates) {
                if (Files.isRegularFile(p)) return p.toAbsolutePath().toString();
            }
            return "java";
        }

        static boolean checkRules(J.Arr rules) {
            if (rules == null || rules.size() == 0) return true;
            String osName = getOsName();
            String arch = getArch();
            boolean result = false;
            for (int i = 0; i < rules.size(); i++) {
                J.Obj rule = rules.get(i).asObj();
                String action = rule.getStr("action", "allow");
                boolean matches = true;
                if (rule.has("os")) {
                    J.Obj osRule = rule.getObj("os");
                    if (osRule != null) {
                        if (osRule.has("name") && !osName.equals(osRule.getStr("name", ""))) matches = false;
                        if (osRule.has("arch") && !arch.equals(osRule.getStr("arch", ""))) matches = false;
                    }
                }
                if (matches) result = "allow".equals(action);
            }
            return result;
        }

        static boolean checkArgRules(J.Arr rules, Map<String, Boolean> features) {
            if (rules == null || rules.size() == 0) return true;
            if (features == null) features = Collections.emptyMap();
            String osName = getOsName();
            String arch = getArch();
            boolean result = false;
            for (int i = 0; i < rules.size(); i++) {
                J.Obj rule = rules.get(i).asObj();
                String action = rule.getStr("action", "allow");
                boolean matches = true;
                if (rule.has("os")) {
                    J.Obj osRule = rule.getObj("os");
                    if (osRule != null) {
                        if (osRule.has("name") && !osName.equals(osRule.getStr("name", ""))) matches = false;
                        if (osRule.has("arch") && !arch.equals(osRule.getStr("arch", ""))) matches = false;
                    }
                }
                if (rule.has("features")) {
                    J.Obj featObj = rule.getObj("features");
                    if (featObj != null) {
                        for (Map.Entry<String, J.Val> e : featObj.map.entrySet()) {
                            boolean required = (e.getValue() instanceof J.Bool) && ((J.Bool) e.getValue()).v;
                            boolean have = features.getOrDefault(e.getKey(), false);
                            if (have != required) {
                                matches = false;
                                break;
                            }
                        }
                    }
                }
                if (matches) result = "allow".equals(action);
            }
            return result;
        }

        static String substituteVars(String text, Map<String, String> variables) {
            if (text == null) return null;
            String out = text;
            for (Map.Entry<String, String> e : variables.entrySet()) {
                out = out.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
            return out;
        }

        static List<String> expandArguments(J.Arr argList, Map<String, String> variables, Map<String, Boolean> features) {
            List<String> expanded = new ArrayList<>();
            if (argList == null) return expanded;
            for (int i = 0; i < argList.size(); i++) {
                J.Val entry = argList.get(i);
                if (entry instanceof J.Str) {
                    expanded.add(substituteVars(((J.Str) entry).v, variables));
                } else if (entry instanceof J.Obj) {
                    J.Obj obj = (J.Obj) entry;
                    if (!checkArgRules(obj.getArr("rules"), features)) continue;
                    J.Val value = obj.get("value");
                    if (value instanceof J.Arr) {
                        J.Arr arr = (J.Arr) value;
                        for (int j = 0; j < arr.size(); j++) {
                            expanded.add(substituteVars(arr.get(j).asStr(), variables));
                        }
                    } else if (value instanceof J.Str) {
                        String s = ((J.Str) value).v;
                        if (s != null && !s.isEmpty()) {
                            expanded.add(substituteVars(s, variables));
                        }
                    }
                }
            }
            return expanded;
        }

        /** Match Python uuid.uuid3(NAMESPACE_DNS, "OfflinePlayer:"+username). */
        static String generateOfflineUuid(String username) {
            UUID ns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
            byte[] nsBytes = new byte[16];
            ByteBuffer.wrap(nsBytes).putLong(ns.getMostSignificantBits()).putLong(ns.getLeastSignificantBits());
            byte[] nameBytes = ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8);
            byte[] data = new byte[16 + nameBytes.length];
            System.arraycopy(nsBytes, 0, data, 0, 16);
            System.arraycopy(nameBytes, 0, data, 16, nameBytes.length);
            // nameUUIDFromBytes = MD5 + version 3 + IETF variant
            return UUID.nameUUIDFromBytes(data).toString();
        }

        static String calculateSha1(Path filepath) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                try (InputStream in = Files.newInputStream(filepath)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
                }
                byte[] dig = md.digest();
                StringBuilder sb = new StringBuilder(dig.length * 2);
                for (byte b : dig) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                return null;
            }
        }

        static boolean fileIsValid(Path dest, String expectedHash, Long expectedSize) {
            if (!Files.isRegularFile(dest)) return false;
            try {
                long actual = Files.size(dest);
                if (expectedSize != null && actual != expectedSize) return false;
                if (expectedHash != null) {
                    if (dest.getFileName().toString().equals(expectedHash) && expectedSize != null) return true;
                    return expectedHash.equals(calculateSha1(dest));
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        static String mavenToRelPath(String name) {
            String[] parts = name.split(":");
            if (parts.length < 3) return name;
            String group = parts[0], artifact = parts[1], version = parts[2];
            String groupPath = group.replace('.', '/');
            return groupPath + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
        }

        static J.Obj mergeVersionInfo(J.Obj child, J.Obj parent) {
            J.Obj merged = J.parseObj(J.stringify(parent));
            merged.put("id", child.get("id"));
            if (child.has("mainClass")) merged.put("mainClass", child.get("mainClass"));
            J.Arr childLibs = child.getArr("libraries");
            J.Arr parentLibs = parent.getArr("libraries");
            J.Arr libs = new J.Arr();
            if (childLibs != null) for (int i = 0; i < childLibs.size(); i++) libs.add(childLibs.get(i));
            if (parentLibs != null) for (int i = 0; i < parentLibs.size(); i++) libs.add(parentLibs.get(i));
            merged.put("libraries", libs);
            if (child.has("arguments")) {
                J.Obj mergedArgs = merged.getObj("arguments");
                if (mergedArgs == null) {
                    mergedArgs = new J.Obj();
                    merged.put("arguments", mergedArgs);
                }
                J.Obj childArgs = child.getObj("arguments");
                for (String key : new String[]{"jvm", "game"}) {
                    J.Arr combined = new J.Arr();
                    J.Arr cv = childArgs != null ? childArgs.getArr(key) : null;
                    J.Arr pv = mergedArgs.getArr(key);
                    if (cv != null) for (int i = 0; i < cv.size(); i++) combined.add(cv.get(i));
                    if (pv != null) for (int i = 0; i < pv.size(); i++) combined.add(pv.get(i));
                    mergedArgs.put(key, combined);
                }
            }
            return merged;
        }

        static J.Obj resolveVersionInfo(J.Obj versionInfo) throws IOException {
            if (!versionInfo.has("inheritsFrom") || versionInfo.get("inheritsFrom").isNull()) {
                return versionInfo;
            }
            String parentId = versionInfo.getStr("inheritsFrom", null);
            Path parentPath = GAME_DIR.resolve("versions").resolve(parentId).resolve(parentId + ".json");
            if (!Files.exists(parentPath)) throw new FileNotFoundException("Parent version missing: " + parentId);
            J.Obj parent = J.parseObj(Files.readString(parentPath, StandardCharsets.UTF_8));
            parent = resolveVersionInfo(parent);
            return mergeVersionInfo(versionInfo, parent);
        }

        static String getSystemTheme() {
            try {
                if (isWindows()) {
                    Process p = new ProcessBuilder(
                            "reg", "query",
                            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                            "/v", "AppsUseLightTheme"
                    ).redirectErrorStream(true).start();
                    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    p.waitFor();
                    for (String line : out.split("\\R")) {
                        if (line.toLowerCase(Locale.ROOT).contains("appsuselighttheme")) {
                            String[] parts = line.trim().split("\\s+");
                            String val = parts[parts.length - 1];
                            if ("0x1".equalsIgnoreCase(val) || "1".equals(val)) return "light";
                            return "dark";
                        }
                    }
                    return "dark";
                }
                if (isMac()) {
                    Process p = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle").start();
                    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    return out.contains("Dark") ? "dark" : "light";
                }
            } catch (Exception ignored) {}
            return "dark";
        }
    }

    // ===================== Net =====================
    static final class Net {
        static {
            try {
                TrustManager[] trustAll = new TrustManager[]{
                        new X509TrustManager() {
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new SecureRandom());
                HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
                HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            } catch (Exception ignored) {}
        }

        static String fetchText(String urlStr, int timeoutSec) throws IOException {
            HttpURLConnection conn = open(urlStr, timeoutSec);
            try (InputStream in = conn.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } finally {
                conn.disconnect();
            }
        }

        static J.Obj fetchJson(String urlStr, int timeoutSec) throws IOException {
            return J.parseObj(fetchText(urlStr, timeoutSec));
        }

        static J.Val fetchJsonVal(String urlStr, int timeoutSec) throws IOException {
            return J.parse(fetchText(urlStr, timeoutSec));
        }

        static J.Obj fetchVersionManifest(int timeoutSec) throws IOException {
            IOException last = null;
            for (String url : VERSION_MANIFEST_URLS) {
                try {
                    return fetchJson(url, timeoutSec);
                } catch (IOException e) {
                    last = e;
                }
            }
            throw new IOException("Could not fetch version manifest: " + last, last);
        }

        static HttpURLConnection open(String urlStr, int timeoutSec) throws IOException {
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setHostnameVerifier((hostname, session) -> true);
            }
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(timeoutSec * 1000);
            conn.setReadTimeout(timeoutSec * 1000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code >= 400) {
                throw new IOException("HTTP " + code + " for " + urlStr);
            }
            return conn;
        }

        static boolean downloadFileFast(String urlStr, Path destPath, String expectedHash, Long expectedSize, int timeoutSec) {
            Path tmp = destPath.resolveSibling(destPath.getFileName().toString() + ".part");
            if (Util.fileIsValid(destPath, expectedHash, expectedSize)) return true;
            try {
                Files.createDirectories(destPath.getParent());
                HttpURLConnection conn = open(urlStr, timeoutSec);
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buf = new byte[DOWNLOAD_CHUNK];
                    int n;
                    while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                } finally {
                    conn.disconnect();
                }
                if (expectedHash != null && !expectedHash.equals(Util.calculateSha1(tmp))) {
                    Files.deleteIfExists(tmp);
                    return false;
                }
                if (expectedSize != null && Files.size(tmp) != expectedSize) {
                    Files.deleteIfExists(tmp);
                    return false;
                }
                try {
                    Files.move(tmp, destPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, destPath, StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            } catch (Exception e) {
                System.out.println("Download failed (" + destPath.getFileName() + "): " + e.getMessage());
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                return false;
            }
        }

        /** Library download task: url, path, sha1, size */
        static Object[] libraryDownloadTask(J.Obj lib, Path libsDir) {
            if (lib.has("downloads")) {
                J.Obj downloads = lib.getObj("downloads");
                if (downloads != null && downloads.has("artifact")) {
                    J.Obj artifact = downloads.getObj("artifact");
                    String path = artifact.getStr("path", null);
                    String url = artifact.getStr("url", null);
                    String sha1 = artifact.has("sha1") ? artifact.getStr("sha1", null) : null;
                    Long size = artifact.has("size") ? artifact.getLong("size", 0) : null;
                    if (artifact.has("size")) size = artifact.getLong("size", 0);
                    else size = null;
                    return new Object[]{url, libsDir.resolve(path), sha1, size};
                }
            }
            if (lib.has("name")) {
                String rel = Util.mavenToRelPath(lib.getStr("name", ""));
                String base = lib.getStr("url", "https://libraries.minecraft.net/");
                if (!base.endsWith("/")) base += "/";
                String sha1 = lib.has("sha1") ? lib.getStr("sha1", null) : null;
                Long size = lib.has("size") ? lib.getLong("size", 0) : null;
                if (!lib.has("size")) size = null;
                return new Object[]{base + rel, libsDir.resolve(rel), sha1, size};
            }
            return null;
        }
    }

    // ===================== Theme =====================
    static final class Theme {
        final Color bgDark, bgDarker, bgPanel, bgInput, bgHeader;
        final Color sidebarActive, accent, accentHover, accentGreen, accentGreenHover;
        final Color accentOrange, accentBlue, textPrimary, textSecondary, textMuted;
        final Color border, wipBanner, buttonBg, buttonBgHover, buttonFg;
        final Color buttonPlay, buttonPlayHover, buttonPlayText;

        Theme(Map<String, String> m) {
            bgDark = c(m, "bg_dark");
            bgDarker = c(m, "bg_darker");
            bgPanel = c(m, "bg_panel");
            bgInput = c(m, "bg_input");
            bgHeader = c(m, "bg_header");
            sidebarActive = c(m, "sidebar_active");
            accent = c(m, "accent");
            accentHover = c(m, "accent_hover");
            accentGreen = c(m, "accent_green");
            accentGreenHover = c(m, "accent_green_hover");
            accentOrange = c(m, "accent_orange");
            accentBlue = c(m, "accent_blue");
            textPrimary = c(m, "text_primary");
            textSecondary = c(m, "text_secondary");
            textMuted = c(m, "text_muted");
            border = c(m, "border");
            wipBanner = c(m, "wip_banner");
            buttonBg = c(m, "button_bg");
            buttonBgHover = c(m, "button_bg_hover");
            buttonFg = c(m, "button_fg");
            buttonPlay = c(m, "button_play");
            buttonPlayHover = c(m, "button_play_hover");
            buttonPlayText = c(m, "button_play_text");
        }

        private static Color c(Map<String, String> m, String k) {
            return Color.decode(m.get(k));
        }

        static final Map<String, String> DARK = map(
                "bg_dark", "#1e1e1e", "bg_darker", "#131313", "bg_panel", "#2c2c2c",
                "bg_input", "#3a3a3a", "bg_header", "#0f0f0f", "sidebar_active", "#3c8527",
                "accent", "#3c8527", "accent_hover", "#4fa536", "accent_green", "#3c8527",
                "accent_green_hover", "#4fa536", "accent_orange", "#e67e22", "accent_blue", "#3c8527",
                "text_primary", "#ffffff", "text_secondary", "#b0b0b0", "text_muted", "#6e6e6e",
                "border", "#404040", "wip_banner", "#8b1a1a", "button_bg", "#3c8527",
                "button_bg_hover", "#4fa536", "button_fg", "#ffffff", "button_play", "#3c8527",
                "button_play_hover", "#4fa536", "button_play_text", "#ffffff"
        );

        static final Map<String, String> LIGHT = map(
                "bg_dark", "#e8e8e8", "bg_darker", "#d4d4d4", "bg_panel", "#ffffff",
                "bg_input", "#f5f5f5", "bg_header", "#1b1b1b", "sidebar_active", "#3c8527",
                "accent", "#3c8527", "accent_hover", "#4fa536", "accent_green", "#3c8527",
                "accent_green_hover", "#4fa536", "accent_orange", "#d97706", "accent_blue", "#3c8527",
                "text_primary", "#1b1b1b", "text_secondary", "#4a4a4a", "text_muted", "#8a8a8a",
                "border", "#c8c8c8", "wip_banner", "#8b1a1a", "button_bg", "#3c8527",
                "button_bg_hover", "#4fa536", "button_fg", "#ffffff", "button_play", "#3c8527",
                "button_play_hover", "#4fa536", "button_play_text", "#ffffff"
        );

        static Map<String, String> map(String... kv) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
            return m;
        }

        static Theme of(String name) {
            return new Theme("light".equals(name) ? LIGHT : DARK);
        }
    }

    // ===================== AssetDownloader =====================
    static final class AssetDownloader {
        final Path gameDir;
        final Path objectsDir;
        final Path indexesDir;
        final IntConsumer progressCb;
        final Consumer<String> statusCb;
        final AtomicInteger downloaded = new AtomicInteger();
        int total;
        final List<String> failed = Collections.synchronizedList(new ArrayList<>());

        AssetDownloader(Path gameDir, IntConsumer progressCb, Consumer<String> statusCb) {
            this.gameDir = gameDir;
            this.objectsDir = gameDir.resolve("assets").resolve("objects");
            this.indexesDir = gameDir.resolve("assets").resolve("indexes");
            this.progressCb = progressCb;
            this.statusCb = statusCb;
        }

        boolean downloadAsset(String assetHash, Long assetSize) {
            String prefix = assetHash.substring(0, 2);
            Path assetPath = objectsDir.resolve(prefix).resolve(assetHash);
            String url = ASSETS_URL + "/" + prefix + "/" + assetHash;
            boolean ok = Net.downloadFileFast(url, assetPath, assetHash, assetSize, 30);
            int d = downloaded.incrementAndGet();
            if (progressCb != null && total > 0) progressCb.accept((int) ((d / (double) total) * 100));
            if (!ok) failed.add(assetHash);
            return ok;
        }

        boolean downloadAllAssets(String assetIndexId, String assetIndexUrl) throws Exception {
            Files.createDirectories(objectsDir);
            Files.createDirectories(indexesDir);
            Path indexPath = indexesDir.resolve(assetIndexId + ".json");
            if (!Files.exists(indexPath) && assetIndexUrl != null) {
                if (statusCb != null) statusCb.accept("Downloading asset index...");
                Net.downloadFileFast(assetIndexUrl, indexPath, null, null, 30);
            }
            if (!Files.exists(indexPath)) throw new FileNotFoundException("Asset index not found: " + indexPath);

            J.Obj assetIndex = J.parseObj(Files.readString(indexPath, StandardCharsets.UTF_8));
            J.Obj objects = assetIndex.getObj("objects");
            if (objects == null) objects = new J.Obj();
            total = objects.map.size();
            downloaded.set(0);
            failed.clear();

            if (statusCb != null) statusCb.accept("Checking " + total + " assets...");

            List<Object[]> toDownload = new ArrayList<>();
            for (Map.Entry<String, J.Val> e : objects.map.entrySet()) {
                J.Obj info = e.getValue().asObj();
                String hash = info.getStr("hash", null);
                Long size = info.has("size") ? info.getLong("size", 0) : null;
                if (!info.has("size")) size = null;
                String prefix = hash.substring(0, 2);
                Path assetPath = objectsDir.resolve(prefix).resolve(hash);
                if (Util.fileIsValid(assetPath, hash, size)) {
                    downloaded.incrementAndGet();
                    continue;
                }
                toDownload.add(new Object[]{hash, size});
            }

            if (statusCb != null) statusCb.accept("Downloading " + toDownload.size() + " assets...");

            if (!toDownload.isEmpty()) {
                ExecutorService pool = Executors.newFixedThreadPool(ASSET_WORKERS);
                try {
                    List<Future<?>> futures = new ArrayList<>();
                    for (Object[] item : toDownload) {
                        futures.add(pool.submit(() -> downloadAsset((String) item[0], (Long) item[1])));
                    }
                    for (Future<?> f : futures) f.get();
                } finally {
                    pool.shutdown();
                }
            }

            if (statusCb != null) {
                if (!failed.isEmpty()) statusCb.accept("Assets done (" + failed.size() + " failed)");
                else statusCb.accept("All assets downloaded!");
            }
            return failed.isEmpty();
        }
    }

    // ===================== LoginDialog =====================
    static final class LoginDialog extends JDialog {
        final String accountKind;
        Map<String, String> result;
        final JTextField emailField = new JTextField();
        final JPasswordField passwordField = new JPasswordField();
        final JTextField usernameField = new JTextField();
        Theme theme;

        LoginDialog(Frame parent, Theme theme, String accountKind, String initialUsername) {
            super(parent, APP_NAME + " — " + ("microsoft".equals(accountKind) ? "Microsoft Sign In" : "Mojang Sign In"), true);
            this.theme = theme;
            this.accountKind = accountKind;
            setSize(460, "microsoft".equals(accountKind) ? 420 : 450);
            setResizable(false);
            setLocationRelativeTo(parent);
            getContentPane().setBackground(theme.bgDark);

            boolean isMs = "microsoft".equals(accountKind);
            JPanel card = new JPanel();
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setBackground(theme.bgPanel);
            card.setBorder(new EmptyBorder(22, 24, 22, 24));

            JLabel icon = new JLabel(isMs ? "🪟" : "☁");
            icon.setFont(new Font("Segoe UI", Font.PLAIN, 28));
            icon.setForeground(isMs ? theme.accentBlue : theme.accentOrange);
            icon.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel title = new JLabel(isMs ? "Microsoft Sign In" : "Mojang Sign In");
            title.setFont(new Font("Segoe UI", Font.BOLD, 16));
            title.setForeground(theme.textPrimary);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel sub = new JLabel("<html><body style='width:360px'>" +
                    (isMs ? "Sign in with the Microsoft account linked to Minecraft."
                            : "Legacy Mojang login. Most accounts have been migrated to Microsoft.")
                    + "</body></html>");
            sub.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            sub.setForeground(theme.textSecondary);
            sub.setAlignmentX(Component.LEFT_ALIGNMENT);

            usernameField.setText(initialUsername == null ? "" : initialUsername);
            styleField(emailField);
            styleField(passwordField);
            styleField(usernameField);

            emailField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { onEmail(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { onEmail(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { onEmail(); }
            });

            card.add(icon);
            card.add(Box.createVerticalStrut(6));
            card.add(title);
            card.add(Box.createVerticalStrut(8));
            card.add(sub);
            card.add(Box.createVerticalStrut(16));
            card.add(fieldLabel("Email"));
            card.add(emailField);
            card.add(Box.createVerticalStrut(10));
            card.add(fieldLabel("Password"));
            card.add(passwordField);
            card.add(Box.createVerticalStrut(10));
            card.add(fieldLabel("Minecraft Username"));
            card.add(usernameField);

            if (!isMs) {
                JLabel tip = new JLabel("<html><body style='width:360px'>Tip: Use a Microsoft account if login fails — Mojang accounts were merged.</body></html>");
                tip.setFont(new Font("Segoe UI", Font.PLAIN, 10));
                tip.setForeground(theme.textMuted);
                tip.setAlignmentX(Component.LEFT_ALIGNMENT);
                card.add(Box.createVerticalStrut(8));
                card.add(tip);
            }

            JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            btnRow.setOpaque(false);
            btnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            JButton cancel = accentBtn("Cancel", e -> { result = null; dispose(); });
            JButton sign = accentBtn("Sign In", e -> submit());
            btnRow.add(sign);
            btnRow.add(cancel);
            card.add(Box.createVerticalStrut(18));
            card.add(btnRow);

            JPanel wrap = new JPanel(new BorderLayout());
            wrap.setBackground(theme.bgDark);
            wrap.setBorder(new EmptyBorder(16, 16, 16, 16));
            wrap.add(card, BorderLayout.CENTER);
            setContentPane(wrap);

            getRootPane().setDefaultButton(sign);
            JRootPane root = getRootPane();
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "escape");
            root.getActionMap().put("escape", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { result = null; dispose(); }
            });

            addWindowListener(new WindowAdapter() {
                public void windowOpened(WindowEvent e) {
                    emailField.requestFocusInWindow();
                }
            });
            SwingUtilities.invokeLater(() -> emailField.requestFocusInWindow());
        }

        JLabel fieldLabel(String t) {
            JLabel l = new JLabel(t);
            l.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            l.setForeground(theme.textSecondary);
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            return l;
        }

        void styleField(JTextField f) {
            f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            f.setAlignmentX(Component.LEFT_ALIGNMENT);
            f.setBackground(theme.bgInput);
            f.setForeground(theme.textPrimary);
            f.setCaretColor(theme.textPrimary);
            f.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            f.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        }

        JButton accentBtn(String text, ActionListener al) {
            JButton b = new JButton(text);
            b.setFont(new Font("Segoe UI", Font.BOLD, 11));
            b.setBackground(theme.buttonBg);
            b.setForeground(theme.buttonFg);
            b.setFocusPainted(false);
            b.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));
            b.addActionListener(al);
            return b;
        }

        void onEmail() {
            String email = emailField.getText().trim();
            if (email.contains("@") && usernameField.getText().trim().isEmpty()) {
                String local = email.split("@", 2)[0];
                StringBuilder safe = new StringBuilder();
                for (char c : local.toCharArray()) {
                    if (Character.isLetterOrDigit(c) || c == '_') safe.append(c);
                    if (safe.length() >= 16) break;
                }
                if (safe.length() >= 3) usernameField.setText(safe.toString());
            }
        }

        void submit() {
            String email = emailField.getText().trim();
            String password = new String(passwordField.getPassword());
            String username = usernameField.getText().trim();
            if (email.isEmpty() || !email.contains("@")) {
                JOptionPane.showMessageDialog(this, "Enter a valid email address.", APP_NAME, JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (password.length() < 4) {
                JOptionPane.showMessageDialog(this, "Enter your account password.", APP_NAME, JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (username.length() < 3 || username.length() > 16) {
                JOptionPane.showMessageDialog(this, "Minecraft username must be 3-16 characters.", APP_NAME, JOptionPane.WARNING_MESSAGE);
                return;
            }
            for (char c : username.toCharArray()) {
                if (!Character.isLetterOrDigit(c) && c != '_') {
                    JOptionPane.showMessageDialog(this, "Username can only use letters, numbers, and underscores.", APP_NAME, JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }
            Map<String, String> r = new HashMap<>();
            r.put("type", accountKind);
            r.put("email", email);
            r.put("display_name", username);
            result = r;
            dispose();
        }
    }

    // ===================== App =====================
    static final class App extends JFrame {
        Theme theme = Theme.of("dark");
        String themeMode = "dark";
        String javaBin = Util.findJava();
        Map<String, String> authSession;
        Process gameProcess;
        OutputStream logHandle;
        Thread logPumpThread;
        String activeTab = "INSTALL";

        final JLabel statusLabel = new JLabel("DO NOT USE WIP — Ready");
        final JProgressBar progressBar = new JProgressBar(0, 100);
        final JButton playButton = new JButton("PLAY — DO NOT USE WIP");
        final JComboBox<String> accountCombo = new JComboBox<>(new String[]{OFFLINE_ACCOUNT, MICROSOFT_ACCOUNT, MOJANG_ACCOUNT});
        final JComboBox<String> versionCombo = new JComboBox<>();
        final JTextField usernameField = new JTextField("Player");
        final JLabel usernameDisplay = new JLabel("Player");
        final JLabel accountIndicator = new JLabel("DO NOT USE WIP");
        final JLabel crackedLabel = new JLabel("✓ OFFLINE — DO NOT USE WIP");
        final JButton signInBtn = new JButton("Sign In");
        final JSlider ramSlider = new JSlider(1, 16, 4);
        final JLabel ramDisplay = new JLabel("4096 MB");
        final JCheckBox fullscreenCb = new JCheckBox("Fullscreen", false);
        final JCheckBox downloadAssetsCb = new JCheckBox("Download All Assets", true);
        final JLabel skinLabel = new JLabel("◆", SwingConstants.CENTER);
        final JPanel playPage = new JPanel(new BorderLayout());
        final JPanel contentHost = new JPanel(new CardLayout());
        final Map<String, JButton> tabButtons = new LinkedHashMap<>();
        JButton versionRefreshBtn;
        JPanel header, sidebar, wipBanner, bottomBar, body, settingsCard, headerRight, logoPanel;
        JLabel bannerLabel, headerBrand, headerInstaller, headerWip, optTitle, optSub;
        JToggleButton themeDarkBtn, themeLightBtn, themeSysBtn;
        JButton chromeMin, chromeMax, chromeClose;
        boolean ignoreAccountChange;

        App() {
            super(APP_NAME);
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setSize(1000, 620);
            setResizable(false);
            setLocationRelativeTo(null);
            try {
                Files.createDirectories(GAME_DIR);
            } catch (IOException ignored) {}
            buildUi();
            applyTheme();
            loadVersions();
            javax.swing.Timer initialSkin = new javax.swing.Timer(500, e -> updateSkin());
            initialSkin.setRepeats(false);
            initialSkin.start();
        }

        void ui(Runnable r) {
            SwingUtilities.invokeLater(r);
        }

        void setStatus(String s) { ui(() -> statusLabel.setText(s)); }
        void setProgress(int p) { ui(() -> progressBar.setValue(p)); }

        void buildUi() {
            setLayout(new BorderLayout());
            getContentPane().setBackground(theme.bgDark);

            header = new JPanel(new BorderLayout());
            header.setPreferredSize(new Dimension(1000, 36));
            logoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
            logoPanel.setOpaque(false);
            headerBrand = new JLabel("MINECRAFT");
            headerBrand.setFont(new Font("Segoe UI", Font.BOLD, 12));
            headerBrand.setForeground(theme.accentGreen);
            headerInstaller = new JLabel("Installer");
            headerInstaller.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            headerInstaller.setForeground(theme.textPrimary);
            headerWip = new JLabel(BRAND_COPY);
            headerWip.setFont(new Font("Segoe UI", Font.BOLD, 10));
            headerWip.setForeground(new Color(0xff6b6b));
            logoPanel.add(headerBrand);
            logoPanel.add(headerInstaller);
            logoPanel.add(headerWip);
            header.add(logoPanel, BorderLayout.WEST);

            headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
            headerRight.setOpaque(false);
            ButtonGroup tg = new ButtonGroup();
            themeDarkBtn = new JToggleButton("Dark");
            themeLightBtn = new JToggleButton("Light");
            themeSysBtn = new JToggleButton("Sys");
            tg.add(themeDarkBtn);
            tg.add(themeLightBtn);
            tg.add(themeSysBtn);
            themeDarkBtn.setSelected(true);
            themeDarkBtn.addActionListener(e -> onThemeChange("dark"));
            themeLightBtn.addActionListener(e -> onThemeChange("light"));
            themeSysBtn.addActionListener(e -> onThemeChange("system"));
            styleTiny(themeDarkBtn);
            styleTiny(themeLightBtn);
            styleTiny(themeSysBtn);
            headerRight.add(themeDarkBtn);
            headerRight.add(themeLightBtn);
            headerRight.add(themeSysBtn);

            chromeMin = new JButton("─");
            chromeMax = new JButton("□");
            chromeClose = new JButton("✕");
            styleTiny(chromeMin);
            styleTiny(chromeMax);
            styleTiny(chromeClose);
            chromeMin.addActionListener(e -> setState(Frame.ICONIFIED));
            chromeMax.addActionListener(e -> {
                // optional no-op / toggle — window is fixed size
            });
            chromeClose.addActionListener(e -> {
                dispose();
                System.exit(0);
            });
            chromeClose.addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) {
                    chromeClose.setBackground(new Color(0xc0392b));
                    chromeClose.setForeground(Color.WHITE);
                }
                public void mouseExited(MouseEvent e) {
                    chromeClose.setBackground(theme.bgHeader);
                    chromeClose.setForeground(theme.textSecondary);
                }
            });
            headerRight.add(chromeMin);
            headerRight.add(chromeMax);
            headerRight.add(chromeClose);
            header.add(headerRight, BorderLayout.EAST);

            JPanel northStack = new JPanel(new BorderLayout());
            northStack.add(header, BorderLayout.NORTH);
            wipBanner = new JPanel(new BorderLayout());
            wipBanner.setPreferredSize(new Dimension(1000, 28));
            wipBanner.setBackground(theme.wipBanner);
            bannerLabel = new JLabel("⚠ DO NOT USE WIP — Unofficial installer. Not affiliated with Mojang Studios or Microsoft.", SwingConstants.CENTER);
            bannerLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
            bannerLabel.setForeground(Color.WHITE);
            wipBanner.add(bannerLabel, BorderLayout.CENTER);
            northStack.add(wipBanner, BorderLayout.SOUTH);
            add(northStack, BorderLayout.NORTH);

            body = new JPanel(new BorderLayout());
            body.setBackground(theme.bgDark);

            sidebar = new JPanel();
            sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
            sidebar.setPreferredSize(new Dimension(210, 0));
            sidebar.setBackground(theme.bgDarker);
            sidebar.setBorder(new EmptyBorder(10, 10, 10, 10));

            JPanel skinFrame = new JPanel(new BorderLayout());
            skinFrame.setBackground(theme.bgPanel);
            skinFrame.setPreferredSize(new Dimension(170, 150));
            skinFrame.setMaximumSize(new Dimension(190, 150));
            skinLabel.setFont(new Font("Segoe UI", Font.PLAIN, 40));
            skinLabel.setForeground(theme.textSecondary);
            skinLabel.setOpaque(true);
            skinLabel.setBackground(theme.bgPanel);
            skinFrame.add(skinLabel, BorderLayout.CENTER);
            sidebar.add(skinFrame);
            sidebar.add(Box.createVerticalStrut(8));
            usernameDisplay.setFont(new Font("Segoe UI", Font.BOLD, 12));
            usernameDisplay.setForeground(theme.textPrimary);
            usernameDisplay.setAlignmentX(Component.CENTER_ALIGNMENT);
            sidebar.add(usernameDisplay);
            accountIndicator.setFont(new Font("Segoe UI", Font.BOLD, 10));
            accountIndicator.setForeground(new Color(0xff6b6b));
            accountIndicator.setAlignmentX(Component.CENTER_ALIGNMENT);
            sidebar.add(accountIndicator);
            sidebar.add(Box.createVerticalStrut(14));

            for (String tab : new String[]{"INSTALL", "SKINS", "SETTINGS", "ABOUT"}) {
                JButton btn = new JButton("  " + tab);
                btn.setHorizontalAlignment(SwingConstants.LEFT);
                btn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                btn.setFocusPainted(false);
                btn.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
                btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
                btn.addActionListener(e -> switchTab(tab));
                tabButtons.put(tab, btn);
                sidebar.add(btn);
                sidebar.add(Box.createVerticalStrut(2));
            }

            sidebar.add(Box.createVerticalGlue());

            body.add(sidebar, BorderLayout.WEST);

            JPanel contentWrapper = new JPanel(new BorderLayout());
            contentWrapper.setBackground(theme.bgDark);
            contentWrapper.setBorder(new EmptyBorder(16, 22, 16, 22));

            playPage.setBackground(theme.bgDark);
            settingsCard = new JPanel();
            settingsCard.setLayout(new BoxLayout(settingsCard, BoxLayout.Y_AXIS));
            settingsCard.setBackground(theme.bgPanel);
            settingsCard.setBorder(new EmptyBorder(20, 22, 20, 22));

            optTitle = new JLabel("Installation Options — DO NOT USE WIP");
            optTitle.setFont(new Font("Segoe UI", Font.BOLD, 15));
            optTitle.setForeground(theme.textPrimary);
            optTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
            optSub = new JLabel("Looks like the official Minecraft installer. This build is unfinished — DO NOT USE WIP.");
            optSub.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            optSub.setForeground(new Color(0xff6b6b));
            optSub.setAlignmentX(Component.LEFT_ALIGNMENT);
            settingsCard.add(optTitle);
            settingsCard.add(Box.createVerticalStrut(4));
            settingsCard.add(optSub);
            settingsCard.add(Box.createVerticalStrut(16));

            JPanel accountRow = row();
            accountRow.add(label("Account type:"));
            accountCombo.setMaximumSize(new Dimension(280, 28));
            accountCombo.setPreferredSize(new Dimension(280, 28));
            accountCombo.addActionListener(e -> {
                if (!ignoreAccountChange) onAccountTypeChange();
            });
            accountRow.add(accountCombo);
            styleBtn(signInBtn);
            signInBtn.addActionListener(e -> openAccountLoginPrompt());
            signInBtn.setVisible(false);
            accountRow.add(signInBtn);
            crackedLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
            crackedLabel.setForeground(theme.accentGreen);
            accountRow.add(Box.createHorizontalStrut(12));
            accountRow.add(crackedLabel);
            settingsCard.add(accountRow);
            settingsCard.add(Box.createVerticalStrut(12));

            JPanel userRow = row();
            userRow.add(label("Username:"));
            usernameField.setMaximumSize(new Dimension(320, 30));
            usernameField.setPreferredSize(new Dimension(280, 30));
            usernameField.setBackground(theme.bgInput);
            usernameField.setForeground(theme.textPrimary);
            usernameField.setCaretColor(theme.textPrimary);
            usernameField.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            usernameField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { onUsernameChange(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { onUsernameChange(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { onUsernameChange(); }
            });
            userRow.add(usernameField);
            settingsCard.add(userRow);
            settingsCard.add(Box.createVerticalStrut(12));

            JPanel verRow = row();
            verRow.add(label("Version:"));
            versionCombo.setMaximumSize(new Dimension(340, 28));
            versionCombo.setPreferredSize(new Dimension(320, 28));
            verRow.add(versionCombo);
            versionRefreshBtn = new JButton("↻");
            styleBtn(versionRefreshBtn);
            versionRefreshBtn.addActionListener(e -> loadVersions());
            versionRefreshBtn.addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) {
                    versionRefreshBtn.setForeground(theme.textPrimary);
                }
                public void mouseExited(MouseEvent e) {
                    versionRefreshBtn.setForeground(theme.buttonFg);
                }
            });
            verRow.add(versionRefreshBtn);
            settingsCard.add(verRow);
            settingsCard.add(Box.createVerticalStrut(12));

            JPanel ramRow = row();
            ramRow.add(label("RAM:"));
            ramDisplay.setFont(new Font("Segoe UI", Font.BOLD, 11));
            ramDisplay.setForeground(theme.accentGreen);
            ramRow.add(ramDisplay);
            ramSlider.setMajorTickSpacing(1);
            ramSlider.setPaintTicks(false);
            ramSlider.setOpaque(false);
            ramSlider.setPreferredSize(new Dimension(340, 36));
            ramSlider.addChangeListener(e -> ramDisplay.setText((ramSlider.getValue() * 1024) + " MB"));
            ramRow.add(ramSlider);
            settingsCard.add(ramRow);
            settingsCard.add(Box.createVerticalStrut(12));

            JPanel optRow = row();
            for (JCheckBox cb : new JCheckBox[]{fullscreenCb, downloadAssetsCb}) {
                cb.setOpaque(false);
                cb.setForeground(theme.textSecondary);
                cb.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                optRow.add(cb);
                optRow.add(Box.createHorizontalStrut(18));
            }
            settingsCard.add(optRow);

            playPage.add(settingsCard, BorderLayout.CENTER);

            contentHost.setOpaque(false);
            contentHost.add(playPage, "INSTALL");
            contentWrapper.add(contentHost, BorderLayout.CENTER);
            body.add(contentWrapper, BorderLayout.CENTER);
            add(body, BorderLayout.CENTER);

            bottomBar = new JPanel(new BorderLayout());
            bottomBar.setPreferredSize(new Dimension(1000, 72));
            bottomBar.setBackground(theme.bgDarker);
            bottomBar.setBorder(new EmptyBorder(10, 20, 10, 20));
            JPanel statusFrame = new JPanel();
            statusFrame.setLayout(new BoxLayout(statusFrame, BoxLayout.Y_AXIS));
            statusFrame.setOpaque(false);
            statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            statusLabel.setForeground(theme.textSecondary);
            statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            progressBar.setPreferredSize(new Dimension(420, 10));
            progressBar.setMaximumSize(new Dimension(420, 10));
            progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
            progressBar.setForeground(theme.accentGreen);
            progressBar.setBackground(theme.bgDarker);
            statusFrame.add(statusLabel);
            statusFrame.add(Box.createVerticalStrut(6));
            statusFrame.add(progressBar);
            bottomBar.add(statusFrame, BorderLayout.WEST);

            playButton.setFont(new Font("Segoe UI", Font.BOLD, 14));
            playButton.setBackground(theme.buttonPlay);
            playButton.setForeground(theme.buttonPlayText);
            playButton.setFocusPainted(false);
            playButton.setBorder(BorderFactory.createEmptyBorder(10, 28, 10, 28));
            playButton.addActionListener(e -> play());
            playButton.addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { playButton.setBackground(theme.buttonPlayHover); }
                public void mouseExited(MouseEvent e) { playButton.setBackground(theme.buttonPlay); }
            });
            bottomBar.add(playButton, BorderLayout.EAST);
            add(bottomBar, BorderLayout.SOUTH);

            updateNavButtons();
            updateAccountUi();
        }

        JPanel row() {
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
            p.setOpaque(false);
            p.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            return p;
        }

        JLabel label(String t) {
            JLabel l = new JLabel(t);
            l.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            l.setForeground(theme.textSecondary);
            l.setBorder(new EmptyBorder(0, 0, 0, 10));
            return l;
        }

        void styleBtn(AbstractButton b) {
            b.setFont(new Font("Segoe UI", Font.BOLD, 11));
            b.setBackground(theme.buttonBg);
            b.setForeground(theme.buttonFg);
            b.setFocusPainted(false);
            b.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
            b.setOpaque(true);
        }

        void styleTiny(AbstractButton b) {
            b.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            b.setBackground(theme.bgHeader);
            b.setForeground(theme.textSecondary);
            b.setFocusPainted(false);
            b.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            b.setOpaque(true);
        }

        void onThemeChange(String mode) {
            themeMode = mode;
            String name = "system".equals(mode) ? Util.getSystemTheme() : mode;
            theme = Theme.of(name);
            applyTheme();
        }

        void applyTheme() {
            getContentPane().setBackground(theme.bgDark);
            if (header != null) header.setBackground(theme.bgHeader);
            if (headerRight != null) headerRight.setBackground(theme.bgHeader);
            if (logoPanel != null) logoPanel.setBackground(theme.bgHeader);
            if (headerBrand != null) {
                headerBrand.setForeground(theme.accentGreen);
                headerBrand.setBackground(theme.bgHeader);
            }
            if (headerInstaller != null) {
                headerInstaller.setForeground(Color.WHITE);
            }
            if (headerWip != null) headerWip.setForeground(new Color(0xff6b6b));
            if (wipBanner != null) wipBanner.setBackground(theme.wipBanner);
            if (bannerLabel != null) {
                bannerLabel.setBackground(theme.wipBanner);
                bannerLabel.setForeground(Color.WHITE);
            }
            if (sidebar != null) sidebar.setBackground(theme.bgDarker);
            if (body != null) body.setBackground(theme.bgDark);
            if (bottomBar != null) bottomBar.setBackground(theme.bgDarker);
            playPage.setBackground(theme.bgDark);
            if (settingsCard != null) {
                settingsCard.setBackground(theme.bgPanel);
                recolorTree(settingsCard, theme.bgPanel);
            }
            if (optTitle != null) {
                optTitle.setForeground(theme.textPrimary);
                optTitle.setBackground(theme.bgPanel);
            }
            if (optSub != null) {
                optSub.setForeground(new Color(0xff6b6b));
                optSub.setBackground(theme.bgPanel);
            }
            statusLabel.setForeground(theme.textSecondary);
            progressBar.setForeground(theme.accentGreen);
            progressBar.setBackground(theme.bgDarker);
            playButton.setBackground(theme.buttonPlay);
            playButton.setForeground(theme.buttonPlayText);
            usernameDisplay.setForeground(theme.textPrimary);
            usernameDisplay.setBackground(theme.bgDarker);
            accountIndicator.setBackground(theme.bgDarker);
            skinLabel.setBackground(theme.bgPanel);
            skinLabel.setForeground(theme.textSecondary);
            usernameField.setBackground(theme.bgInput);
            usernameField.setForeground(theme.textPrimary);
            usernameField.setCaretColor(theme.textPrimary);
            ramDisplay.setForeground(theme.accentGreen);
            ramSlider.setBackground(theme.bgPanel);
            ramSlider.setForeground(theme.textPrimary);
            for (JCheckBox cb : new JCheckBox[]{fullscreenCb, downloadAssetsCb}) {
                cb.setForeground(theme.textSecondary);
                cb.setBackground(theme.bgPanel);
            }
            styleBtn(signInBtn);
            if (versionRefreshBtn != null) styleBtn(versionRefreshBtn);
            for (AbstractButton b : new AbstractButton[]{themeDarkBtn, themeLightBtn, themeSysBtn, chromeMin, chromeMax, chromeClose}) {
                if (b != null) styleTiny(b);
            }
            if (chromeClose != null) {
                chromeClose.setBackground(theme.bgHeader);
                chromeClose.setForeground(theme.textSecondary);
            }
            updateNavButtons();
            updateAccountUi();
            recolorTree(getContentPane(), theme.bgDark);
            repaint();
        }

        void recolorTree(Component c, Color parentBg) {
            if (c == null) return;
            if (c instanceof JLabel) {
                JLabel l = (JLabel) c;
                if (l.getParent() != null) {
                    Color pbg = l.getParent().getBackground();
                    if (pbg != null) l.setBackground(pbg);
                }
            } else if (c instanceof JTextField) {
                ((JTextField) c).setBackground(theme.bgInput);
                ((JTextField) c).setForeground(theme.textPrimary);
                ((JTextField) c).setCaretColor(theme.textPrimary);
            } else if (c instanceof JCheckBox) {
                ((JCheckBox) c).setForeground(theme.textSecondary);
                ((JCheckBox) c).setBackground(theme.bgPanel);
            } else if (c instanceof JSlider) {
                ((JSlider) c).setBackground(theme.bgPanel);
                ((JSlider) c).setForeground(theme.textPrimary);
            }
            if (c instanceof Container) {
                for (Component child : ((Container) c).getComponents()) {
                    recolorTree(child, parentBg);
                }
            }
        }

        void updateNavButtons() {
            for (Map.Entry<String, JButton> e : tabButtons.entrySet()) {
                JButton btn = e.getValue();
                if (e.getKey().equals(activeTab)) {
                    btn.setBackground(theme.sidebarActive);
                    btn.setForeground(Color.WHITE);
                    btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
                } else {
                    btn.setBackground(theme.bgDarker);
                    btn.setForeground(theme.textSecondary);
                    btn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                }
            }
        }

        void switchTab(String tab) {
            if (!tab.equals("INSTALL")) {
                JOptionPane.showMessageDialog(this, tab + " — DO NOT USE WIP (coming soon)", APP_NAME, JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            activeTab = tab;
            updateNavButtons();
            CardLayout cl = (CardLayout) contentHost.getLayout();
            cl.show(contentHost, tab);
        }

        // ---- Account ----
        void openAccountLoginPrompt() {
            String acc = (String) accountCombo.getSelectedItem();
            if (MICROSOFT_ACCOUNT.equals(acc)) promptLogin("microsoft");
            else if (MOJANG_ACCOUNT.equals(acc)) promptLogin("mojang");
        }

        boolean promptLogin(String kind) {
            String expected = "microsoft".equals(kind) ? MICROSOFT_ACCOUNT : MOJANG_ACCOUNT;
            ignoreAccountChange = true;
            accountCombo.setSelectedItem(expected);
            ignoreAccountChange = false;
            LoginDialog dlg = new LoginDialog(this, theme, kind, usernameField.getText().trim());
            dlg.setVisible(true);
            if (dlg.result != null) {
                authSession = dlg.result;
                usernameField.setText(authSession.get("display_name"));
                usernameDisplay.setText(authSession.get("display_name"));
                updateAccountUi();
                updateSkin();
                return true;
            }
            if (authSession == null || !kind.equals(authSession.get("type"))) {
                ignoreAccountChange = true;
                accountCombo.setSelectedItem(OFFLINE_ACCOUNT);
                ignoreAccountChange = false;
                updateAccountUi();
            }
            return false;
        }

        void onAccountTypeChange() {
            String acc = (String) accountCombo.getSelectedItem();
            if (OFFLINE_ACCOUNT.equals(acc)) {
                authSession = null;
                updateAccountUi();
            } else if (MICROSOFT_ACCOUNT.equals(acc)) {
                if (authSession == null || !"microsoft".equals(authSession.get("type"))) promptLogin("microsoft");
                else updateAccountUi();
            } else {
                if (authSession == null || !"mojang".equals(authSession.get("type"))) promptLogin("mojang");
                else updateAccountUi();
            }
        }

        void updateAccountUi() {
            String acc = (String) accountCombo.getSelectedItem();
            if (OFFLINE_ACCOUNT.equals(acc)) {
                crackedLabel.setText("✓ OFFLINE — DO NOT USE WIP");
                crackedLabel.setForeground(theme.accentGreen);
                accountIndicator.setText("DO NOT USE WIP");
                usernameField.setEnabled(true);
                signInBtn.setVisible(false);
                return;
            }
            String kind = MICROSOFT_ACCOUNT.equals(acc) ? "microsoft" : "mojang";
            boolean signedIn = authSession != null && kind.equals(authSession.get("type"));
            if (signedIn) {
                crackedLabel.setText("✓ SIGNED IN — DO NOT USE WIP");
                crackedLabel.setForeground(theme.accentGreen);
                accountIndicator.setText(authSession.get("display_name") + " (DO NOT USE WIP)");
                usernameField.setEnabled(false);
                signInBtn.setText("Switch Account");
            } else {
                crackedLabel.setText("⚠ SIGN IN — DO NOT USE WIP");
                crackedLabel.setForeground(theme.accentOrange);
                accountIndicator.setText("DO NOT USE WIP");
                usernameField.setEnabled(false);
                signInBtn.setText("Sign In");
            }
            signInBtn.setVisible(true);
        }

        javax.swing.Timer skinTimer;

        void onUsernameChange() {
            usernameDisplay.setText(usernameField.getText());
            if (skinTimer != null) skinTimer.stop();
            skinTimer = new javax.swing.Timer(600, e -> updateSkin());
            skinTimer.setRepeats(false);
            skinTimer.start();
        }

        void updateSkin() {
            String username = usernameField.getText().trim();
            if (username.isEmpty()) {
                skinLabel.setIcon(null);
                skinLabel.setText("◆");
                skinLabel.setFont(new Font("Segoe UI", Font.PLAIN, 40));
                return;
            }
            final String shortName = username.length() > 8 ? username.substring(0, 8) : username;
            new Thread(() -> {
                try {
                    String url = SKIN_SERVER + "/head/" + username + "/150.png";
                    HttpURLConnection conn = Net.open(url, 5);
                    try (InputStream in = conn.getInputStream()) {
                        BufferedImage img = ImageIO.read(in);
                        if (img == null) throw new IOException("bad image");
                        ImageIcon icon = new ImageIcon(img);
                        ui(() -> {
                            skinLabel.setIcon(icon);
                            skinLabel.setText("");
                        });
                    } finally {
                        conn.disconnect();
                    }
                } catch (Exception e) {
                    ui(() -> {
                        skinLabel.setIcon(null);
                        skinLabel.setText("<html><center>◆<br>" + shortName + "</center></html>");
                        skinLabel.setFont(new Font("Segoe UI", Font.PLAIN, 18));
                    });
                }
            }, "skin-load").start();
        }

        void loadVersions() {
            setStatus("DO NOT USE WIP — Loading versions...");
            new Thread(() -> {
                try {
                    J.Obj data = Net.fetchVersionManifest(10);
                    J.Arr versions = data.getArr("versions");
                    List<String> list = new ArrayList<>();
                    for (int i = 0; i < versions.size(); i++) {
                        J.Obj v = versions.get(i).asObj();
                        String type = v.getStr("type", "");
                        String id = v.getStr("id", "");
                        if ("release".equals(type)) list.add(id + " (release)");
                        else if ("snapshot".equals(type) && list.size() < 60) list.add(id + " (snapshot)");
                        if (list.size() >= 80) break;
                    }
                    ui(() -> setVersions(list));
                } catch (Exception e) {
                    ui(() -> setVersions(Arrays.asList(
                            "1.21.4 (release)", "1.20.1 (release)", "1.19.4 (release)", "1.18.2 (release)"
                    )));
                }
            }, "load-versions").start();
        }

        void setVersions(List<String> versions) {
            versionCombo.removeAllItems();
            for (String v : versions) versionCombo.addItem(v);
            if (!versions.isEmpty()) versionCombo.setSelectedIndex(0);
            statusLabel.setText("DO NOT USE WIP — Ready");
        }

        // ===================== Download / Launch =====================
        List<Path> downloadLibraries(J.Obj versionInfo, Consumer<String> statusCb) throws Exception {
            Path libsDir = GAME_DIR.resolve("libraries");
            Files.createDirectories(libsDir);
            String osName = Util.getOsName();
            List<Object[]> downloadTasks = new ArrayList<>();
            List<Path> nativePaths = new ArrayList<>();

            J.Arr libraries = versionInfo.getArr("libraries");
            if (libraries == null) return nativePaths;

            for (int i = 0; i < libraries.size(); i++) {
                J.Obj lib = libraries.get(i).asObj();
                if (lib.has("rules") && !Util.checkRules(lib.getArr("rules"))) continue;

                Object[] task = Net.libraryDownloadTask(lib, libsDir);
                if (task != null) {
                    Path path = (Path) task[1];
                    String sha1 = (String) task[2];
                    Long size = (Long) task[3];
                    if (!Util.fileIsValid(path, sha1, size)) downloadTasks.add(task);
                }

                if (lib.has("natives") && lib.has("downloads")) {
                    J.Obj natives = lib.getObj("natives");
                    String nativeKey = natives.getStr(osName, "");
                    if (nativeKey.contains("${arch}")) {
                        String bits = ("x64".equals(Util.getArch()) || "arm64".equals(Util.getArch())) ? "64" : "32";
                        nativeKey = nativeKey.replace("${arch}", bits);
                    }
                    J.Obj downloads = lib.getObj("downloads");
                    if (!nativeKey.isEmpty() && downloads != null && downloads.has("classifiers")) {
                        J.Obj classifiers = downloads.getObj("classifiers");
                        if (classifiers != null && classifiers.has(nativeKey)) {
                            J.Obj nativeInfo = classifiers.getObj(nativeKey);
                            Path nativePath = libsDir.resolve(nativeInfo.getStr("path", ""));
                            String sha1 = nativeInfo.has("sha1") ? nativeInfo.getStr("sha1", null) : null;
                            Long size = nativeInfo.has("size") ? nativeInfo.getLong("size", 0) : null;
                            if (!nativeInfo.has("size")) size = null;
                            if (!Util.fileIsValid(nativePath, sha1, size)) {
                                downloadTasks.add(new Object[]{
                                        nativeInfo.getStr("url", null), nativePath, sha1, size
                                });
                            }
                            nativePaths.add(nativePath);
                        }
                    }
                }
            }

            if (!downloadTasks.isEmpty()) {
                if (statusCb != null) statusCb.accept("Downloading " + downloadTasks.size() + " libraries...");
                ExecutorService pool = Executors.newFixedThreadPool(LIB_WORKERS);
                try {
                    List<Future<Boolean>> futures = new ArrayList<>();
                    for (Object[] t : downloadTasks) {
                        futures.add(pool.submit(() -> Net.downloadFileFast(
                                (String) t[0], (Path) t[1], (String) t[2], (Long) t[3], 60)));
                    }
                    for (Future<Boolean> f : futures) {
                        if (!Boolean.TRUE.equals(f.get())) throw new RuntimeException("Some libraries failed to download");
                    }
                } finally {
                    pool.shutdown();
                }
            }
            return nativePaths;
        }

        String buildClasspath(J.Obj resolvedInfo, String clientId) {
            Path libsDir = GAME_DIR.resolve("libraries");
            List<String> parts = new ArrayList<>();
            J.Arr libraries = resolvedInfo.getArr("libraries");
            if (libraries != null) {
                for (int i = 0; i < libraries.size(); i++) {
                    J.Obj lib = libraries.get(i).asObj();
                    if (lib.has("rules") && !Util.checkRules(lib.getArr("rules"))) continue;
                    Object[] task = Net.libraryDownloadTask(lib, libsDir);
                    if (task == null) continue;
                    Path libPath = (Path) task[1];
                    if (Files.exists(libPath)) parts.add(libPath.toAbsolutePath().toString());
                }
            }
            Path jarPath = GAME_DIR.resolve("versions").resolve(clientId).resolve(clientId + ".jar");
            if (Files.exists(jarPath)) parts.add(jarPath.toAbsolutePath().toString());
            return String.join(CLASSPATH_SEP, parts);
        }

        void extractNatives(Path nativePath, Path nativesDir) {
            try {
                Files.createDirectories(nativesDir);
                try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(nativePath))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();
                        if (name.startsWith("META-INF/")) continue;
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (lower.endsWith(".so") || lower.endsWith(".dll") || lower.endsWith(".dylib") || lower.endsWith(".jnilib")) {
                            String base = Paths.get(name).getFileName().toString();
                            Path target = nativesDir.resolve(base);
                            try (OutputStream out = Files.newOutputStream(target)) {
                                zis.transferTo(out);
                            }
                            if (!Util.isWindows()) {
                                try {
                                    Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
                                    Files.setPosixFilePermissions(target, perms);
                                } catch (Exception ex) {
                                    File f = target.toFile();
                                    f.setReadable(true, false);
                                    f.setWritable(true, false);
                                    f.setExecutable(true, false);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("Native extract failed: " + e.getMessage());
            }
        }

        Object[] downloadVersion(String versionId, IntConsumer progressCb, Consumer<String> statusCb) throws Exception {
            String actualId = versionId.contains(" (") ? versionId.split(" \\(", 2)[0] : versionId;
            if (statusCb != null) statusCb.accept("DO NOT USE WIP — Fetching " + actualId + "...");

            J.Obj manifest = Net.fetchVersionManifest(15);
            String versionUrl = null;
            J.Arr versions = manifest.getArr("versions");
            for (int i = 0; i < versions.size(); i++) {
                J.Obj v = versions.get(i).asObj();
                if (actualId.equals(v.getStr("id", ""))) {
                    versionUrl = v.getStr("url", null);
                    break;
                }
            }
            if (versionUrl == null) throw new IllegalArgumentException("Version " + actualId + " not found");

            J.Obj versionInfo = Net.fetchJson(versionUrl, 15);
            Path versionDir = GAME_DIR.resolve("versions").resolve(actualId);
            Files.createDirectories(versionDir);
            Path nativesDir = versionDir.resolve("natives");
            Files.createDirectories(nativesDir);
            Files.createDirectories(GAME_DIR.resolve("libraries"));

            Files.writeString(versionDir.resolve(actualId + ".json"), J.stringifyPretty(versionInfo), StandardCharsets.UTF_8);

            Path jarPath = versionDir.resolve(actualId + ".jar");
            J.Obj client = versionInfo.getObj("downloads").getObj("client");
            String clientUrl = client.getStr("url", null);
            String clientSha1 = client.has("sha1") ? client.getStr("sha1", null) : null;
            Long clientSize = client.has("size") ? client.getLong("size", 0) : null;
            if (!client.has("size")) clientSize = null;
            if (!Util.fileIsValid(jarPath, clientSha1, clientSize)) {
                if (statusCb != null) statusCb.accept("DO NOT USE WIP — Downloading " + actualId + ".jar...");
                if (!Net.downloadFileFast(clientUrl, jarPath, clientSha1, clientSize, 60)) {
                    throw new RuntimeException("Failed to download " + actualId + ".jar");
                }
            }

            List<Path> nativePaths = downloadLibraries(versionInfo, statusCb);
            for (Path np : nativePaths) {
                if (Files.exists(np)) extractNatives(np, nativesDir);
            }

            J.Obj assetIndex = versionInfo.getObj("assetIndex");
            if (downloadAssetsCb.isSelected()) {
                if (statusCb != null) statusCb.accept("DO NOT USE WIP — Downloading assets...");
                AssetDownloader ad = new AssetDownloader(GAME_DIR, progressCb, statusCb);
                ad.downloadAllAssets(assetIndex.getStr("id", null), assetIndex.getStr("url", null));
            } else {
                Path indexPath = GAME_DIR.resolve("assets").resolve("indexes").resolve(assetIndex.getStr("id", "") + ".json");
                if (!Files.exists(indexPath)) {
                    String sha1 = assetIndex.has("sha1") ? assetIndex.getStr("sha1", null) : null;
                    Net.downloadFileFast(assetIndex.getStr("url", null), indexPath, sha1, null, 60);
                }
            }

            if (statusCb != null) statusCb.accept("DO NOT USE WIP — " + actualId + " ready");
            return new Object[]{versionInfo, actualId};
        }

        List<String> buildLaunchArgs(J.Obj versionInfo, String actualId, String username, int ramMb, Path nativesDir, String classpath) {
            String mainClass = versionInfo.getStr("mainClass", "net.minecraft.client.main.Main");
            String offlineUuid = Util.generateOfflineUuid(username);
            String userType = "legacy";
            if (authSession != null) {
                userType = "microsoft".equals(authSession.get("type")) ? "msa" : "mojang";
            }
            Map<String, String> variables = new LinkedHashMap<>();
            variables.put("natives_directory", nativesDir.toAbsolutePath().toString());
            variables.put("launcher_name", "MinecraftInstaller-WIP");
            variables.put("launcher_version", "0.1");
            variables.put("classpath", classpath);
            variables.put("auth_player_name", username);
            variables.put("version_name", actualId);
            variables.put("game_directory", GAME_DIR.toAbsolutePath().toString());
            variables.put("assets_root", GAME_DIR.resolve("assets").toAbsolutePath().toString());
            variables.put("assets_index_name", versionInfo.getObj("assetIndex").getStr("id", ""));
            variables.put("auth_uuid", offlineUuid);
            variables.put("auth_access_token", "0");
            variables.put("clientid", "");
            variables.put("auth_xuid", "");
            variables.put("user_type", userType);
            variables.put("version_type", versionInfo.getStr("type", "release"));

            Map<String, Boolean> features = new HashMap<>();
            features.put("is_demo_user", false);
            features.put("has_custom_resolution", false);
            features.put("has_quick_plays_support", false);
            features.put("is_quick_play_singleplayer", false);
            features.put("is_quick_play_multiplayer", false);
            features.put("is_quick_play_realms", false);

            List<String> memory = new ArrayList<>(Arrays.asList(javaBin, "-Xmx" + ramMb + "M", "-Xms512M"));
            List<String> args;
            if (versionInfo.has("arguments")) {
                J.Obj arguments = versionInfo.getObj("arguments");
                List<String> jvmArgs = Util.expandArguments(arguments.getArr("jvm"), variables, features);
                List<String> gameArgs = Util.expandArguments(arguments.getArr("game"), variables, features);
                args = new ArrayList<>(memory);
                args.addAll(jvmArgs);
                args.add(mainClass);
                args.addAll(gameArgs);
            } else {
                args = new ArrayList<>(memory);
                args.addAll(Arrays.asList(
                        "-Djava.library.path=" + nativesDir.toAbsolutePath(),
                        "-Dminecraft.launcher.brand=MinecraftInstaller-WIP",
                        "-Dminecraft.launcher.version=0.1",
                        "-cp", classpath,
                        mainClass,
                        "--username", username,
                        "--version", actualId,
                        "--gameDir", GAME_DIR.toAbsolutePath().toString(),
                        "--assetsDir", GAME_DIR.resolve("assets").toAbsolutePath().toString(),
                        "--assetIndex", versionInfo.getObj("assetIndex").getStr("id", ""),
                        "--uuid", offlineUuid,
                        "--accessToken", "0",
                        "--userType", userType,
                        "--versionType", versionInfo.getStr("type", "release")
                ));
            }
            if (fullscreenCb.isSelected() && !args.contains("--fullscreen")) args.add("--fullscreen");
            return args;
        }

        void monitorGame(Process process, String actualId, OutputStream logOut, Thread logPump) {
            try {
                int code = process.waitFor();
                if (logPump != null) {
                    try { logPump.join(10000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                setStatus(code == 0 ? "Game closed" : "Game exited (code " + code + ") — see logs/minecraft-installer-wip-latest.log");
            } catch (Exception e) {
                setStatus("Game monitor stopped");
            } finally {
                if (logPump != null && logPump.isAlive()) {
                    try { logPump.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                try {
                    if (logOut != null) {
                        logOut.flush();
                        logOut.close();
                    }
                } catch (Exception ignored) {}
                logHandle = null;
                logPumpThread = null;
                gameProcess = null;
                ui(() -> {
                    playButton.setEnabled(true);
                    playButton.setText("PLAY — DO NOT USE WIP");
                });
            }
        }

        void play() {
            String acc = (String) accountCombo.getSelectedItem();
            final String username;
            if (MICROSOFT_ACCOUNT.equals(acc)) {
                if (authSession == null || !"microsoft".equals(authSession.get("type"))) {
                    if (!promptLogin("microsoft")) return;
                }
                username = authSession.get("display_name");
            } else if (MOJANG_ACCOUNT.equals(acc)) {
                if (authSession == null || !"mojang".equals(authSession.get("type"))) {
                    if (!promptLogin("mojang")) return;
                }
                username = authSession.get("display_name");
            } else {
                username = usernameField.getText().trim();
                if (username.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "Enter a username!", APP_NAME, JOptionPane.WARNING_MESSAGE);
                    return;
                }
                for (char c : username.toCharArray()) {
                    if (!Character.isLetterOrDigit(c) && c != '_') {
                        JOptionPane.showMessageDialog(this, "Invalid username! Use only letters, numbers, underscore.", APP_NAME, JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                }
                if (username.length() < 3 || username.length() > 16) {
                    JOptionPane.showMessageDialog(this, "Username must be 3-16 characters!", APP_NAME, JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }

            String version = (String) versionCombo.getSelectedItem();
            if (version == null || version.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Select a version!", APP_NAME, JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (gameProcess != null && gameProcess.isAlive()) {
                JOptionPane.showMessageDialog(this, "Minecraft is already running.", APP_NAME, JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            playButton.setEnabled(false);
            playButton.setText("DO NOT USE WIP...");
            progressBar.setValue(0);

            new Thread(() -> {
                try {
                    IntConsumer progressCb = this::setProgress;
                    Consumer<String> statusCb = this::setStatus;

                    Object[] dv = downloadVersion(version, progressCb, statusCb);
                    J.Obj versionInfo = (J.Obj) dv[0];
                    String launchId = (String) dv[1];
                    String clientId = launchId;

                    J.Obj resolved = Util.resolveVersionInfo(versionInfo);
                    String baseId = versionInfo.has("inheritsFrom") && !versionInfo.get("inheritsFrom").isNull()
                            ? versionInfo.getStr("inheritsFrom", clientId) : clientId;
                    Path jarPath = GAME_DIR.resolve("versions").resolve(baseId).resolve(baseId + ".jar");
                    if (!Files.exists(jarPath)) throw new FileNotFoundException("Missing game jar: " + jarPath);
                    Path nativesDir = GAME_DIR.resolve("versions").resolve(baseId).resolve("natives");

                    String classpath = buildClasspath(resolved, baseId);
                    int ramMb = ramSlider.getValue() * 1024;
                    List<String> args = buildLaunchArgs(resolved, launchId, username, ramMb, nativesDir, classpath);

                    setStatus("Launching " + launchId + "...");
                    Path logDir = GAME_DIR.resolve("logs");
                    Files.createDirectories(logDir);
                    Path logPath = logDir.resolve("minecraft-installer-wip-latest.log");
                    OutputStream logOut = Files.newOutputStream(logPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    logHandle = logOut;

                    ProcessBuilder pb = new ProcessBuilder(args);
                    pb.directory(GAME_DIR.toFile());
                    pb.redirectErrorStream(true);
                    Process process = pb.start();
                    gameProcess = process;

                    // pump stdout to log
                    Thread logPump = new Thread(() -> {
                        try (InputStream in = process.getInputStream()) {
                            in.transferTo(logOut);
                            logOut.flush();
                        } catch (IOException ignored) {}
                    }, "game-log");
                    logPumpThread = logPump;
                    logPump.start();

                    setStatus("Playing " + launchId);
                    new Thread(() -> monitorGame(process, launchId, logOut, logPump), "game-monitor").start();
                } catch (Exception e) {
                    e.printStackTrace();
                    setStatus("Launch failed!");
                    ui(() -> JOptionPane.showMessageDialog(this, "Error:\n" + e.getMessage(), APP_NAME, JOptionPane.ERROR_MESSAGE));
                    try { if (logHandle != null) logHandle.close(); } catch (Exception ignored) {}
                    logHandle = null;
                    gameProcess = null;
                    ui(() -> {
                        playButton.setEnabled(true);
                        playButton.setText("PLAY — DO NOT USE WIP");
                    });
                }
            }, "launch").start();
        }
    }

    public static void main(String[] args) {
        if (Util.isWindows()) {
            try {
                System.setProperty("sun.java2d.dpiaware", "true");
                // Best-effort DPI awareness (fragile; skip quietly on failure)
                try {
                    Class<?> shcore = Class.forName("com.sun.jna.platform.win32.Shcore");
                    // JNA not bundled — ignore
                    if (shcore != null) { /* no-op */ }
                } catch (ClassNotFoundException ignored) {}
            } catch (Exception ignored) {}
        }
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Exception ignored) {}
            App app = new App();
            app.setVisible(true);
        });
    }
}
