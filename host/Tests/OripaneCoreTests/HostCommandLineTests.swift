import Testing
@testable import OripaneCore

@Suite struct HostCommandLineTests {
    private func parsed(_ args: [String]) -> HostCommandLine.Parsed? {
        if case let .parsed(p) = HostCommandLine.parse(args) { return p }
        return nil
    }

    private func failure(_ args: [String]) -> String? {
        if case let .failure(message) = HostCommandLine.parse(args) { return message }
        return nil
    }

    private func split(_ line: String) -> [String] {
        line.split(separator: " ").map(String.init)
    }

    @Test func noArgumentsRunsWithNoOptions() {
        #expect(HostCommandLine.parse([]) == .parsed(.init(command: "run", values: [:], flags: [], operand: nil)))
    }

    @Test(arguments: [
        "--help", "-h", "run --help", "display-check -h", "run --port 47900 --help",
        "run --prot 1 --help", "probe --out --host --help",
    ])
    func helpWinsWhereverItStandsOutsideAValue(_ line: String) {
        #expect(HostCommandLine.parse(split(line)) == .help)
    }

    @Test func helpAfterAValueOptionIsThatValue() {
        #expect(parsed(split("probe --host --help"))?.values["--host"] == "--help")
    }

    @Test func anUnknownCommandFailsBeforeHelpIsLookedFor() {
        #expect(failure(split("frobnicate --help"))?.contains("unknown command frobnicate") == true)
    }

    @Test func runKeepsTheValueOfASourceOption() {
        let p = parsed(split("--source test"))
        #expect(p?.command == "run")
        #expect(p?.values["--source"] == "test")
    }

    @Test(arguments: [
        ("--prot 47900", "--prot"),
        ("run --input", "--input"),
        ("probe --auto-approve", "--auto-approve"),
        ("run --port=47900", "--port=47900"),
        ("run --port", "--port"),
        ("run --mode foo", "foo"),
        ("run --source x", "x"),
        ("run --codec h265", "h265"),
        ("run --fps abc", "abc"),
        ("run --scale x", "x"),
        ("probe --resize 3:100", "3:100"),
        ("probe --resize 3:100xx200", "3:100xx200"),
        ("probe --resize 3:100x200x", "3:100x200x"),
        ("probe --resize 0x1p3:100x200", "0x1p3:100x200"),
        ("probe --codecs ,", ","),
        ("probe --codecs hevc,", "hevc,"),
        ("frobnicate", "frobnicate"),
        ("pairings x", "x"),
        ("vectors a b", "b"),
        ("login-item maybe", "maybe"),
        ("display-check bogus", "bogus"),
    ])
    func aBadArgumentFailsNamingIt(_ line: String, _ culprit: String) {
        let message = failure(split(line))
        #expect(message?.contains(culprit) == true, "\(line) → \(message ?? "no failure")")
    }

    @Test(arguments: ["65535", "0", "abc"])
    func aPortOutsideTheRangeFailsWithTheRangeMessage(_ port: String) {
        #expect(failure(["run", "--port", port]) == "--port must be 1–65534 (media uses the next port), got \(port)")
    }

    @Test func messagesFollowTheirForm() {
        #expect(failure(split("run --source x")) == "--source must be screen or test, got x")
        #expect(failure(split("run --mode foo")) == "--mode must be extend, mirror or match, got foo")
        #expect(failure(split("run --scale x")) == "--scale must be a number, got x")
        #expect(failure(split("run --fps abc")) == "--fps must be an integer, got abc")
        #expect(failure(split("run --port")) == "--port needs a value")
        #expect(failure(split("run --prot 1")) == "unknown option --prot for run")
        #expect(failure(split("pairings x")) == "unexpected argument x for pairings")
        #expect(failure(split("probe --resize 3:100")) == "--resize must be <after-seconds>:<w>x<h>, got 3:100")
        #expect(failure(split("probe --codecs ,")) == "--codecs must be a comma-separated list of codecs, got ,")
        #expect(failure(split("login-item maybe")) == "login-item needs on, off or status, got maybe")
        #expect(failure(split("display-check bogus")) == "display-check needs one of create, stream, resize, recreate, match, got bogus")
    }

    @Test func aMissingOperandFailsNamingTheCommand() {
        #expect(failure(["vectors"]) == "vectors needs <path>")
        #expect(failure(["login-item"]) == "login-item needs on, off or status")
        #expect(failure(["display-check"]) == "display-check needs one of create, stream, resize, recreate, match")
    }

    @Test func aValueTakesTheNextArgumentEvenWhenItLooksLikeAnOption() {
        #expect(parsed(split("probe --out --input"))?.values["--out"] == "--input")
        #expect(parsed(split("probe --out --input"))?.flags.isEmpty == true)
    }

    @Test func wellFormedResizeAndCodecsPass() {
        #expect(parsed(split("probe --resize 3:100x200"))?.values["--resize"] == "3:100x200")
        #expect(parsed(split("probe --codecs hevc,h264"))?.values["--codecs"] == "hevc,h264")
    }

    @Test func theFirstOfARepeatedValueWins() {
        #expect(parsed(split("run --port 47940 --port 47800"))?.values["--port"] == "47940")
    }

    @Test func aLaterRepeatIsStillChecked() {
        #expect(failure(split("run --port 47940 --port 99999"))?.contains("99999") == true)
    }

    @Test func aRepeatedFlagPasses() {
        #expect(parsed(split("run --dry-run-input --dry-run-input"))?.flags == ["--dry-run-input"])
    }

    @Test(arguments: [
        ("vectors /tmp/spec/vectors.json", "vectors", "/tmp/spec/vectors.json"),
        ("login-item off", "login-item", "off"),
        ("login-item status", "login-item", "status"),
        ("run", "run", nil),
        ("run --mode mirror", "run", nil),
        ("run --mode match", "run", nil),
        ("pairings", "pairings", nil),
        ("unpair-all", "unpair-all", nil),
        ("display-check create", "display-check", "create"),
        ("display-check stream", "display-check", "stream"),
        ("display-check resize", "display-check", "resize"),
        ("display-check recreate", "display-check", "recreate"),
        ("display-check match", "display-check", "match"),
        ("run --source test --auto-approve", "run", nil),
        ("probe --codecs hevc,h264", "probe", nil),
        ("probe", "probe", nil),
        ("--source test --dry-run-input --no-bonjour --port 47900", "run", nil),
        ("run --port 47950 --port 47940 --source test --dry-run-input --no-bonjour --auto-approve", "run", nil),
    ] as [(String, String, String?)])
    func everyDocumentedCallParses(_ line: String, _ command: String, _ operand: String?) {
        let p = parsed(split(line))
        #expect(p?.command == command, "\(line)")
        #expect(p?.operand == operand, "\(line)")
    }

    @Test func theTestCopyArgumentsReachTheirValues() {
        let p = parsed(split("--source test --dry-run-input --no-bonjour --port 47900"))
        #expect(p?.values == ["--source": "test", "--port": "47900"])
        #expect(p?.flags == ["--dry-run-input", "--no-bonjour"])
    }

    @Test func usageNamesEveryOptionHelpAndTheEnvironment() {
        let names = [
            "--port", "--source", "--mode", "--scale", "--fps", "--codec", "--bitrate", "--group", "--drop",
            "--auto-approve", "--dry-run-input", "--no-bonjour",
            "--host", "--width", "--height", "--seconds", "--out", "--state", "--resize", "--codecs", "--input",
            "-h, --help",
            "ORIPANE_STATE_DIR", "ORIPANE_FAKE_STREAM_FAILURE", "ORIPANE_FAKE_MATCH", "ORIPANE_FAKE_AWDL",
        ]
        for name in names { #expect(HostCommandLine.usage.contains(name), "\(name)") }
    }
}
