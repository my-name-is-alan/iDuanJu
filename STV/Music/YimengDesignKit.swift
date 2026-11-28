import SwiftUI
import UIKit

// MARK: - Yimeng Original Design System Tokens (1:1 with yimeng/ios/Yimeng/Theme.swift & Doodle.swift)

enum YMTheme {
    static let lime = Color(red: 0.784, green: 0.961, blue: 0.259)       // #C8F542
    static let limeLight = Color(red: 0.310, green: 0.420, blue: 0.000)  // #4F6B00
    static let heart = Color(red: 1.0, green: 0.373, blue: 0.659)        // #FF5FA8
    static let gold = Color(red: 0.949, green: 0.718, blue: 0.020)       // #F2B705
    static let silver = Color(red: 0.604, green: 0.643, blue: 0.698)     // #9AA4B2
    static let bronze = Color(red: 0.780, green: 0.467, blue: 0.227)     // #C7773A
    static let paper = Color(red: 0.969, green: 0.945, blue: 0.902)      // #F7F1E6
    static let paperInk = Color(white: 0.10)

    static func accent(_ s: ColorScheme) -> Color { s == .dark ? lime : limeLight }
    static func base(_ s: ColorScheme) -> Color {
        s == .dark
            ? Color(red: 0.039, green: 0.039, blue: 0.047)
            : Color(red: 0.965, green: 0.961, blue: 0.945)
    }
    static func fg(_ s: ColorScheme) -> Color { s == .dark ? .white : Color(white: 0.07) }
    static func secondary(_ s: ColorScheme) -> Color { s == .dark ? Color.white.opacity(0.60) : Color.black.opacity(0.50) }
    static func fill(_ s: ColorScheme) -> Color { s == .dark ? Color.white.opacity(0.08) : Color.black.opacity(0.06) }
    static func divider(_ s: ColorScheme) -> Color { s == .dark ? Color.white.opacity(0.06) : Color.black.opacity(0.08) }
    static func dashBorder(_ s: ColorScheme) -> Color { s == .dark ? Color.white.opacity(0.10) : Color.black.opacity(0.12) }
}

/// Deterministic PRNG (SplitMix64) so every track gets its own reproducible hand-drawn doodle ornament.
struct YMSeededRNG {
    private var state: UInt64
    init(_ seed: UInt64) { self.state = seed == 0 ? 0x9E3779B97F4A7C15 : seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
    mutating func range(_ lo: Double, _ hi: Double) -> Double {
        lo + (hi - lo) * (Double(next() >> 11) / Double(1 << 53))
    }
}

// MARK: - Hand-Drawn Procedural Shapes (WobblyFrame, ScribbleCircle, ScribbleUnderline, Crown, Wave, Ticket)

struct YMWobblyFrame: Shape {
    var seed: UInt64 = 7
    var inset: CGFloat = 4
    func path(in r: CGRect) -> Path {
        var rng = YMSeededRNG(seed)
        let rr = r.insetBy(dx: inset, dy: inset)
        let pts = [
            CGPoint(x: rr.minX, y: rr.minY),
            CGPoint(x: rr.maxX, y: rr.minY),
            CGPoint(x: rr.maxX, y: rr.maxY),
            CGPoint(x: rr.minX, y: rr.maxY)
        ]
        var p = Path()
        for pass in 0..<2 {
            let amp = min(3.2, max(1.2, r.width / 42))
            let j: (CGFloat) -> CGFloat = { v in
                v + CGFloat(rng.range(-Double(amp), Double(amp))) * (pass == 0 ? 1.0 : 1.55)
            }
            p.move(to: CGPoint(x: j(pts[0].x), y: j(pts[0].y)))
            for i in 1...4 {
                let a = pts[(i - 1) % 4], b = pts[i % 4]
                let mid = CGPoint(x: j((a.x + b.x) / 2), y: j((a.y + b.y) / 2))
                p.addQuadCurve(to: CGPoint(x: j(b.x), y: j(b.y)), control: mid)
            }
        }
        return p
    }
}

struct YMScribbleCircle: Shape {
    var seed: UInt64 = 19
    func path(in r: CGRect) -> Path {
        var rng = YMSeededRNG(seed)
        let cx = r.midX, cy = r.midY
        let rx = r.width * 0.48, ry = r.height * 0.45
        var p = Path()
        let steps = 42
        for i in 0...steps {
            let t = Double(i) / Double(steps)
            let a = t * 2 * .pi * 1.08 - 0.35
            let wob = 1.0 + 0.05 * sin(a * 3) + rng.range(-0.025, 0.025)
            let pt = CGPoint(x: cx + CGFloat(cos(a) * wob) * rx, y: cy + CGFloat(sin(a) * wob) * ry)
            if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
        }
        return p
    }
}

struct YMScribbleUnderline: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.minX + 2, y: r.midY + 1.5))
        p.addQuadCurve(to: CGPoint(x: r.maxX - 2, y: r.midY - 0.8), control: CGPoint(x: r.midX, y: r.minY + 1))
        return p
    }
}

struct YMCrownScribble: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.minX + 1, y: r.maxY - 1))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.14, y: r.minY + r.height * 0.25))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.36, y: r.maxY * 0.62))
        p.addLine(to: CGPoint(x: r.midX, y: r.minY + 0.5))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.64, y: r.maxY * 0.62))
        p.addLine(to: CGPoint(x: r.maxX - r.width * 0.14, y: r.minY + r.height * 0.25))
        p.addLine(to: CGPoint(x: r.maxX - 1, y: r.maxY - 1))
        p.closeSubpath()
        return p
    }
}

struct YMCrayonWave: Shape {
    var waves: Int = 4
    var phase: CGFloat = 0
    var animatableData: CGFloat {
        get { phase }
        set { phase = newValue }
    }
    func path(in r: CGRect) -> Path {
        var p = Path()
        let count = max(24, waves * 14)
        for i in 0...count {
            let t = CGFloat(i) / CGFloat(count)
            let x = r.minX + t * r.width
            let y = r.midY + sin(t * .pi * 2 * CGFloat(waves) - phase) * (r.height * 0.36)
            if i == 0 { p.move(to: CGPoint(x: x, y: y)) } else { p.addLine(to: CGPoint(x: x, y: y)) }
        }
        return p
    }
}

/// Continuously flowing double-pass hand-drawn crayon wave (pauses automatically under Reduce Motion).
struct YMAnimatedWave: View {
    var waves: Int = 4
    var color: Color
    var lineWidth: CGFloat = 2.0
    var speed: Double = 2.2
    var active: Bool = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var phase: CGFloat = 0

    var body: some View {
        ZStack {
            YMCrayonWave(waves: waves, phase: phase)
                .stroke(color.opacity(0.32), style: StrokeStyle(lineWidth: lineWidth * 0.85, lineCap: .round, lineJoin: .round))
                .offset(x: 0.7, y: 0.8)
            YMCrayonWave(waves: waves, phase: phase)
                .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
        }
        .onAppear { restartWave() }
        .compatOnChange(of: speed) { _ in restartWave() }
        .compatOnChange(of: active) { _ in restartWave() }
        .compatOnChange(of: reduceMotion) { _ in restartWave() }
    }

    private func restartWave() {
        var tx = Transaction()
        tx.disablesAnimations = true
        withTransaction(tx) { phase = 0 }
        guard active && !reduceMotion else { return }
        let period = max(0.8, (Double.pi * 2.0) / max(speed, 0.25))
        DispatchQueue.main.async {
            withAnimation(.linear(duration: period).repeatForever(autoreverses: false)) {
                phase = .pi * 2
            }
        }
    }
}

/// Unified Yimeng hand-drawn double-pass crayon tab glyphs (1:1 with YMIcons.swift).
enum YMTabGlyph: Int {
    case home = 0
    case tvbox = 1
    case discover = 2
    case music = 3
    case profile = 4
    case search = 5

    func path(in size: CGFloat) -> Path {
        let unit: Path
        switch self {
        case .home:
            var p = Path()
            p.move(to: CGPoint(x: 0.50, y: 0.14))
            p.addLine(to: CGPoint(x: 0.88, y: 0.46))
            p.addLine(to: CGPoint(x: 0.78, y: 0.46))
            p.addLine(to: CGPoint(x: 0.78, y: 0.86))
            p.addLine(to: CGPoint(x: 0.56, y: 0.86))
            p.addLine(to: CGPoint(x: 0.56, y: 0.62))
            p.addLine(to: CGPoint(x: 0.44, y: 0.62))
            p.addLine(to: CGPoint(x: 0.44, y: 0.86))
            p.addLine(to: CGPoint(x: 0.22, y: 0.86))
            p.addLine(to: CGPoint(x: 0.22, y: 0.46))
            p.addLine(to: CGPoint(x: 0.12, y: 0.46))
            p.closeSubpath()
            unit = p
        case .tvbox:
            var p = Path()
            // Retro rabbit-ear antennae + rounded TV bezel
            p.move(to: CGPoint(x: 0.34, y: 0.14)); p.addLine(to: CGPoint(x: 0.50, y: 0.30))
            p.move(to: CGPoint(x: 0.66, y: 0.14)); p.addLine(to: CGPoint(x: 0.50, y: 0.30))
            p.addRoundedRect(in: CGRect(x: 0.14, y: 0.30, width: 0.72, height: 0.54), cornerSize: CGSize(width: 0.14, height: 0.14))
            // Play triangle inside TV
            p.move(to: CGPoint(x: 0.44, y: 0.47)); p.addLine(to: CGPoint(x: 0.62, y: 0.57)); p.addLine(to: CGPoint(x: 0.44, y: 0.67)); p.closeSubpath()
            unit = p
        case .discover:
            var p = Path()
            let r: CGFloat = 0.08
            p.addRoundedRect(in: CGRect(x: 0.16, y: 0.16, width: 0.28, height: 0.28), cornerSize: CGSize(width: r, height: r))
            p.addRoundedRect(in: CGRect(x: 0.56, y: 0.16, width: 0.28, height: 0.28), cornerSize: CGSize(width: r, height: r))
            p.addRoundedRect(in: CGRect(x: 0.16, y: 0.56, width: 0.28, height: 0.28), cornerSize: CGSize(width: r, height: r))
            p.addRoundedRect(in: CGRect(x: 0.56, y: 0.56, width: 0.28, height: 0.28), cornerSize: CGSize(width: r, height: r))
            unit = p
        case .music:
            var p = Path()
            p.addEllipse(in: CGRect(x: 0.17, y: 0.63, width: 0.22, height: 0.18))
            p.addEllipse(in: CGRect(x: 0.55, y: 0.56, width: 0.22, height: 0.18))
            p.move(to: CGPoint(x: 0.38, y: 0.71)); p.addLine(to: CGPoint(x: 0.38, y: 0.22))
            p.addLine(to: CGPoint(x: 0.76, y: 0.16)); p.addLine(to: CGPoint(x: 0.76, y: 0.64))
            unit = p
        case .profile:
            var p = Path()
            p.addEllipse(in: CGRect(x: 0.32, y: 0.13, width: 0.36, height: 0.36))
            p.move(to: CGPoint(x: 0.16, y: 0.87))
            p.addQuadCurve(to: CGPoint(x: 0.84, y: 0.87), control: CGPoint(x: 0.50, y: 0.49))
            unit = p
        case .search:
            var p = Path()
            p.addEllipse(in: CGRect(x: 0.18, y: 0.14, width: 0.52, height: 0.52))
            p.move(to: CGPoint(x: 0.62, y: 0.58)); p.addLine(to: CGPoint(x: 0.86, y: 0.84))
            unit = p
        }
        return unit.applying(CGAffineTransform(scaleX: size, y: size))
    }
}

/// Double-pass crayon stroke icon with animated stroke draw-in when activated (1:1 with Yimeng `YMIcon`).
struct YMTabIcon: View {
    let glyph: YMTabGlyph
    var size: CGFloat = 21
    var active: Bool = false
    @Environment(\.colorScheme) private var scheme
    @State private var drawn: CGFloat = 1

    var body: some View {
        let ink = active ? YMTheme.accent(scheme) : YMTheme.fg(scheme).opacity(0.68)
        let path = glyph.path(in: size)
        let lw: CGFloat = active ? 1.85 : 1.55
        ZStack {
            path
                .trim(from: 0, to: drawn)
                .stroke(ink.opacity(0.32), style: StrokeStyle(lineWidth: lw * 0.82, lineCap: .round, lineJoin: .round))
                .offset(x: 0.65, y: 0.7)
            path
                .trim(from: 0, to: drawn)
                .stroke(ink, style: StrokeStyle(lineWidth: lw, lineCap: .round, lineJoin: .round))
        }
        .frame(width: size, height: size)
        .scaleEffect(active ? 1.04 : 1.0)
        .animation(.spring(response: 0.30, dampingFraction: 0.68), value: active)
        .compatOnChange(of: active) { isOn in
            guard isOn else { drawn = 1; return }
            drawn = 0.08
            withAnimation(.easeOut(duration: 0.38)) { drawn = 1 }
        }
    }
}

/// Crystal-clear Yimeng floating dock surface: deep obsidian glass in dark mode (zero muddy grey fog!), warm translucent cream in light mode.
struct YMDockSurface<S: InsettableShape>: View {
    let shape: S
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack {
            #if compiler(>=6.2)
            if #available(iOS 26.0, *) {
                Color.clear
                    .glassEffect(.clear, in: shape)
                    .overlay(
                        shape.fill(scheme == .dark ? Color(red: 0.055, green: 0.055, blue: 0.068).opacity(0.76) : Color(red: 0.98, green: 0.975, blue: 0.96).opacity(0.92))
                    )
            } else {
                shape.fill(.ultraThinMaterial)
                    .overlay(shape.fill(scheme == .dark ? Color(red: 0.05, green: 0.05, blue: 0.06).opacity(0.74) : Color(red: 0.98, green: 0.975, blue: 0.96).opacity(0.90)))
            }
            #else
            shape.fill(.ultraThinMaterial)
                .overlay(shape.fill(scheme == .dark ? Color(red: 0.05, green: 0.05, blue: 0.06).opacity(0.74) : Color(red: 0.98, green: 0.975, blue: 0.96).opacity(0.90)))
            #endif
            shape.strokeBorder(
                scheme == .dark ? Color.white.opacity(0.13) : Color.black.opacity(0.09),
                style: StrokeStyle(lineWidth: 1.0, lineCap: .round, dash: [6, 4])
            )
        }
        .shadow(color: .black.opacity(scheme == .dark ? 0.45 : 0.12), radius: 16, y: 6)
    }
}

/// Delicate 4-point hand-drawn sparkle accent used next to editorial section titles.
struct YMDoodleSparkle: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        let cx = r.midX, cy = r.midY
        let rx = r.width * 0.48, ry = r.height * 0.48
        p.move(to: CGPoint(x: cx, y: cy - ry))
        p.addQuadCurve(to: CGPoint(x: cx + rx, y: cy), control: CGPoint(x: cx + rx * 0.14, y: cy - ry * 0.14))
        p.addQuadCurve(to: CGPoint(x: cx, y: cy + ry), control: CGPoint(x: cx + rx * 0.14, y: cy + ry * 0.14))
        p.addQuadCurve(to: CGPoint(x: cx - rx, y: cy), control: CGPoint(x: cx - rx * 0.14, y: cy + ry * 0.14))
        p.addQuadCurve(to: CGPoint(x: cx, y: cy - ry), control: CGPoint(x: cx - rx * 0.14, y: cy - ry * 0.14))
        p.closeSubpath()
        return p
    }
}

/// Physical perforated ticket stub (`今日乐签`) with semicircular top/bottom punch notches at `notchX`.
struct YMTicketShape: Shape {
    var cornerRadius: CGFloat = 16
    var notchX: CGFloat = 84
    var notchRadius: CGFloat = 7
    func path(in r: CGRect) -> Path {
        var p = Path()
        let cr = min(cornerRadius, min(r.width, r.height) / 2)
        let nx = min(max(notchX, cr + notchRadius + 4), r.width - cr - notchRadius - 4)
        p.move(to: CGPoint(x: r.minX + cr, y: r.minY))
        p.addLine(to: CGPoint(x: r.minX + nx - notchRadius, y: r.minY))
        p.addArc(center: CGPoint(x: r.minX + nx, y: r.minY), radius: notchRadius, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: true)
        p.addLine(to: CGPoint(x: r.maxX - cr, y: r.minY))
        p.addArc(center: CGPoint(x: r.maxX - cr, y: r.minY + cr), radius: cr, startAngle: .degrees(-90), endAngle: .degrees(0), clockwise: false)
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY - cr))
        p.addArc(center: CGPoint(x: r.maxX - cr, y: r.maxY - cr), radius: cr, startAngle: .degrees(0), endAngle: .degrees(90), clockwise: false)
        p.addLine(to: CGPoint(x: r.minX + nx + notchRadius, y: r.maxY))
        p.addArc(center: CGPoint(x: r.minX + nx, y: r.maxY), radius: notchRadius, startAngle: .degrees(0), endAngle: .degrees(180), clockwise: true)
        p.addLine(to: CGPoint(x: r.minX + cr, y: r.maxY))
        p.addArc(center: CGPoint(x: r.minX + cr, y: r.maxY - cr), radius: cr, startAngle: .degrees(90), endAngle: .degrees(180), clockwise: false)
        p.addLine(to: CGPoint(x: r.minX, y: r.minY + cr))
        p.addArc(center: CGPoint(x: r.minX + cr, y: r.minY + cr), radius: cr, startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false)
        p.closeSubpath()
        return p
    }
}

// MARK: - Signature Yimeng Components (YMCard, DoodleTabs, RankBadge, ChartCollage, YMToggle, DreamField)

struct YMCardModifier: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    var radius: CGFloat = 20
    func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(YMTheme.fill(scheme))
            )
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(YMTheme.dashBorder(scheme), style: StrokeStyle(lineWidth: 1, dash: [6, 4]))
            )
    }
}

extension View {
    func ymCard(radius: CGFloat = 20) -> some View { modifier(YMCardModifier(radius: radius)) }
}

/// Hand-drawn lime crayon-wave toggle (`YMToggleStyle` from yimeng).
struct YMToggleStyle: ToggleStyle {
    @Environment(\.colorScheme) private var scheme
    func makeBody(configuration: Configuration) -> some View {
        HStack {
            configuration.label
            Spacer(minLength: 10)
            Button {
                withAnimation(.spring(response: 0.26, dampingFraction: 0.78)) {
                    configuration.isOn.toggle()
                }
            } label: {
                ZStack(alignment: configuration.isOn ? .trailing : .leading) {
                    Capsule()
                        .fill(configuration.isOn ? YMTheme.accent(scheme) : YMTheme.fill(scheme))
                        .overlay(
                            Capsule().strokeBorder(
                                configuration.isOn ? Color.clear : YMTheme.fg(scheme).opacity(0.24),
                                style: StrokeStyle(lineWidth: 1, dash: [3, 3])
                            )
                        )
                    if configuration.isOn {
                        YMCrayonWave(waves: 3)
                            .stroke(Color.black.opacity(0.32), style: StrokeStyle(lineWidth: 1.4, lineCap: .round))
                            .frame(width: 24, height: 10)
                            .padding(.trailing, 24)
                    }
                    Circle()
                        .fill(Color.white)
                        .frame(width: 22, height: 22)
                        .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
                        .padding(3)
                }
                .frame(width: 50, height: 28)
            }
            .buttonStyle(.plain)
        }
    }
}

/// Signature Yimeng `DoodleTabs` with animated stroke-by-stroke `ScribbleCircle` + `#C8F542` highlighter `ScribbleUnderline`.
struct YMDoodleTabs<T: Hashable>: View {
    let items: [T]
    @Binding var selection: T
    let title: (T) -> String
    @Environment(\.colorScheme) private var scheme
    @State private var drawn: CGFloat = 1

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 14) {
                ForEach(items, id: \.self) { item in
                    let active = item == selection
                    Button {
                        guard !active else { return }
                        selection = item
                    } label: {
                        VStack(spacing: 2) {
                            ZStack {
                                if active {
                                    YMScribbleCircle(seed: UInt64(abs(title(item).hashValue) + 17))
                                        .trim(from: 0, to: drawn)
                                        .stroke(YMTheme.fg(scheme).opacity(0.55), style: StrokeStyle(lineWidth: 1.25, lineCap: .round))
                                        .frame(width: max(46, CGFloat(title(item).count * 16 + 18)), height: 28)
                                }
                                Text(title(item))
                                    .font(.system(size: 14.5, weight: active ? .bold : .medium))
                                    .foregroundStyle(active ? YMTheme.fg(scheme) : YMTheme.secondary(scheme))
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                            }
                            if active {
                                YMScribbleUnderline()
                                    .trim(from: 0, to: drawn)
                                    .stroke(YMTheme.accent(scheme).opacity(0.90), style: StrokeStyle(lineWidth: 4.5, lineCap: .round))
                                    .frame(width: max(26, CGFloat(title(item).count * 13)), height: 6)
                            } else {
                                Color.clear.frame(height: 6)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .compatOnChange(of: selection) { _ in
            drawn = 0
            withAnimation(.easeOut(duration: 0.48)) { drawn = 1 }
        }
    }
}

/// Gold / Silver / Bronze hand-drawn circle rank badge with tiny crown on #1.
struct YMRankBadge: View {
    let rank: Int
    private var tint: Color {
        switch rank {
        case 1: YMTheme.gold
        case 2: YMTheme.silver
        case 3: YMTheme.bronze
        default: .secondary
        }
    }
    var body: some View {
        ZStack(alignment: .top) {
            if rank == 1 {
                YMCrownScribble()
                    .stroke(YMTheme.gold, style: StrokeStyle(lineWidth: 1.3, lineCap: .round, lineJoin: .round))
                    .frame(width: 11, height: 7)
                    .offset(y: -8)
            }
            ZStack {
                YMScribbleCircle(seed: UInt64(rank * 31 + 5))
                    .fill(tint.opacity(0.18))
                YMScribbleCircle(seed: UInt64(rank * 31 + 5))
                    .stroke(tint, lineWidth: 1.3)
                Text("\(rank)")
                    .font(.system(size: 11, weight: .heavy, design: .rounded))
                    .foregroundStyle(tint)
            }
            .frame(width: 20, height: 20)
        }
    }
}

/// Three fanned Polaroid covers for chart rows (`ChartCollage`).
struct YMChartCollage: View {
    let urls: [String]
    var body: some View {
        let picks = Array(urls.prefix(3))
        ZStack {
            if picks.count >= 3 {
                coverTile(picks[2]).rotationEffect(.degrees(-8)).offset(x: -2, y: 12)
            }
            if picks.count >= 2 {
                coverTile(picks[1]).rotationEffect(.degrees(7)).offset(x: 12, y: 0)
            }
            coverTile(picks.first ?? "").offset(x: -8, y: -8)
        }
        .frame(width: 96, height: 96)
    }

    private func coverTile(_ url: String) -> some View {
        YMCoverImage(url: url, corner: 9)
            .frame(width: 64, height: 64)
            .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous).stroke(Color.white.opacity(0.85), lineWidth: 1.8))
            .shadow(color: .black.opacity(0.26), radius: 5, y: 2)
    }
}

/// Lime-outlined `试听` capsule & subtle lossless `QualityBadge`.
struct YMPreviewCapsule: View {
    @Environment(\.colorScheme) private var scheme
    var label: String = "试听"
    var body: some View {
        Text(label)
            .font(.system(size: 10, weight: .semibold))
            .foregroundStyle(YMTheme.accent(scheme))
            .padding(.horizontal, 6)
            .frame(height: 17)
            .overlay(Capsule().stroke(YMTheme.accent(scheme), lineWidth: 1))
    }
}

struct YMQualityBadge: View {
    let label: String
    let lossless: Bool
    var body: some View {
        HStack(spacing: 5) {
            if lossless {
                Circle().fill(YMTheme.lime).frame(width: 5.5, height: 5.5)
            }
            Text(label)
                .font(.system(size: 11, weight: .semibold, design: .rounded))
                .tracking(0.4)
                .foregroundStyle(.white.opacity(0.92))
        }
        .padding(.horizontal, 10)
        .frame(height: 23)
        .background(Capsule().fill(Color.white.opacity(0.10)))
        .overlay(Capsule().stroke(Color.white.opacity(0.35), lineWidth: 0.9))
    }
}

private final class YMCoverCache {
    static let shared = YMCoverCache()
    private let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.countLimit = 180
        c.totalCostLimit = 64 * 1024 * 1024
        return c
    }()
    private let lock = NSLock()
    private var inflight: [String: Task<UIImage?, Never>] = [:]

    func cached(_ key: String) -> UIImage? {
        cache.object(forKey: key as NSString)
    }

    func load(_ urlString: String) async -> UIImage? {
        if let hit = cached(urlString) { return hit }
        guard let url = URL(string: urlString), !urlString.isEmpty else { return nil }
        let task: Task<UIImage?, Never> = lock.withLock {
            if let existing = inflight[urlString] { return existing }
            let created = Task.detached(priority: .userInitiated) { [weak self] () -> UIImage? in
                defer {
                    self?.lock.withLock { _ = self?.inflight.removeValue(forKey: urlString) }
                }
                var req = URLRequest(url: url, cachePolicy: .returnCacheDataElseLoad, timeoutInterval: 15)
                req.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
                guard let (data, resp) = try? await MusicDirectNet.session.data(for: req),
                      let http = resp as? HTTPURLResponse,
                      (200...299).contains(http.statusCode),
                      let img = UIImage(data: data)
                else { return nil }
                self?.cache.setObject(img, forKey: urlString as NSString, cost: data.count)
                return img
            }
            inflight[urlString] = created
            return created
        }
        return await task.value
    }
}

/// Lightweight cached cover image with shared NSCache + deterministic gradient fallback.
struct YMCoverImage: View {
    let url: String
    var corner: CGFloat = 12
    var isCircle: Bool = false

    @State private var loaded: UIImage?

    var body: some View {
        let img = loaded ?? YMCoverCache.shared.cached(url)
        Group {
            if let img {
                Image(uiImage: img)
                    .resizable()
                    .scaledToFill()
            } else {
                placeholder
            }
        }
        .clipped()
        .modifier(YMClipShapeModifier(corner: corner, isCircle: isCircle))
        .task(id: url) {
            if let hit = YMCoverCache.shared.cached(url) {
                loaded = hit
                return
            }
            loaded = nil
            if let fetched = await YMCoverCache.shared.load(url) {
                loaded = fetched
            }
        }
    }

    private var placeholder: some View {
        let hue = Double(abs(url.hashValue) % 360) / 360.0
        return LinearGradient(
            colors: [Color(hue: hue, saturation: 0.45, brightness: 0.28), Color(hue: (hue + 0.12).truncatingRemainder(dividingBy: 1), saturation: 0.35, brightness: 0.14)],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .overlay {
            Image(systemName: "music.note")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white.opacity(0.35))
        }
    }
}

private struct YMClipShapeModifier: ViewModifier {
    let corner: CGFloat
    let isCircle: Bool
    func body(content: Content) -> some View {
        if isCircle { content.clipShape(Circle()) }
        else { content.clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous)) }
    }
}

/// Cover-derived breathing DreamField backdrop for Now Playing & iPad Vinyl Stage.
struct YMDreamField: View, Equatable {
    let seed: UInt64
    var dimForLyrics: Bool = false

    private var palette: [Color] {
        var rng = YMSeededRNG(seed)
        let h1 = rng.range(0.0, 1.0)
        let h2 = (h1 + rng.range(0.08, 0.22)).truncatingRemainder(dividingBy: 1.0)
        let h3 = (h1 + rng.range(0.32, 0.55)).truncatingRemainder(dividingBy: 1.0)
        return [
            Color(hue: h1, saturation: 0.62, brightness: 0.34),
            Color(hue: h2, saturation: 0.58, brightness: 0.28),
            Color(hue: h3, saturation: 0.52, brightness: 0.24),
            Color(red: 0.05, green: 0.05, blue: 0.07)
        ]
    }

    var body: some View {
        let cols = palette
        ZStack {
            ZStack {
                Color(red: 0.04, green: 0.04, blue: 0.055)
                RadialGradient(
                    colors: [cols[0].opacity(0.92), cols[0].opacity(0.28), .clear],
                    center: UnitPoint(x: 0.18, y: 0.16),
                    startRadius: 10,
                    endRadius: 340
                )
                RadialGradient(
                    colors: [cols[1].opacity(0.88), cols[1].opacity(0.24), .clear],
                    center: UnitPoint(x: 0.86, y: 0.34),
                    startRadius: 10,
                    endRadius: 310
                )
                RadialGradient(
                    colors: [cols[2].opacity(0.86), cols[2].opacity(0.22), .clear],
                    center: UnitPoint(x: 0.25, y: 0.78),
                    startRadius: 10,
                    endRadius: 330
                )
                LinearGradient(
                    colors: [.black.opacity(0.16), .clear, .black.opacity(0.42)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
            .drawingGroup()

            Color(red: 0.047, green: 0.039, blue: 0.055)
                .opacity(dimForLyrics ? 0.54 : 0)
                .animation(.easeOut(duration: 0.18), value: dimForLyrics)
        }
        .ignoresSafeArea()
    }
}

/// Deterministic hand-drawn doodle halo around Now Playing album cover (`DoodleLayer`).
struct YMDoodleCoverHalo: View {
    let seed: UInt64
    var body: some View {
        ZStack {
            YMWobblyFrame(seed: seed, inset: -10)
                .stroke(Color.white.opacity(0.55), style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))
            YMWobblyFrame(seed: seed ^ 0x55, inset: -14)
                .stroke(YMTheme.lime.opacity(0.32), style: StrokeStyle(lineWidth: 1.1, lineCap: .round, dash: [10, 8]))
            Image(systemName: "sparkle")
                .font(.system(size: 14, weight: .light))
                .foregroundStyle(YMTheme.lime.opacity(0.85))
                .offset(x: 138, y: -124)
            Image(systemName: "moon.stars")
                .font(.system(size: 13, weight: .light))
                .foregroundStyle(Color.white.opacity(0.65))
                .offset(x: -136, y: -116)
        }
        .allowsHitTesting(false)
    }
}

/// Spinning 33⅓ rpm vinyl record + hand-drawn spring tonearm for iPad Landscape Now Playing.
struct YMVinylTurntable: View {
    let coverURL: String
    let isPlaying: Bool
    let seed: UInt64
    @State private var spinAngle: Double = 0

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(Color.black.opacity(0.36))
                .overlay(
                    YMWobblyFrame(seed: seed, inset: 6)
                        .stroke(Color.white.opacity(0.32), lineWidth: 1.4)
                )

            ZStack {
                // Grooved black vinyl disc
                Circle()
                    .fill(RadialGradient(colors: [Color(white: 0.14), Color(white: 0.05), Color(white: 0.08)], center: .center, startRadius: 48, endRadius: 145))
                    .frame(width: 268, height: 268)
                    .overlay {
                        ForEach(0..<8, id: \.self) { i in
                            Circle()
                                .stroke(Color.white.opacity(0.06), lineWidth: 0.8)
                                .frame(width: CGFloat(130 + i * 18), height: CGFloat(130 + i * 18))
                        }
                    }
                    .overlay(
                        AngularGradient(
                            colors: [.clear, .white.opacity(0.14), .clear, .white.opacity(0.10), .clear],
                            center: .center
                        )
                        .clipShape(Circle())
                    )
                    .overlay {
                        YMCoverImage(url: coverURL, isCircle: true)
                            .frame(width: 112, height: 112)
                            .overlay(Circle().stroke(Color.white.opacity(0.28), lineWidth: 1.5))
                    }
                    .overlay {
                        Circle().fill(Color.black).frame(width: 14, height: 14)
                            .overlay(Circle().stroke(Color.white.opacity(0.35), lineWidth: 1))
                    }
                    .rotationEffect(.degrees(spinAngle))

                // Hand-drawn pivoting tonearm
                VStack(spacing: 0) {
                    Circle().fill(Color.white.opacity(0.22)).frame(width: 24, height: 24)
                        .overlay(Circle().stroke(Color.white.opacity(0.65), lineWidth: 1.5))
                    Rectangle().fill(Color.white.opacity(0.82)).frame(width: 3, height: 126)
                    RoundedRectangle(cornerRadius: 3).fill(YMTheme.lime).frame(width: 11, height: 18)
                }
                .rotationEffect(.degrees(isPlaying ? 18 : -4), anchor: .top)
                .offset(x: 112, y: -38)
                .animation(.spring(response: 0.55, dampingFraction: 0.72), value: isPlaying)
            }
        }
        .frame(width: 340, height: 340)
        .onAppear { syncSpin(playing: isPlaying) }
        .onChange(of: isPlaying) { playing in syncSpin(playing: playing) }
    }

    private func syncSpin(playing: Bool) {
        if playing {
            withAnimation(.linear(duration: 18).repeatForever(autoreverses: false)) {
                spinAngle = 360
            }
        } else {
            var tx = Transaction()
            tx.disablesAnimations = true
            withTransaction(tx) {
                spinAngle = 0
            }
        }
    }
}
