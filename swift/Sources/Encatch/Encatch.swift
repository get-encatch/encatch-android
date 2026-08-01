@_exported import EncatchCore

/// Placeholder re-export — the idiomatic Swift façade (typed errors, real enums instead of the
/// generated Kotlin/Native class hierarchy, async/await wrappers) lands on top of this.
/// Kotlin/Native's Swift export already renames the generated `EncatchCoreEncatch` ObjC class to
/// plain `Encatch` for Swift callers (see the generated `-Swift.h`), so `Encatch.shared` works
/// directly without any aliasing on our part.
public typealias EncatchCoreSDK = Encatch
