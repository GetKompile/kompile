#!/usr/bin/env python3
"""
Real end-to-end TUI test using pyte virtual terminal emulator.
pyte renders cursor-addressed output correctly, so we see EXACTLY
what the user's real terminal shows.
"""
import pexpect
import pyte
import sys
import time
import os

BINARY = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "target", "kompile-cli")
ROWS, COLS = 40, 120


def screen_text(screen):
    """Get all non-blank lines from the virtual terminal screen."""
    lines = []
    for row in range(screen.lines):
        line = ""
        for col in range(screen.columns):
            line += screen.buffer[row][col].data
        stripped = line.rstrip()
        if stripped:
            lines.append(stripped)
    return lines


def screen_dump(screen):
    """Full screen dump as a string."""
    out = []
    for row in range(screen.lines):
        line = ""
        for col in range(screen.columns):
            line += screen.buffer[row][col].data
        out.append(line.rstrip())
    # Remove trailing blank lines
    while out and not out[-1].strip():
        out.pop()
    return "\n".join(out)


def main():
    env = os.environ.copy()
    env["TERM"] = "xterm-256color"

    print(f"Binary: {BINARY}")
    print(f"Screen: {ROWS}x{COLS}")
    print()

    # Virtual terminal emulator — renders cursor-addressed output properly
    screen = pyte.Screen(COLS, ROWS)
    stream = pyte.Stream(screen)

    child = pexpect.spawn(
        BINARY,
        args=["chat", "--mode=emulated", "--agent=claude"],
        encoding="utf-8",
        timeout=60,
        env=env,
        dimensions=(ROWS, COLS),
    )

    def feed_output(wait_secs=3):
        """Read available output and feed to virtual terminal."""
        deadline = time.time() + wait_secs
        while time.time() < deadline:
            try:
                chunk = child.read_nonblocking(size=65536, timeout=0.5)
                stream.feed(chunk)
            except pexpect.TIMEOUT:
                pass
            except pexpect.EOF:
                break

    # Wait for TUI startup
    print("Waiting for TUI to render...")
    feed_output(8)

    print("=== INITIAL SCREEN ===")
    dump = screen_dump(screen)
    print(dump)
    print("======================")
    print()

    # Send message
    print("Sending: 'say hello'")
    child.sendline("say hello")

    # Wait for agent response
    print("Waiting for response (30s)...")
    feed_output(30)

    print()
    print("=== SCREEN AFTER RESPONSE ===")
    dump = screen_dump(screen)
    print(dump)
    print("=============================")
    print()

    # Analysis
    lines = screen_text(screen)
    content = "\n".join(lines).lower()

    has_hello = "hello" in content
    has_error = "error" in content
    gen_lines = sum(1 for l in lines if "generating" in l.lower())

    print("ANALYSIS:")
    print(f"  Has 'hello' in content: {has_hello}")
    print(f"  Has 'error' in content: {has_error}")
    print(f"  Lines with 'generating': {gen_lines}")
    print(f"  Non-blank lines: {len(lines)}")
    print()

    if has_hello and gen_lines <= 2:
        print("RESULT: OUTPUT VISIBLE - response appeared, no generating spam")
    elif has_error:
        print("RESULT: ERROR - agent returned error")
    elif gen_lines > 5:
        print("RESULT: GENERATING SPAM - old bug present")
    else:
        print("RESULT: NO OUTPUT - response missing from screen")
        print()
        print("Content lines found:")
        for i, l in enumerate(lines):
            print(f"  {i:2d}: {l}")

    # Cleanup
    child.sendintr()
    time.sleep(1)
    child.sendline("/quit")
    try:
        child.expect(pexpect.EOF, timeout=5)
    except:
        child.close(force=True)


if __name__ == "__main__":
    main()
