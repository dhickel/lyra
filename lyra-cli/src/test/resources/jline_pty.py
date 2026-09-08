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
    elif mode == "readline":
        until(b"lyra> ")
        send("import std->io as io io->::readLine[]\n")
        # Acceptance disables paste/raw editing before generated program input
        # resumes the one terminal reader.
        until(b"\x1b[?2004l")
        send("программа 😀\n")
        result = plain(until(b"lyra> "))
        assert "программа".encode("utf-8") in result, (result, bytes(transcript))
        assert b"\\uD83D\\uDE00" in result, (result, bytes(transcript))
        send("let @pub afterInput :String = \"次\"\n")
        until(b"lyra> ")
        send(":history\n")
        history = plain(until(b"lyra> "))
        assert b"1: import std->io as io io->::readLine[]" in history, history
        assert "2: let @pub afterInput :String = \"次\"".encode("utf-8") in history, history
        assert b": \xd0\xbf\xd1\x80\xd0\xbe\xd0\xb3\xd1\x80\xd0\xb0\xd0\xbc\xd0\xbc\xd0\xb0" not in history, history
        send(":quit\n")
        finish(0)
    elif mode in ("rich-cancel", "plain-cancel"):
        # A generated constant-stack self-tail spin: the submission imports
        # std->io, declares the looping function and enters the loop after
        # one println. Session safe points observe the Ctrl-C token at the
        # loop backedge without touching main or the host process.
        spin = ("import std->io as io let @pub spin :Fn<I32;I32> = "
                "(=> |n| ((== n n) -> ::spin[(+ n 1)] : 0)) "
                "{ io->::println[\"started\"] (spin 0) }\n")
        if mode == "rich-cancel":
            until(b"lyra> ")
        send(spin)
        if mode == "rich-cancel":
            # The line was accepted (paste mode disabled) before the
            # generated program printed; the echoed source text is not the
            # output marker.
            until(b"\x1b[?2004l")
        until(b"started\r\n")
        time.sleep(0.2)
        send("\x03")
        if mode == "rich-cancel":
            until(b"lyra> ")
        else:
            time.sleep(0.5)
        send("let @pub after :I32 = 42\n")
        if mode == "rich-cancel":
            until(b"lyra> ")
        send(":bindings\n")
        if mode == "rich-cancel":
            bindings = plain(until(b"lyra> "))
        else:
            send(":quit\n")
            finish(1)
            bindings = plain(transcript[transcript.rindex(b":bindings") + len(b":bindings"):])
        assert b"after :I32\n" in bindings, (bindings, bytes(transcript))
        assert b"spin" not in bindings, ("cancelled staged names published", bindings)
        if mode == "rich-cancel":
            send(":quit\n")
            finish(0)
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
        assert b"LYR-REPL-USAGE" in plain(until(b"lyra> ")), bytes(transcript)
        send(":type let @pub notThere :I32 = 5\n")
        assert b"Unit" in plain(until(b"lyra> ")), bytes(transcript)
        send(":bindings\n")
        bindings = plain(until(b"lyra> "))
        assert b"notThere" not in bindings, ("type query published a name", bindings)
        if mode == "rich":
            # Execution-host file/module completion: listing reads the
            # configured source root without compiling or pinning, and the
            # completed path loads after Enter. The module must be imported
            # before :reload can rebuild its retained graph.
            send("import newmod\n")
            until(b"lyra> ")
            send(":load newm\t\n")
            until(b"lyra> ")
            assert b":load newmod.lyra" in plain(transcript), bytes(transcript)
            send(":bindings\n")
            bindings = plain(until(b"lyra> "))
            assert b"loaded :I32\n" in bindings, bindings
            send(":reload newm\t\n")
            reloaded = plain(until(b"lyra> "))
            assert b":reload newmod" in reloaded, reloaded
            assert b"LYC-" not in reloaded, reloaded
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
