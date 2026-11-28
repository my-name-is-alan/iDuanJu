import XCTest
@testable import STV

final class HongguoQRLoginTests: XCTestCase {
    func testBuildCheckQRURLPrefersCapturedSameOriginURL() {
        let captured = "https://hongguoduanju.com/passport/web/check_qrconnect/?token=abc_123&a_bogus=xyz"
        let url = HongguoWebAccount.buildCheckQRURL(checkURL: captured, token: "fallback")
        XCTAssertEqual(url?.absoluteString, captured)
    }

    func testBuildCheckQRURLConstructsCleanURLFromTokenWhenCapturedMissing() {
        let url = HongguoWebAccount.buildCheckQRURL(checkURL: nil, token: "416fb527_lf")
        XCTAssertNotNil(url)
        let comps = url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false) }
        XCTAssertEqual(comps?.host, "hongguoduanju.com")
        XCTAssertEqual(comps?.path, "/passport/web/check_qrconnect/")
        XCTAssertEqual(comps?.queryItems?.first(where: { $0.name == "token" })?.value, "416fb527_lf")
        XCTAssertEqual(comps?.queryItems?.first(where: { $0.name == "aid" })?.value, "513755")
    }

    func testBuildCheckQRURLRejectsForeignHost() {
        let url = HongguoWebAccount.buildCheckQRURL(checkURL: "https://evil.example/check_qrconnect?token=x", token: "tok1")
        XCTAssertEqual(url?.host, "hongguoduanju.com")
    }

    func testParseQRConnectResponseHandlesAllStatuses() {
        let waiting = Data(#"{"data":{"error_code":0,"status":"new"},"message":"success"}"#.utf8)
        XCTAssertEqual(HongguoWebAccount.parseQRConnectResponse(waiting), .waiting)

        let scanned = Data(#"{"data":{"error_code":0,"status":"scanned"},"message":"success"}"#.utf8)
        XCTAssertEqual(HongguoWebAccount.parseQRConnectResponse(scanned), .scanned)

        let confirmed = Data(#"{"data":{"error_code":0,"status":"confirmed","redirect_url":"https://hongguoduanju.com/"},"message":"success"}"#.utf8)
        XCTAssertEqual(HongguoWebAccount.parseQRConnectResponse(confirmed), .confirmed(redirectURL: "https://hongguoduanju.com/"))

        let confirmedNumeric = Data(#"{"data":{"error_code":0,"status":3,"redirect_url":""},"message":"success"}"#.utf8)
        XCTAssertEqual(HongguoWebAccount.parseQRConnectResponse(confirmedNumeric), .confirmed(redirectURL: nil))

        let expired = Data(#"{"data":{"error_code":0,"status":"expired"},"message":"success"}"#.utf8)
        XCTAssertEqual(HongguoWebAccount.parseQRConnectResponse(expired), .expired)

        let refused = Data(#"{"data":{"error_code":0,"status":"refuse"},"message":"success"}"#.utf8)
        XCTAssertEqual(HongguoWebAccount.parseQRConnectResponse(refused), .expired)
    }

    func testMergeCookieMapReplacesAndDropsExpiredCookies() {
        let old = HTTPCookie(properties: [
            .domain: ".hongguoduanju.com",
            .path: "/",
            .name: "sessionid",
            .value: "old-val"
        ])!
        let fresh = HTTPCookie(properties: [
            .domain: ".hongguoduanju.com",
            .path: "/",
            .name: "sessionid",
            .value: "new-val"
        ])!
        let dropped = HTTPCookie(properties: [
            .domain: ".hongguoduanju.com",
            .path: "/",
            .name: "temp",
            .value: "x",
            .expires: Date(timeIntervalSinceNow: -60)
        ])!
        let tempAlive = HTTPCookie(properties: [
            .domain: ".hongguoduanju.com",
            .path: "/",
            .name: "temp",
            .value: "alive"
        ])!

        let merged = HongguoWebAccount.mergeCookieMap(
            ["sessionid": old, "temp": tempAlive],
            incoming: [fresh, dropped]
        )
        XCTAssertEqual(merged["sessionid"]?.value, "new-val")
        XCTAssertNil(merged["temp"])
    }
}
