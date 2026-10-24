These small APKs contain a synthetic `com.example.fawtest` manifest with no application code.
They are signed with disposable regression-test certificates, never a production key.
Fixtures cover successive versions, same-version conflicts, another signer, debuggable/split
packages, and an unsupported minimum SDK. Tests need no Android SDK at runtime.
