#!/usr/bin/env python3
"""
CapCut Xposed Bridge Client v7
Connects to: @capcut_xposed_bridge (LocalSocket abstract)
By: R4X

Usage:
  python3 capcut_bot_v7.py

ADB forward required:
  adb forward tcp:7979 localabstract:capcut_xposed_bridge
"""

import socket
import json
import time
import subprocess
import sys

HOST = '127.0.0.1'
PORT = 7979


def adb_forward():
    """Setup ADB port forwarding"""
    result = subprocess.run(
        ['adb', 'forward', 'tcp:7979', 'localabstract:capcut_xposed_bridge'],
        capture_output=True, text=True
    )
    if result.returncode == 0:
        print("[+] ADB forward set: tcp:7979 -> capcut_xposed_bridge")
        return True
    else:
        print(f"[!] ADB forward failed: {result.stderr}")
        return False


class CapcutBridge:
    def __init__(self, host=HOST, port=PORT):
        self.host = host
        self.port = port
        self.sock = None

    def connect(self):
        """Connect to bridge via ADB forward"""
        if not adb_forward():
            print("[!] Make sure device is connected and CapCut is open")
            return False
        
        for attempt in range(5):
            try:
                self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                self.sock.settimeout(10)
                self.sock.connect((self.host, self.port))
                print(f"[+] Connected to bridge!")
                return True
            except ConnectionRefusedError:
                print(f"[!] Connection refused (attempt {attempt+1}/5). Is CapCut open?")
                time.sleep(2)
            except Exception as e:
                print(f"[!] Connect error: {e}")
                time.sleep(2)
        return False

    def send(self, cmd: dict) -> dict:
        """Send command and get response"""
        try:
            msg = json.dumps(cmd) + '\n'
            self.sock.sendall(msg.encode())
            
            # FIX: use makefile readline to avoid hang on missing \n
            response = b''
            self.sock.settimeout(10)
            while True:
                chunk = self.sock.recv(4096)
                if not chunk:
                    break
                response += chunk
                if b'\n' in response:
                    break
            
            return json.loads(response.decode().strip())
        except Exception as e:
            return {"status": "error", "message": str(e)}

    def ping(self):
        return self.send({"type": "ping"})

    def tap(self, x, y):
        return self.send({"type": "tap", "x": x, "y": y})

    def swipe(self, x1, y1, x2, y2, duration=300):
        return self.send({"type": "swipe", "x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration": duration})

    def long_press(self, x, y, duration=1000):
        return self.send({"type": "long_press", "x": x, "y": y, "duration": duration})

    def key_event(self, keycode):
        """Android keycode: 3=HOME, 4=BACK, 26=POWER, etc."""
        return self.send({"type": "key_event", "keycode": keycode})

    def hierarchy(self):
        """Get UI hierarchy XML"""
        return self.send({"type": "hierarchy"})

    def get_activity(self):
        """Get current activity info"""
        return self.send({"type": "get_activity"})

    def shell(self, cmd):
        return self.send({"type": "shell", "cmd": cmd})

    def close(self):
        if self.sock:
            self.sock.close()


# ─── DEMO USAGE ──────────────────────────────────────────────────────────────

def demo():
    bridge = CapcutBridge()
    
    print("=== CapCut Xposed Bridge v7 ===")
    print("Make sure:")
    print("  1. LSPosed installed")
    print("  2. CapcutXposed-v7.apk installed & module enabled")
    print("  3. CapCut is open")
    print("")

    if not bridge.connect():
        print("[!] Failed to connect. Exiting.")
        sys.exit(1)

    # Ping test
    r = bridge.ping()
    print(f"[ping] {r}")

    # Check activity
    r = bridge.get_activity()
    print(f"[activity] {r}")

    # Example: open editor and tap timeline
    print("\n[*] Example commands:")
    print("  bridge.tap(360, 800)       # tap center")
    print("  bridge.swipe(100,800, 600,800)  # swipe timeline")
    print("  bridge.key_event(4)         # back button")
    print("  bridge.hierarchy()          # get UI XML")

    # Interactive mode
    print("\n[*] Interactive mode (type 'exit' to quit):")
    while True:
        try:
            cmd = input(">> ").strip()
            if cmd == 'exit':
                break
            elif cmd == 'ping':
                print(bridge.ping())
            elif cmd == 'activity':
                print(bridge.get_activity())
            elif cmd == 'hierarchy':
                r = bridge.hierarchy()
                xml = r.get('xml', '')
                print(xml[:2000] + ('...' if len(xml) > 2000 else ''))
            elif cmd.startswith('tap '):
                parts = cmd.split()
                print(bridge.tap(int(parts[1]), int(parts[2])))
            elif cmd.startswith('shell '):
                print(bridge.shell(cmd[6:]))
            else:
                print("Commands: ping, activity, hierarchy, tap X Y, shell CMD, exit")
        except KeyboardInterrupt:
            break

    bridge.close()
    print("[*] Done")


if __name__ == '__main__':
    demo()
