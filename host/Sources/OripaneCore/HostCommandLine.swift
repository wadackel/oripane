/// The arguments of `oripane-host`, checked before anything starts: a typo stops the process
/// instead of falling back to a default and starting a host the caller did not ask for.
public enum HostCommandLine {
    public static let usage = """
    usage: oripane-host <command> [options]

      -h, --help          print this usage; any command takes it
    When no command is given, or the first argument is an option, run is assumed.

    commands:
      run                 serve one client (default)
      probe               connect as a headless client and report what arrives
      pairings            list paired clients
      unpair-all          forget every paired client
      vectors <path>      write the golden vectors shared with the Android tests
      login-item on|off|status  add or remove the app at ~/Applications/Oripane.app as a login item
      display-check <step>  exercise the virtual display alone: create, stream, resize, recreate, match

    run options (also when opened as Oripane.app: Finder, open --args …, a login item):
      --port <n>               control port (default 47800); media uses the next one
      --source screen|test     capture the screen (default) or a synthetic pattern
      --mode extend|mirror|match  override the client's choice
      --scale <f>              stream at this fraction of the client's resolution, over the phone's
                               setting (otherwise the phone's, 1 by default)
      --fps <n>                capture rate, over the phone's setting (otherwise the phone's, 60)
      --codec h264|hevc        video codec, over what the phone offers (otherwise HEVC when it offers it)
      --bitrate <bps>          fixed bitrate instead of the adaptive default, over the client's limit
      --group <n>              fragments per parity packet (default 8)
      --drop <p>               drop this fraction of outgoing packets (testing)
      --auto-approve           accept pairing requests without asking (testing)
      --dry-run-input          log input instead of injecting it
      --no-bonjour             do not advertise on the local network; saved phones then reach this
                               host only through the addresses they stored, never by discovery

    probe options:
      --host <ip> --port <n> --width <n> --height <n> --mode extend|mirror|match --seconds <s> --out <file> --state <file> --input
      --resize <after-seconds>:<w>x<h>
      --codecs <list>          codecs to offer, comma-separated (e.g. hevc,h264; none by default, so H.264)

    environment:
      ORIPANE_STATE_DIR=<dir>          keep pairings, the host identity and the app's host.log in <dir>
                                       instead of Application Support and ~/Library/Logs/Oripane
      ORIPANE_FAKE_STREAM_FAILURE=<s>  end each stream <s> seconds after it starts as if capture or the
                                       encoder had failed (bye stream_failed); 0 fails before it starts
      ORIPANE_FAKE_MATCH=0             fail every attempt to mirror the main display, so run falls back
                                       to mirror for match and display-check match fails
      ORIPANE_FAKE_AWDL=1|0            report AWDL as on (1) or off (0) whatever awdl0 says
    """

    public static let displayCheckSteps = ["create", "stream", "resize", "recreate", "match"]

    public enum Outcome: Equatable {
        case help
        case parsed(Parsed)
        /// The message, without the usage.
        case failure(String)
    }

    public struct Parsed: Equatable {
        /// `run` when none was given.
        public var command: String
        /// Value options, keyed by name (`--port`); the first of a repeated one.
        public var values: [String: String]
        /// Flags given (`--dry-run-input`).
        public var flags: Set<String>
        /// `vectors <path>`, `login-item <on|off|status>`, `display-check <step>`.
        public var operand: String?
    }

    /// `args` is `CommandLine.arguments` without the executable. It neither exits nor prints.
    public static func parse(_ args: [String]) -> Outcome {
        let named = args.first.map { !$0.hasPrefix("-") } ?? false
        let command = named ? args[0] : "run"
        let rest = named ? args.dropFirst() : args[...]
        guard let spec = specs[command] else { return .failure("unknown command \(command)") }

        var i = rest.startIndex
        while i < rest.endIndex {
            if spec.values[rest[i]] != nil { i += 2; continue }
            if rest[i] == "-h" || rest[i] == "--help" { return .help }
            i += 1
        }

        var parsed = Parsed(command: command, values: [:], flags: [], operand: nil)
        i = rest.startIndex
        while i < rest.endIndex {
            let arg = rest[i]
            i += 1
            if let check = spec.values[arg] {
                guard i < rest.endIndex else { return .failure("\(arg) needs a value") }
                let value = rest[i]
                i += 1
                if let problem = check.problem(name: arg, value: value) { return .failure(problem) }
                // The first wins, so a wrapper can put the caller's arguments before its own defaults.
                if parsed.values[arg] == nil { parsed.values[arg] = value }
            } else if spec.flags.contains(arg) {
                parsed.flags.insert(arg)
            } else if arg.hasPrefix("-") {
                return .failure("unknown option \(arg) for \(command)")
            } else if parsed.operand == nil, let operand = spec.operand {
                if let choices = operand.choices, !choices.contains(arg) { return .failure("\(operand.needs), got \(arg)") }
                parsed.operand = arg
            } else {
                return .failure("unexpected argument \(arg) for \(command)")
            }
        }
        if parsed.operand == nil, let operand = spec.operand { return .failure(operand.needs) }
        return .parsed(parsed)
    }

    private enum Check {
        case any, port, int, number, resize, codecs
        case choice([String])

        func problem(name: String, value: String) -> String? {
            switch self {
            case .any:
                return nil
            case .port:
                // The media port is the next one, so 65535 has no room for it.
                guard let port = UInt16(value), (1...65534).contains(port) else {
                    return "\(name) must be 1–65534 (media uses the next port), got \(value)"
                }
                return nil
            case .int:
                return Int(value) == nil ? "\(name) must be an integer, got \(value)" : nil
            case .number:
                return Double(value) == nil ? "\(name) must be a number, got \(value)" : nil
            case .resize:
                let parts = value.split(separator: ":", omittingEmptySubsequences: false)
                let size = parts.count == 2 ? parts[1].split(separator: "x", omittingEmptySubsequences: false) : []
                // Counted as well: Double also reads hex floats such as 0x1p3, which carry an x of their own.
                let ok = size.count == 2 && value.filter { $0 == "x" }.count == 1
                    && Double(parts[0]) != nil && Int(size[0]) != nil && Int(size[1]) != nil
                return ok ? nil : "\(name) must be <after-seconds>:<w>x<h>, got \(value)"
            case .codecs:
                let ok = value.split(separator: ",", omittingEmptySubsequences: false).allSatisfy { !$0.isEmpty }
                return ok ? nil : "\(name) must be a comma-separated list of codecs, got \(value)"
            case let .choice(choices):
                guard !choices.contains(value) else { return nil }
                let list = choices.dropLast().joined(separator: ", ") + " or " + choices.last!
                return "\(name) must be \(list), got \(value)"
            }
        }
    }

    private struct Operand {
        /// The failure when it is missing; `, got <value>` follows when it is not one of `choices`.
        var needs: String
        var choices: [String]?
    }

    private struct Spec {
        var values: [String: Check] = [:]
        var flags: Set<String> = []
        var operand: Operand?
    }

    private static let mode = Check.choice(["extend", "mirror", "match"])

    private static let specs: [String: Spec] = [
        "run": Spec(
            values: [
                "--port": .port, "--source": .choice(["screen", "test"]), "--mode": mode, "--scale": .number,
                "--fps": .int, "--codec": .choice(["h264", "hevc"]), "--bitrate": .int, "--group": .int,
                "--drop": .number,
            ],
            flags: ["--auto-approve", "--dry-run-input", "--no-bonjour"]
        ),
        "probe": Spec(
            values: [
                "--host": .any, "--port": .port, "--width": .int, "--height": .int, "--mode": mode,
                "--seconds": .number, "--out": .any, "--state": .any, "--resize": .resize, "--codecs": .codecs,
            ],
            flags: ["--input"]
        ),
        "pairings": Spec(),
        "unpair-all": Spec(),
        "vectors": Spec(operand: Operand(needs: "vectors needs <path>")),
        "login-item": Spec(operand: Operand(needs: "login-item needs on, off or status", choices: ["on", "off", "status"])),
        "display-check": Spec(operand: Operand(
            needs: "display-check needs one of " + displayCheckSteps.joined(separator: ", "),
            choices: displayCheckSteps
        )),
    ]
}
