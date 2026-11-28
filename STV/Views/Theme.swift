import SwiftUI
import UIKit

/// App palette, light + dark (follows the system). Dark is the PlutoFish mini-app palette; light is its
/// counterpart (white surfaces on a soft grey ground). Brand blue marks focus and playback progress.
/// Screens that must stay dark (player, image viewer, manga reader) scope `.stvLocalColorScheme(.dark)` to their
/// own subtree + leaf hosting controller, so underlying NavigationStack pages stay in the user's light/dark theme.
enum STVTheme {
    static let bg = adaptive(light: (0.965, 0.961, 0.945), dark: (0.039, 0.039, 0.047))
    static let surface = adaptive(light: (1, 1, 1), dark: (0.078, 0.078, 0.094))
    static let elevated = adaptive(light: (0.922, 0.918, 0.902), dark: (0.149, 0.153, 0.176))
    static let text = adaptive(light: (0.078, 0.078, 0.086), dark: (0.957, 0.957, 0.965))
    static let secondary = adaptive(light: (0.392, 0.400, 0.431), dark: (0.604, 0.612, 0.651))
    static let accent = adaptive(light: (0.310, 0.420, 0.000), dark: (0.784, 0.961, 0.259))
    static let accentWarm = adaptive(light: (0.835, 0.522, 0.078), dark: (0.949, 0.710, 0.361))

    // MARK: Unified editorial tokens (shared with Yimeng Music palette — crisp obsidian/cream + acid lime/olive)
    /// Third-level text: dimension labels, hints, timestamps.
    static let tertiary = rgba(light: (100, 102, 110, 0.68), dark: (154, 156, 166, 0.64))
    /// Unselected control ground (chips, segmented track, round buttons).
    static let glass = rgba(light: (20, 20, 22, 0.055), dark: (255, 255, 255, 0.07))
    static let glassBorder = rgba(light: (20, 20, 22, 0.11), dark: (255, 255, 255, 0.13))
    /// 1 px separators between rows / around the segmented track.
    static let hairline = rgba(light: (20, 20, 22, 0.09), dark: (255, 255, 255, 0.10))
    static let accentSoft = rgba(light: (79, 107, 0, 0.11), dark: (200, 245, 66, 0.15))
    static let accentLine = rgba(light: (79, 107, 0, 0.36), dark: (200, 245, 66, 0.44))
    /// Text / icon on a solid accent fill.
    static let onAccent = rgba(light: (255, 255, 255, 1), dark: (10, 10, 12, 1))
    static let warmSoft = rgba(light: (213, 133, 20, 0.12), dark: (242, 181, 92, 0.15))
    static let ok = rgba(light: (30, 158, 87, 1), dark: (95, 208, 138, 1))
    static let okSoft = rgba(light: (30, 158, 87, 0.12), dark: (95, 208, 138, 0.14))
    /// Cards: crisp obsidian translucency in dark, warm white + subtle rim in light.
    static let card = rgba(light: (255, 255, 255, 1), dark: (255, 255, 255, 0.055))
    static let cardBorder = rgba(light: (20, 20, 22, 0.09), dark: (255, 255, 255, 0.11))
    /// Selected thumb of the segmented control and neutral ("全部") chips.
    static let thumb = rgba(light: (255, 255, 255, 1), dark: (255, 255, 255, 0.16))
    /// Floating bars (tab bar, sticky filter, play bar) under the blur.
    static let bar = rgba(light: (246, 245, 241, 0.84), dark: (16, 16, 20, 0.78))
    static let sheet = rgba(light: (246, 245, 241, 1), dark: (20, 20, 24, 1))
    static let rail = rgba(light: (20, 20, 22, 0.035), dark: (255, 255, 255, 0.04))
    static let gold = rgba(light: (219, 154, 0, 1), dark: (255, 199, 64, 1))
    static let silver = rgba(light: (135, 147, 173, 1), dark: (199, 209, 230, 1))
    static let bronze = rgba(light: (197, 115, 58, 1), dark: (219, 140, 82, 1))
    /// Pinned headers: page colour at 82 % under the blur.
    static let pin = rgba(light: (246, 245, 241, 0.84), dark: (10, 10, 12, 0.82))
    static let dim = rgba(light: (20, 20, 22, 0.34), dark: (0, 0, 0, 0.56))
    /// Card / thumb shadows only exist in light mode (dark relies on borders).
    static let shadowSoft = rgba(light: (20, 20, 22, 0.06), dark: (0, 0, 0, 0))
    static let shadowThumb = rgba(light: (20, 20, 22, 0.10), dark: (0, 0, 0, 0))
    static let barShadow = rgba(light: (20, 20, 22, 0.12), dark: (0, 0, 0, 0.34))
    /// Top-of-page ambient glow (subtle editorial lime/olive tint on obsidian & warm cream).
    static let ambientWarm = [rgba(light: (79, 107, 0, 0.09), dark: (200, 245, 66, 0.14)),
                              rgba(light: (213, 185, 110, 0.05), dark: (110, 145, 48, 0.05))]
    static let ambientCool = [rgba(light: (79, 107, 0, 0.08), dark: (160, 210, 80, 0.12)),
                              rgba(light: (79, 107, 0, 0.03), dark: (70, 100, 50, 0.04))]
    /// Yimeng heart pink for favorites and destructive badges.
    static let favorite = rgba(light: (230, 57, 132, 1), dark: (255, 95, 168, 1))
    static let favoriteSoft = rgba(light: (230, 57, 132, 0.11), dark: (255, 95, 168, 0.17))
    static let favoriteLine = rgba(light: (230, 57, 132, 0.32), dark: (255, 95, 168, 0.42))
    static func rankColor(_ r: Int) -> Color { r == 1 ? gold : r == 2 ? silver : r == 3 ? bronze : tertiary }

    private static func adaptive(light: (Double, Double, Double), dark: (Double, Double, Double)) -> Color {
        Color(UIColor { t in
            let c = t.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: c.0, green: c.1, blue: c.2, alpha: 1)
        })
    }
    private static func rgba(light: (Double, Double, Double, Double), dark: (Double, Double, Double, Double)) -> Color {
        Color(UIColor { t in
            let c = t.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: c.0 / 255, green: c.1 / 255, blue: c.2 / 255, alpha: c.3)
        })
    }
}

struct GlassBackground: ViewModifier {
    var radius: CGFloat = 20
    func body(content: Content) -> some View {
        content.liquidGlass(corner: radius)
    }
}

extension View {
    func stvGlass(_ radius: CGFloat = 20) -> some View { modifier(GlassBackground(radius: radius)) }

    /// Scopes `scheme` strictly to this screen's SwiftUI subtree and its own leaf `UIViewController` (never
    /// `UINavigationController`, `UITabBarController`, or `UIWindow`), preventing pushed dark pages from turning
    /// underlying light-mode pages black during back-swipe / pop transitions.
    func stvLocalColorScheme(_ scheme: ColorScheme) -> some View {
        self.environment(\.colorScheme, scheme)
            .background(LocalInterfaceStyleHost(style: scheme == .dark ? .dark : .light))
    }
}

private struct LocalInterfaceStyleHost: UIViewRepresentable {
    let style: UIUserInterfaceStyle
    func makeUIView(context: Context) -> ProbeView {
        let v = ProbeView()
        v.targetStyle = style
        v.isUserInteractionEnabled = false
        v.isHidden = true
        return v
    }
    func updateUIView(_ uiView: ProbeView, context: Context) {
        if uiView.targetStyle != style {
            uiView.targetStyle = style
            uiView.applyStyle()
        }
    }

    final class ProbeView: UIView {
        var targetStyle: UIUserInterfaceStyle = .dark
        private weak var appliedVC: UIViewController?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            applyStyle()
        }

        func applyStyle() {
            guard let window else { return }
            let root = window.rootViewController
            var responder: UIResponder? = self
            var leafVC: UIViewController?
            while let cur = responder {
                if let vc = cur as? UIViewController {
                    if vc is UINavigationController || vc is UITabBarController || vc is UISplitViewController || vc === root {
                        break
                    }
                    leafVC = vc
                }
                responder = cur.next
            }
            guard let vc = leafVC else { return }
            appliedVC = vc
            if vc.overrideUserInterfaceStyle != targetStyle {
                vc.overrideUserInterfaceStyle = targetStyle
                vc.setNeedsStatusBarAppearanceUpdate()
                vc.navigationController?.setNeedsStatusBarAppearanceUpdate()
            }
        }
    }
}

struct GlassChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button { DS.tick(); action() } label: { Text(title) }
            .buttonStyle(ToneChipStyle(on: selected))
            .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct GlassChipBar<T: Hashable>: View {
    let items: [T]
    @Binding var selection: T
    let label: (T) -> String
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(items, id: \.self) { it in
                    GlassChip(title: label(it), selected: selection == it) { selection = it }
                }
            }
            .padding(.horizontal, 16)
        }
    }
}
