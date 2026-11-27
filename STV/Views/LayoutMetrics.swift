import SwiftUI
import UIKit

enum SizeTier: Equatable {
    case compact, medium, expanded
}

/// Unified responsive layout metrics shared across iPhone, iPad, Split View, and Stage Manager.
/// Breakpoints match Android `LayoutMetrics` (Compact < 600, Medium 600..<840, Expanded >= 840).
struct LayoutMetrics: Equatable {
    var width: CGFloat = 390
    var height: CGFloat = 844

    var tier: SizeTier {
        if width >= 840 { return .expanded }
        if width >= 600 { return .medium }
        return .compact
    }

    var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }
    var isLandscape: Bool { width > height }
    var isUltra: Bool { width >= 1180 }
    var useRail: Bool { width >= 840 }
    var railWidth: CGFloat { 72 }
    var useTwoPageReader: Bool { width >= 840 && isLandscape }

    /// Horizontal page gutter: 16 phone, 24 tablet portrait/split, 28 tablet landscape.
    var pagePadding: CGFloat {
        switch tier {
        case .expanded: return 28
        case .medium: return 24
        case .compact: return 16
        }
    }

    /// Portrait poster grid columns (home, category, manga, TVBox, library, novel, search).
    var posterCols: Int {
        if width >= 1180 { return 6 }
        if width >= 840 { return 5 }
        if width >= 600 { return 4 }
        return 3
    }

    /// Continue watching grid columns on tablets (0 = horizontal phone shelf).
    var continueGridCols: Int {
        switch tier {
        case .expanded: return 4
        case .medium: return 2
        case .compact: return 0
        }
    }

    /// Horizontal shelf poster card width.
    var shelfCardWidth: CGFloat {
        switch tier {
        case .expanded: return 146
        case .medium: return 132
        case .compact: return 112
        }
    }

    /// Landscape / wide card grid columns.
    var wideCardCols: Int {
        if width >= 840 { return 4 }
        if width >= 600 { return 3 }
        return 2
    }

    /// Episode selector grid columns inside detail and player sheets.
    var episodeCols: Int {
        if width >= 1180 { return 12 }
        if width >= 840 { return 10 }
        if width >= 600 { return 8 }
        return 6
    }

    var detailMaxWidth: CGFloat? {
        switch tier {
        case .compact: return nil
        case .medium: return 780
        case .expanded: return 1240
        }
    }
    var readerMaxWidth: CGFloat? {
        switch tier {
        case .compact: return nil
        case .medium: return 560
        case .expanded: return 640
        }
    }
    var mangaMaxWidth: CGFloat? { tier == .compact ? nil : 760 }

    func posterGridItems(spacing: CGFloat = 10, alignment: Alignment = .top) -> [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: spacing, alignment: alignment), count: posterCols)
    }

    func wideGridItems(spacing: CGFloat = 12, alignment: Alignment = .top) -> [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: spacing, alignment: alignment), count: wideCardCols)
    }

    func episodeGridItems(spacing: CGFloat = 8) -> [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: spacing), count: episodeCols)
    }
}

private struct LayoutMetricsKey: EnvironmentKey {
    static let defaultValue = LayoutMetrics()
}

private struct RailLeadingInsetKey: EnvironmentKey {
    static let defaultValue: CGFloat = 0
}

extension EnvironmentValues {
    var layoutMetrics: LayoutMetrics {
        get { self[LayoutMetricsKey.self] }
        set { self[LayoutMetricsKey.self] = newValue }
    }
    /// Horizontal offset reserved for the floating Expanded navigation rail so `.dsAmbient()` bleeds seamlessly behind it.
    var railLeadingInset: CGFloat {
        get { self[RailLeadingInsetKey.self] }
        set { self[RailLeadingInsetKey.self] = newValue }
    }
}
