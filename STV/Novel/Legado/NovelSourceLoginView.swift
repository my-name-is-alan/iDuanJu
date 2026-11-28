import SwiftUI

/// 书源登录: supports Login UI v1, LegadoTeam Login UI v2 dynamic state protocol, and in-app WebView login.
struct NovelSourceLoginView: View {
    @Environment(\.dismiss) private var dismiss
    let source: LegadoSource
    @State private var fields: [LegadoLoginV2.V2Field] = []
    @State private var values: [String: String] = [:]
    @State private var fieldErrors: [String: String] = [:]
    @State private var v2StateJSON = "{}"
    @State private var busy = false
    @State private var message: String?
    @State private var loggedIn = false

    private var isV2: Bool { LegadoLoginV2.isV2(source) }

    var body: some View {
        let inputs = fields.filter { $0.type != "button" }, buttons = fields.filter { $0.type == "button" }
        let canLogin = !isV2 && LegadoClient.loginJS(source) != nil
        DSSheet(source.bookSourceName, subtitle: isV2 ? "书源登录 · Login UI v2" : "书源登录") {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    DSSection {
                        HStack(spacing: 10) {
                            Circle().fill(loggedIn ? STVTheme.ok : STVTheme.tertiary).frame(width: 8, height: 8)
                            Text(loggedIn ? "已登录 / 已保存登录信息" : "未登录").dsFont(15, .medium).foregroundStyle(loggedIn ? STVTheme.ok : STVTheme.secondary)
                            Spacer(minLength: 0)
                            if isV2 { DSStatus(text: "v2", tone: .accent) }
                            if loggedIn {
                                Button {
                                    LegadoSourceState.logout(source.bookSourceUrl)
                                    values = [:]; fieldErrors = [:]; loggedIn = false; message = "已清除登录信息与 Cookie"
                                } label: { MiniButtonLabel(title: "退出登录", tint: DSTone.danger, ground: DSTone.dangerSoft) }
                                    .buttonStyle(PressScale())
                            }
                        }
                        .dsRow()
                    }
                    if let url = LegadoClient.loginPage(source) {
                        DSSection(footer: "在网页里登录账号后点右上角「完成」，登录状态（Cookie）会用于这个书源的请求。") {
                            DSRow("在内置浏览器里登录", icon: "safari", chevron: true) {
                                Task { await LegadoBrowser.open(url: url, html: nil, title: "登录 " + source.bookSourceName, sourceURL: source.bookSourceUrl); refresh() }
                            }
                            .accessibilityIdentifier("login.browser")
                        }
                    }
                    if !inputs.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            ForEach(inputs, id: \.key) { f in input(f) }
                            if !isV2 {
                                Button { save(); message = "已保存" } label: { PillButtonPlain(title: "保存") }.buttonStyle(PressScale(scale: 0.98))
                            }
                        }
                        .padding(.horizontal, 20)
                    }
                    if !buttons.isEmpty {
                        DSSection {
                            ForEach(buttons, id: \.key) { f in
                                DSRow(f.name, icon: "bolt") {
                                    if isV2 { runV2(f.action ?? f.key) } else { runV1(f.action ?? "") }
                                }
                                .disabled(busy || (!isV2 && (f.action ?? "").isEmpty))
                            }
                        }
                    }
                    if let message {
                        Text(message).dsFont(14, .medium, relativeTo: .subheadline).foregroundStyle(STVTheme.accent)
                            .padding(.horizontal, 20).textSelection(.enabled)
                    }
                }
                .padding(.bottom, 16)
            }
        } footer: {
            if canLogin {
                DSSheetFooter {
                    Button { save(); runV1("typeof login === 'function' ? login() : ''") } label: {
                        ZStack {
                            PillButtonLabel(title: busy ? "" : "登录", expand: true)
                            if busy { DSSpinner(size: 18, tint: STVTheme.onAccent) }
                        }
                    }
                    .buttonStyle(PressScale(scale: 0.98))
                    .disabled(busy)
                    .accessibilityLabel("登录")
                    .accessibilityIdentifier("login.submit")
                }
            }
        }
        .dsSheetChrome([.large])
        .onAppear {
            values = LegadoSourceState.loginInfo(source.bookSourceUrl)
            loadFields(stateJSON: "{}")
            refresh()
        }
    }

    @ViewBuilder private func input(_ f: LegadoLoginV2.V2Field) -> some View {
        let b = Binding(
            get: { values[f.key] ?? "" },
            set: { values[f.key] = $0; fieldErrors.removeValue(forKey: f.key) }
        )
        VStack(alignment: .leading, spacing: 4) {
            switch f.type {
            case "label":
                Text(f.value ?? f.name).font(.system(size: 13)).foregroundStyle(STVTheme.secondary).padding(.horizontal, 4)
            case "password":
                DSTextField(f.name, text: b, icon: "lock", secure: true)
            case "select", "toggle":
                DSPicker(f.name, options: f.options, selection: b, style: .row) { $0 }
                    .background(STVTheme.glass, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            default:
                DSTextField(f.name, text: b, icon: "person").textInputAutocapitalization(.never).autocorrectionDisabled()
            }
            if let err = fieldErrors[f.key] {
                Text(err).font(.system(size: 12)).foregroundStyle(DSTone.danger).padding(.horizontal, 6)
            }
        }
    }

    private func loadFields(stateJSON: String) {
        let src = source
        if isV2 {
            busy = true
            Task {
                let loaded = (try? await LegadoClient.run { try LegadoLoginV2.render(src, stateJSON: stateJSON) }) ?? []
                fields = loaded
                for f in loaded {
                    if let v = f.value { values[f.key] = v }
                    else if values[f.key] == nil && !f.defaultValue.isEmpty && (f.type == "select" || f.type == "toggle") {
                        values[f.key] = f.defaultValue
                    }
                }
                busy = false
            }
        } else {
            let v1 = LegadoClient.loginFields(src)
            fields = v1.map { LegadoLoginV2.V2Field(key: $0.name, name: $0.name, type: $0.type, options: $0.options, action: $0.action, defaultValue: $0.defaultValue, value: nil) }
            for f in fields where values[f.key] == nil && !f.defaultValue.isEmpty && f.type == "select" { values[f.key] = f.defaultValue }
        }
    }

    private func refresh() { loggedIn = LegadoSourceState.isLoggedIn(source.bookSourceUrl) }

    private func save() {
        if !values.isEmpty { LegadoSourceState.setLoginInfo(source.bookSourceUrl, values) }
    }

    private func runV1(_ action: String) {
        let s = source
        busy = true; message = nil
        Task {
            do {
                let r = try await LegadoClient.run { try LegadoClient.runLogin(s, action) }
                message = r.isEmpty ? "已执行" : r
            } catch {
                message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
            busy = false
            refresh()
        }
    }

    private func runV2(_ action: String) {
        let s = source, st = v2StateJSON, form = values
        busy = true; message = nil; fieldErrors = [:]
        Task {
            do {
                let res = try await LegadoClient.run { try LegadoLoginV2.performAction(s, action: action, stateJSON: st, form: form) }
                fieldErrors = res.errors
                if let next = res.stateJSON { v2StateJSON = next }
                if let m = res.message, !m.isEmpty { message = m }
                refresh()
                if res.close { dismiss(); return }
                if let next = res.stateJSON { loadFields(stateJSON: next) }
            } catch {
                message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
            busy = false
        }
    }
}
