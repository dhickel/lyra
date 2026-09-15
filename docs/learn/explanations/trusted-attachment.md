# Trusted loopback attachment

Lyra application attachment is an explicitly enabled development control surface. It binds only to loopback, but it has no authentication. Any local process that can reach the listener can submit code with the application's authority.

Loopback limits network reachability. It does not distinguish trusted from untrusted processes on the same host or account. Static typing, visibility, owner-thread checks, module ownership, and lifecycle checks still apply because they preserve correct language execution. They are not a hostile-client security boundary.

Attachment uses the real application root. It does not clone module state or expose stack locals and private names. Public root exports enter the attached scope, and ordinary `@mut` assignment changes real root storage.

Live work must execute on the application's owner thread. Generated safe points or explicit Java host polling service queued operations one at a time. Cancellation sets an identity-specific cooperative request. It does not force-stop the thread or terminate `main`, and blocking host I/O can delay observation.

The protocol retains application I/O and filesystem authority at the host. Program streams are not tunneled through the REPL connection. Attached load, reload, and completion operations inspect the host filesystem.

No authentication, TLS, non-loopback listener, process isolation, quota, forced termination, arbitrary process-ID attachment, or hostile-code sandbox is provided. Use a separate process and security boundary for untrusted source.

Read the warning and procedure in [Attach to a running application](../how-to/attach-to-application.md).
