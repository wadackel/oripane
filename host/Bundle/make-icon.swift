// Draws the app icon into an .iconset directory for `iconutil`: the mark on a dark rounded square.
// Its planes are those of host/Sources/oripane-host/App/Mark.swift, copied because a script
// cannot import that target; the Dock draws the mark smaller than the Android launcher does.
//   swift host/Bundle/make-icon.swift <out.iconset>
// Command Line Tools have no actool, so there is no asset catalog to compile.
import AppKit

let out = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "AppIcon.iconset")
try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)

func color(_ hex: UInt32) -> CGColor {
    CGColor(srgbRed: CGFloat(hex >> 16 & 0xff) / 255, green: CGFloat(hex >> 8 & 0xff) / 255, blue: CGFloat(hex & 0xff) / 255, alpha: 1)
}

/// Lower plane, fold face, upper plane, in the mark's own frame (y down), in painting order.
func markPlanes() -> [CGPath] {
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
}

func draw(_ px: Int) -> Data {
    let s = CGFloat(px)
    let ctx = CGContext(data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    // The macOS grid: an 824 pt square with a 185 pt corner, centred on 1024.
    let inset = s * 100 / 1024
    let body = CGRect(x: inset, y: inset, width: s - 2 * inset, height: s - 2 * inset)
    ctx.addPath(CGPath(roundedRect: body, cornerWidth: s * 185 / 1024, cornerHeight: s * 185 / 1024, transform: nil))
    ctx.setFillColor(color(0x111316))
    ctx.fillPath()

    // The Android viewport is 108 units with a 72-unit safe zone; that zone fills the body.
    let unit = body.width / 72
    ctx.translateBy(x: body.minX - 18 * unit, y: body.maxY + 18 * unit)
    ctx.scaleBy(x: unit, y: -unit)

    ctx.translateBy(x: 51.52, y: 56.48)
    ctx.scaleBy(x: 0.58, y: 0.58)
    ctx.rotate(by: -.pi / 4)
    for (path, fill) in zip(markPlanes(), [0x18E2B0, 0x0D7E62, 0xA0EEDA] as [UInt32]) {
        ctx.addPath(path)
        ctx.setFillColor(color(fill))
        ctx.fillPath()
    }

    return NSBitmapImageRep(cgImage: ctx.makeImage()!).representation(using: .png, properties: [:])!
}

for size in [16, 32, 128, 256, 512] {
    try draw(size).write(to: out.appendingPathComponent("icon_\(size)x\(size).png"))
    try draw(size * 2).write(to: out.appendingPathComponent("icon_\(size)x\(size)@2x.png"))
}
