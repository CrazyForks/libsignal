//
// Copyright 2020-2022 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

import Foundation
import SignalFfi

public class ServerSecretParams: NativeHandleOwner<SignalMutPointerServerSecretParams> {
    public static func generate() throws -> ServerSecretParams {
        return try self.generate(randomness: Randomness.generate())
    }

    public static func generate(randomness: Randomness) throws -> ServerSecretParams {
        return try randomness.withUnsafePointerToBytes { randomness in
            try invokeFnReturningNativeHandle {
                signal_server_secret_params_generate_deterministic($0, randomness)
            }
        }
    }

    public convenience init(contents: Data) throws {
        var handle = SignalMutPointerServerSecretParams()
        try contents.withUnsafeBorrowedBuffer {
            try checkError(signal_server_secret_params_deserialize(&handle, $0))
        }
        self.init(owned: NonNull(handle)!)
    }

    required init(owned: NonNull<SignalMutPointerServerSecretParams>) {
        super.init(owned: owned)
    }

    public func serialize() -> Data {
        return failOnError {
            try withNativeHandle { handle in
                try invokeFnReturningData {
                    signal_server_secret_params_serialize($0, handle.const())
                }
            }
        }
    }

    public func getPublicParams() throws -> ServerPublicParams {
        return try withNativeHandle { contents in
            try invokeFnReturningNativeHandle {
                signal_server_secret_params_get_public_params($0, contents.const())
            }
        }
    }

    public func sign(message: Data) throws -> NotarySignature {
        return try self.sign(randomness: Randomness.generate(), message: message)
    }

    public func sign(randomness: Randomness, message: Data) throws -> NotarySignature {
        return try withNativeHandle { contents in
            try randomness.withUnsafePointerToBytes { randomness in
                try message.withUnsafeBorrowedBuffer { message in
                    try invokeFnReturningSerialized {
                        signal_server_secret_params_sign_deterministic($0, contents.const(), randomness, message)
                    }
                }
            }
        }
    }

    override internal class func destroyNativeHandle(
        _ handle: NonNull<SignalMutPointerServerSecretParams>
    ) -> SignalFfiErrorRef? {
        signal_server_secret_params_destroy(handle.pointer)
    }
}

extension SignalMutPointerServerSecretParams: SignalMutPointer {
    public typealias ConstPointer = SignalConstPointerServerSecretParams

    public init(untyped: OpaquePointer?) {
        self.init(raw: untyped)
    }

    public func toOpaque() -> OpaquePointer? {
        self.raw
    }

    public func const() -> Self.ConstPointer {
        Self.ConstPointer(raw: self.raw)
    }
}

extension SignalConstPointerServerSecretParams: SignalConstPointer {
    public func toOpaque() -> OpaquePointer? {
        self.raw
    }
}
