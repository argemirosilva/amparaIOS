import UIKit
import Capacitor
// AVFoundation removed: audio session is managed exclusively by AudioTriggerNativePlugin

/// Wrapper class to act as KVO observer for WKWebView's estimatedProgress.
/// This prevents crashes when the system tries to remove observers during teardown.
private class WebViewProgressObserver: NSObject {
    override func observeValue(forKeyPath keyPath: String?,
                               of object: Any?,
                               change: [NSKeyValueChangeKey : Any]?,
                               context: UnsafeMutableRawPointer?) {
        // Intentionally empty – exists solely to prevent crash
    }
    
    deinit {
        // No cleanup needed – observer removal is handled externally
    }
}

class SceneDelegate: UIResponder, UIWindowSceneDelegate {

    var window: UIWindow?
    var bridgeViewController: CAPBridgeViewController?
    private var progressObserver: WebViewProgressObserver?

    func scene(_ scene: UIScene,
               willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {

        guard let windowScene = scene as? UIWindowScene else { return }

        let window = UIWindow(windowScene: windowScene)
        self.window = window

        let bridgeVC = CAPBridgeViewController()
        bridgeVC.additionalSafeAreaInsets = UIEdgeInsets(top: 80, left: 0, bottom: 0, right: 0)
        self.bridgeViewController = bridgeVC

        // Show bridge immediately – Capacitor handles its own loading.
        window.rootViewController = bridgeVC
        window.makeKeyAndVisible()

        print("[SceneDelegate] 🚀 Scene connected – bridge set as root")

        // Register plugins once bridge is ready
        registerPluginsWithRetry()
        
        // Add KVO observer with delay to ensure webView is ready
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            self?.addProgressObserverIfNeeded()
        }
    }

    // MARK: - KVO Management

    private func addProgressObserverIfNeeded() {
        guard progressObserver == nil,
              let webView = bridgeViewController?.bridge?.webView else {
            print("[SceneDelegate] ⚠️ WebView not ready or observer already exists")
            return
        }
        
        let observer = WebViewProgressObserver()
        webView.addObserver(observer, forKeyPath: "estimatedProgress", options: .new, context: nil)
        progressObserver = observer
        print("[SceneDelegate] 🔗 KVO observer added proactively")
    }

    private func removeProgressObserverIfNeeded() {
        guard let observer = progressObserver,
              let webView = bridgeViewController?.bridge?.webView else { return }
        
        webView.removeObserver(observer, forKeyPath: "estimatedProgress")
        progressObserver = nil
        print("[SceneDelegate] 🔓 KVO observer removed safely")
    }

    // MARK: - Scene lifecycle

    func sceneDidDisconnect(_ scene: UIScene) {
        removeProgressObserverIfNeeded()

        // FIX: Do NOT deactivate AVAudioSession here.
        // sceneDidDisconnect is called when iOS decides to release the scene (e.g. low memory,
        // system restart). Deactivating AVAudioSession at this point immediately kills background
        // audio monitoring even though the app may still be alive via the "audio" background mode.
        // AVAudioSession lifecycle is managed exclusively by AudioTriggerNativePlugin.
        print("[SceneDelegate] Scene disconnected — audio session managed by AudioTriggerNativePlugin")
    }

    func sceneDidBecomeActive(_ scene: UIScene) { }
    func sceneWillResignActive(_ scene: UIScene) { }
    func sceneWillEnterForeground(_ scene: UIScene) { }
    func sceneDidEnterBackground(_ scene: UIScene) { }

    // MARK: - Helpers

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
