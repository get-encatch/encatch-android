#if canImport(UIKit) && canImport(SwiftUI)
import SwiftUI
import UIKit

/// An inline Encatch survey in a SwiftUI layout, the counterpart of React Native's, Flutter's and
/// Compose's `EncatchInlineForm` (same parameters). It wraps `EncatchInlineFormView` and sizes
/// itself to the survey (0 when none is shown, a placeholder while loading, then the survey's own
/// height), so don't give it a fixed height.
///
/// ```swift
/// EncatchInlineForm(formId: "your-inline-form-id")
/// ```
///
/// - `formId`: catches `showForm` calls for that form only; `nil` catches any form no other inline
///   slot claimed.
/// - `minHeight`: the smallest height once a survey is shown.
/// - `onOverlayOpenChange`: called when an in-form overlay (Q&A with AI, a scheduler) opens or closes.
@available(iOS 15.0, *)
public struct EncatchInlineForm: View {
    private let formId: String?
    private let minHeight: CGFloat
    private let onOverlayOpenChange: ((Bool) -> Void)?
    @State private var height: CGFloat = 0

    public init(formId: String? = nil, minHeight: CGFloat = 0, onOverlayOpenChange: ((Bool) -> Void)? = nil) {
        self.formId = formId
        self.minHeight = minHeight
        self.onOverlayOpenChange = onOverlayOpenChange
    }

    public var body: some View {
        InlineFormRepresentable(
            formId: formId,
            minHeight: minHeight,
            onOverlayOpenChange: onOverlayOpenChange,
            height: $height
        )
        .frame(height: height)
    }
}

@available(iOS 15.0, *)
private struct InlineFormRepresentable: UIViewRepresentable {
    let formId: String?
    let minHeight: CGFloat
    let onOverlayOpenChange: ((Bool) -> Void)?
    @Binding var height: CGFloat

    func makeUIView(context: Context) -> EncatchInlineFormView {
        let view = EncatchInlineFormView()
        configure(view)
        return view
    }

    func updateUIView(_ view: EncatchInlineFormView, context: Context) {
        configure(view)
    }

    private func configure(_ view: EncatchInlineFormView) {
        if view.formId != formId { view.formId = formId }
        view.minHeight = minHeight
        view.onOverlayOpenChange = onOverlayOpenChange
        // the view reports its height during its own layout: applied on the next main-queue turn,
        // never while SwiftUI is updating the view
        view.onHeightChange = { [binding = $height] newHeight in
            DispatchQueue.main.async {
                if binding.wrappedValue != newHeight { binding.wrappedValue = newHeight }
            }
        }
    }
}
#endif
