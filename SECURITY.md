# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 2.x.x   | :white_check_mark: |
| 1.x.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Reporting a Vulnerability

We take the security of iCalDAV seriously. If you believe you have found a security vulnerability, please report it to us as described below.

### How to Report

**Please do NOT report security vulnerabilities through public GitHub issues.**

Instead, please use [GitHub Security Advisories](https://github.com/iCalDAV/iCalDAV/security/advisories/new) to report vulnerabilities privately.

### What to Include

Please include the following information in your report:

- Type of vulnerability (e.g., XXE, SSRF, injection, DoS)
- Full paths of affected source files
- Location of the affected code (tag/branch/commit or direct URL)
- Step-by-step instructions to reproduce the issue
- Proof-of-concept or exploit code (if possible)
- Impact assessment of the vulnerability

## Security Considerations

### Known Security Features

iCalDAV implements the following security measures:

#### Input Validation

- **ICS Size Limits**: Maximum file size enforced to prevent memory exhaustion
- **RRULE Limits**: Maximum recurrence expansion to prevent DoS
- **URL Validation**: Only `https://`, `http://`, and `webcal://` schemes allowed
- **Path Sanitization**: Path traversal attempts are rejected

#### XML Security

- **XXE Prevention**: External entity processing disabled in XML parser
- **DTD Processing**: Disabled by default
- **Entity Expansion Limits**: Configured to prevent billion laughs attack

#### Network Security

- **TLS by Default**: HTTPS enforced for all CalDAV operations
- **Certificate Validation**: Full certificate chain validation
- **Timeout Enforcement**: Connection and read timeouts prevent hanging

### Security Best Practices for Users

When using iCalDAV in your application:

1. **Credential Management**
   - Never log credentials or authentication tokens
   - Use secure credential storage (e.g., Android Keystore)
   - Consider OAuth2 where supported by the server

2. **Network Security**
   - Always use HTTPS endpoints
   - Consider certificate pinning for sensitive applications
   - Implement rate limiting to avoid server abuse

3. **Input Handling**
   - Validate calendar URLs before passing to the library
   - Sanitize event data before displaying to users
   - Be cautious with event URLs and attachments

4. **Error Handling**
   - Don't expose stack traces to end users
   - Log errors without sensitive information
   - Handle parse errors gracefully

### Threat Model

| Threat | Mitigation | Status |
|--------|------------|--------|
| XXE Injection | External entities disabled | ✅ |
| SSRF via URL | URL scheme validation | ✅ |
| DoS via large ICS | Size limits enforced | ✅ |
| DoS via infinite RRULE | Expansion limits enforced | ✅ |
| Path Traversal | Path normalization | ✅ |
| Credential Leakage | No credential logging | ✅ |
| Man-in-the-Middle | TLS required | ✅ |

## Security Updates

Security updates are released as patch versions (e.g., 1.0.1) and announced via:

- GitHub Security Advisories
- Release notes

We recommend enabling GitHub notifications for security advisories on this repository.

## Acknowledgments

We thank the following individuals for responsibly disclosing security issues:

- (No reports yet)

---

*This security policy was last updated in January 2026.*