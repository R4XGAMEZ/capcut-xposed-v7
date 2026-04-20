package com.r4x.capcut_xposed;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import de.robv.android.xposed.XposedBridge;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LocalSocket IPC Bridge
 * Python bot connects to abstract socket: "capcut_xposed_bridge"
 *
 * Protocol: JSON over newlines
 * Commands:
 *   {"type":"ping"}
 *   {"type":"tap","x":100,"y":200}
 *   {"type":"swipe","x1":100,"y1":200,"x2":300,"y2":200,"duration":300}
 *   {"type":"hierarchy"}
 *   {"type":"get_activity"}
 *   {"type":"shell","cmd":"..."}
 */
public class BridgeSocketServer {

    private static final String TAG = "R4X_Bridge";
    private static final String SOCKET_NAME = "capcut_xposed_bridge";

    private final Context context;
    private final ClassLoader classLoader;
    private LocalServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean running = false;

    // Activity references (set by hooks)
    private volatile Object editActivity;
    private volatile Object mainActivity;
    private volatile Object exportActivity;

    public BridgeSocketServer(Context context, ClassLoader classLoader) {
        this.context = context;
        this.classLoader = classLoader;
    }

    public void start() {
        running = true;
        try {
            serverSocket = new LocalServerSocket(SOCKET_NAME);
            XposedBridge.log(TAG + ": Listening on @" + SOCKET_NAME);

            while (running) {
                try {
                    LocalSocket client = serverSocket.accept();
                    executor.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (running) {
                        XposedBridge.log(TAG + ": Accept error: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            XposedBridge.log(TAG + ": Server start failed: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        executor.shutdownNow();
    }

    private void handleClient(LocalSocket client) {
        XposedBridge.log(TAG + ": Client connected");
        try (
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            PrintWriter writer = new PrintWriter(client.getOutputStream(), true)
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String response = processCommand(line);
                writer.println(response);
            }
        } catch (IOException e) {
            XposedBridge.log(TAG + ": Client error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
            XposedBridge.log(TAG + ": Client disconnected");
        }
    }

    private String processCommand(String rawJson) {
        try {
            JSONObject cmd = new JSONObject(rawJson);
            String type = cmd.getString("type");

            switch (type) {
                case "ping":
                    return buildOk("pong");

                case "get_activity":
                    return getActivityInfo();

                case "tap":
                    return doTap(cmd);

                case "swipe":
                    return doSwipe(cmd);

                case "hierarchy":
                    return getHierarchy();

                case "shell":
                    return doShell(cmd);

                case "long_press":
                    return doLongPress(cmd);

                case "key_event":
                    return doKeyEvent(cmd);

                default:
                    return buildError("unknown_command: " + type);
            }
        } catch (JSONException e) {
            return buildError("json_parse_error: " + e.getMessage());
        } catch (Exception e) {
            return buildError("exception: " + e.getMessage());
        }
    }

    // ─── ACTIONS ─────────────────────────────────────────────────────────────

    private String doTap(JSONObject cmd) throws JSONException {
        int x = cmd.getInt("x");
        int y = cmd.getInt("y");
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{
                "input", "tap", String.valueOf(x), String.valueOf(y)
            });
            proc.waitFor(); // FIX: race condition fix
            return buildOk("tapped " + x + "," + y);
        } catch (Exception e) {
            return buildError("tap failed: " + e.getMessage());
        }
    }

    private String doSwipe(JSONObject cmd) throws JSONException {
        int x1 = cmd.getInt("x1");
        int y1 = cmd.getInt("y1");
        int x2 = cmd.getInt("x2");
        int y2 = cmd.getInt("y2");
        int duration = cmd.optInt("duration", 300);
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{
                "input", "swipe",
                String.valueOf(x1), String.valueOf(y1),
                String.valueOf(x2), String.valueOf(y2),
                String.valueOf(duration)
            });
            proc.waitFor(); // FIX: race condition fix
            return buildOk("swiped");
        } catch (Exception e) {
            return buildError("swipe failed: " + e.getMessage());
        }
    }

    private String doLongPress(JSONObject cmd) throws JSONException {
        int x = cmd.getInt("x");
        int y = cmd.getInt("y");
        int duration = cmd.optInt("duration", 1000);
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{
                "input", "swipe",
                String.valueOf(x), String.valueOf(y),
                String.valueOf(x), String.valueOf(y),
                String.valueOf(duration)
            });
            proc.waitFor(); // FIX: race condition fix
            return buildOk("long_pressed " + x + "," + y);
        } catch (Exception e) {
            return buildError("long_press failed: " + e.getMessage());
        }
    }

    private String doKeyEvent(JSONObject cmd) throws JSONException {
        int keyCode = cmd.getInt("keycode");
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{
                "input", "keyevent", String.valueOf(keyCode)
            });
            proc.waitFor(); // FIX: race condition fix
            return buildOk("key_event " + keyCode);
        } catch (Exception e) {
            return buildError("key_event failed: " + e.getMessage());
        }
    }

    private String doShell(JSONObject cmd) throws JSONException {
        String shellCmd = cmd.getString("cmd");
        try {
            Process proc = Runtime.getRuntime().exec(shellCmd);
            BufferedReader stdout = new BufferedReader(
                new InputStreamReader(proc.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = stdout.readLine()) != null) {
                sb.append(l).append("\n");
            }
            proc.waitFor();
            JSONObject resp = new JSONObject();
            resp.put("status", "ok");
            resp.put("output", sb.toString().trim());
            resp.put("exit_code", proc.exitValue());
            return resp.toString();
        } catch (Exception e) {
            return buildError("shell failed: " + e.getMessage());
        }
    }

    private String getActivityInfo() {
        try {
            JSONObject resp = new JSONObject();
            resp.put("status", "ok");
            resp.put("edit_activity_active", editActivity != null);
            resp.put("main_activity_active", mainActivity != null);
            resp.put("export_activity_active", exportActivity != null);

            String currentActivity = "unknown";
            if (editActivity != null) currentActivity = "EditActivity";
            else if (exportActivity != null) currentActivity = "ExportActivity";
            else if (mainActivity != null) currentActivity = "MainActivity";
            resp.put("current_activity", currentActivity);

            return resp.toString();
        } catch (JSONException e) {
            return buildError(e.getMessage());
        }
    }

    private String getHierarchy() {
        // FIX: use /data/local/tmp path with proper shell array to avoid permission issues
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{
                "sh", "-c", "uiautomator dump /data/local/tmp/uidump.xml && cat /data/local/tmp/uidump.xml"
            });
            proc.waitFor();

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);

            String xml = sb.toString();
            if (xml.isEmpty()) {
                return buildError("hierarchy dump empty - check uiautomator permission");
            }

            JSONObject resp = new JSONObject();
            resp.put("status", "ok");
            resp.put("xml", xml);
            return resp.toString();
        } catch (Exception e) {
            return buildError("hierarchy dump failed: " + e.getMessage());
        }
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private String buildOk(String message) {
        try {
            JSONObject j = new JSONObject();
            j.put("status", "ok");
            j.put("message", message);
            return j.toString();
        } catch (JSONException e) {
            return "{\"status\":\"ok\"}";
        }
    }

    private String buildError(String message) {
        try {
            JSONObject j = new JSONObject();
            j.put("status", "error");
            j.put("message", message);
            return j.toString();
        } catch (JSONException e) {
            return "{\"status\":\"error\"}";
        }
    }

    // ─── SETTERS (called by hooks) ─────────────────────────────────────────────

    public void setEditActivity(Object activity) {
        this.editActivity = activity;
        XposedBridge.log(TAG + ": EditActivity reference " + 
            (activity != null ? "set" : "cleared"));
    }

    public void setMainActivity(Object activity) {
        this.mainActivity = activity;
    }

    public void setExportActivity(Object activity) {
        this.exportActivity = activity;
    }
}
