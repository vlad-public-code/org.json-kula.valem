package org.json_kula.valem.console;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.cli.CliBootstrap;
import org.json_kula.valem.service.ModelOperations;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Valem console application — JSON-over-stdin/stdout REPL.
 *
 * <h2>Protocol</h2>
 * <p>Send one JSON object per line on {@code stdin}; receive one JSON object per line on {@code stdout}.
 *
 * <p>Input:
 * <pre>
 *   {"cmd": "command-name", ...args}
 * </pre>
 *
 * <p>Output on success:
 * <pre>
 *   {"ok": true, "result": <value>}
 * </pre>
 *
 * <p>Output on error:
 * <pre>
 *   {"ok": false, "error": "<message>"}
 * </pre>
 *
 * <p>Send {@code {"cmd":"help"}} to list all available commands.
 * Send {@code {"cmd":"exit"}} or close stdin to terminate.
 *
 * <h2>Storage &amp; modes</h2>
 * <p>By default all state is held in memory for the duration of the process — nothing is persisted.
 * Pass {@code --url <base>} (optionally {@code --api-key <key>}, or the {@code VALEM_URL} /
 * {@code VALEM_API_KEY} environment variables) to drive a durable, shared {@code valem-web}
 * server instead.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -jar valem-console.jar                      # embedded, in-memory (default)
 *   java -jar valem-console.jar --url https://host   # remote against valem-web
 *   # or pipe commands:
 *   echo '{"cmd":"list-models"}' | java -jar valem-console.jar
 * </pre>
 */
public class ConsoleApp {

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.READ_ENUMS_USING_TO_STRING, false)
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        CliBootstrap.Options opts = CliBootstrap.parse(args, System::getenv);
        if (opts.help()) {
            printUsage(System.out);
            return;
        }
        if (opts.version()) {
            System.out.println("valem-console 1.0.0");
            return;
        }
        if (!opts.unknown().isEmpty()) {
            System.err.println("[valem-console] unknown argument(s): " + opts.unknown());
            printUsage(System.err);
            return;
        }

        ModelOperations   service    = CliBootstrap.createModelOperations(opts, mapper);
        CommandDispatcher dispatcher = new CommandDispatcher(service, mapper);
        if (opts.remote()) {
            System.err.println("[valem-console] remote mode: driving " + opts.url());
        }

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty()) continue;

            String response;
            try {
                ObjectNode cmd = (ObjectNode) mapper.readTree(line);
                String cmdName = cmd.path("cmd").asText();

                if ("exit".equals(cmdName)) {
                    response = mapper.writeValueAsString(ok("bye"));
                    System.out.println(response);
                    System.out.flush();
                    break;
                }

                Object result = dispatcher.dispatch(cmd);
                response = mapper.writeValueAsString(ok(result));

            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                response = mapper.writeValueAsString(error("Invalid JSON: " + e.getOriginalMessage()));
            } catch (Exception e) {
                response = mapper.writeValueAsString(error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }

            System.out.println(response);
            System.out.flush();
        }
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("""
            valem-console — JSON-over-stdin/stdout REPL

            Usage:
              java -jar valem-console.jar [options]

            Options:
              --url <base>       Drive a remote valem-web server (default: embedded, in-memory)
              --api-key <key>    API key for the remote server
              -h, --help         Print this help and exit
              -V, --version      Print the version and exit

            Environment:
              VALEM_URL, VALEM_API_KEY   Fallbacks for --url / --api-key

            Send one JSON command object per line; {"cmd":"help"} lists commands. Close stdin or send
            {"cmd":"exit"} to terminate.""");
    }

    private static Map<String, Object> ok(Object result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        map.put("result", result);
        return map;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", false);
        map.put("error", message);
        return map;
    }
}
