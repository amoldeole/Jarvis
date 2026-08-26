# Security model

Phone Guardian is designed for local-first file management.

- File content is read only after Android grants a MediaStore or SAF permission.
- Shared-storage changes use a pending Room transaction, copy/verify, then source delete. A destination is never overwritten.
- Unknown folders are protected by default. Exact duplicate cleanup is never automatic.
- Trash is app-private and encrypted-at-rest secrets use Android Keystore. A Trash copy is not a permanent backup; export backups separately.
- The local browser service is off by default, uses a random Keystore-backed token, rejects non-private peers, and is revoked when disabled. It never configures router port forwarding.
- Cloud AI and Google Drive providers are disabled in the base build. No background file upload or telemetry is present.
- Accessibility and microphone are optional and requested only when used.

Please report security issues privately to the repository maintainers rather than publishing file access details in a public issue. Do not include personal files, credentials, keystores, or pairing tokens in reports.
