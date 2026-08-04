#if canImport(UIKit)
import UIKit

/// Theme-aware WebView loading overlay, ported from `:android`'s `FormWebViewSkeleton` (itself a
/// port of the React Native SDK's `FormWebViewLoading.tsx`): a pulsing (700ms up/down loop)
/// placeholder — header bar, two text rows, an input block, and a button block — shown from form
/// load until the bridge's `form:ready`.
final class FormWebViewSkeletonView: UIView {

    private let barsContainer = UIView()
    private var bars: [UIView] = []
    private var activeMode = "light"

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        addSubview(barsContainer)
        // header, wide text row, narrow text row, input block, button block
        for _ in 0..<5 {
            let bar = UIView()
            bars.append(bar)
            barsContainer.addSubview(bar)
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    func start(activeMode: String) {
        self.activeMode = activeMode
        let barColor: UIColor = activeMode == "dark"
            ? UIColor.white.withAlphaComponent(26.0 / 255.0)
            : UIColor.black.withAlphaComponent(20.0 / 255.0)
        bars.forEach { $0.backgroundColor = barColor }
        isHidden = false
        alpha = 1

        barsContainer.layer.removeAnimation(forKey: "pulse")
        let pulse = CABasicAnimation(keyPath: "opacity")
        pulse.fromValue = 0.4
        pulse.toValue = 1.0
        pulse.duration = 0.7
        pulse.autoreverses = true
        pulse.repeatCount = .infinity
        pulse.timingFunction = CAMediaTimingFunction(name: .linear)
        barsContainer.layer.add(pulse, forKey: "pulse")
    }

    /// Fades out over the real form rather than vanishing in a single frame — the crossfade is
    /// what makes the skeleton→form handoff feel smooth.
    func stop(animated: Bool = true) {
        guard !isHidden else { return }
        guard animated else {
            barsContainer.layer.removeAnimation(forKey: "pulse")
            isHidden = true
            return
        }
        UIView.animate(withDuration: 0.3, delay: 0, options: [.curveEaseOut, .beginFromCurrentState]) {
            self.alpha = 0
        } completion: { _ in
            self.barsContainer.layer.removeAnimation(forKey: "pulse")
            self.isHidden = true
            self.alpha = 1
        }
    }

    // Same geometry as the Android/RN skeleton (dp == pt on iOS).
    override func layoutSubviews() {
        super.layoutSubviews()
        barsContainer.frame = bounds

        let paddingH: CGFloat = 20
        let w = max(bounds.width - paddingH * 2, 0)
        var y: CGFloat = 28

        func place(_ index: Int, x: CGFloat, width: CGFloat, height: CGFloat, radius: CGFloat) {
            bars[index].frame = CGRect(x: x, y: y, width: width, height: height)
            bars[index].layer.cornerRadius = radius
        }

        // Header bar: 16pt tall, 60% wide
        place(0, x: paddingH, width: w * 0.6, height: 16, radius: 8)
        y += 16 + 24
        // Question text row (wide): 12pt tall, 90% wide
        place(1, x: paddingH, width: w * 0.9, height: 12, radius: 6)
        y += 12 + 10
        // Question text row (narrow): 12pt tall, 65% wide
        place(2, x: paddingH, width: w * 0.65, height: 12, radius: 6)
        y += 12 + 24
        // Input block: 44pt tall, full width
        place(3, x: paddingH, width: w, height: 44, radius: 10)
        y += 44 + 20
        // Button block: 44pt tall, 50% wide, centered
        let buttonWidth = w * 0.5
        place(4, x: paddingH + (w - buttonWidth) / 2, width: buttonWidth, height: 44, radius: 10)
    }
}
#endif
