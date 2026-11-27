package bentrix.mcp;

import bentrix.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Finding the running application through Bentrix's agents folder, and pairing with it. */
public final class Discovery {
    public static final String ENDPOINT_FILE = "endpoint.json";
    public static final String SECRET_FILE = "local.secret";

    private Discovery() {}

    /** The folder Bentrix writes its endpoint and secret into - user_dir(Data, "agents"), as the application has it. */
    public static Path agentsDir() {
        String lad = System.getenv("LOCALAPPDATA");
        if (lad != null && !lad.isEmpty()) {
            return Path.of(lad, "BENTRIX", "agents");
        }
        String home = System.getenv("HOME");
        return home == null || home.isEmpty() ? Path.of("bentrix-agents") : Path.of(home, ".local", "share", "bentrix", "agents");
    }

    @SuppressWarnings("unchecked")
    public static String readEndpoint(Path folder) {
        Path file = folder.resolve(ENDPOINT_FILE);
        Map<String, Object> doc;
        try {
            doc = (Map<String, Object>) Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new Client.NotRunningException("Bentrix is not running: there is no readable " + ENDPOINT_FILE + " in " + folder);
        }
        if (!(doc.get("url") instanceof String url) || !url.startsWith("http://127.0.0.1:") || !(doc.get("pid") instanceof Double pid)) {
            throw new Client.NotRunningException("Bentrix is not running: " + file + " is not an endpoint file");
        }
        if (ProcessHandle.of(pid.longValue()).map(ProcessHandle::isAlive).orElse(false) == false) {
            throw new Client.NotRunningException("Bentrix is not running: " + file + " is stale (process " + pid.longValue() + " has ended)");
        }
        return url;
    }

    public static String readSecret(Path folder) {
        String secret;
        try {
            secret = Files.readString(folder.resolve(SECRET_FILE), StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            throw new Client.PairingRefusedException("there is no readable " + SECRET_FILE + " in " + folder + ": Bentrix writes it at every start");
        }
        if (secret.isEmpty()) {
            throw new Client.PairingRefusedException(SECRET_FILE + " is empty");
        }
        return secret;
    }

    /** The name this process pairs under: BENTRIX_AGENT_NAME, else the program's main class. */
    public static String defaultName() {
        String env = System.getenv("BENTRIX_AGENT_NAME");
        if (env != null && !env.isBlank()) {
            return env.strip();
        }
        String command = System.getProperty("sun.java.command", "");
        String main = command.isEmpty() ? "" : command.split(" ")[0];
        return main.isEmpty() ? Client.CLIENT_NAME : Client.CLIENT_NAME + " (" + main + ")";
    }

    /** Present the secret to url/pair and answer the token. The secret travels in this one body. */
    @SuppressWarnings("unchecked")
    public static String pair(String url, String secret, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("secret", secret);
        body.put("client", Map.of("name", name));
        HttpResponse<String> resp;
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.replaceAll("/$", "") + "/pair"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new Client.PairingRefusedException("the pairing request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Client.PairingRefusedException("the pairing request was interrupted");
        }
        Map<String, Object> doc;
        try {
            doc = (Map<String, Object>) Json.parse(resp.body());
        } catch (RuntimeException e) {
            doc = new LinkedHashMap<>();
        }
        if (resp.statusCode() < 200 || resp.statusCode() > 299) {
            Map<String, Object> err = doc.get("error") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            Object code = err.getOrDefault("code", "");
            if ("wrong_secret".equals(code)) {
                throw new Client.PairingRefusedException("Bentrix refused the secret in the secret file: it writes a new secret at every start");
            }
            throw new Client.PairingRefusedException("Bentrix answered the pairing request with HTTP " + resp.statusCode() + " " + code + ": " + err.getOrDefault("message", ""));
        }
        if (!(doc.get("token") instanceof String token) || token.isEmpty()) {
            throw new Client.PairingRefusedException("Bentrix answered the pairing request with no token");
        }
        return token;
    }

    /** Find the running application and get a token: {url, token}. Either argument may be null. */
    public static String[] discover(Path agentsFolder, String name) {
        Path folder = agentsFolder == null ? agentsDir() : agentsFolder;
        String url = readEndpoint(folder);
        return new String[] {url, pair(url, readSecret(folder), name == null ? defaultName() : name)};
    }
}
