import SwiftUI
import Encatch

/// Port of `encatch-ios-tester`'s `HomeTabView` — width-capped so it doesn't stretch edge-to-edge
/// across a wide Mac window, `GroupBox` sections instead of flat gray `.card()`s, system button
/// styles instead of the iOS pill `ButtonStyle`s.
struct HomeView: View {
    @ObservedObject var state: TesterState

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if let userName = state.prefs.userName {
                    HStack(spacing: 12) {
                        InitialsAvatar(name: userName, size: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Signed in as").font(.caption).foregroundColor(.secondary)
                            Text(userName).font(.headline)
                        }
                        Spacer()
                        Button("Edit Profile") { state.onboardingStep = .editProfile(username: userName) }
                    }
                    .padding()
                    .background(.quaternary.opacity(0.3))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                }

                GroupBox("Last SDK Event") {
                    HStack(spacing: 8) {
                        Image(systemName: "waveform.path.ecg").foregroundColor(.accentColor)
                        Text(state.lastEvent)
                            .font(.callout.monospaced())
                            .foregroundColor(.secondary)
                            .lineLimit(2)
                        Spacer()
                    }
                    .padding(.top, 4)
                }

                GroupBox("Forms") {
                    VStack(alignment: .leading, spacing: 10) {
                        Button(action: { state.showModalForm() }) {
                            Label("Show Form", systemImage: "rectangle.portrait.on.rectangle.portrait.fill")
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.borderedProminent)

                        Button(action: { state.showPrefillSheet = true }) {
                            Label("Prefill Answers & Show Form…", systemImage: "text.badge.checkmark")
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.bordered)
                        .sheet(isPresented: $state.showPrefillSheet) {
                            PrefillSheet(onApply: { state.applyPrefill($0) })
                        }

                        if let interceptorFormId = state.prefs.interceptorFormId, !interceptorFormId.isEmpty {
                            Button(action: { state.showInterceptorForm() }) {
                                Label("Show Form (Interceptor Test)", systemImage: "hand.raised.fill")
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                    .padding(.top, 4)
                }
            }
            .padding(24)
            .frame(maxWidth: 480, alignment: .leading)
        }
        .frame(maxWidth: .infinity, alignment: .center)
        .onAppear { state.trackHomeViewed() }
    }
}
