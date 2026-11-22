import AppKit

/// The Oripane mark: one sheet with a step fold, as three planes in its own frame (y down), the
/// same paths as `android/app/src/main/res/drawable/oripane_symbol.xml`. `host/Bundle/make-icon.swift`
/// keeps a copy, since it runs as a script and cannot import this target.
enum Mark {
    /// Lower plane, fold face, upper plane: the order they are painted in, as the paper lies.
    static let planes: [CGPath] = {
        let lower = CGMutablePath()
        lower.move(to: CGPoint(x: 20, y: 2.25))
        lower.addLine(to: CGPoint(x: 20, y: 18.05))
        lower.addQuadCurve(to: CGPoint(x: 16.25, y: 21.8), control: CGPoint(x: 20, y: 21.8))
        lower.addLine(to: CGPoint(x: -16.25, y: 21.8))
        lower.addQuadCurve(to: CGPoint(x: -20, y: 18.05), control: CGPoint(x: -20, y: 21.8))
        lower.addLine(to: CGPoint(x: -20, y: 2.25))
        lower.closeSubpath()
        let fold = CGMutablePath()
        fold.addLines(between: [CGPoint(x: -8, y: -2.25), CGPoint(x: 32, y: -2.25), CGPoint(x: 20, y: 2.25), CGPoint(x: -20, y: 2.25)])
        fold.closeSubpath()
        let upper = CGMutablePath()
        upper.move(to: CGPoint(x: -8, y: -2.25))
        upper.addLine(to: CGPoint(x: -8, y: -18.05))
        upper.addQuadCurve(to: CGPoint(x: -4.25, y: -21.8), control: CGPoint(x: -8, y: -21.8))
        upper.addLine(to: CGPoint(x: 28.25, y: -21.8))
        upper.addQuadCurve(to: CGPoint(x: 32, y: -18.05), control: CGPoint(x: 32, y: -21.8))
        upper.addLine(to: CGPoint(x: 32, y: -2.25))
        upper.closeSubpath()
        return [lower, fold, upper]
    }()

    /// The menu bar icon, 16 pt, as a template: only alpha tells the planes apart, and macOS
    /// colours it for the bar. Idle is the same drawing at 45 %.
    static func menuBarImage(idle: Bool) -> NSImage {
        let side: CGFloat = 16
        let alphas: [CGFloat] = [0.7, 0.42, 1].map { idle ? $0 * 0.45 : $0 }
        let image = NSImage(size: NSSize(width: side, height: side), flipped: true) { _ in
            guard let ctx = NSGraphicsContext.current?.cgContext else { return false }
            // The mark fills a 63-unit box at (22.5, 22.5) in the 108-unit icon grid.
            ctx.scaleBy(x: side / 63, y: side / 63)
            ctx.translateBy(x: -22.5, y: -22.5)
            ctx.translateBy(x: 50.06, y: 57.94)
            ctx.scaleBy(x: 0.93, y: 0.93)
            ctx.rotate(by: -.pi / 4)
            for (path, alpha) in zip(planes, alphas) {
                ctx.addPath(path)
                ctx.setFillColor(CGColor(gray: 0, alpha: alpha))
                ctx.fillPath()
            }
            return true
        }
        image.isTemplate = true
        image.accessibilityDescription = "Oripane"
        return image
    }
}
