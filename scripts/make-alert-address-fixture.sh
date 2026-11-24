#!/bin/zsh
# Writes PolarisTests/Fixtures/alert-server-addresses.json: for each address below, what the iOS
# app's own `AlertServer.address` (Polaris/Services/AlertServer.swift) gives, an address or the
# problem's name. It compiles that file with a small driver on this Mac, so it needs Xcode's Swift
# toolchain; macOS's Foundation is the same URL code iOS uses. Both platforms' tests assert every
# case, so Android's port must spell and refuse addresses exactly as iOS does.
#
# Run it again after changing AlertServer.swift or the list, then run both test suites.
set -euo pipefail
cd "${0:A:h}/.."

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

cat > "$work/main.swift" <<'SWIFT'
import Foundation

let cases = [
    // Accepted, and how they are spelt.
    "https://alerts.example.org", "https://alerts_1.example.org", "https://a.example.org/", "HTTPS://A.Example.org:443/Base/",
    "  https://a.example.org//  ", "https://a.example.org:8443/x/y", "https://a.example.org/Path/To", "https://a.example.org/path%20x",
    "https://alerts.example.com:443/", "https://a.example.org:", "https://a.example.org:99999", "https://a.example.org:0",
    "https://a.example.org/x y", "https://ä.example.org", "https://a.example.org/ä", "https://a.example.org/%zz",
    "https://[::1]",
    // Plain http only for this computer.
    "http://localhost:8787", "http://LOCALHOST", "http://localhost:80", "http://127.0.0.1:8787", "http://[::1]:8787",
    "http://a.example.org", "http://a.example.org:80",
    // A LAN address, written in the documentation range (RFC 5737): plain http to anything but
    // this computer is refused, private network or not.
    "http://203.0.113.5:8787",
    // Refused shapes.
    "https://user@a.example.org", "https://user:pw@a.example.org", "https://a.example.org?x=1", "https://a.example.org?",
    "https://a.example.org#f", "https://alerts.example.com/#a", "https://a b.org", "https://exa mple.org/x", "ftp://a.example.org",
    "a.example.org", "https://", "", "javascript:alert(1)", "https://a.example.org:abc", "https://a.example.org:1234567890123456789",
]

var out: [[String: String]] = []
for input in cases {
    do { out.append(["input": input, "url": try AlertServer.address(input).absoluteString]) }
    catch { out.append(["input": input, "problem": "\(error)"]) }
}
let file: [String: Any] = [
    "about": "Alert server addresses: what iOS AlertServer.address gives for each input. Written by scripts/make-alert-address-fixture.sh from the Swift code; both platforms assert every case.",
    "cases": out,
]
let data = try JSONSerialization.data(withJSONObject: file, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
FileHandle.standardOutput.write(data)
FileHandle.standardOutput.write("\n".data(using: .utf8)!)
SWIFT

xcrun swiftc -O Polaris/Services/AlertServer.swift "$work/main.swift" -o "$work/addresses" 2>/dev/null
"$work/addresses" > PolarisTests/Fixtures/alert-server-addresses.json
echo "Wrote PolarisTests/Fixtures/alert-server-addresses.json"
