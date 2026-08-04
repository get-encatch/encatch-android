import SwiftUI
import Encatch

/// CTA-driven destinations (`form:ctaTriggered` with `action=app_navigate`) that replace the
/// split view's detail pane — ported from `encatch-ios-tester`'s `BillingView`/
/// `RouteNotFoundView`, width-capped so the centered hero content doesn't stretch edge-to-edge
/// across a wide Mac window.
struct BillingView: View {
    let route: String
    @ObservedObject var state: TesterState

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "creditcard").font(.system(size: 40)).foregroundColor(.accentColor)
            Text("Billing").font(.title2.weight(.semibold))
            Text("Reached via CTA app_navigate route: \"\(route)\"")
                .font(.callout)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
            Button("Back to Home") { state.screen = .main }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.top, 8)
        }
        .frame(maxWidth: 380)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear { state.trackScreen("Billing") }
    }
}

struct RouteNotFoundView: View {
    let route: String
    @ObservedObject var state: TesterState

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "questionmark.circle").font(.system(size: 40)).foregroundColor(.orange)
            Text("Route Not Found").font(.title2.weight(.semibold))
            Text("The CTA requested an unmapped route: \"\(route)\"")
                .font(.callout)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
            Button("Go Back") { state.screen = .main }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.top, 8)
        }
        .frame(maxWidth: 380)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear { state.trackScreen("RouteNotFound") }
    }
}
