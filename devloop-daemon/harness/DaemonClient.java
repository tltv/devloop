import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Shared daemon client for the measurement harnesses.
 * <p>
 * The harnesses used to talk to a second socket the app opened just for them.
 * That socket is gone: it duplicated the connection the connector already holds,
 * and its copy of the redefine logic had silently fallen behind. They now go
 * through the daemon's {@code redefine} verb, which returns the connector's reply
 * verbatim with none of {@code apply}'s escalation policy - so the harnesses
 * still measure raw JVM behaviour, on one transport.
 */
final class DaemonClient {

    private final int port;
    private final String token;

    private DaemonClient(int port, String token) {
        this.port = port;
        this.token = token;
    }

    /** Reads the daemon handshake; fails loudly if no daemon is running. */
    static DaemonClient discover(Path root) throws IOException {
        Path file = root.resolve(".vaadin").resolve("daemon.properties");
        if (!Files.isRegularFile(file)) {
            throw new IOException("no daemon handshake at " + file
                    + " - run ./vaadin-dev start first");
        }
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return new DaemonClient(
                Integer.parseInt(props.getProperty("port", "-1")),
                props.getProperty("token", ""));
    }

    /** Pushes named classes at the app and returns the connector's raw reply. */
    String redefine(List<String> binaryNames) throws IOException {
        List<String> lines = send("redefine " + String.join(",", binaryNames));
        return lines.isEmpty() ? "ERR kind=no-reply" : lines.get(lines.size() - 1);
    }

    String status() throws IOException {
        return String.join(" | ", send("status"));
    }

    /**
     * Sends one command and collects the progress lines. The daemon answers with
     * {@code > text} lines terminated by {@code EXIT <code>}.
     */
    private List<String> send(String command) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true,
                        StandardCharsets.UTF_8);
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8))) {
            out.println(token + " " + command);
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("EXIT ")) {
                    break;
                }
                lines.add(line.startsWith("> ") ? line.substring(2) : line);
            }
            return lines;
        }
    }
}
