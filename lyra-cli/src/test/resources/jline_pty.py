"""Linux PTY assertions against the real Java 25 JLine provider, not a mock."""
import errno
import fcntl
import os
import pty
import re
import select
import signal
import struct
import subprocess
import sys
import termios
import time

mode, *command = sys.argv[1:]
master, slave = pty.openpty()
fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
original = termios.tcgetattr(slave)


def controlling_terminal():
    # Background shell jobs inherit ignored INT/QUIT. A fresh interactive
    # controlling terminal must not inherit that non-interactive disposition.
    signal.signal(signal.SIGINT, signal.SIG_DFL)
    signal.signal(signal.SIGQUIT, signal.SIG_DFL)
    os.setsid()
    fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
    assert os.tcgetpgrp(slave) == os.getpgrp(), "child is not the terminal foreground group"


if mode == "rich-ignored":
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    signal.signal(signal.SIGQUIT, signal.SIG_IGN)

environment = dict(os.environ, TERM="xterm-256color")
process = subprocess.Popen(command, stdin=slave, stdout=slave, stderr=slave,
                           env=environment, preexec_fn=controlling_terminal)
pending = bytearray()
transcript = bytearray()


def drain(timeout=0.1):
    if not select.select([master], [], [], timeout)[0]:
        return
    try:
        data = os.read(master, 65536)
    except OSError as failure:
        if failure.errno != errno.EIO:
            raise
        return
    pending.extend(data)
    transcript.extend(data)


def until(marker):
    if marker == b"lyra> ":
        # Resize redraws include the prompt too. A new read enables paste mode;
        # only that marker proves the previous source was actually accepted.
        marker = b"\x1b[?2004hlyra> "
    deadline = time.monotonic() + 15
    while marker not in pending:
        assert time.monotonic() < deadline, (marker, bytes(transcript))
        assert process.poll() is None, (process.returncode, bytes(transcript))
        drain()
    end = pending.index(marker) + len(marker)
    data = bytes(pending[:end])
    del pending[:end]
    return data


def send(text):
    os.write(master, text.encode("utf-8"))


def plain(data):
    return re.sub(rb"\x1b(?:\[[0-?]*[ -/]*[@-~]|[=>])", b"", data).replace(b"\r", b"")


def finish(status):
    deadline = time.monotonic() + 15
    while process.poll() is None:
        assert time.monotonic() < deadline, bytes(transcript)
        drain()
    drain(0)
    assert process.returncode == status, (process.returncode, bytes(transcript))
    assert termios.tcgetattr(slave) == original, ("terminal attributes not restored", original,
                                                termios.tcgetattr(slave), bytes(transcript))


try:
    if mode == "plain":
        send(":help\n:quit\n")
        finish(0)
        assert b"Commands:" in transcript, bytes(transcript)
        assert b"\x1b" not in transcript, bytes(transcript)
        assert b"lyra> " not in transcript, bytes(transcript)
    elif mode == "fallback":
        until(b"using plain console")
        send(":help\n:quit\n")
        finish(0)
        assert b"Commands:" in transcript, bytes(transcript)
        assert b"\x1b" not in transcript, bytes(transcript)
    elif mode == "vi":
        until(b"lyra> ")
        send(":quix\x1brt\n")
        finish(0)
        assert b"\x1b[?2004h" in transcript, bytes(transcript)
        assert b"\x1b[?2004l" in transcript, bytes(transcript)
    elif mode == "ownership":
        until(b"lyra> ")
        send("let value :String = \"😀\"\nпрограмма 😀\nlet next :String = \"次\"\n")
        until(b"program-input> ")
        attributes = termios.tcgetattr(slave)
        assert attributes == original, ("program input must be cooked, not JLine raw mode", original, attributes)
        until(b"lyra> ")
        until(b"after-close> ")
        send("still-open\n")
        finish(0)
        assert b"ownership-ok" in transcript, bytes(transcript)
    else:
        assert mode in ("rich", "rich-ignored"), mode
        until(b"lyra> ")
        send("\x1b[200~let @pub first :I32 = 1\nlet @pub second :I32 = 2\x1b[201~")
        deadline = time.monotonic() + 0.3
        while time.monotonic() < deadline:
            drain(0.03)
        assert b"lyra> " not in pending, ("paste submitted without Enter", bytes(transcript))
        send("\n")
        until(b"lyra> ")
        send(":bindings\n")
        bindings = plain(until(b"lyra> "))
        assert b"first :I32\nsecond :I32\n" in bindings, bindings
        send("(abandoned")
        until(b"abandoned")
        send("\x03")
        assert b"LYC-" not in until(b"lyra> "), bytes(transcript)
        send("let @pub resized :I32 = (")
        # Wait for typed text before signalling the live line editor.
        until(b"resized")
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 16, 42, 0, 0))
        os.kill(process.pid, signal.SIGWINCH)
        send("\n+ 1 2)\n")
        until(b"...> ")
        until(b"lyra> ")
        send(":bindings\n")
        bindings = plain(until(b"lyra> "))
        assert b"resized :I32\n" in bindings, bindings
        send(":type 99\n")
        assert b"I64" in plain(until(b"lyra> "))
        send(":reload\n")
        assert b"LYR-REPL-RELOAD-UNSUPPORTED" in plain(until(b"lyra> "))
        send(":quit\n")
        finish(2)
        assert transcript.count(b"\x1b[?2004h") == transcript.count(b"\x1b[?2004l"), bytes(transcript)
    print("pty-ok:" + mode)
finally:
    if process.poll() is None:
        process.kill()
        process.wait(timeout=5)
    os.close(master)
    os.close(slave)
