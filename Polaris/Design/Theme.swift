import SwiftUI

/// A printed star atlas: cool chart paper, navy ink, chart-blue for anything you
/// can tap, and one gold star (the mark, the best route, Go). Line colours are
/// the only other colour on screen. At night the same atlas turns over: night-sky
/// paper, pale ink, and the gold star unchanged.
enum Theme {
    /// The sheet and every screen behind content.
    static let paper = adaptive(light: rgb(0.961, 0.965, 0.980), dark: rgb(0.043, 0.059, 0.110))
    /// Rows and grouped surfaces, a step up from the paper.
    static let surface = adaptive(light: .white, dark: rgb(0.090, 0.110, 0.173))
    /// Body text: a navy that reads as black, or a pale blue-white at night.
    static let ink = adaptive(light: rgb(0.051, 0.082, 0.188), dark: rgb(0.914, 0.929, 0.965))
    /// Navy in either appearance, for text on gold and on pale line colours.
    static let navy = Color(red: 0.051, green: 0.082, blue: 0.188)
    /// Tint for controls and links.
    static let tint = adaptive(light: rgb(0.145, 0.329, 0.780), dark: rgb(0.478, 0.635, 1.0))
    /// The star. A fill under navy text; too light for text on paper.
    static let gold = Color(red: 0.949, green: 0.722, blue: 0.180)
    /// Gold that draws a mark or a word on the surface: darker by day, brighter by night.
    static let goldInk = adaptive(light: rgb(0.604, 0.416, 0.0), dark: rgb(0.949, 0.757, 0.306))
    static let hairline = adaptive(light: rgb(0.051, 0.082, 0.188, 0.10), dark: rgb(1, 1, 1, 0.12))

    /// Semantic colours, used only for what they mean.
    static let live = adaptive(light: rgb(0.086, 0.478, 0.263), dark: rgb(0.310, 0.820, 0.545))
    static let warning = adaptive(light: rgb(0.761, 0.255, 0.047), dark: rgb(1.0, 0.561, 0.353))
    /// You, and where a trip starts.
    static let here = adaptive(light: rgb(0.184, 0.435, 0.922), dark: rgb(0.353, 0.576, 1.0))

    /// The Go banner and stop markers on the map: navy by day, a lifted night blue by night.
    static let banner = adaptive(light: rgb(0.051, 0.082, 0.188), dark: rgb(0.137, 0.173, 0.290))
    /// Under route lines on the map, so they stand off the streets.
    static let casing = adaptive(light: .white, dark: rgb(0.043, 0.059, 0.110))

    /// Corner radius for grouped surfaces.
    static let radius: CGFloat = 18

    static func color(_ provider: ProviderID) -> Color {
        switch provider {
        case .google: Color(red: 0.26, green: 0.52, blue: 0.96)
        case .tfl: Color(red: 0.86, green: 0.14, blue: 0.12)
        case .transitous: Color(red: 0.10, green: 0.60, blue: 0.40)
        case .apple: adaptive(light: rgb(0.45, 0.45, 0.45), dark: rgb(0.62, 0.62, 0.62))
        }
    }

    /// A line's colour for drawing it (on the map, down the itinerary). The
    /// Northern line's black disappears at night, so near-black lines lift to grey.
    static func line(_ color: RGB?) -> Color {
        guard let color else { return .gray }
        let own = rgb(color.r, color.g, color.b)
        return color.luminance < 0.2 ? adaptive(light: own, dark: rgb(0.64, 0.67, 0.74)) : Color(uiColor: own)
    }

    private static func rgb(_ r: Double, _ g: Double, _ b: Double, _ alpha: Double = 1) -> UIColor {
        UIColor(red: r, green: g, blue: b, alpha: alpha)
    }

    private static func adaptive(light: UIColor, dark: UIColor) -> Color {
        Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? dark : light })
    }

    /// Place names and the wordmark: New York, like an atlas's labels.
    static func title(_ style: Font.TextStyle = .title2) -> Font { .system(style, design: .serif).weight(.semibold) }
}

extension Color {
    init(_ rgb: RGB) { self.init(red: rgb.r, green: rgb.g, blue: rgb.b) }
}

extension View {
    /// A grouped surface: white on the paper with a hairline edge. Only around
    /// a group of rows or one distinct object, never nested.
    func surface(highlighted: Bool = false) -> some View {
        background(Theme.surface, in: .rect(cornerRadius: Theme.radius))
            .overlay(RoundedRectangle(cornerRadius: Theme.radius)
                .strokeBorder(highlighted ? Theme.goldInk.opacity(0.55) : Theme.hairline, lineWidth: highlighted ? 1 : 0.75))
    }
}

/// A four-pointed star with long north and south rays.
struct NorthStar: Shape {
    /// How fat the star is where its rays meet, as a share of its size.
    var waist: CGFloat = 0.09

    func path(in rect: CGRect) -> Path {
        let c = CGPoint(x: rect.midX, y: rect.midY)
        let v = rect.height / 2, h = rect.width / 2 * 0.72, waist = min(rect.width, rect.height) * waist
        var p = Path()
        p.move(to: CGPoint(x: c.x, y: c.y - v))
        p.addQuadCurve(to: CGPoint(x: c.x + h, y: c.y), control: CGPoint(x: c.x + waist, y: c.y - waist))
        p.addQuadCurve(to: CGPoint(x: c.x, y: c.y + v), control: CGPoint(x: c.x + waist, y: c.y + waist))
        p.addQuadCurve(to: CGPoint(x: c.x - h, y: c.y), control: CGPoint(x: c.x - waist, y: c.y + waist))
        p.addQuadCurve(to: CGPoint(x: c.x, y: c.y - v), control: CGPoint(x: c.x - waist, y: c.y - waist))
        return p
    }
}

/// Polaris's mark: a north star whose four points each carry a smaller star,
/// and theirs smaller again. While a search runs, each of the four branches
/// stands for one source and fills in when that source answers.
struct FractalStar: View {
    var size: CGFloat = 28
    /// Branches drawn solid (north, east, south, west); the rest are outlines.
    var lit: [Bool] = [true, true, true, true]
    var color: Color = Theme.goldInk

    var body: some View {
        Canvas { context, canvas in
            let centre = CGPoint(x: canvas.width / 2, y: canvas.height / 2)
            // The big star fills the middle; its children overhang to the edge.
            let radius = canvas.height / 2 / 1.52
            draw(&context, at: centre, radius: radius, depth: 0, lit: true)
            for (index, direction) in Self.directions.enumerated() {
                let tip = tipOffset(direction, radius: radius)
                draw(&context, at: CGPoint(x: centre.x + tip.x, y: centre.y + tip.y), radius: radius * 0.38,
                     depth: 1, lit: lit.indices.contains(index) ? lit[index] : true)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    private static let directions: [CGPoint] = [CGPoint(x: 0, y: -1), CGPoint(x: 1, y: 0), CGPoint(x: 0, y: 1), CGPoint(x: -1, y: 0)]

    private func tipOffset(_ direction: CGPoint, radius: CGFloat) -> CGPoint {
        // North and south rays are longer than east and west.
        let reach = direction.x == 0 ? radius : radius * 0.72
        return CGPoint(x: direction.x * reach, y: direction.y * reach)
    }

    private func draw(_ context: inout GraphicsContext, at centre: CGPoint, radius: CGFloat, depth: Int, lit: Bool) {
        let rect = CGRect(x: centre.x - radius, y: centre.y - radius, width: radius * 2, height: radius * 2)
        let star = NorthStar(waist: 0.045).path(in: rect)
        if lit {
            context.fill(star, with: .color(color))
        } else {
            context.stroke(star, with: .color(color.opacity(0.45)), lineWidth: max(0.6, radius * 0.08))
        }
        guard depth < 2 else { return }
        for direction in Self.directions {
            let tip = tipOffset(direction, radius: radius)
            draw(&context, at: CGPoint(x: centre.x + tip.x, y: centre.y + tip.y), radius: radius * 0.34, depth: depth + 1, lit: lit)
        }
    }
}

extension Date {
    var clock: String { formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits)) }
}

extension TimeInterval {
    /// "26 min", "1 h 05".
    var minutesText: String {
        let minutes = Int((self / 60).rounded())
        return minutes < 60 ? "\(minutes) min" : String(format: "%d h %02d", minutes / 60, minutes % 60)
    }

    /// "+3 min", "−2 min", for comparing one time with another.
    var signedMinutesText: String {
        let minutes = Int((self / 60).rounded())
        return minutes == 0 ? "same time" : minutes > 0 ? "+\(minutes) min" : "−\(-minutes) min"
    }
}
