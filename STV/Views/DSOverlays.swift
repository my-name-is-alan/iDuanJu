import SwiftUI
import UIKit

// Overlays of the design system: sheets, option sheets, dialogs, toasts, loading and empty states.
// Sheets and dialogs are glass surfaces (`onGlassSurface`): controls inside them draw plain fills.

// MARK: - Sheet

/// Container of every sheet: title row with a close button, content, optional pinned footer. Partial-height sheets paint no
/// background on iOS 26, so they get the system Liquid Glass.
struct DSSheet<Trailing: View, Content: View, Footer: View>: View {
    let title: String
    var subtitle: String? = nil
    var closeID: String? = nil
    var onClose: (() -> Void)? = nil
    @ViewBuilder var trailing: Trailing
    @ViewBuilder var content: Content
    @ViewBuilder var footer: Footer
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        content.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .modifier(SheetBars(header: header, footer: footer))
            .onGlassSurface()
            .tint(STVTheme.accent)
            .dynamicTypeSize(...DynamicTypeSize.accessibility2)
    }

    private var header: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).dsFont(20, .bold, relativeTo: .title3).foregroundStyle(STVTheme.text).lineLimit(1)
                    .accessibilityAddTraits(.isHeader).accessibilityIdentifier("sheet.title")
                if let subtitle { Text(subtitle).dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.secondary).lineLimit(2) }
            }
            Spacer(minLength: 0)
            trailing
            RoundIconButton(systemName: "xmark", size: 36) { if let onClose { onClose() } else { dismiss() } }
                .accessibilityLabel("关闭").accessibilityIdentifier(closeID ?? "sheet.close")
        }
        .padding(.horizontal, 20).padding(.top, 22).padding(.bottom, 12)
    }
}

/// Title row and pinned actions of a sheet. iOS 26: bars, so scrolling content dissolves under them with the system edge
/// effect; earlier: stacked around the content.
private struct SheetBars<Header: View, Footer: View>: ViewModifier {
    let header: Header
    let footer: Footer
    func body(content: Content) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            let top = content.safeAreaBar(edge: .top, spacing: 0) { header }
            if Footer.self == EmptyView.self {
                top.scrollEdgeEffectStyle(.soft, for: .all)
            } else {
                top.safeAreaBar(edge: .bottom, spacing: 0) { footer }.scrollEdgeEffectStyle(.soft, for: .all)
            }
        } else {
            VStack(spacing: 0) { header; content; footer }
        }
        #else
        VStack(spacing: 0) { header; content; footer }
        #endif
    }
}

extension DSSheet where Trailing == EmptyView, Footer == EmptyView {
    init(_ title: String, subtitle: String? = nil, closeID: String? = nil, onClose: (() -> Void)? = nil, @ViewBuilder content: () -> Content) {
        self.init(title: title, subtitle: subtitle, closeID: closeID, onClose: onClose, trailing: { EmptyView() }, content: content, footer: { EmptyView() })
    }
}

extension DSSheet where Trailing == EmptyView {
    init(_ title: String, subtitle: String? = nil, closeID: String? = nil, onClose: (() -> Void)? = nil, @ViewBuilder content: () -> Content, @ViewBuilder footer: () -> Footer) {
        self.init(title: title, subtitle: subtitle, closeID: closeID, onClose: onClose, trailing: { EmptyView() }, content: content, footer: footer)
    }
}

/// Pinned action(s) at the bottom of a sheet.
struct DSSheetFooter<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        HStack(spacing: 10) { content }
            .padding(.horizontal, 20).padding(.top, 10).padding(.bottom, 12)
    }
}

extension View {
    /// Sheet chrome shared by all sheets: detents, drag indicator, corner radius, glass background.
    func dsSheetChrome(_ detents: Set<PresentationDetent> = [.medium, .large], indicator: Visibility = .visible) -> some View {
        presentationDetents(detents)
            .presentationDragIndicator(indicator)
            .modifier(SheetCorner())
            .liquidSheetBackground()
    }

    func dsSheet<C: View>(isPresented: Binding<Bool>, title: String, subtitle: String? = nil, detents: Set<PresentationDetent> = [.medium, .large], @ViewBuilder content: @escaping () -> C) -> some View {
        sheet(isPresented: isPresented) { DSSheet(title, subtitle: subtitle, content: content).dsSheetChrome(detents) }
    }

    func dsSheet<I: Identifiable, C: View>(item: Binding<I?>, title: @escaping (I) -> String, detents: Set<PresentationDetent> = [.medium, .large], @ViewBuilder content: @escaping (I) -> C) -> some View {
        sheet(item: item) { i in DSSheet(title(i)) { content(i) }.dsSheetChrome(detents) }
    }

    /// List of choices with a checkmark; picking one closes the sheet.
    func dsOptions<T: Hashable>(isPresented: Binding<Bool>, title: String, options: [T], selection: Binding<T>, label: @escaping (T) -> String, note: @escaping (T) -> String? = { _ in nil }) -> some View {
        sheet(isPresented: isPresented) {
            DSSheet(title) {
                DSOptionList(options: options, selected: selection.wrappedValue, label: label, note: note) { selection.wrappedValue = $0; isPresented.wrappedValue = false }
            }
            .dsSheetChrome(options.count > 6 ? [.medium, .large] : [.height(CGFloat(110 + options.count * 60)), .large])
        }
    }
}

/// iOS 26 rounds sheets concentrically with the display by itself.
private struct SheetCorner: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) { content } else { content.compatPresentationCornerRadius(28) }
    }
}

struct DSOptionList<T: Hashable>: View {
    let options: [T]
    let selected: T?
    let label: (T) -> String
    var note: (T) -> String? = { _ in nil }
    let pick: (T) -> Void
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(options, id: \.self) { o in
                        let on = o == selected
                        Button { DS.tick(); pick(o) } label: {
                            HStack(spacing: 12) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(label(o)).dsFont(16, on ? .semibold : .regular).foregroundStyle(on ? STVTheme.accent : STVTheme.text).multilineTextAlignment(.leading)
                                    if let n = note(o), !n.isEmpty { Text(n).dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.secondary).lineLimit(1) }
                                }
                                Spacer(minLength: 0)
                                if on { Image(systemName: "checkmark").font(.system(size: 15, weight: .semibold)).foregroundStyle(STVTheme.accent) }
                            }
                            .padding(.horizontal, 20).frame(minHeight: 54).contentShape(Rectangle())
                        }
                        .buttonStyle(DSRowPress())
                        .accessibilityAddTraits(on ? .isSelected : [])
                        .id(o)
                        if o != options.last { Hairline().padding(.leading, 20) }
                    }
                }
                .padding(.bottom, 24)
            }
            .onAppear { if let selected { proxy.scrollTo(selected, anchor: .center) } }
        }
    }
}

// MARK: - Dialog

/// One button of a dialog.
struct DSDialogAction: Identifiable {
    enum Role { case normal, primary, destructive, cancel }
    /// Titles are unique inside one dialog; a stable id keeps buttons alive while a field inside the dialog is edited.
    var id: String { title }
    let title: String
    var role: Role = .normal
    var disabled: () -> Bool = { false }
    var action: () -> Void = {}

    static func primary(_ t: String, disabled: @escaping () -> Bool = { false }, _ f: @escaping () -> Void = {}) -> DSDialogAction { .init(title: t, role: .primary, disabled: disabled, action: f) }
    static func destructive(_ t: String, _ f: @escaping () -> Void) -> DSDialogAction { .init(title: t, role: .destructive, action: f) }
    static func normal(_ t: String, _ f: @escaping () -> Void) -> DSDialogAction { .init(title: t, action: f) }
    static func cancel(_ t: String = "取消", _ f: @escaping () -> Void = {}) -> DSDialogAction { .init(title: t, role: .cancel, action: f) }
}

/// Centred glass card over a dimmed page: alerts, confirmations and small forms.
private struct DSDialogCard<Extra: View>: View {
    let title: String
    let message: String?
    let actions: [DSDialogAction]
    let extra: Extra
    let close: (DSDialogAction?) -> Void
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var stacked: Bool { actions.count > 2 || actions.contains { $0.title.count > 7 } }

    var body: some View {
        ZStack {
            STVTheme.dim.ignoresSafeArea()
                .opacity(shown ? 1 : 0)
                .contentShape(Rectangle())
                .onTapGesture { finish(nil) }
                .accessibilityHidden(true)
            if shown {
                card
                    .padding(.horizontal, 36)
                    .transition(reduceMotion ? .opacity : .scale(scale: 1.08).combined(with: .opacity))
            }
        }
        .onAppear { withAnimation(DS.motion(.spring(response: 0.30, dampingFraction: 0.82), reduce: reduceMotion)) { shown = true } }
        .accessibilityAddTraits(.isModal)
        .accessibilityAction(.escape) { finish(nil) }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 8) {
                Text(title).dsFont(17, .semibold, relativeTo: .headline).foregroundStyle(STVTheme.text)
                    .fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isHeader)
                if let message, !message.isEmpty {
                    ScrollView {
                        Text(message).dsFont(14, relativeTo: .subheadline).foregroundStyle(STVTheme.secondary).lineSpacing(3)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .compatScrollBounceBasedOnSize()
                    .frame(maxHeight: 260).fixedSize(horizontal: false, vertical: true)
                }
                extra
            }
            .padding(.horizontal, 22).padding(.top, 22).padding(.bottom, 18)
            buttons.padding(.horizontal, 14).padding(.bottom, 14)
        }
        .frame(maxWidth: 340)
        .onGlassSurface()
        // Denser than a bar: whatever sits behind a dialog must not show through its text.
        .liquidGlass(corner: 34, tone: .tinted(STVTheme.sheet.opacity(0.66)))
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
    }

    @ViewBuilder private var buttons: some View {
        // Cancel goes last when stacked, first (leading) when side by side.
        let cancel = actions.filter { $0.role == .cancel }, rest = actions.filter { $0.role != .cancel }
        if stacked {
            VStack(spacing: 8) { ForEach(rest + cancel) { button($0) } }
        } else {
            HStack(spacing: 8) { ForEach(cancel + rest) { button($0) } }
        }
    }

    private func button(_ a: DSDialogAction) -> some View {
        let disabled = a.disabled()
        return Button { finish(a) } label: {
            Text(a.title).dsFont(16, a.role == .cancel ? .medium : .semibold).lineLimit(1).minimumScaleFactor(0.8)
                .foregroundStyle(a.role == .primary ? STVTheme.onAccent : a.role == .destructive ? DSTone.danger : STVTheme.text)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(a.role == .primary ? STVTheme.accent : a.role == .destructive ? DSTone.dangerSoft : STVTheme.glass, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(PressScale(scale: 0.97))
        .disabled(disabled).opacity(disabled ? 0.4 : 1)
    }

    private func finish(_ a: DSDialogAction?) {
        withAnimation(.easeOut(duration: 0.16)) { shown = false }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.17) { close(a) }
    }
}

/// Presents the card in a transparent full-screen cover, so it sits above tab bars, sheets and the keyboard area alike.
private struct DSDialogPresenter<Extra: View>: ViewModifier {
    @Binding var isPresented: Bool
    let title: String
    let message: String?
    let actions: () -> [DSDialogAction]
    @ViewBuilder let extra: () -> Extra
    @State private var cover = false

    func body(content: Content) -> some View {
        content
            .fullScreenCover(isPresented: $cover) {
                DSDialogCard(title: title, message: message, actions: actions(), extra: extra()) { a in
                    set(false)
                    let run = a?.action ?? actions().first(where: { $0.role == .cancel })?.action
                    // After the cover is gone: actions may present something themselves. The binding is cleared only after
                    // the action ran, as actions read the state it is made of (`Binding(get: { deleting != nil } …)`).
                    DispatchQueue.main.async {
                        run?()
                        isPresented = false
                    }
                }
                .background(ClearCoverBackground())
                .compatPresentationBackground(Color.clear)
            }
            .compatOnChange(of: isPresented) { v in if v != cover { set(v) } }
            .onAppear { if isPresented && !cover { set(true) } }
    }

    /// The card animates itself; the cover must not slide.
    private func set(_ on: Bool) {
        var t = Transaction()
        t.disablesAnimations = true
        withTransaction(t) { cover = on }
    }
}

/// iOS 16.0–16.3 have no `presentationBackground`: clear the hosting view instead.
private struct ClearCoverBackground: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        if #unavailable(iOS 16.4) { DispatchQueue.main.async { v.superview?.superview?.backgroundColor = .clear } }
        v.backgroundColor = .clear
        return v
    }
    func updateUIView(_ uiView: UIView, context: Context) {}
}

extension View {
    /// Alert replacement. `extra` holds inputs (DSTextField) between the message and the buttons.
    func dsDialog<E: View>(_ title: String, isPresented: Binding<Bool>, message: String? = nil, actions: @escaping () -> [DSDialogAction], @ViewBuilder extra: @escaping () -> E) -> some View {
        modifier(DSDialogPresenter(isPresented: isPresented, title: title, message: message, actions: actions, extra: extra))
    }
    func dsDialog(_ title: String, isPresented: Binding<Bool>, message: String? = nil, actions: @escaping () -> [DSDialogAction]) -> some View {
        modifier(DSDialogPresenter(isPresented: isPresented, title: title, message: message, actions: actions, extra: { EmptyView() }))
    }
    /// Destructive / primary confirmation (confirmationDialog replacement).
    func dsConfirm(_ title: String, isPresented: Binding<Bool>, message: String? = nil, confirm: String, destructive: Bool = true, action: @escaping () -> Void) -> some View {
        dsDialog(title, isPresented: isPresented, message: message) {
            [destructive ? .destructive(confirm, action) : .primary(confirm, disabled: { false }, action), .cancel()]
        }
    }
}

// MARK: - Toast

private struct DSToastModifier: ViewModifier {
    @Binding var message: String?
    var icon: String?
    var bottom: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottom) {
                if let message {
                    HStack(spacing: 8) {
                        if let icon { Image(systemName: icon).font(.system(size: 14, weight: .semibold)).foregroundStyle(STVTheme.accent) }
                        Text(message).dsFont(14, .medium, relativeTo: .subheadline).foregroundStyle(STVTheme.text).multilineTextAlignment(.leading).lineLimit(4)
                    }
                    .padding(.horizontal, 18).padding(.vertical, 12)
                    .liquidGlass(corner: 24)
                    .padding(.horizontal, 24).padding(.bottom, bottom)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                    .onTapGesture { self.message = nil }
                    .accessibilityIdentifier("toast")
                }
            }
            .animation(DS.motion(DS.panel, reduce: reduceMotion), value: message)
            .task(id: message) {
                guard let message else { return }
                UIAccessibility.post(notification: .announcement, argument: message)
                try? await Task.sleep(for: .seconds(message.count > 24 ? 4 : 2.4))
                if !Task.isCancelled, self.message == message { self.message = nil }
            }
    }
}

extension View {
    /// Short feedback in a floating glass capsule; clears itself.
    func dsToast(_ message: Binding<String?>, icon: String? = nil, bottom: CGFloat = 24) -> some View {
        modifier(DSToastModifier(message: message, icon: icon, bottom: bottom))
    }
}

// MARK: - Loading

/// Arc spinner. Core Animation drives the rotation in the render server, so it never stutters while the main thread is busy.
struct DSSpinner: View {
    var size: CGFloat = 22
    var tint: Color = STVTheme.secondary
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        SpinnerLayer(tint: UIColor(tint), line: max(2, size * 0.11), pulse: reduceMotion)
            .frame(width: size, height: size)
            .accessibilityElement().accessibilityLabel("加载中")
    }

    private struct SpinnerLayer: UIViewRepresentable {
        let tint: UIColor
        let line: CGFloat
        let pulse: Bool
        func makeUIView(context: Context) -> Arc { Arc() }
        func updateUIView(_ v: Arc, context: Context) { v.configure(tint: tint, line: line, pulse: pulse) }
        func sizeThatFits(_ proposal: ProposedViewSize, uiView: Arc, context: Context) -> CGSize? {
            CGSize(width: proposal.width ?? 22, height: proposal.height ?? 22)
        }

        final class Arc: UIView {
            private let arc = CAShapeLayer()
            private var tint: UIColor = .gray
            private var pulse = false
            override init(frame: CGRect) {
                super.init(frame: frame)
                isUserInteractionEnabled = false
                arc.fillColor = nil
                arc.lineCap = .round
                arc.strokeEnd = 0.72
                layer.addSublayer(arc)
            }
            required init?(coder: NSCoder) { fatalError() }
            func configure(tint: UIColor, line: CGFloat, pulse: Bool) {
                self.tint = tint
                self.pulse = pulse
                arc.lineWidth = line
                arc.strokeColor = tint.resolvedColor(with: traitCollection).cgColor
                setNeedsLayout()
            }
            override func layoutSubviews() {
                super.layoutSubviews()
                CATransaction.begin()
                CATransaction.setDisableActions(true)
                arc.frame = bounds
                arc.path = UIBezierPath(arcCenter: CGPoint(x: bounds.midX, y: bounds.midY), radius: max(1, min(bounds.width, bounds.height) / 2 - arc.lineWidth / 2),
                                        startAngle: -.pi / 2, endAngle: 1.5 * .pi, clockwise: true).cgPath
                CATransaction.commit()
                start()
            }
            override func didMoveToWindow() { super.didMoveToWindow(); start() }
            override func traitCollectionDidChange(_ previous: UITraitCollection?) {
                super.traitCollectionDidChange(previous)
                arc.strokeColor = tint.resolvedColor(with: traitCollection).cgColor
            }
            private func start() {
                guard window != nil else { arc.removeAllAnimations(); return }
                let key = pulse ? "pulse" : "spin"
                guard arc.animation(forKey: key) == nil else { return }
                arc.removeAllAnimations()
                let a = CABasicAnimation(keyPath: pulse ? "opacity" : "transform.rotation.z")
                a.fromValue = pulse ? 1 : 0
                a.toValue = pulse ? 0.3 : 2 * Double.pi
                a.duration = 0.85
                a.autoreverses = pulse
                a.repeatCount = .infinity
                a.isRemovedOnCompletion = false
                arc.add(a, forKey: key)
            }
        }
    }
}

/// Centred loading state with an optional line of text.
struct DSLoading: View {
    var label: String? = nil
    var tint: Color = STVTheme.secondary
    var body: some View {
        VStack(spacing: 12) {
            DSSpinner(size: 26, tint: tint)
            if let label { Text(label).dsFont(13, relativeTo: .footnote).foregroundStyle(tint) }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
        .accessibilityElement(children: .combine)
    }
}

/// Blocking progress in a floating glass card.
struct DSBusyOverlay: View {
    let label: String
    var body: some View {
        HStack(spacing: 12) {
            DSSpinner(size: 20, tint: STVTheme.accent)
            Text(label).dsFont(15, .medium, relativeTo: .subheadline).foregroundStyle(STVTheme.text)
        }
        .padding(.horizontal, 22).padding(.vertical, 16)
        .liquidGlass(corner: 26)
        .transition(.opacity.combined(with: .scale(scale: 0.95)))
    }
}

// MARK: - Empty state

struct DSEmptyState: View {
    let title: String
    let systemImage: String
    var message: String? = nil
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    init(_ title: String, systemImage: String, message: String? = nil, actionTitle: String? = nil, action: (() -> Void)? = nil) {
        self.title = title
        self.systemImage = systemImage
        self.message = message
        self.actionTitle = actionTitle
        self.action = action
    }

    var body: some View {
        VStack(spacing: 10) {
            ZStack {
                Circle().fill(STVTheme.accentSoft)
                YMScribbleCircle(seed: UInt64(abs(title.hashValue) % 97 + 13))
                    .stroke(STVTheme.accent.opacity(0.80), style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
                    .padding(-3)
                Image(systemName: systemImage)
                    .font(.system(size: 26, weight: .semibold)).foregroundStyle(STVTheme.accent)
            }
            .frame(width: 66, height: 66)
            .padding(.bottom, 4)
            .accessibilityHidden(true)
            Text(title).dsFont(17, .bold, relativeTo: .headline).foregroundStyle(STVTheme.text).multilineTextAlignment(.center)
            YMAnimatedWave(waves: 3, color: STVTheme.accent.opacity(0.50), lineWidth: 1.8, speed: 1.8)
                .frame(width: 54, height: 8)
            if let message {
                Text(message).dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.secondary).multilineTextAlignment(.center).lineSpacing(3)
            }
            if let actionTitle, let action {
                Button(action: action) { PillButtonLabel(title: actionTitle, small: true) }.buttonStyle(PressScale()).padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 32).padding(.vertical, 36)
    }
}
