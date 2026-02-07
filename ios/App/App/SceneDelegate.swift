import UIKit
import Capacitor
import AVFoundation

class SceneDelegate: UIResponder, UIWindowSceneDelegate {

    var window: UIWindow?
    var bridgeViewController: CAPBridgeViewController?
    /// Tracks whether we added a KVO observer so we can safely remove it.
    private var isObservingProgress = false

    func scene(_ scene: UIScene,
               willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {

        guard let windowScene = scene as? UIWindowScene else { return }

        let window = UIWindow(windowScene: windowScene)
        self.window = window

        let bridgeVC = CAPBridgeViewController()
        bridgeVC.additionalSafeAreaInsets = UIEdgeInsets(top: 80, left: 0, bottom: 0, right: 0)
        self.bridgeViewController = bridgeVC

        // Proactively register as KVO observer so that any internal
        // removeObserver call during teardown will NOT crash.
        if let webView = bridgeVC.bridge?.webView {
            webView.addObserver(self, forKeyPath: "estimatedProgress", options: .new, context: nil)
            isObservingProgress = true
            print("[SceneDelegate] 🔗 KVO observer added proactively")
        }

        // Show bridge immediately – Capacitor handles its own loading.
        window.rootViewController = bridgeVC
        window.makeKeyAndVisible()

        print("[SceneDelegate] 🚀 Scene connected – bridge set as root")

        // Register plugins once bridge is ready
        registerPluginsWithRetry()
    }

    // MARK: - KVO (exists solely to prevent crash on teardown)

    override func observeValue(forKeyPath keyPath: String?,
                               of object: Any?,
                               change: [NSKeyValueChangeKey : Any]?,
                               context: UnsafeMutableRawPointer?) {
        // intentionally empty – we only observe to keep the registration alive
    }

    // MARK: - Scene lifecycle

    func sceneDidDisconnect(_ scene: UIScene) {
        removeProgressObserverIfNeeded()

        print("[SceneDelegate] 🚨 Scene disconnecting – releasing audio session")
        do {
            try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            print("[SceneDelegate] ✅ Audio session deactivated on disconnect")
        } catch {
            print("[SceneDelegate] ⚠️ Could not deactivate audio session: \(error.localizedDescription)")
        }
    }

    func sceneDidBecomeActive(_ scene: UIScene) { }
    func sceneWillResignActive(_ scene: UIScene) { }
    func sceneWillEnterForeground(_ scene: UIScene) { }
    func sceneDidEnterBackground(_ scene: UIScene) { }

    // MARK: - Helpers

    private func removeProgressObserverIfNeeded() {
        guard isObservingProgress,
              let webView = bridgeViewController?.bridge?.webView else { return }
        webView.removeObserver(self, forKeyPath: "estimatedProgress")
        isObservingProgress = false
        print("[SceneDelegate] 🔓 KVO observer removed safely")
    }

    private func registerPluginsWithRetry(retryCount: Int = 5, delay: TimeInterval = 0.4) {
        guard let bridge = bridgeViewController?.bridge as? CapacitorBridge else {
            if retryCount > 0 {
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                    self?.registerPluginsWithRetry(retryCount: retryCount - 1, delay: delay)
                }
            }
            return
        }
        PluginRegistration.registerPlugins(with: bridge)
        print("[SceneDelegate] ✅ Plugins registered")
    }
}
