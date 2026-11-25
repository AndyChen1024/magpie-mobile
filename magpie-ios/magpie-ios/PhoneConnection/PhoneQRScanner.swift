#if os(iOS)
@preconcurrency import AVFoundation
import SwiftUI
import UIKit

struct PhoneQRScannerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var canScan = false
    @State private var cameraUnavailable = false
    let onScan: (String) -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if cameraUnavailable {
                    Text("Camera unavailable. You can paste the pairing link instead.")
                        .multilineTextAlignment(.center)
                        .padding()
                } else if canScan {
                    if scenePhase == .active {
                        PhoneQRPreview(onScan: onScan, onUnavailable: { cameraUnavailable = true })
                            .accessibilityHidden(true)
                    }
                    Text("Point the camera at the pairing code on your Mac.")
                        .font(.subheadline)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                } else {
                    ProgressView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.bottom, 16)
            .navigationTitle("Scan Pairing Code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .task {
            guard AVCaptureDevice.default(for: .video) != nil else { cameraUnavailable = true; return }
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized: canScan = true
            case .notDetermined:
                canScan = await AVCaptureDevice.requestAccess(for: .video)
                cameraUnavailable = !canScan
            default: cameraUnavailable = true
            }
        }
    }
}

private struct PhoneQRPreview: UIViewControllerRepresentable {
    let onScan: (String) -> Void
    let onUnavailable: () -> Void

    func makeUIViewController(context: Context) -> CameraController {
        CameraController(onScan: onScan, onUnavailable: onUnavailable)
    }

    func updateUIViewController(_ uiViewController: CameraController, context: Context) {}

    static func dismantleUIViewController(_ uiViewController: CameraController, coordinator: ()) {
        uiViewController.stop()
    }

    final class CameraController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        private let capture = AVCaptureSession()
        private let cameraQueue = DispatchQueue(label: "magpie.phone-pairing-camera")
        private var preview: AVCaptureVideoPreviewLayer?
        private var didScan = false
        private let onScan: (String) -> Void
        private let onUnavailable: () -> Void

        init(onScan: @escaping (String) -> Void, onUnavailable: @escaping () -> Void) {
            self.onScan = onScan
            self.onUnavailable = onUnavailable
            super.init(nibName: nil, bundle: nil)
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { nil }

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            let output = AVCaptureMetadataOutput()
            guard let camera = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: camera), capture.canAddInput(input)
            else { reportUnavailable(); return }
            capture.addInput(input)
            guard capture.canAddOutput(output) else { reportUnavailable(); return }
            capture.addOutput(output)
            guard output.availableMetadataObjectTypes.contains(.qr) else { reportUnavailable(); return }
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            let preview = AVCaptureVideoPreviewLayer(session: capture)
            preview.videoGravity = .resizeAspectFill
            view.layer.addSublayer(preview)
            self.preview = preview
            cameraQueue.async { [capture] in capture.startRunning() }
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            preview?.frame = view.bounds
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            guard !didScan, let code = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
                  code.type == .qr, let link = code.stringValue else { return }
            stop()
            onScan(link)
        }

        func stop() {
            didScan = true
            cameraQueue.async { [capture] in capture.stopRunning() }
        }

        private func reportUnavailable() {
            Task { @MainActor [weak self] in self?.onUnavailable() }
        }
    }
}
#endif
