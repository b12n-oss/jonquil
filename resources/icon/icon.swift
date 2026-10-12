// Generates the 1024px Jonquil icon PNG; run through `bb app:icon`.
// Draws Jonquil's app icon at 1024x1024: a jonquil (six petals around a
// trumpet) on a rounded square. Usage: swift icon.swift out.png
import AppKit
let size: CGFloat = 1024
let out = CommandLine.arguments[1]
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size), pixelsHigh: Int(size),
                           bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                           colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
let inset: CGFloat = 100
let tile = NSRect(x: inset, y: inset, width: size - 2*inset, height: size - 2*inset)
NSColor(calibratedRed: 0.12, green: 0.30, blue: 0.22, alpha: 1).setFill()
NSBezierPath(roundedRect: tile, xRadius: 185, yRadius: 185).fill()
let c = NSPoint(x: size/2, y: size/2)
for i in 0..<6 {
  let a = CGFloat(i) * .pi / 3 + .pi / 2
  let t = NSAffineTransform(); t.translateX(by: c.x, yBy: c.y); t.rotate(byRadians: a)
  let petal = NSBezierPath(ovalIn: NSRect(x: 40, y: -78, width: 300, height: 156))
  petal.transform(using: t as AffineTransform)
  NSColor(calibratedRed: 0.99, green: 0.93, blue: 0.55, alpha: 1).setFill(); petal.fill()
  NSColor(calibratedRed: 0.86, green: 0.74, blue: 0.25, alpha: 1).setStroke(); petal.lineWidth = 6; petal.stroke()
}
let trumpet = NSBezierPath(ovalIn: NSRect(x: c.x - 120, y: c.y - 120, width: 240, height: 240))
NSColor(calibratedRed: 0.97, green: 0.60, blue: 0.13, alpha: 1).setFill(); trumpet.fill()
let throat = NSBezierPath(ovalIn: NSRect(x: c.x - 62, y: c.y - 62, width: 124, height: 124))
NSColor(calibratedRed: 0.85, green: 0.42, blue: 0.08, alpha: 1).setFill(); throat.fill()
NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
