import SwiftUI
import UIKit

// i短剧 redesign building blocks (ui-redesign/styles.css + video.css). Colours come from STVTheme; every size here
// is the spec's pt value. Screens compose these instead of styling controls ad hoc.
// Floating controls (bars, round buttons, capsules) are Liquid Glass through GlassKit; cards, rows and chips inside
// scrolling content stay plain. Form controls and overlays live in DSControls.swift / DSOverlays.swift.

enum DS {
    /// One spring for selection changes (tabs, segments, chips) and one for panels / bars.
    static let select = Animation.spring(response: 0.32, dampingFraction: 0.86)
    static let panel = Animation.spring(response: 0.42, dampingFraction: 0.88)
    static let press = Animation.spring(response: 0.22, dampingFraction: 0.8)
    /// Reduce Motion: cross-fade instead of springs.
    static func motion(_ a: Animation, reduce: Bool) -> Animation { reduce ? .easeOut(duration: 0.18) : a }

    @MainActor private static let selectionHaptic = UISelectionFeedbackGenerator()
    /// Haptic tick for a meaningful selection (segment, tab, switch, reader theme). Never on plain navigation.
    @MainActor static func tick() { selectionHaptic.selectionChanged() }

    /// Puts the keyboard away.
    @MainActor static func endEditing() { UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) }

    /// Spaced numbers like the spec: "512.6 万", "全 82 集".
    static func count(_ n: Int) -> String {
        if n >= 100_000_000 { return String(format: "%.1f 亿", Double(n) / 100_000_000) }
        if n >= 10_000 { return String(format: "%.1f 万", Double(n) / 10_000) }
        return "\(n)"
    }
    static func episodes(_ s: HGSeries) -> String? {
        if let sub = s.subtitle, sub.contains("更新至") || sub.hasPrefix("全") { return spaced(sub) }
        if s.source.isLongVideo { return s.duration > 0 ? Fmt.clock(s.duration) : s.episodeCount > 1 ? "全 \(s.episodeCount) 集" : nil }
        return s.episodeCount > 0 ? "全 \(s.episodeCount) 集" : nil
    }
    /// "更新至24集" → "更新至 24 集".
    static func spaced(_ t: String) -> String {
        var out = "", prevDigit: Bool? = nil
        for ch in t {
            let d = ch.isNumber || ch == "."
            if let p = prevDigit, p != d, ch != " ", out.last != " " { out.append(" ") }
            out.append(ch); prevDigit = ch == " " ? nil : d
        }
        return out
    }
    /// Grid subtitle: "512.6 万热度", else "都市 · 玄幻", else the episode count.
    static func heat(_ s: HGSeries) -> String {
        if s.source.isLongVideo { return Fmt.meta(s) }
        if s.playCount > 0 { return count(s.playCount) + "热度" }
        return genres(s) ?? episodes(s) ?? ""
    }
    static func genres(_ s: HGSeries, _ n: Int = 2) -> String? {
        let g = s.tags.prefix(n).joined(separator: " · ")
        return g.isEmpty ? nil : g
    }
    /// Home shelf subtitle: "悬疑 · 96 集".
    static func shelfMeta(_ s: HGSeries) -> String {
        if s.source.isLongVideo { return Fmt.meta(s) }
        let parts = [s.tags.first, s.episodeCount > 0 ? "\(s.episodeCount) 集" : nil].compactMap { $0 }
        return parts.isEmpty ? heat(s) : parts.joined(separator: " · ")
    }
}

// MARK: - Type

/// Spec pt size that follows Dynamic Type.
private struct DSFont: ViewModifier {
    @ScaledMetric private var size: CGFloat
    let weight: Font.Weight
    init(_ size: CGFloat, _ weight: Font.Weight, _ style: Font.TextStyle) {
        _size = ScaledMetric(wrappedValue: size, relativeTo: style)
        self.weight = weight
    }
    func body(content: Content) -> some View { content.font(.system(size: size, weight: weight)) }
}

extension View {
    /// System font at the spec's pt size, scaled with Dynamic Type.
    func dsFont(_ size: CGFloat, _ weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> some View {
        modifier(DSFont(size, weight, style))
    }
}

// MARK: - Surfaces

/// `.card`: card fill + 1 px border; light mode adds the soft drop shadow.
private struct DSCardGround: ViewModifier {
    let radius: CGFloat
    @Environment(\.colorScheme) private var scheme
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.onGlass) private var onGlass
    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        content.background {
            // The shadow is part of the static ground shape. A `.shadow` on the live content made every card an
            // offscreen pass per frame while scrolling, in dark mode too (where it is fully transparent).
            if onGlass {
                // Inside a glass sheet a card is only a quiet fill, so the sheet stays one piece of glass.
                shape.fill(STVTheme.glass)
            } else if scheme == .light {
                shape.fill(STVTheme.card.shadow(.drop(color: STVTheme.shadowSoft, radius: 14, y: 10)))
            } else {
                shape.fill(STVTheme.card)
            }
            if !onGlass || contrast == .increased {
                shape.strokeBorder(
                    contrast == .increased ? STVTheme.secondary.opacity(0.5) : STVTheme.cardBorder,
                    style: StrokeStyle(lineWidth: 1.15, lineCap: .round, dash: [6, 4])
                )
            }
        }
    }
}

/// Floating glass bar. Only the pre-iOS 26 fallback needs a drop shadow: the system material lifts itself.
private struct DSBarGround: ViewModifier {
    let radius: CGFloat
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.liquidGlass(corner: radius)
        } else {
            content.liquidGlass(corner: radius).shadow(color: STVTheme.barShadow, radius: 15, y: 10)
        }
    }
}

/// Root pages without a header: content dissolves under the status bar once it scrolls.
private struct StatusFade: ViewModifier {
    @State private var scrolled = false
    func body(content: Content) -> some View {
        content
            .compatOnScrolled(threshold: 6) { if scrolled != $0 { scrolled = $0 } }
            .overlay(alignment: .top) {
                Color.clear.frame(height: 2).frame(maxWidth: .infinity)
                    .background { LiquidEdgeFade(visible: scrolled) }
                    .allowsHitTesting(false)
            }
    }
}

extension View {
    /// On a root page's ScrollView (its content carries `compatScrollContent()`).
    func dsStatusFade() -> some View { modifier(StatusFade()) }
    func dsCard(_ radius: CGFloat = 20) -> some View { modifier(DSCardGround(radius: radius)) }
    /// Floating glass bar (tab bar, sticky filter strip, play bar).
    func dsBar(_ radius: CGFloat) -> some View { modifier(DSBarGround(radius: radius)) }
    /// Pinned header: content dissolves into the page colour under it (no hairline, no slab).
    func dsPinned(_ on: Bool) -> some View { background { LiquidEdgeFade(visible: on) } }
    /// Page ground with the spec's top glow (`.phone.amb` / `.amb-cool`), bleeding seamlessly behind the Expanded rail.
    func dsAmbient(cool: Bool = false, visible: Bool = true) -> some View {
        modifier(DSAmbient(cool: cool, visible: visible))
    }
}

private struct DSAmbient: ViewModifier {
    let cool: Bool
    let visible: Bool
    @Environment(\.railLeadingInset) private var railInset
    func body(content: Content) -> some View {
        content
            .background(alignment: .top) {
                AmbientGlow(cool: cool)
                    .opacity(visible ? 1 : 0)
                    .animation(.easeOut(duration: 0.25), value: visible)
                    .padding(.leading, -railInset)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
            }
            .background {
                STVTheme.bg
                    .padding(.leading, -railInset)
                    .ignoresSafeArea()
            }
    }
}

/// radial-gradient(130% 100% at 25% -8%, c0 0, c1 36–40 %, transparent 72 %) over the top 380 pt.
struct AmbientGlow: View {
    var cool = false
    var body: some View {
        GeometryReader { g in
            let w = g.size.width, h: CGFloat = 380
            let c = cool ? STVTheme.ambientCool : STVTheme.ambientWarm
            RadialGradient(stops: [.init(color: c[0], location: 0), .init(color: c[1], location: cool ? 0.40 : 0.36),
                                   .init(color: c[1].opacity(0), location: 0.72)],
                           center: .center, startRadius: 0, endRadius: h)
                .frame(width: h * 2, height: h * 2)
                .scaleEffect(x: 1.3 * w / h, y: 1)
                .position(x: 0.25 * w, y: -0.08 * h)
        }
        .frame(height: 380)
        .clipped()
    }
}

// MARK: - Level 1: underline text tabs (`.ttabs`)

struct UnderlineTabs<T: Hashable>: View {
    let items: [T]
    @Binding var selection: T
    let label: (T) -> String
    var small = false
    var idPrefix = "tabs"
    @Namespace private var ns
    var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: small ? 24 : 22) {
            ForEach(items, id: \.self) { it in
                let on = it == selection
                Button { if !on { DS.tick(); withAnimation(DS.select) { selection = it } } } label: {
                    UnderlineTabLabel(title: label(it), on: on, small: small, ns: ns)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("\(idPrefix).\(label(it))")
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .frame(height: small ? 34 : 40, alignment: small ? .center : .bottom)
    }
}

struct UnderlineTabLabel: View {
    let title: String
    let on: Bool
    var small = false
    var ink: Color = STVTheme.text
    var inkOff: Color = STVTheme.secondary
    let ns: Namespace.ID
    @State private var drawn: CGFloat = 1
    var body: some View {
        let activeSize: CGFloat = small ? 18 : 24
        let idleSize: CGFloat = small ? 16 : 17
        let activeWeight: Font.Weight = small ? .bold : .heavy
        let anchor: UnitPoint = small ? .bottom : .bottomLeading
        let align: Alignment = small ? .bottom : .bottomLeading

        // Hidden sizer drives neighbor spacing while the overlaid `.fixedSize()` layers morph smoothly
        // via `scaleEffect`, preventing CoreText from clipping CJK characters (e.g. "AI" + "剧") mid-spring.
        Text(title)
            .font(.system(size: on ? activeSize : idleSize, weight: on ? activeWeight : .regular))
            .tracking(on && !small ? 0.2 : 0)
            .lineLimit(1)
            .fixedSize()
            .hidden()
            .overlay(alignment: align) {
                Text(title)
                    .font(.system(size: activeSize, weight: activeWeight))
                    .tracking(!small ? 0.2 : 0)
                    .foregroundStyle(ink)
                    .lineLimit(1)
                    .fixedSize()
                    .scaleEffect(on ? 1 : idleSize / activeSize, anchor: anchor)
                    .opacity(on ? 1 : 0)
            }
            .overlay(alignment: align) {
                Text(title)
                    .font(.system(size: idleSize, weight: .regular))
                    .foregroundStyle(inkOff)
                    .lineLimit(1)
                    .fixedSize()
                    .scaleEffect(on ? activeSize / idleSize : 1, anchor: anchor)
                    .opacity(on ? 0 : 1)
            }
            .accessibilityLabel(title)
            .padding(.bottom, small ? 0 : 7)
            .background {
                if on && !small {
                    YMScribbleCircle(seed: UInt64(abs(title.hashValue) % 89 + 11))
                        .trim(from: 0, to: drawn)
                        .stroke(STVTheme.accent.opacity(0.32), style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                        .padding(.horizontal, -9)
                        .padding(.top, -3)
                        .padding(.bottom, 1)
                }
            }
            .overlay(alignment: align) {
                if on {
                    YMScribbleUnderline()
                        .trim(from: 0, to: drawn)
                        .stroke(STVTheme.accent, style: StrokeStyle(lineWidth: small ? 2.6 : 3.4, lineCap: .round))
                        .frame(width: small ? 26 : 36, height: 6)
                        .offset(x: small ? 0 : 1, y: small ? 8 : 1)
                }
            }
            .contentShape(Rectangle())
            .compatOnChange(of: on) { active in
                guard active else { drawn = 1; return }
                drawn = 0
                withAnimation(.easeOut(duration: 0.46)) { drawn = 1 }
            }
    }
}

// MARK: - Level 2: segmented control (`.seg`)

/// Capsule track with one sliding thumb. The thumb is a single Liquid Glass piece that travels between segments
/// (plain fill when the control itself sits on glass).
struct SegmentedTabs<T: Hashable>: View {
    let items: [T]
    @Binding var selection: T
    let label: (T) -> String
    var idPrefix = "seg"
    @Environment(\.onGlass) private var onGlass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorSchemeContrast) private var contrast

    private var index: Int { items.firstIndex(of: selection) ?? 0 }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items, id: \.self) { it in
                let on = it == selection
                Button {
                    guard !on else { return }
                    DS.tick()
                    withAnimation(DS.motion(DS.select, reduce: reduceMotion)) { selection = it }
                } label: {
                    Text(label(it))
                        .font(.system(size: 13.5, weight: on ? .bold : .medium))
                        .foregroundStyle(on ? STVTheme.bg : STVTheme.secondary)
                        .lineLimit(1).minimumScaleFactor(0.75)
                        .padding(.horizontal, 6)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("\(idPrefix).\(label(it))")
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .background { thumb }
        .padding(3.5)
        .frame(height: 40)
        .background(STVTheme.glass, in: Capsule())
        .overlay(
            Capsule().strokeBorder(
                contrast == .increased ? STVTheme.secondary.opacity(0.5) : STVTheme.glassBorder,
                style: StrokeStyle(lineWidth: 1.0, lineCap: .round, dash: [5, 3.5])
            )
        )
    }

    private var thumb: some View {
        GeometryReader { g in
            let w = g.size.width / CGFloat(max(items.count, 1))
            Capsule()
                .fill(STVTheme.text)
                .shadow(color: STVTheme.shadowThumb, radius: 3, y: 1.5)
                .frame(width: w)
                .offset(x: w * CGFloat(index))
        }
        .allowsHitTesting(false)
    }
}

// MARK: - Level 3: tone chips (`.chip`)

struct ToneChipStyle: ButtonStyle {
    var on: Bool
    /// "全部" / range chips: neutral thumb when selected instead of the accent tint.
    var neutral = false
    var small = false
    /// Chips in a floating header: Liquid Glass capsules. Chips inside scrolling content stay plain fills.
    var floating = false
    func makeBody(configuration: Configuration) -> some View {
        ToneChipBody(label: configuration.label, on: on, neutral: neutral, small: small, floating: floating, pressed: configuration.isPressed)
    }
}

private struct ToneChipBody<L: View>: View {
    let label: L
    let on: Bool
    let neutral: Bool
    let small: Bool
    let floating: Bool
    let pressed: Bool
    @Environment(\.isEnabled) private var enabled
    @Environment(\.onGlass) private var onGlass
    @Environment(\.colorSchemeContrast) private var contrast

    private var fill: Color {
        on ? STVTheme.text : (pressed ? STVTheme.glassBorder : STVTheme.glass)
    }
    private var rim: Color {
        if on { return neutral ? .clear : STVTheme.accent.opacity(0.55) }
        return contrast == .increased ? STVTheme.secondary.opacity(0.5) : STVTheme.hairline
    }

    var body: some View {
        label
            .dsFont(small ? 12.5 : 13.5, on ? .bold : .medium, relativeTo: .subheadline)
            .foregroundStyle(on ? STVTheme.bg : (pressed ? STVTheme.text : STVTheme.secondary))
            .lineLimit(1)
            .padding(.horizontal, small ? 12 : 14)
            .frame(minHeight: small ? 28 : 32)
            .modifier(Ground(glass: floating && !onGlass && !on, on: on, neutral: neutral, fill: fill, rim: rim))
            .contentShape(Capsule())
            .scaleEffect(pressed ? 0.96 : 1)
            .opacity(enabled ? 1 : 0.38)
            .animation(DS.press, value: pressed)
    }

    private struct Ground: ViewModifier {
        let glass: Bool, on: Bool, neutral: Bool
        let fill: Color, rim: Color
        func body(content: Content) -> some View {
            if glass {
                content.liquidGlassCapsule(tone: on && !neutral ? .tinted(STVTheme.accentSoft) : .regular, interactive: true)
            } else {
                content.background {
                    Capsule().fill(on && neutral ? AnyShapeStyle(fill.shadow(.drop(color: STVTheme.shadowThumb, radius: 3, y: 1.5))) : AnyShapeStyle(fill))
                    if on {
                        Capsule().strokeBorder(rim, lineWidth: 1.0)
                    } else {
                        Capsule().strokeBorder(rim, style: StrokeStyle(lineWidth: 1.0, lineCap: .round, dash: [4.5, 3.5]))
                    }
                }
            }
        }
    }
}

/// Chip with an inline count (`.chip em`).
struct CountLabel: View {
    let title: String
    let count: Int
    var body: some View {
        HStack(spacing: 5) {
            Text(title)
            Text("\(count)").font(.system(size: 12, weight: .medium)).opacity(0.72)
        }
    }
}

/// One horizontally scrolling row of tone chips; the right edge fades out when the row overflows.
struct ChipRow<T: Hashable>: View {
    let items: [T]
    @Binding var selection: T
    let label: (T) -> String
    /// Items rendered with the neutral selected look ("全部").
    var neutral: (T) -> Bool = { _ in false }
    var fade = true
    var small = false
    var leading: CGFloat = 16
    var idPrefix = "chip"
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                // Lazy: a dimension can carry 40+ options and several rows sit in one panel.
                LazyHStack(spacing: 8) {
                    ForEach(items, id: \.self) { it in
                        Button { DS.tick(); withAnimation(DS.select) { selection = it } } label: { Text(label(it)) }
                            .buttonStyle(ToneChipStyle(on: it == selection, neutral: neutral(it), small: small))
                            .id(it)
                            .accessibilityIdentifier("\(idPrefix).\(label(it))")
                            .accessibilityAddTraits(it == selection ? .isSelected : [])
                    }
                }
                .padding(.leading, leading).padding(.trailing, 24)
                // The scroll view and the fade mask clip to the row; this margin keeps the selected neutral chip's
                // shadow (`.chip.all.on`) inside them, and the negative padding below gives the space back.
                .padding(.vertical, 6)
            }
            .compatScrollBounceBasedOnSize(axes: .horizontal)
            .mask { if fade { FadeMask() } else { Color.black } }
            .padding(.vertical, -6)
            .compatOnChange(of: selection) { it in withAnimation(DS.select) { proxy.scrollTo(it, anchor: .center) } }
        }
    }
}

/// `.chips.fade`: opaque to 84 %, then fades to the right edge.
struct FadeMask: View {
    var body: some View {
        LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.84), .init(color: .clear, location: 1)],
                       startPoint: .leading, endPoint: .trailing)
    }
}

// MARK: - Buttons

/// `.cbtn`: round glass button (`.acc` accent glyph, `.ov` over imagery).
struct RoundIconButton: View {
    enum Tone { case glass, accent, overlay }
    let systemName: String
    var tone: Tone = .glass
    var size: CGFloat = 40
    var action: () -> Void
    @Environment(\.onGlass) private var onGlass
    var body: some View {
        Button(action: action) {
            Image(systemName: systemName).font(.system(size: size * 0.40, weight: .semibold))
                .foregroundStyle(tone == .accent ? STVTheme.accent : tone == .overlay ? .white : STVTheme.text)
                .frame(width: size, height: size)
                .modifier(LiquidCircleGround(onGlass: onGlass, onMedia: tone == .overlay))
                // 44 pt target around the smaller visual.
                .contentShape(Circle().inset(by: min(0, (size - 44) / 2)))
        }
        .buttonStyle(GlassPress())
    }
}

/// `.capbtn`: 36 pt glass capsule with an optional leading / trailing icon.
struct CapsuleLabel: View {
    let title: String
    var icon: String? = nil
    var trailingIcon: String? = nil
    @Environment(\.onGlass) private var onGlass
    var body: some View {
        let label = HStack(spacing: 6) {
            if let icon { Image(systemName: icon).font(.system(size: 13, weight: .semibold)).foregroundStyle(STVTheme.secondary) }
            Text(title).font(.system(size: 14, weight: .semibold)).foregroundStyle(STVTheme.text).lineLimit(1)
            if let trailingIcon { Image(systemName: trailingIcon).font(.system(size: 11, weight: .semibold)).foregroundStyle(STVTheme.secondary) }
        }
        .padding(.horizontal, 14).frame(height: 40)
        .contentShape(Capsule())
        if onGlass { label.background(Capsule().fill(STVTheme.glass)) } else { label.liquidGlassCapsule(interactive: true) }
    }
}

/// `.btn`: accent / white(ink) capsule button; `small` is the 32 pt variant.
/// `glass`: the primary action of a detail page, in the content or floating — tinted Liquid Glass instead of a flat fill.
struct PillButtonLabel: View {
    enum Tone { case accent, ink, white }
    let title: String
    var icon: String? = nil
    var tone: Tone = .accent
    var small = false
    var height: CGFloat? = nil
    var expand = false
    var glass = false
    @Environment(\.onGlass) private var onGlass
    private var fill: Color { tone == .accent ? STVTheme.accent : tone == .ink ? STVTheme.text : Color.white }
    var body: some View {
        let h = height ?? (small ? 32 : 48)
        let label = HStack(spacing: 6) {
            if let icon { Image(systemName: icon).font(.system(size: small ? 10.5 : 12.5, weight: .bold)) }
            Text(title).font(.system(size: small ? 13 : 15.5, weight: .bold)).lineLimit(1).minimumScaleFactor(0.8)
        }
        .foregroundStyle(tone == .accent ? STVTheme.onAccent : tone == .ink ? STVTheme.bg : Color(red: 0.039, green: 0.039, blue: 0.047))
        .padding(.horizontal, small ? 14 : 22)
        .frame(maxWidth: expand ? .infinity : nil)
        .frame(height: h)
        .contentShape(Capsule())
        if glass && !onGlass { label.liquidGlassCapsule(tone: .tinted(fill), interactive: true) } else { label.background(Capsule().fill(fill)) }
    }
}

/// Secondary action next to a `PillButtonLabel`: neutral fill, same metrics.
struct PillButtonPlain: View {
    let title: String
    var icon: String? = nil
    var height: CGFloat = 48
    var expand = true
    var body: some View {
        HStack(spacing: 6) {
            if let icon { Image(systemName: icon).font(.system(size: 14, weight: .semibold)) }
            Text(title).font(.system(size: 16, weight: .medium)).lineLimit(1).minimumScaleFactor(0.8)
        }
        .foregroundStyle(STVTheme.text)
        .padding(.horizontal, 18)
        .frame(maxWidth: expand ? .infinity : nil)
        .frame(height: height)
        .background(Capsule().fill(STVTheme.glass))
        .contentShape(Capsule())
    }
}

/// Scales to 0.97 while pressed (spec "按下").
struct PressScale: ButtonStyle {
    var scale: CGFloat = 0.96
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(DS.press, value: configuration.isPressed)
    }
}

/// Press feedback for buttons whose label is interactive Liquid Glass. The system material already lights up and stretches
/// under the finger on iOS 26; fading or squeezing it hard on top of that would flatten the glass.
struct GlassPress: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        if #available(iOS 26.0, *) {
            configuration.label
                .scaleEffect(configuration.isPressed ? 0.98 : 1)
                .animation(DS.press, value: configuration.isPressed)
        } else {
            configuration.label
                .scaleEffect(configuration.isPressed ? 0.95 : 1)
                .opacity(configuration.isPressed ? 0.85 : 1)
                .animation(DS.press, value: configuration.isPressed)
        }
    }
}

// MARK: - Navigation bar (`.nav`)

struct DSNavBar<Leading: View, Trailing: View>: View {
    var title: String
    @ViewBuilder var leading: Leading
    @ViewBuilder var trailing: Trailing
    var body: some View {
        DSNavBarLayout {
            Text(title).dsFont(17, .semibold, relativeTo: .headline).foregroundStyle(STVTheme.text).lineLimit(1)
                .truncationMode(.tail)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("nav.title")
            HStack(spacing: 8) { leading }
            LiquidGlassGroup(spacing: 8) { HStack(spacing: 8) { trailing } }
        }
        .frame(height: 44)
        .padding(.horizontal, 16).padding(.top, 2).padding(.bottom, 8)
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }
}

/// Centers the title on screen whenever leading & trailing items leave enough room; if trailing actions are wide
/// (e.g. a 4-icon glass capsule), centers the title within the open gap between leading and trailing so it never overlaps.
private struct DSNavBarLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        CGSize(width: proposal.width ?? 320, height: proposal.height ?? 44)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 3 else { return }
        let titleView = subviews[0]
        let leadingView = subviews[1]
        let trailingView = subviews[2]

        let leadSize = leadingView.sizeThatFits(.unspecified)
        let trailSize = trailingView.sizeThatFits(.unspecified)

        leadingView.place(at: CGPoint(x: bounds.minX, y: bounds.midY), anchor: .leading, proposal: ProposedViewSize(leadSize))
        trailingView.place(at: CGPoint(x: bounds.maxX, y: bounds.midY), anchor: .trailing, proposal: ProposedViewSize(trailSize))

        let gap: CGFloat = 10
        let leftLimit = bounds.minX + (leadSize.width > 0 ? leadSize.width + gap : 0)
        let rightLimit = bounds.maxX - (trailSize.width > 0 ? trailSize.width + gap : 0)
        let availW = max(0, rightLimit - leftLimit)
        guard availW > 0 else { return }

        let titleSize = titleView.sizeThatFits(ProposedViewSize(width: availW, height: bounds.height))
        let fittedW = min(titleSize.width, availW)
        let screenCenteredX = bounds.midX - fittedW / 2
        let titleX: CGFloat
        if screenCenteredX >= leftLimit && (screenCenteredX + fittedW) <= rightLimit {
            titleX = screenCenteredX
        } else {
            titleX = leftLimit + (availW - fittedW) / 2
        }
        titleView.place(
            at: CGPoint(x: titleX, y: bounds.midY),
            anchor: .leading,
            proposal: ProposedViewSize(width: fittedW, height: bounds.height)
        )
    }
}

extension DSNavBar where Trailing == EmptyView {
    init(title: String, @ViewBuilder leading: () -> Leading) { self.title = title; self.leading = leading(); self.trailing = EmptyView() }
}

/// The back button every sub-page uses.
struct DSBackButton: View {
    var tone: RoundIconButton.Tone = .glass
    var action: () -> Void
    var body: some View {
        RoundIconButton(systemName: "chevron.left", tone: tone, size: 44, action: action).accessibilityLabel("返回").accessibilityIdentifier("nav.back")
    }
}

/// Trailing bar action: one glass capsule (text) or circle (icon) like a system toolbar item.
struct DSBarButton: View {
    var title: String? = nil
    var systemName: String? = nil
    var tint: Color? = nil
    var action: () -> Void
    @Environment(\.isEnabled) private var enabled
    var body: some View {
        Button(action: action) { DSBarLabel(title: title, systemName: systemName, tint: tint) }
            .buttonStyle(GlassPress())
            .opacity(enabled ? 1 : 0.4)
    }
}

struct DSBarLabel: View {
    var title: String? = nil
    var systemName: String? = nil
    var tint: Color? = nil
    @Environment(\.onGlass) private var onGlass
    var body: some View {
        let label = Group {
            if let systemName { Image(systemName: systemName).font(.system(size: 17, weight: .medium)) }
            else if let title { Text(title).font(.system(size: 15, weight: .semibold)).lineLimit(1).fixedSize() }
        }
        .foregroundStyle(tint ?? STVTheme.text)
        .padding(.horizontal, systemName == nil ? 14 : 0)
        .frame(minWidth: 44, minHeight: 44)
        .contentShape(Capsule())
        if onGlass { label.background(Capsule().fill(STVTheme.glass)) } else { label.liquidGlassCapsule(interactive: true) }
    }
}

private struct DSNavigation<T: View>: ViewModifier {
    let title: String
    var back: (() -> Void)?
    @ViewBuilder var trailing: () -> T
    @Environment(\.dismiss) private var dismiss
    func body(content: Content) -> some View {
        content
            .liquidTopBar {
                DSNavBar(title: title) {
                    DSBackButton { if let back { back() } else { dismiss() } }
                } trailing: { trailing() }
            }
            .background(STVTheme.bg.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
    }
}

extension View {
    /// Sub-page chrome: floating glass back button, centred title, glass trailing actions. The navigation stack and its
    /// swipe-back are untouched, only the stock bar is replaced.
    func dsNavigation<T: View>(_ title: String, back: (() -> Void)? = nil, @ViewBuilder trailing: @escaping () -> T) -> some View {
        modifier(DSNavigation(title: title, back: back, trailing: trailing))
    }
    func dsNavigation(_ title: String, back: (() -> Void)? = nil) -> some View {
        modifier(DSNavigation(title: title, back: back, trailing: { EmptyView() }))
    }
}

// MARK: - Search bar (`.search`)

struct DSSearchBarLabel: View {
    var placeholder = "搜索你想看的作品"
    var ink: Color? = nil
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").font(.system(size: 17, weight: .medium))
            Text(placeholder).font(.system(size: 16)).lineLimit(1)
            Spacer(minLength: 0)
        }
        .foregroundStyle(ink ?? STVTheme.secondary)
    }
}

// MARK: - Section header (`.sech`)

struct DSSectionHeader<Trailing: View>: View {
    let title: String
    var note: String? = nil
    var inset: CGFloat = 16
    @ViewBuilder var trailing: Trailing
    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            HStack(spacing: 5) {
                YMDoodleSparkle()
                    .fill(STVTheme.accent)
                    .frame(width: 11, height: 11)
                    .rotationEffect(.degrees(-10))
                Text(title).dsFont(19, .heavy, relativeTo: .title3).foregroundStyle(STVTheme.text).accessibilityAddTraits(.isHeader)
                    .overlay(alignment: .bottomLeading) {
                        YMScribbleUnderline()
                            .stroke(STVTheme.accent.opacity(0.65), style: StrokeStyle(lineWidth: 2.2, lineCap: .round))
                            .frame(width: 24, height: 4)
                            .offset(y: 4)
                    }
            }
            if let note { Text(note).dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.secondary) }
            Spacer(minLength: 0)
            trailing
        }
        .padding(.horizontal, inset)
    }
}

extension DSSectionHeader where Trailing == EmptyView {
    init(title: String, note: String? = nil, inset: CGFloat = 16) { self.title = title; self.note = note; self.inset = inset; self.trailing = EmptyView() }
}

/// "全部 ›" link label (`.more`).
struct MoreLabel: View {
    var title = "全部"
    var icon: String? = nil
    var body: some View {
        HStack(spacing: 1) {
            if let icon { Image(systemName: icon).font(.system(size: 12, weight: .medium)).padding(.trailing, 3) }
            Text(title)
            if icon == nil { Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold)) }
        }
        .font(.system(size: 13)).foregroundStyle(STVTheme.secondary)
        .contentShape(Rectangle().inset(by: -10))
    }
}

// MARK: - Small labels

/// `.tag`: 20 pt neutral label.
struct DSTag: View {
    let text: String
    var body: some View {
        Text(text).font(.system(size: 11)).foregroundStyle(STVTheme.secondary).lineLimit(1)
            .padding(.horizontal, 7).frame(height: 20)
            .background(STVTheme.glass, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
    }
}

/// `.st`: status label (ok / warn / muted / accent).
struct DSStatus: View {
    enum Tone { case ok, warn, muted, accent }
    let text: String
    var tone: Tone
    var body: some View {
        let (fg, bg): (Color, Color) = switch tone {
        case .ok: (STVTheme.ok, STVTheme.okSoft)
        case .warn: (STVTheme.accentWarm, STVTheme.warmSoft)
        case .muted: (STVTheme.tertiary, STVTheme.glass)
        case .accent: (STVTheme.accent, STVTheme.accentSoft)
        }
        Text(text).font(.system(size: 11, weight: .semibold)).foregroundStyle(fg).lineLimit(1)
            .padding(.horizontal, 7).frame(height: 20)
            .background(bg, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
    }
}

/// Source label on hero artwork: on the page colour, so it reads over any picture.
struct DSHeroTag: View {
    let text: String
    var trailingIcon: String? = nil
    var body: some View {
        HStack(spacing: 4) {
            Text(text).font(.system(size: 11.5, weight: .semibold)).lineLimit(1)
            if let trailingIcon { Image(systemName: trailingIcon).font(.system(size: 9, weight: .bold)) }
        }
        .foregroundStyle(STVTheme.accent)
        .padding(.horizontal, 9).frame(height: 24)
        .background(STVTheme.bg.opacity(0.84), in: Capsule())
        .overlay(Capsule().strokeBorder(STVTheme.accent.opacity(0.45), style: StrokeStyle(lineWidth: 0.9, lineCap: .round, dash: [3.5, 2.5])))
    }
}

/// `.bar`: 3 pt progress track. Scaled fill instead of a GeometryReader: it sits in scrolling cells.
struct DSProgressBar: View {
    var value: Double
    var height: CGFloat = 3
    var track: Color = STVTheme.glassBorder
    var tint: Color = STVTheme.accent
    var body: some View {
        Capsule().fill(track)
            .overlay(alignment: .leading) {
                Rectangle().fill(tint).scaleEffect(x: min(max(value.isFinite ? value : 0, 0), 1), y: 1, anchor: .leading)
            }
            .clipShape(Capsule())
            .frame(height: height)
            .animation(.easeOut(duration: 0.3), value: value)
            .accessibilityElement()
            .accessibilityValue("\(Int((min(max(value.isFinite ? value : 0, 0), 1) * 100).rounded()))%")
    }
}

/// `.mini`: small accent capsule action.
struct MiniButtonLabel: View {
    let title: String
    var tint: Color = STVTheme.accent
    var ground: Color = STVTheme.accentSoft
    var body: some View {
        Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(tint).lineLimit(1)
            .padding(.horizontal, 12).frame(height: 30)
            .background(Capsule().fill(ground))
            .contentShape(Capsule().inset(by: -7))
    }
}

/// `.dot` / `.lico`: tinted icon wells.
struct IconWell: View {
    let systemName: String
    var size: CGFloat = 34
    var corner: CGFloat? = nil
    var tint: Color = STVTheme.accent
    var ground: Color = STVTheme.accentSoft
    var body: some View {
        Image(systemName: systemName).font(.system(size: size * 0.46, weight: .semibold)).foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(ground, in: RoundedRectangle(cornerRadius: corner ?? size / 2, style: .continuous))
            .overlay {
                if corner == nil {
                    YMScribbleCircle(seed: UInt64(abs(systemName.hashValue) % 83 + 5))
                        .stroke(tint.opacity(0.55), style: StrokeStyle(lineWidth: 1.15, lineCap: .round))
                        .padding(-1.5)
                }
            }
            .accessibilityHidden(true)
    }
}

// MARK: - Posters

/// Yimeng hand-drawn rank badge on posters: tilted gold crown for #1, scribble-circled medal for #2/#3, clean pill for #4+.
struct PosterRankNumeral: View {
    let rank: Int
    var body: some View {
        let accent: Color = switch rank {
        case 1: YMTheme.gold
        case 2: YMTheme.silver
        case 3: YMTheme.bronze
        default: Color.white.opacity(0.85)
        }
        VStack(spacing: -2) {
            if rank == 1 {
                YMCrownScribble()
                    .fill(YMTheme.gold)
                    .frame(width: 17, height: 10)
                    .rotationEffect(.degrees(-8))
                    .shadow(color: .black.opacity(0.55), radius: 2, y: 1)
            }
            ZStack {
                Circle()
                    .fill(Color.black.opacity(rank <= 3 ? 0.68 : 0.52))
                if rank <= 3 {
                    YMScribbleCircle(seed: UInt64(rank * 31 + 7))
                        .stroke(accent, style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
                        .padding(1)
                }
                Text("\(rank)")
                    .font(.system(size: rank <= 3 ? 14 : 12.5, weight: .black, design: .rounded))
                    .foregroundStyle(rank <= 3 ? accent : .white)
            }
            .frame(width: rank <= 3 ? 28 : 24, height: rank <= 3 ? 28 : 24)
        }
        .padding(.leading, 7)
        .padding(.top, rank == 1 ? 5 : 7)
    }
}

/// 3:4 poster with the bottom scrim, optional rank / 新剧 badges and the episode label (`.poster`).
struct PosterArt: View {
    let series: HGSeries
    var rank: Int? = nil
    var corner: CGFloat = 12
    var showEpisodes = true
    var body: some View {
        Color.clear
            .aspectRatio(series.isLandscapeCover ? 16 / 9 : 3 / 4, contentMode: .fit)
            .overlay { CoverView(url: series.cover, corner: 0, maxPixelSize: series.isLandscapeCover ? CoverPixels.wide : CoverPixels.poster) }
            .overlay {
                LinearGradient(stops: [.init(color: .clear, location: 0.44), .init(color: .black.opacity(0.74), location: 1)],
                               startPoint: .top, endPoint: .bottom).allowsHitTesting(false)
            }
            .overlay(alignment: .topLeading) { if let rank { PosterRankNumeral(rank: rank) } }
            .overlay(alignment: .topTrailing) {
                if series.isNew {
                    Text("新剧").font(.system(size: 10.5, weight: .semibold)).foregroundStyle(.white)
                        .padding(.horizontal, 6).frame(height: 18)
                        .background(.black.opacity(0.5), in: RoundedRectangle(cornerRadius: 5)).padding(6)
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if showEpisodes, let e = DS.episodes(series) {
                    Text(e).font(.system(size: 11, weight: .medium)).foregroundStyle(.white.opacity(0.94))
                        .shadow(color: .black.opacity(0.60), radius: 2, y: 1)
                        .lineLimit(1)
                        .padding(.trailing, 8).padding(.bottom, 7)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
    }
}

/// Grid / shelf cell: poster + 14 pt title + 12 pt subtitle (`.cell`).
struct PosterCellView: View {
    let series: HGSeries
    var rank: Int? = nil
    var subtitle: String? = nil
    @Environment(\.zoomNS) private var ns
    var body: some View {
        NavigationLink(value: Route.series(series)) {
            VStack(alignment: .leading, spacing: 0) {
                PosterArt(series: series, rank: rank).zoomSource(series.id, ns)
                Text(series.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(STVTheme.text).lineLimit(1)
                    .padding(.top, 7)
                let sub = subtitle ?? DS.heat(series)
                if !sub.isEmpty {
                    Text(sub).font(.system(size: 12)).foregroundStyle(STVTheme.secondary).lineLimit(1).padding(.top, 2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale(scale: 0.97))
    }
}

/// Ranked list row from #4 on (`.rrow`): numeral · 62×84 poster · title, tags, episodes + heat.
struct RankListRow: View {
    let series: HGSeries
    let rank: Int
    @Environment(\.zoomNS) private var ns
    var body: some View {
        NavigationLink(value: Route.series(series)) {
            HStack(spacing: 12) {
                Text("\(rank)").font(.system(size: 20, weight: .heavy)).italic()
                    .foregroundStyle(STVTheme.rankColor(rank)).frame(width: 26)
                    .minimumScaleFactor(0.6).lineLimit(1)
                CoverView(url: series.cover, corner: 9, maxPixelSize: CoverPixels.thumb).frame(width: 62, height: 84).zoomSource(series.id, ns)
                VStack(alignment: .leading, spacing: 0) {
                    Text(series.title).font(.system(size: 16, weight: .semibold)).foregroundStyle(STVTheme.text).lineLimit(1)
                    let tags = Array((series.tags + (series.isNew ? ["新剧"] : [])).prefix(3))
                    if !tags.isEmpty {
                        HStack(spacing: 5) { ForEach(tags, id: \.self) { DSTag(text: $0) } }.padding(.top, 6)
                    }
                    HStack(spacing: 5) {
                        if let e = DS.episodes(series) { Text(e) }
                        if series.playCount > 0 {
                            HStack(spacing: 2) {
                                Image(systemName: "flame").font(.system(size: 11, weight: .semibold))
                                Text(DS.count(series.playCount))
                            }
                            .fontWeight(.semibold).foregroundStyle(STVTheme.accentWarm)
                        }
                    }
                    .font(.system(size: 12)).foregroundStyle(STVTheme.secondary).padding(.top, 7)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale(scale: 0.98))
    }
}

/// Hairline separator (`+ .row { border-top }`).
struct Hairline: View {
    var body: some View { STVTheme.hairline.frame(height: 1) }
}
