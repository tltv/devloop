package com.vaadin.devloop.connector;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import com.vaadin.flow.server.VaadinService;

/**
 * Registers the running app with the daemon over a connection held open for the
 * app's lifetime.
 * <p>
 * The connection is the liveness signal in both directions: the daemon learns
 * the app is up when it arrives and that the app is gone when it closes, with no
 * polling and no port probing. The daemon passes its port and token in as system
 * properties at launch, so there is nothing to discover and no file to read.
 * <p>
 * If those properties are absent the app was not launched by the daemon (a
 * developer running it from an IDE, say) and this quietly does nothing - the
 * ownership model says the daemon aggregates state, it never competes for it.
 */
final class DevLoopRegistration {

    private DevLoopRegistration() {
    }

    static void start(VaadinService service) {
        String port = System.getProperty("vaadin.devloop.daemonPort");
        String token = System.getProperty("vaadin.devloop.token");
        if (port == null || token == null) {
            System.out.println(
                    "[devloop] not daemon-launched; skipping registration");
            return;
        }

        String mode = service.getDeploymentConfiguration()
                .isDevModeLiveReloadEnabled()
                        ? "DEVELOPMENT_FRONTEND_LIVERELOAD"
                        : "DEVELOPMENT_BUNDLE";

        Thread thread = new Thread(() -> hold(Integer.parseInt(port), token,
                mode), "devloop-registration");
        thread.setDaemon(true);
        thread.start();
    }

    private static void hold(int port, String token, String mode) {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true,
                        StandardCharsets.UTF_8);
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setKeepAlive(true);
            out.println(token + " register " + mode + " "
                    + ProcessHandle.current().pid());
            System.out.println("[devloop] registered with daemon on port " + port
                    + " (" + in.readLine() + ")");
            // Block for the app's lifetime, serving commands on the same
            // connection. Reaching the end of the stream means the daemon went
            // away; the JVM exiting closes this socket, which is how the daemon
            // learns the app is gone.
            String request;
            while ((request = in.readLine()) != null) {
                out.println(handle(request.trim()));
            }
            System.out.println("[devloop] daemon closed the registration");
        } catch (Exception e) {
            System.out.println("[devloop] registration ended: " + e);
        }
    }

    /**
     * Commands the daemon issues over the registration connection. Always
     * answers exactly one line, even on failure, so the daemon never blocks
     * waiting for a reply that is not coming.
     */
    private static String handle(String request) {
        try {
            if (request.startsWith("REDEFINE ")) {
                return DevLoopRedefiner
                        .redefine(request.substring("REDEFINE ".length()).trim());
            }
            if (request.startsWith("RESOURCES ")) {
                return DevLoopRedefiner
                        .resources(request.substring("RESOURCES ".length()).trim());
            }
            if (request.equals("INFO")) {
                return DevLoopRedefiner.info();
            }
            if (request.equals("FRONTEND")) {
                return "OK frontend=" + DevLoopRedefiner.frontendStatus();
            }
            if (request.equals("PING")) {
                return "OK pong";
            }
            return "ERR kind=protocol message=unknown-request";
        } catch (Throwable t) {
            return "ERR kind=internal class=" + t.getClass().getName()
                    + " message=" + String.valueOf(t.getMessage())
                            .replaceAll("\\s+", " ");
        }
    }
}
